package `in`.driftzero.app.pose

import `in`.driftzero.app.trips.TripRecorder
import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.DisplacementModel
import `in`.driftzero.core.FilterSnapshot
import `in`.driftzero.core.GeoPoint
import `in`.driftzero.core.GraphEdge
import `in`.driftzero.core.HmmRoadMatcher
import `in`.driftzero.core.MapMatchStatus
import `in`.driftzero.core.MotionPseudoRuntime
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.ResetReason
import `in`.driftzero.core.RoadGraph
import `in`.driftzero.core.RoadMatcher
import `in`.driftzero.core.SensorFrame
import `in`.driftzero.core.VectorFrame
import `in`.driftzero.core.Wgs84
import `in`.driftzero.core.ZuptAccelMotionModel
import `in`.driftzero.core.withMapMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live vehicle pose for Compose and MapLibre. UI reads [state]; it must not
 * own GNSS or IMU. The 10 Hz sample is [DeadReckoningFilter].
 * [MotionPseudoRuntime] infers on [tick], not in the sensor callback.
 * [ZuptAccelMotionModel] uses `linear.json` when packed. A [DisplacementModel]
 * injects Δp when `linear_dp.json` loaded. Δp is χ²-gated and is not a
 * screening claim. The speed student is the live IMU measurement.
 * [navic] is chipset constellation counts. It is not a filter input.
 */
