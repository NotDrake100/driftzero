package `in`.driftzero.app.pose

import `in`.driftzero.app.trips.TripRecorder
import `in`.driftzero.core.BlackoutInputs
import `in`.driftzero.core.BlackoutRisk
import `in`.driftzero.core.ClockDomain
import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.CoastMode
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.DriftBudgetTracker
import `in`.driftzero.core.GeoPoint
import `in`.driftzero.core.GraphEdge
import `in`.driftzero.core.HmmRoadMatcher
import `in`.driftzero.core.InsConfig
import `in`.driftzero.core.IntegritySnapshot
import `in`.driftzero.core.MapMatchResult
import `in`.driftzero.core.MapMatchStatus
import `in`.driftzero.core.MetresPerSecond
import `in`.driftzero.core.MotionPseudoMeasurement
import `in`.driftzero.core.MotionPseudoRuntime
import `in`.driftzero.core.MountImuEmit
import `in`.driftzero.core.MountQuality
import `in`.driftzero.core.MountSession
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.OptionalScalar
import `in`.driftzero.core.PhoneMotionGuard
import `in`.driftzero.core.Quality
import `in`.driftzero.core.ResetReason
import `in`.driftzero.core.MapCoastSession
import `in`.driftzero.core.RoadGraph
import `in`.driftzero.core.RoadHeadingDecision
import `in`.driftzero.core.RoadMatcher
import `in`.driftzero.core.SensorFrame
import `in`.driftzero.core.SensorKind
import `in`.driftzero.core.ShadowMapStore
import `in`.driftzero.core.Vector3Payload
import `in`.driftzero.core.VectorFrame
import `in`.driftzero.core.VectorPayload
import `in`.driftzero.core.Wgs84
import `in`.driftzero.core.withMapMatch
import kotlin.math.ln
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
 * A Ready `graph.bin` enables [MapCoastSession]: MATCHED heading plus
 * along-track Road DNA. Unmatched or ambiguous coasts inflate P. No
 * lateral snap. [navic] is chipset constellation counts. It is not a
 * filter input.
 *
 * Mount: after a still capture, [MountSession] holds gravity (m/s^2) and gyro
 * bias (rad/s) in the phone frame. Yaw is resolved only while GNSS is accepted,
 * using the GNSS speed-delta sign (ADR 007). Filter IMU is
 * [VectorFrame.VEHICLE_FLU] only at ALIGNED / ALIGNED_HIGH. Otherwise the
 * frame stays [VectorFrame.ANDROID_DEVICE]. Magnetometer is stored in uT and
 * is not fused.
 */
