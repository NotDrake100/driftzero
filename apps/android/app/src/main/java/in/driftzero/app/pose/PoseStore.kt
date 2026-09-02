package `in`.driftzero.app.pose

import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.DisplacementModel
import `in`.driftzero.core.FilterSnapshot
import `in`.driftzero.core.HmmRoadMatcher
import `in`.driftzero.core.MotionPseudoRuntime
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.RoadGraph
import `in`.driftzero.core.RoadMatcher
import `in`.driftzero.core.SensorFrame
import `in`.driftzero.core.VectorFrame
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

    private val _simulateGpsOff = MutableStateFlow(false)
    val simulateGpsOff: StateFlow<Boolean> = _simulateGpsOff.asStateFlow()

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
        filter.ingestGnss(stamped)
        publish()
    }

    fun ingestAccel(timestamp: Nanoseconds, x: Double, y: Double, z: Double) {
        filter.ingestAccel(timestamp, x, y, z, VectorFrame.ANDROID_DEVICE)
        motion.ingestAccel(timestamp, x, y, z)
    }

    fun ingestGyro(timestamp: Nanoseconds, x: Double, y: Double, z: Double) {
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
        }
        filter.setGnssHeld(off)
        publish()
    }

    fun toggleSimulateGpsOff() {
        setSimulateGpsOff(!_simulateGpsOff.value)
    }

    fun tick() {
        val now = nowNs()
        motion.inferAt(now)?.let { filter.ingestMotionPseudo(it, now) }
        motion.inferDisplacementAt(now)?.let { filter.ingestDisplacementPseudo(it, now) }
        publish()
    }

    fun setRoadGraph(next: RoadGraph?) {
        graph = next
        matcher = if (next == null || next.isEmpty()) {
            null
        } else {
            HmmRoadMatcher()
        }
        matcher?.reset()
        publish()
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
    }

    private fun nowNs(): Nanoseconds = Nanoseconds(clockNs().coerceAtLeast(0L))

    companion object {
        private const val NS_PER_S: Double = 1_000_000_000.0
        private const val RESTAMP_AFTER_S: Double = 5.0
    }
}