class PoseStore(
    private val filter: DeadReckoningFilter = DeadReckoningFilter(),
    private val motion: MotionPseudoRuntime = MotionPseudoRuntime(),
    private val clockNs: () -> Long,
    private var matcher: RoadMatcher? = null,
    private var graph: RoadGraph? = null,
    val navic: NavicMonitor = NavicMonitor(),
) {
    private val _state = MutableStateFlow<NavigationState?>(null)
    val state: StateFlow<NavigationState?> = _state.asStateFlow()
    private var edgesById: Map<String, GraphEdge> = graph?.edges?.associateBy { it.id } ?: emptyMap()

    private val _simulateGpsOff = MutableStateFlow(false)
    val simulateGpsOff: StateFlow<Boolean> = _simulateGpsOff.asStateFlow()
    private val _lastGnssSeenNs = MutableStateFlow<Long?>(null)
    val lastGnssSeenNs: StateFlow<Long?> = _lastGnssSeenNs.asStateFlow()
    private val ticks = TickIntervals()
    private val rawTrailBuf = RingBuffer<PosePoint>(TRAIL_CAP)
    private val fusedTrailBuf = RingBuffer<PosePoint>(TRAIL_CAP)
    private val modeStripBuf = RingBuffer<NavigationMode>(TRAIL_CAP)
    private var holdStartNs: Long? = null
    private var holdStart: PosePoint? = null
    private var recorder: TripRecorder? = null
    private var replayClockNs: Long? = null
    private val _replayActive = MutableStateFlow(false)
    val replayActive: StateFlow<Boolean> = _replayActive.asStateFlow()

    fun attachRecorder(next: TripRecorder?) {
        recorder = next
    }

    fun ingestGnss(fix: CoastFix) {
        if (_simulateGpsOff.value) {
            return
        }
        val now = nowNs()
        val ageS = (now.value - fix.timestamp.value) / NS_PER_S
        val stamped = if (ageS > RESTAMP_AFTER_S || ageS < 0.0) {
            fix.copy(timestamp = now)
        } else {
            fix
        }
        _lastGnssSeenNs.value = now.value
        rawTrailBuf.add(PosePoint(stamped.latitudeDeg, stamped.longitudeDeg))
        recorder?.offerGnss(stamped)
        filter.ingestGnss(stamped)
        publish()
    }

    fun ingestAccel(timestamp: Nanoseconds, x: Double, y: Double, z: Double) {
        recorder?.offerAccel(timestamp, x, y, z)
        filter.ingestAccel(timestamp, x, y, z, VectorFrame.ANDROID_DEVICE)
        motion.ingestAccel(timestamp, x, y, z)
    }

    fun ingestGyro(timestamp: Nanoseconds, x: Double, y: Double, z: Double) {
        recorder?.offerGyro(timestamp, x, y, z)
        filter.ingestGyro(timestamp, x, y, z, VectorFrame.ANDROID_DEVICE)
        motion.ingestGyro(timestamp, x, y, z)
    }

    fun ingestSensor(frame: SensorFrame) {
        filter.consume(frame)
        motion.ingestFrame(frame)
    }

    fun setSimulateGpsOff(off: Boolean) {
        _simulateGpsOff.value = off
        if (off) {
            navic.clear()
            holdStartNs = clockNowNs()
            val pose = _state.value
            holdStart = pose?.let { PosePoint(it.position.latitude.value, it.position.longitude.value) }
            recorder?.holdStart(holdStartNs!!)
        } else {
            val end = clockNowNs()
            recorder?.holdEnd(end)
            holdStartNs = null
            holdStart = null
        }
        filter.setGnssHeld(off)
        publish()
    }

    fun holdElapsedS(): Double? {
        val start = holdStartNs ?: return null
        return ((clockNowNs() - start) / NS_PER_S).coerceAtLeast(0.0)
    }

    fun holdDistanceM(): Double? {
        val start = holdStart ?: return null
        val pose = _state.value ?: return null
        return Wgs84.distanceMetres(
            start.latitudeDeg,
            start.longitudeDeg,
            pose.position.latitude.value,
            pose.position.longitude.value,
        )
    }

    fun toggleSimulateGpsOff() {
        setSimulateGpsOff(!_simulateGpsOff.value)
    }

    fun tick() {
        val now = nowNs()
        ticks.record(now.value)
        motion.inferAt(now)?.let { filter.ingestMotionPseudo(it, now) }
        motion.inferDisplacementAt(now)?.let { filter.ingestDisplacementPseudo(it, now) }
        publish()
    }

    fun tickAt(timestamp: Nanoseconds) {
        replayClockNs = timestamp.value
        tick()
    }

    fun beginReplay() {
        _replayActive.value = true
        replayClockNs = null
        attachRecorder(null)
        filter.reset(ResetReason.USER)
        matcher?.reset()
        rawTrailBuf.clear()
        fusedTrailBuf.clear()
        modeStripBuf.clear()
        _state.value = null
        _lastGnssSeenNs.value = null
        holdStartNs = null
        holdStart = null
        _simulateGpsOff.value = false
        filter.setGnssHeld(false)
    }

    fun endReplay() {
        replayClockNs = null
        _replayActive.value = false
    }

    fun p95GapMs(): Double? = ticks.p95Ms()

    fun rawTrail(): List<PosePoint> = rawTrailBuf.toList()

    fun fusedTrail(): List<PosePoint> = fusedTrailBuf.toList()

    fun modeStrip(): List<NavigationMode> = modeStripBuf.toList()

    fun setRoadGraph(next: RoadGraph?) {
        graph = next
        edgesById = next?.edges?.associateBy { it.id } ?: emptyMap()
        matcher = if (next == null || next.isEmpty()) {
            null
        } else {
            HmmRoadMatcher()
        }
        matcher?.reset()
        publish()
    }

    /** Centreline of the matched edge for the map overlay. Null unless the matcher is decided. */
    fun matchedRoad(state: NavigationState?): List<GeoPoint>? {
        if (state == null || state.mapMatch.status != MapMatchStatus.MATCHED) {
            return null
        }
        val id = state.mapMatch.roadSegmentId ?: return null
        return edgesById[id]?.points
    }

    private fun publish() {
        val raw = filter.poseAt(nowNs())
        val activeMatcher = matcher
        val activeGraph = graph
        _state.value = if (
            raw != null &&
            activeMatcher != null &&
            activeGraph != null &&
            !activeGraph.isEmpty()
        ) {
            raw.withMapMatch(activeMatcher.update(FilterSnapshot(raw), activeGraph))
        } else {
            raw
        }
        val pose = _state.value
        if (pose != null) {
            fusedTrailBuf.add(PosePoint(pose.position.latitude.value, pose.position.longitude.value))
            modeStripBuf.add(pose.mode)
            if (!_replayActive.value) {
                recorder?.offerState(pose)
            }
        }
    }

    fun clockNowNs(): Long = (replayClockNs ?: clockNs()).coerceAtLeast(0L)

    private fun nowNs(): Nanoseconds = Nanoseconds(clockNowNs())

    companion object {
        private const val NS_PER_S: Double = 1_000_000_000.0
        private const val RESTAMP_AFTER_S: Double = 5.0
        const val TRAIL_CAP: Int = 600
    }
}

data class PosePoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
)