class PoseStore(
    private val filter: DeadReckoningFilter = liveFilter(),
    private val motion: MotionPseudoRuntime = MotionPseudoRuntime(),
    private val clockNs: () -> Long,
    private var matcher: RoadMatcher? = null,
    private var graph: RoadGraph? = null,
    val navic: NavicMonitor = NavicMonitor(),
    private val profiles: MountProfileStore = MountProfileStore.None,
    private val shadowStore: ShadowMapStore = ShadowMapStore(),
    private val mapCoast: MapCoastSession = MapCoastSession(),
) {
    private val _state = MutableStateFlow<NavigationState?>(null)
    val state: StateFlow<NavigationState?> = _state.asStateFlow()
    private var edgesById: Map<String, GraphEdge> = graph?.edges?.associateBy { it.id } ?: emptyMap()

    private val _simulateGpsOff = MutableStateFlow(false)
    val simulateGpsOff: StateFlow<Boolean> = _simulateGpsOff.asStateFlow()
    private val _lastGnssSeenNs = MutableStateFlow<Long?>(null)
    val lastGnssSeenNs: StateFlow<Long?> = _lastGnssSeenNs.asStateFlow()
    private val _lastTrustedFix = MutableStateFlow<PosePoint?>(null)
    val lastTrustedFix: StateFlow<PosePoint?> = _lastTrustedFix.asStateFlow()
    private val _coastedDistanceM = MutableStateFlow(0.0)
    val coastedDistanceM: StateFlow<Double> = _coastedDistanceM.asStateFlow()
    private val _correction = MutableStateFlow<CorrectionStroke?>(null)
    val correction: StateFlow<CorrectionStroke?> = _correction.asStateFlow()
    private val ticks = TickIntervals()
    private val rawTrailBuf = RingBuffer<PosePoint>(TRAIL_CAP)
    private val fusedTrailBuf = RingBuffer<PosePoint>(TRAIL_CAP)
    private val modeStripBuf = RingBuffer<NavigationMode>(TRAIL_CAP)
    private var holdStartNs: Long? = null
    private var holdStart: PosePoint? = null
    private var coastAnchor: PosePoint? = null
    private var recorder: TripRecorder? = null
    private var replayClockNs: Long? = null
    private val _replayActive = MutableStateFlow(false)
    val replayActive: StateFlow<Boolean> = _replayActive.asStateFlow()
    private val mount = MountSession()
    private val phoneMotion = PhoneMotionGuard()
    private var placementPending = false
    private val _mountQuality = MutableStateFlow(MountQuality.PENDING)
    val mountQuality: StateFlow<MountQuality> = _mountQuality.asStateFlow()
    private val _mountReason = MutableStateFlow<String?>(null)
    /** User-visible remount copy. Sheet owner should show this on the reason row. */
    val mountReason: StateFlow<String?> = _mountReason.asStateFlow()
    private val _mountYawConfidence = MutableStateFlow<Double?>(null)
    val mountYawConfidence: StateFlow<Double?> = _mountYawConfidence.asStateFlow()
    private val _roadDecision = MutableStateFlow<RoadHeadingDecision?>(null)
    /** Last [RoadHeadingAid.decide] while coasting. Null when not coasting or no match. */
    val roadDecision: StateFlow<RoadHeadingDecision?> = _roadDecision.asStateFlow()
    private val _lastAccelEmit = MutableStateFlow<MountImuEmit?>(null)
    val lastAccelEmit: StateFlow<MountImuEmit?> = _lastAccelEmit.asStateFlow()
    private val _lastGyroEmit = MutableStateFlow<MountImuEmit?>(null)
    val lastGyroEmit: StateFlow<MountImuEmit?> = _lastGyroEmit.asStateFlow()
    private val _magCapturedUnused = MutableStateFlow(false)
    /** True after a magnetometer frame was stored. Fusion does not use it. */
    val magCapturedUnused: StateFlow<Boolean> = _magCapturedUnused.asStateFlow()
    private val _lastMagnetometer = MutableStateFlow<MagnetometerSample?>(null)
    val lastMagnetometer: StateFlow<MagnetometerSample?> = _lastMagnetometer.asStateFlow()
    private var lastResetReason: ResetReason? = null
    private var lastGnssSpeedMps: Double? = null
    private var lastGnssSpeedDeltaMps: Double? = null
    private var lastGnssLatDeg: Double? = null
    private var lastGnssLonDeg: Double? = null
    private var lastAcceptedGnssNs: Long? = null
    private val stillDetector = LiveStillDetector()
    private var lastPushedStill: Boolean = false
    private var magSequence: Long = 0L
    private var auxSequence: Long = 0L
    private val driftTracker = DriftBudgetTracker()
    private val shadow = shadowStore.load()
    private val _integrity = MutableStateFlow<IntegritySnapshot?>(null)
    val integrity: StateFlow<IntegritySnapshot?> = _integrity.asStateFlow()
    private var travelledM: Double = 0.0
    private var lastTravel: PosePoint? = null
    private var lastAccuracyM: Double? = null
    private var lastAccuracyNs: Long? = null
    private var accuracyTrendMps: Double? = null
    private var lastGnssRestoredNs: Long? = null
    private var lastShadowSaveNs: Long = Long.MIN_VALUE
    private var pendingShadowJson: String? = null
    private val gate = Any()
    private val imuLock = Any()
    private val imuQueue = ArrayDeque<QueuedImu>(IMU_QUEUE_CAP)

    init {
        val json = profiles.loadJson()
        if (json != null) {
            mount.restoreFromJson(json)
            _mountQuality.value = mount.quality()
            _mountYawConfidence.value = yawConfidenceFromProfileJson(json)
        }
        mapCoast.setGraph(graph)
    }

    fun lastResetReason(): ResetReason? = lastResetReason

    fun attachRecorder(next: TripRecorder?) {
        recorder = next
    }

    fun ingestGnss(fix: CoastFix) {
        synchronized(gate) {
            drainImuLocked()
            ingestGnssLocked(fix)
        }
    }

    /**
     * Sensor callback path. Copies one sample. [tick] and [ingestGnss] drain
     * the queue. No filter, disk, or StateFlow work here.
     */
    internal fun offerImu(
        kind: QueuedImuKind,
        timestamp: Nanoseconds,
        x: Double,
        y: Double,
        z: Double,
        accuracyCode: Int = 2,
        biasX: Double? = null,
        biasY: Double? = null,
        biasZ: Double? = null,
    ) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return
        }
        synchronized(imuLock) {
            if (imuQueue.size == IMU_QUEUE_CAP) {
                imuQueue.removeFirst()
            }
            imuQueue.addLast(QueuedImu(kind, timestamp, x, y, z, accuracyCode, biasX, biasY, biasZ))
        }
    }

    fun takePendingShadowJson(): String? {
        synchronized(gate) {
            val json = pendingShadowJson
            pendingShadowJson = null
            return json
        }
    }

    fun writeShadowJson(json: String) {
        shadowStore.writeText(json)
    }

    fun coastPreconditionArmed(): Boolean = filter.coastPreconditionArmed()

    private fun ingestGnssLocked(fix: CoastFix) {
        if (_simulateGpsOff.value) {
            recorder?.offerGnss(fix, held = true)
            return
        }
        val now = nowNs()
        syncImuStill(now.value)
        val ageS = (now.value - fix.timestamp.value) / NS_PER_S
        val stamped = if (ageS > RESTAMP_AFTER_S || ageS < 0.0) {
            fix.copy(timestamp = now)
        } else {
            fix
        }
        val prev = _state.value
        val prevMode = prev?.mode
        val prevPoint = prev?.let { PosePoint(it.position.latitude.value, it.position.longitude.value) }
        val trustedAgeS = prev?.gnssHealth?.lastTrustedFixAgeS ?: 0.0
        val prevLat = lastGnssLatDeg
        val prevLon = lastGnssLonDeg
        val jumpM = if (prevLat != null && prevLon != null) {
            Wgs84.distanceMetres(prevLat, prevLon, stamped.latitudeDeg, stamped.longitudeDeg)
        } else {
            0.0
        }
        val dtS = lastAcceptedGnssNs?.let { (now.value - it) / NS_PER_S } ?: 0.0
        val still = lastPushedStill
        if (still && prevLat != null && LiveStillDetector.rejectGnssWhileStill(stamped.speedMps, jumpM, dtS)) {
            _lastGnssSeenNs.value = now.value
            noteGnssSpeed(stamped.speedMps)
            filter.noteGnssHeartbeat(now)
            publish()
            return
        }
        var measured = stamped.copy(
            horizontalAccuracyM = inflateGnssAccuracyAfterGap(
                stamped.horizontalAccuracyM,
                trustedAgeS,
                jumpM,
                staleAfterS = filter.gnssStaleAfterS(),
            ),
        )
        if (still) {
            measured = measured.copy(speedMps = 0.0, headingRad = null)
        } else if (measured.horizontalAccuracyM > LIVE_INS_CONFIG.degradedAccuracyM) {
            measured = measured.copy(speedMps = null, headingRad = null)
        }
        lastGnssLatDeg = stamped.latitudeDeg
        lastGnssLonDeg = stamped.longitudeDeg
        lastAcceptedGnssNs = now.value
        noteAccuracy(measured.horizontalAccuracyM, now.value)
        val accepted = PosePoint(measured.latitudeDeg, measured.longitudeDeg)
        val outageBefore = prevMode != null && BlackoutOverlay.isOutageMode(prevMode)
        if (!outageBefore) {
            _lastTrustedFix.value = accepted
        }
        _lastGnssSeenNs.value = now.value
        rawTrailBuf.add(accepted)
        recorder?.offerGnss(measured)
        noteGnssSpeed(measured.speedMps)
        filter.ingestGnss(measured)
        publish()
        val next = _state.value
        if (outageBefore && next?.mode == NavigationMode.REACQUIRING && prevPoint != null) {
            _correction.value = CorrectionStroke(from = prevPoint, to = accepted, startedNs = now.value)
        }
        if (next != null && !BlackoutOverlay.isOutageMode(next.mode)) {
            _lastTrustedFix.value = accepted
        }
    }

    /**
     * Phone-frame accelerometer, m/s^2, [VectorFrame.ANDROID_DEVICE]
     * (x right, y top of screen, z out of the screen). Filter input is rotated
     * to [VectorFrame.VEHICLE_FLU] (x forward, y left, z up) only after yaw
     * is ALIGNED / ALIGNED_HIGH. The student keeps the phone-frame sample.
     */
    fun ingestAccel(timestamp: Nanoseconds, x: Double, y: Double, z: Double, accuracyCode: Int = 2) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return
        }
        recorder?.offerAccel(timestamp, x, y, z, accuracyCode)
        motion.ingestAccel(timestamp, x, y, z)
        if (!_replayActive.value) {
            stillDetector.onAccel(timestamp.value, x, y, z)
            syncImuStill(timestamp.value)
        }
        if (_replayActive.value) {
            filter.ingestAccel(timestamp, x, y, z, VectorFrame.ANDROID_DEVICE)
            return
        }
        val still = !_replayActive.value && stillDetector.isStill(timestamp.value)
        val emit = mount.onAccel(
            timestamp.value,
            x,
            y,
            z,
            gnssSpeedDeltaMps = gnssSpeedDeltaForYaw(),
            gnssAccepted = gnssAcceptedForYaw(),
            phoneStill = still,
        )
        applyMountEmit(accel = true, emit = emit)
        filter.ingestAccel(timestamp, emit.x, emit.y, emit.z, emit.frame)
    }

    /**
     * Phone-frame gyroscope, rad/s, [VectorFrame.ANDROID_DEVICE]. When aligned,
     * phone-frame gyro bias from the still profile is subtracted, then the
     * vector is rotated into [VectorFrame.VEHICLE_FLU].
     */
    fun ingestGyro(timestamp: Nanoseconds, x: Double, y: Double, z: Double, accuracyCode: Int = 2) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return
        }
        recorder?.offerGyro(timestamp, x, y, z, accuracyCode)
        motion.ingestGyro(timestamp, x, y, z)
        if (!_replayActive.value) {
            stillDetector.onGyro(timestamp.value, x, y, z)
            syncImuStill(timestamp.value)
        }
        if (_replayActive.value) {
            filter.ingestGyro(timestamp, x, y, z, VectorFrame.ANDROID_DEVICE)
            return
        }
        val phone = phoneMotion.onGyro(timestamp.value, x, y, z)
        filter.setPhoneMotion(phone.handling || placementPending, phone.yawUpRadps, timestamp)
        val emit = mount.onGyro(timestamp.value, x, y, z)
        applyMountEmit(accel = false, emit = emit)
        filter.ingestGyro(timestamp, emit.x, emit.y, emit.z, emit.frame)
    }

    /**
     * Phone-frame magnetometer, microtesla (uT), [VectorFrame.ANDROID_DEVICE].
     * Stored and handed to [DeadReckoningFilter.consume], which no-ops
     * MAGNETOMETER. Does not change pose.
     */
    fun ingestMagnetometer(
        timestamp: Nanoseconds,
        xUt: Double,
        yUt: Double,
        zUt: Double,
        accuracyCode: Int = 2,
    ) {
        if (!xUt.isFinite() || !yUt.isFinite() || !zUt.isFinite()) {
            return
        }
        val code = accuracyCode.coerceIn(-1, 3)
        val frame = SensorFrame(
            sourceId = MAG_SOURCE_ID,
            sequence = magSequence,
            timestamp = timestamp,
            clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
            kind = SensorKind.MAGNETOMETER,
            quality = Quality(available = true, accuracyCode = code, flags = setOf(FLAG_MAG_UNUSED)),
            payload = VectorPayload(
                Vector3Payload(xUt, yUt, zUt, unit = MAG_UNIT, frame = VectorFrame.ANDROID_DEVICE),
            ),
        )
        magSequence += 1L
        _lastMagnetometer.value = MagnetometerSample(timestamp, xUt, yUt, zUt, VectorFrame.ANDROID_DEVICE)
        _magCapturedUnused.value = true
        recorder?.offerSensor(frame)
        filter.consume(frame)
    }

    /**
     * Gravity, linear acceleration, or uncalibrated gyro. Trip log only.
     * [DeadReckoningFilter.consume] ignores these kinds. Do not treat them
     * as accelerometer or gyroscope for the ESKF.
     */
    fun ingestLoggedImu(
        kind: SensorKind,
        timestamp: Nanoseconds,
        x: Double,
        y: Double,
        z: Double,
        unit: String,
        accuracyCode: Int = 2,
        biasX: Double? = null,
        biasY: Double? = null,
        biasZ: Double? = null,
    ) {
        if (kind != SensorKind.GRAVITY &&
            kind != SensorKind.LINEAR_ACCELERATION &&
            kind != SensorKind.GYROSCOPE_UNCALIBRATED
        ) {
            return
        }
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return
        }
        if (kind == SensorKind.GRAVITY && unit == "m/s^2" && accuracyCode > 0) {
            phoneMotion.onGravity(timestamp.value, x, y, z)
        }
        val frame = SensorFrame(
            sourceId = MAG_SOURCE_ID,
            sequence = auxSequence,
            timestamp = timestamp,
            clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
            kind = kind,
            quality = Quality(available = true, accuracyCode = accuracyCode.coerceIn(-1, 3)),
            payload = VectorPayload(
                Vector3Payload(
                    x,
                    y,
                    z,
                    unit = unit,
                    frame = VectorFrame.ANDROID_DEVICE,
                    biasX = biasX,
                    biasY = biasY,
                    biasZ = biasZ,
                ),
            ),
        )
        auxSequence += 1L
        recorder?.offerSensor(frame)
        filter.consume(frame)
    }

    fun ingestSensor(frame: SensorFrame) {
        if (frame.kind == SensorKind.MAGNETOMETER) {
            val vector = (frame.payload as? VectorPayload)?.vector
            if (vector != null) {
                ingestMagnetometer(frame.timestamp, vector.x, vector.y, vector.z, frame.quality.accuracyCode)
            } else {
                filter.consume(frame)
            }
            return
        }
        if (_replayActive.value) {
            filter.consume(frame)
            motion.ingestFrame(frame)
            return
        }
        when (frame.kind) {
            SensorKind.ACCELEROMETER -> {
                val v = (frame.payload as VectorPayload).vector
                ingestAccel(frame.timestamp, v.x, v.y, v.z)
            }
            SensorKind.GYROSCOPE -> {
                val v = (frame.payload as VectorPayload).vector
                ingestGyro(frame.timestamp, v.x, v.y, v.z)
            }
            SensorKind.GRAVITY, SensorKind.LINEAR_ACCELERATION, SensorKind.GYROSCOPE_UNCALIBRATED -> {
                val v = (frame.payload as VectorPayload).vector
                ingestLoggedImu(
                    frame.kind,
                    frame.timestamp,
                    v.x,
                    v.y,
                    v.z,
                    unit = v.unit,
                    accuracyCode = frame.quality.accuracyCode,
                    biasX = v.biasX,
                    biasY = v.biasY,
                    biasZ = v.biasZ,
                )
            }
            else -> {
                filter.consume(frame)
                motion.ingestFrame(frame)
            }
        }
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
        synchronized(gate) { publish() }
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
        synchronized(gate) {
            drainImuLocked()
            val now = nowNs()
            ticks.record(now.value)
            syncImuStill(now.value)
            if (!_replayActive.value && lastPushedStill) {
                filter.ingestMotionPseudo(STILL_ZUPT, now)
            } else {
                motion.inferAt(now)?.let { filter.ingestMotionPseudo(it, now) }
            }
            motion.inferDisplacementAt(now)?.let { filter.ingestDisplacementPseudo(it, now) }
            publish()
        }
    }

    fun tickAt(timestamp: Nanoseconds) {
        replayClockNs = timestamp.value
        tick()
    }

    fun beginReplay() {
        _replayActive.value = true
        replayClockNs = null
        attachRecorder(null)
        phoneMotion.reset()
        placementPending = false
        filter.reset(ResetReason.USER)
        lastResetReason = ResetReason.USER
        matcher?.reset()
        rawTrailBuf.clear()
        fusedTrailBuf.clear()
        modeStripBuf.clear()
        _state.value = null
        _lastGnssSeenNs.value = null
        _lastTrustedFix.value = null
        lastGnssLatDeg = null
        lastGnssLonDeg = null
        lastAcceptedGnssNs = null
        lastGnssSpeedMps = null
        lastGnssSpeedDeltaMps = null
        stillDetector.reset()
        lastPushedStill = false
        filter.setImuStill(false)
        _coastedDistanceM.value = 0.0
        _correction.value = null
        coastAnchor = null
        holdStartNs = null
        holdStart = null
        _simulateGpsOff.value = false
        filter.setGnssHeld(false)
        _roadDecision.value = null
        driftTracker.reset()
        mapCoast.reset()
        lastShadowSaveNs = Long.MIN_VALUE
        pendingShadowJson = null
        filter.clearCoastPrecondition()
        synchronized(imuLock) { imuQueue.clear() }
        _integrity.value = null
        travelledM = 0.0
        lastTravel = null
        lastAccuracyM = null
        lastAccuracyNs = null
        accuracyTrendMps = null
        lastGnssRestoredNs = null
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
        mapCoast.setGraph(next)
        synchronized(gate) { publish() }
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
        val now = nowNs()
        val raw = filter.poseAt(now)
        val activeMatcher = matcher
        val activeGraph = graph
        val matchResult = if (
            raw != null &&
            activeMatcher != null &&
            activeGraph != null &&
            !activeGraph.isEmpty()
        ) {
            mapCoast.match(raw, activeMatcher, activeGraph)
        } else {
            null
        }
        if (raw != null && matchResult != null && isCoastingNow()) {
            val report = mapCoast.apply(filter, matchResult, raw, now.value)
            _roadDecision.value = report.action.heading
        } else {
            _roadDecision.value = null
        }
        val afterAid = if (matchResult != null && isCoastingNow()) {
            filter.poseAt(now) ?: raw
        } else {
            raw
        }
        val matched = if (afterAid != null && matchResult != null) {
            afterAid.withMapMatch(matchResult)
        } else {
            afterAid
        }
        val displayed = if (matched != null && lastPushedStill && !_replayActive.value) {
            matched.copy(motion = matched.motion.copy(speed = MetresPerSecond(0.0)))
        } else {
            matched
        }
        _state.value = displayed?.let { decorateHealth(it) }
        val pose = _state.value
        if (pose != null) {
            val nextPoint = PosePoint(pose.position.latitude.value, pose.position.longitude.value)
            _coastedDistanceM.value = BlackoutOverlay.accumulateCoastM(
                _coastedDistanceM.value,
                coastAnchor,
                nextPoint,
                pose.mode,
            )
            coastAnchor = nextPoint
            fusedTrailBuf.add(nextPoint)
            modeStripBuf.add(pose.mode)
            accumulateTravel(nextPoint)
            noteShadow(pose, nextPoint)
            if (!_replayActive.value) {
                recorder?.offerState(pose)
            }
        }
        publishIntegrity(pose, matchResult, now)
    }

    fun clockNowNs(): Long = (replayClockNs ?: clockNs()).coerceAtLeast(0L)

    private fun nowNs(): Nanoseconds = Nanoseconds(clockNowNs())

    private fun noteGnssSpeed(speedMps: Double?) {
        if (speedMps == null || !speedMps.isFinite()) {
            return
        }
        val prev = lastGnssSpeedMps
        if (prev != null) {
            lastGnssSpeedDeltaMps = speedMps - prev
        }
        lastGnssSpeedMps = speedMps
    }

    private fun gnssAcceptedForYaw(): Boolean {
        if (_simulateGpsOff.value || _replayActive.value) {
            return false
        }
        val lastNs = _lastGnssSeenNs.value ?: return false
        val ageS = (clockNowNs() - lastNs) / NS_PER_S
        return ageS <= filter.gnssStaleAfterS()
    }

    private fun gnssSpeedDeltaForYaw(): Double? {
        if (!gnssAcceptedForYaw()) {
            return null
        }
        val delta = lastGnssSpeedDeltaMps ?: return null
        return if (delta.isFinite()) delta else null
    }

    private fun applyMountEmit(accel: Boolean, emit: MountImuEmit) {
        if (accel) {
            _lastAccelEmit.value = emit
        } else {
            _lastGyroEmit.value = emit
        }
        _mountQuality.value = emit.quality
        if (emit.profileChanged) {
            val saved = mount.profileJson()
            profiles.saveJson(saved)
            _mountYawConfidence.value = yawConfidenceFromProfileJson(saved)
        }
        if (emit.quality == MountQuality.ALIGNED || emit.quality == MountQuality.ALIGNED_HIGH) {
            placementPending = false
        }
        if (emit.quality != MountQuality.PENDING) {
            _mountReason.value = null
        }
        if (emit.remount) {
            synchronized(gate) {
                lastResetReason = ResetReason.REMOUNT
                placementPending = true
                phoneMotion.disturb(clockNowNs())
                filter.setPhoneMotion(true, null, Nanoseconds(clockNowNs()))
                matcher?.reset()
                profiles.saveJson(null)
                _mountReason.value = MountSession.REMOUNT_USER_REASON
                _mountQuality.value = MountQuality.PENDING
                _mountYawConfidence.value = null
                _roadDecision.value = null
                mapCoast.reset()
                publish()
            }
        }
    }

    /**
     * Held GNSS or age older than the live [DeadReckoningFilter.gnssStaleAfterS].
     * NHC stays in the filter. It already skips unless imuFrame is VEHICLE_FLU.
     */
    private fun isCoastingNow(): Boolean {
        if (_simulateGpsOff.value) {
            return true
        }
        val lastNs = _lastGnssSeenNs.value ?: return false
        return (clockNowNs() - lastNs) / NS_PER_S > filter.gnssStaleAfterS()
    }

    private fun syncImuStill(nowNs: Long) {
        if (_replayActive.value) {
            if (lastPushedStill) {
                lastPushedStill = false
                filter.setImuStill(false)
            }
            return
        }
        val still = stillDetector.isStill(nowNs)
        if (still != lastPushedStill) {
            lastPushedStill = still
            filter.setImuStill(still)
        }
    }

    private fun accumulateTravel(next: PosePoint) {
        val prev = lastTravel
        if (prev != null) {
            val step = Wgs84.distanceMetres(
                prev.latitudeDeg,
                prev.longitudeDeg,
                next.latitudeDeg,
                next.longitudeDeg,
            )
            if (step.isFinite() && step < 200.0) {
                travelledM += step
            }
        }
        lastTravel = next
    }

    private fun noteAccuracy(accuracyM: Double, nowNs: Long) {
        val prev = lastAccuracyM
        val prevNs = lastAccuracyNs
        if (prev != null && prevNs != null && nowNs > prevNs) {
            val dt = (nowNs - prevNs) / NS_PER_S
            if (dt >= 0.2) {
                accuracyTrendMps = (accuracyM - prev) / dt
            }
        }
        lastAccuracyM = accuracyM
        lastAccuracyNs = nowNs
    }

    private fun noteShadow(pose: NavigationState, point: PosePoint) {
        if (_replayActive.value) {
            return
        }
        val lost = pose.mode == NavigationMode.DEAD_RECKONING ||
            pose.mode == NavigationMode.LOW_CONFIDENCE ||
            pose.gnssHealth.lastTrustedFixAgeS > filter.gnssStaleAfterS()
        shadow.observe(
            latitudeDeg = point.latitudeDeg,
            longitudeDeg = point.longitudeDeg,
            lost = lost,
            accuracyM = lastAccuracyM,
            cn0DbHz = navic.visibility.value.meanUsedCn0DbHz,
        )
        val nowNs = clockNowNs()
        if (lastShadowSaveNs == Long.MIN_VALUE || nowNs - lastShadowSaveNs >= SHADOW_SAVE_MIN_NS) {
            pendingShadowJson = shadow.toJson()
            lastShadowSaveNs = nowNs
        }
    }

    private fun publishIntegrity(pose: NavigationState?, match: MapMatchResult?, now: Nanoseconds) {
        if (pose == null) {
            _integrity.value = null
            if (!_replayActive.value) {
                filter.clearCoastPrecondition()
            }
            return
        }
        val drift = driftTracker.update(
            timestampNs = now.value,
            horizontal95M = pose.uncertainty.horizontal95.value,
            speedMps = pose.motion.speed.value,
            travelledM = travelledM,
        )
        val snap = navic.visibility.value
        val usedSats = if (snap == NavicSnapshot.NONE) null else snap.used
        val mapPresent = graph != null && !graph!!.isEmpty()
        val heading = pose.motion.heading.value
        val tunnel = BlackoutRisk.tunnelAheadM(
            graph = graph,
            edgeId = match?.match?.roadSegmentId,
            alongM = match?.displayPose?.alongTrackM,
            headingRad = heading,
        )
        val tunnelM = (tunnel as? OptionalScalar.Available)?.value
        val occ = shadow.occupancyAhead(
            latitudeDeg = pose.position.latitude.value,
            longitudeDeg = pose.position.longitude.value,
            headingRad = heading,
        )
        val blackout = BlackoutRisk.evaluate(
            BlackoutInputs(
                usedSats = usedSats,
                meanUsedCn0DbHz = snap.meanUsedCn0DbHz,
                horizontalAccuracyM = lastAccuracyM,
                accuracyTrendMps = accuracyTrendMps,
                tunnelAheadM = tunnelM,
                shadowOccupancy = occ,
                mapPresent = mapPresent,
            ),
        )
        if (filter.gnssTrustJustReleased()) {
            lastGnssRestoredNs = now.value
        }
        val restored = lastGnssRestoredNs != null &&
            (now.value - lastGnssRestoredNs!!) / NS_PER_S < 8.0
        val next = IntegritySnapshot(
            drift = drift,
            blackout = blackout,
            gnssQuarantined = filter.gnssQuarantined(),
            gnssRestored = restored && !filter.gnssQuarantined(),
            placement = mount.placement(),
            travelledM = travelledM,
        )
        if (next != _integrity.value) {
            _integrity.value = next
        }
        if (_replayActive.value) {
            return
        }
        if (blackout.preconditioning) {
            filter.armCoastPrecondition(now, lastGnssSpeedMps)
        } else {
            filter.clearCoastPrecondition()
        }
    }

    private fun drainImuLocked() {
        val batch: List<QueuedImu>
        synchronized(imuLock) {
            if (imuQueue.isEmpty()) {
                return
            }
            batch = imuQueue.toList()
            imuQueue.clear()
        }
        for (sample in batch) {
            when (sample.kind) {
                QueuedImuKind.ACCEL -> ingestAccel(
                    sample.timestamp,
                    sample.x,
                    sample.y,
                    sample.z,
                    accuracyCode = sample.accuracyCode,
                )
                QueuedImuKind.GYRO -> ingestGyro(
                    sample.timestamp,
                    sample.x,
                    sample.y,
                    sample.z,
                    accuracyCode = sample.accuracyCode,
                )
                QueuedImuKind.MAG -> ingestMagnetometer(
                    sample.timestamp,
                    sample.x,
                    sample.y,
                    sample.z,
                    accuracyCode = sample.accuracyCode,
                )
                QueuedImuKind.GRAVITY -> ingestLoggedImu(
                    SensorKind.GRAVITY,
                    sample.timestamp,
                    sample.x,
                    sample.y,
                    sample.z,
                    unit = "m/s^2",
                    accuracyCode = sample.accuracyCode,
                )
                QueuedImuKind.LINEAR -> ingestLoggedImu(
                    SensorKind.LINEAR_ACCELERATION,
                    sample.timestamp,
                    sample.x,
                    sample.y,
                    sample.z,
                    unit = "m/s^2",
                    accuracyCode = sample.accuracyCode,
                )
                QueuedImuKind.GYRO_UNCAL -> ingestLoggedImu(
                    SensorKind.GYROSCOPE_UNCALIBRATED,
                    sample.timestamp,
                    sample.x,
                    sample.y,
                    sample.z,
                    unit = "rad/s",
                    accuracyCode = sample.accuracyCode,
                    biasX = sample.biasX,
                    biasY = sample.biasY,
                    biasZ = sample.biasZ,
                )
            }
        }
    }

    private fun decorateHealth(state: NavigationState): NavigationState {
        val extra = LinkedHashSet<String>(state.health.flags.size + 2)
        extra.addAll(state.health.flags)
        if (_magCapturedUnused.value) {
            extra.add(FLAG_MAG_CAPTURED_UNUSED)
        }
        if (_mountReason.value != null) {
            extra.add(FLAG_MOUNT_REMOUNT)
        }
        if (extra == state.health.flags) {
            return state
        }
        return state.copy(health = state.health.copy(flags = extra))
    }

    companion object {
        private const val NS_PER_S: Double = 1_000_000_000.0
        private const val RESTAMP_AFTER_S: Double = 5.0
        /** Live APK. Indoor GNSS gaps shorter than this stay GNSS or Assisted. */
        const val LIVE_STALE_AFTER_S: Double = 8.0
        const val TRAIL_CAP: Int = 600
        const val FLAG_MAG_CAPTURED_UNUSED: String = "mag_captured_unused"
        const val FLAG_MAG_UNUSED: String = "unused"
        const val FLAG_MOUNT_REMOUNT: String = "mount_remount"
        private const val MAG_SOURCE_ID: String = "phone"
        private const val MAG_UNIT: String = "uT"
        private const val SHADOW_SAVE_MIN_NS: Long = 5_000_000_000L
        private const val IMU_QUEUE_CAP: Int = 256
        private const val YAW_CONFIDENCE_KEY: String = "\"yaw_confidence\""

        /** Live APK coast model. Default [InsConfig] is STRAPDOWN for replay hashes. */
        val LIVE_INS_CONFIG: InsConfig = InsConfig(
            coastMode = CoastMode.YAW_SPEED_HOLD,
            staleAfterS = LIVE_STALE_AFTER_S,
            coastHonestP = true,
            studentForwardSpeed = true,
            coastLatchGnssSpeed = true,
            gnssQuarantine = true,
        )

        fun liveFilter(): DeadReckoningFilter = DeadReckoningFilter(LIVE_INS_CONFIG)

        private val STILL_ZUPT: MotionPseudoMeasurement = MotionPseudoMeasurement(
            forwardSpeed = MetresPerSecond(0.0),
            yawRateRadps = 0.0,
            stopProbability = 0.95,
            logSpeedVariance = ln(0.05 * 0.05),
            idle = true,
        )

        internal fun yawConfidenceFromProfileJson(json: String?): Double? {
            if (json.isNullOrEmpty()) {
                return null
            }
            val at = json.indexOf(YAW_CONFIDENCE_KEY)
            if (at < 0) {
                return null
            }
            val colon = json.indexOf(':', startIndex = at + YAW_CONFIDENCE_KEY.length)
            if (colon < 0) {
                return null
            }
            var end = colon + 1
            while (end < json.length && json[end].isWhitespace()) {
                end++
            }
            val start = end
            while (end < json.length) {
                val c = json[end]
                val digit = c in '0'..'9' || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E'
                if (!digit) {
                    break
                }
                end++
            }
            val value = json.substring(start, end).toDoubleOrNull() ?: return null
            return if (value.isFinite() && value in 0.0..1.0) value else null
        }
    }
}

data class PosePoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
)

internal enum class QueuedImuKind {
    ACCEL,
    GYRO,
    MAG,
    GRAVITY,
    LINEAR,
    GYRO_UNCAL,
}

private data class QueuedImu(
    val kind: QueuedImuKind,
    val timestamp: Nanoseconds,
    val x: Double,
    val y: Double,
    val z: Double,
    val accuracyCode: Int,
    val biasX: Double? = null,
    val biasY: Double? = null,
    val biasZ: Double? = null,
)
