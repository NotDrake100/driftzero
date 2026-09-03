package `in`.driftzero.core

import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Strapdown INS plus 15-state ESKF. IMU time is the sample timestamp
 * (SensorEvent.timestamp), never wall-clock arrival.
 *
 * Error state: δp, δv, δθ, ba, bg (Solà). Nominal INS is n-frame ENU
 * ([NFrameMechanization], Groves §5.4, Titterton §3.5.3). GNSS updates when
 * the fix is healthy and younger than [STALE_AFTER_S]. Otherwise propagate
 * only (dead reckoning).
 *
 * [InsConfig.coastMode] selects the coast model. [CoastMode.STRAPDOWN] keeps
 * full specific-force integration (v1/v2). [CoastMode.YAW_SPEED_HOLD] holds
 * horizontal speed and yaws from the gravity-vertical gyro while coasting.
 * Optional [InsConfig.coastStopDetect] ZUPTs from IMU vibration while held
 * speed would otherwise skip still-ZUPT. [InsConfig.weakHeadingPolicy]
 * HOLD_COURSE zeros yaw rate when the heading-gyro pick is weak.
 * [InsConfig.coastLatchGnssSpeed] latches a recent reported GNSS speed
 * (including accuracy-ok fixes the position gate rejected). After a unique-fix
 * gap of at least [InsConfig.gnssReseedAfterS] a sanity-ok GNSS re-seeds pose
 * instead of gating, except while already fused unless
 * [InsConfig.gnssReseedWhileFused]. The 15-state ESKF remains so ZUPT, NHC, and
 * [applyRoadHeading] still land. NHC runs only in [VectorFrame.VEHICLE_FLU].
 *
 * [MotionModel] is not owned here. [MotionPseudoRuntime] infers on the 10 Hz
 * worker and injects [MotionPseudoMeasurement] through [ingestMotionPseudo]
 * and [DisplacementPseudoMeasurement] through [ingestDisplacementPseudo].
 */
class DeadReckoningFilter(
    private val config: InsConfig = InsConfig(),
) {
    private val lock = Any()
    private val p = Square(EskfDim.N)
    private val phi = Square(EskfDim.N)
    private val tmp = Square(EskfDim.N)
    private val tmp2 = Square(EskfDim.N)
    private val joseph = JosephScratch()
    private val dx = DoubleArray(EskfDim.N)
    private val hRow = DoubleArray(EskfDim.N * 3)
    private val residual = DoubleArray(3)
    private val rMeas = DoubleArray(9)
    private val accelHist = DoubleArray(ACCEL_HIST)

    private var initialized: Boolean = false
    private var numericalOk: Boolean = true
    private var gnssHeld: Boolean = false
    private var originLatDeg: Double = 0.0
    private var originLonDeg: Double = 0.0
    private var originAltM: Double = 0.0
    private var eastM: Double = 0.0
    private var northM: Double = 0.0
    private var upM: Double = 0.0
    private var ve: Double = 0.0
    private var vn: Double = 0.0
    private var vu: Double = 0.0
    private var q: Quat = Quat.IDENTITY
    private var ba: Vec3 = Vec3.ZERO
    private var bg: Vec3 = Vec3.ZERO
    private var timeNs: Long = 0L
    private var lastAccel: Vec3? = null
    private var lastGyro: Vec3? = null
    private var lastImuNs: Long = -1L
    private var lastTrustedGnssNs: Long = -1L
    private var lastHeadingRad: Double = 0.0
    private var lastAltM: Double? = null
    private var lastHorizAccM: Double = 25.0
    private var imuFrame: VectorFrame = VectorFrame.ANDROID_DEVICE
    private var outputSequence: Long = 0L
    private var accelHistIdx: Int = 0
    private var accelHistCount: Int = 0
    private var lastZupt: Boolean = false
    private var lastNhc: Boolean = false
    private var lastRoadHeading: Boolean = false
    private var lastPseudo: Boolean = false
    private var lastDisplacement: Boolean = false
    private var lastDisplacementGated: Boolean = false
    private var lastAcceptedDisplacementNs: Long = -1L
    private var imuGap: Boolean = false
    private var lastStopProbability: Double = 0.0
    private var lastBump: Boolean = false
    private val clones: ArrayDeque<PoseClone> = ArrayDeque()
    private var coastedSinceFix: Boolean = false
    private var reacquiredFixes: Int = 0
    private var lastGatedGnssNs: Long = -1L
    private var speedBeforeCoast: Double = 0.0
    private var coastSnapshotTaken: Boolean = false
    private var heldSpeedMps: Double = 0.0
    private var coastHeadingRad: Double = 0.0
    private var lastAcceptedGnssSpeedMps: Double? = null
    private var lastAcceptedGnssSpeedNs: Long = -1L
    private var lastReportedGnssSpeedMps: Double? = null
    private var lastReportedGnssSpeedNs: Long = -1L
    private var consecutiveGnssGates: Int = 0
    private var gnssGateInflated: Boolean = false
    private var resumeSpeedMps: Double = 0.0
    private var coastStopped: Boolean = false
    private var quietSinceNs: Long = -1L
    private var noisySinceNs: Long = -1L
    private var burstUntilNs: Long = -1L
    private var resumeInhibitUntilNs: Long = -1L
    private var coastStopArmed: Boolean = !config.coastStopRequireStoppedPrefix
    private var stopCalibrated: Boolean = false
    private var coastStopDisarmed: Boolean = false
    private var effectiveStopAccelVar: Double = config.coastStopAccelVar
    private var effectiveStopGyroRadps: Double = config.coastStopGyroRadps
    private var headingPickWeak: Boolean = false
    private var headingPickForced: Boolean = false
    private var coastElapsedS: Double = 0.0
    private var lastGnssReseed: Boolean = false
    private var lastGnssAdmit: String = ""
    private var lastWouldAdmitWithoutReseed: Boolean = false
    private var lastAcceptedUniqueNs: Long = -1L
    private var lastAcceptedUniqueLat: Double = 0.0
    private var lastAcceptedUniqueLon: Double = 0.0
    /** Phone IMU still for the live path. Replay leaves this false. */
    private var imuStill: Boolean = false
    private val gnssTrail: ArrayDeque<TrailFix> = ArrayDeque()
    private val vib: ArrayDeque<VibSample> = ArrayDeque()
    private val prefixStoppedVars: ArrayList<Double> = ArrayList()
    private val prefixMovingVars: ArrayList<Double> = ArrayList()
    private val prefixStoppedGyros: ArrayList<Double> = ArrayList()
    private val prefixMovingGyros: ArrayList<Double> = ArrayList()

    fun setGnssHeld(held: Boolean) {
        synchronized(lock) {
            if (held && !gnssHeld) {
                if (config.coastLatchGnssSpeed) {
                    coastSnapshotTaken = false
                }
                captureCoastSnapshot()
            }
            gnssHeld = held
            if (!held && !isCoasting(timeNs)) {
                coastSnapshotTaken = false
            }
        }
    }

    fun isGnssHeld(): Boolean = synchronized(lock) { gnssHeld }

    /** Held GNSS or age older than [InsConfig.staleAfterS]. Same predicate as [applyRoadHeading]. */
    fun isCoastingAt(now: Nanoseconds): Boolean = synchronized(lock) { isCoasting(now.value) }

    fun noteRoadHeadingSkipped() {
        synchronized(lock) { lastRoadHeading = false }
    }

    /**
     * Live-phone table still. When true, still-ZUPT is not skipped for a
     * held vehicle speed, GNSS velocity is ignored, and ZUPT holds position.
     */
    fun setImuStill(still: Boolean) {
        synchronized(lock) {
            imuStill = still
        }
    }

    /** GNSS age (s) before [NavigationMode.DEAD_RECKONING] unless GNSS is held. */
    fun gnssStaleAfterS(): Double = synchronized(lock) { config.staleAfterS }

    /**
     * A live fix arrived but was not applied (table still vs jumpy indoor
     * GNSS). Keeps GNSS age fresh so a few-second drop is not a tunnel.
     */
    fun noteGnssHeartbeat(timestamp: Nanoseconds) {
        synchronized(lock) {
            if (!initialized || gnssHeld) {
                return
            }
            lastTrustedGnssNs = timestamp.value
        }
    }

    /**
     * Heading-gyro pick quality for [WeakHeadingPolicy.HOLD_COURSE].
     * [forced] true means Replay CLI wins over gyro quality flags.
     */
    fun setHeadingPickWeak(weak: Boolean, forced: Boolean = true) {
        synchronized(lock) {
            headingPickWeak = weak
            headingPickForced = forced
        }
    }

    fun noteQualityFlags(flags: Set<String>) {
        synchronized(lock) {
            if (headingPickForced) {
                return
            }
            if (flags.contains(FLAG_HEADING_PICK_WEAK)) {
                headingPickWeak = true
            }
        }
    }

    fun ingestAccel(
        timestamp: Nanoseconds,
        x: Double,
        y: Double,
        z: Double,
        frame: VectorFrame = VectorFrame.ANDROID_DEVICE,
    ) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return
        }
        synchronized(lock) {
            imuFrame = frame
            lastAccel = Vec3(x, y, z)
            onImu(timestamp.value)
        }
    }

    fun ingestGyro(
        timestamp: Nanoseconds,
        x: Double,
        y: Double,
        z: Double,
        frame: VectorFrame = VectorFrame.ANDROID_DEVICE,
    ) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return
        }
        synchronized(lock) {
            imuFrame = frame
            lastGyro = Vec3(x, y, z)
            onImu(timestamp.value)
        }
    }

    fun ingestGnss(fix: CoastFix) {
        synchronized(lock) {
            if (gnssHeld) {
                return
            }
            recordReportedGnssSpeed(fix)
            if (fix.horizontalAccuracyM <= config.maxGnssAccuracyM) {
                rememberFix(fix)
            }
            if (!initialized) {
                initializeFromGnss(fix)
                return
            }
            predictTo(fix.timestamp.value)
            if (!numericalOk) {
                return
            }
            if (fix.horizontalAccuracyM > config.maxGnssAccuracyM) {
                return
            }
            applyGnss(fix)
        }
    }

    /**
     * Speed / stop pseudo-measurement hook for a learned [MotionModel].
     * High stop probability triggers ZUPT. Otherwise forward speed is a
     * vehicle-x measurement. Yaw-rate is reserved for a later sibling.
     */
    fun ingestMotionPseudo(meas: MotionPseudoMeasurement, timestamp: Nanoseconds) {
        synchronized(lock) {
            if (!initialized) {
                return
            }
            lastStopProbability = meas.stopProbability
            predictTo(timestamp.value)
            if (!numericalOk) {
                return
            }
            applyMotionPseudo(meas)
        }
    }

    /**
     * TLIO-style 2D/3D Δp update. z is HACF displacement over the IMU window.
     * R comes from exp(2 log σ). Rejected when νᵀ S⁻¹ ν > [InsConfig.displacementChi2Gate]
     * (~11.345, 99th percentile, 3 dof). Overlapping 1 s windows inflate R ×10.
     * A cloned pose at [DisplacementPseudoMeasurement.windowStart] is the prior.
     * Full 15-state stochastic cloning is not implemented; the clone is treated
     * as known so H acts on current position only.
     */
    fun ingestDisplacementPseudo(meas: DisplacementPseudoMeasurement, timestamp: Nanoseconds) {
        synchronized(lock) {
            if (!initialized) {
                return
            }
            predictTo(timestamp.value)
            if (!numericalOk) {
                return
            }
            applyDisplacement(meas)
        }
    }

    /**
     * 1-dof road heading update. Joseph form. [RoadHeadingFeedback] and
     * Replay `--road-graph` call this while coasting.
     * Applied only while coasting. Position is restored after inject so the
     * bearing never snaps lat/lon. [speedHintMps] may rotate held speed onto
     * the accepted heading. Chi-square gate is [InsConfig.roadHeadingChi2Gate]
     * (default 6.63, 99th percentile, 1 dof).
     */
    fun applyRoadHeading(
        bearingRad: Double,
        stdRad: Double,
        speedHintMps: Double?,
    ): RoadHeadingResult {
        synchronized(lock) {
            lastRoadHeading = false
            if (!initialized || !numericalOk) {
                return RoadHeadingResult(accepted = false, reason = RoadHeadingReason.NOT_INITIALIZED)
            }
            if (!isCoasting(timeNs)) {
                return RoadHeadingResult(accepted = false, reason = RoadHeadingReason.NOT_COASTING)
            }
            if (!bearingRad.isFinite() || !stdRad.isFinite() || stdRad <= 0.0) {
                return RoadHeadingResult(accepted = false, reason = RoadHeadingReason.INVALID_STD)
            }
            val speed = hypot(ve, vn)
            val heading = headingRad(speed)
            val nu = wrapPi(bearingRad - heading)
            hRow.fill(0.0)
            hRow[EskfDim.ITH + 2] = 1.0
            residual[0] = nu
            rMeas.fill(0.0)
            rMeas[0] = stdRad * stdRad
            val chi2 = innovationChiSquared(p, hRow, residual, rMeas, 1, joseph)
                ?: return RoadHeadingResult(accepted = false, reason = RoadHeadingReason.NUMERICAL)
            if (chi2 > config.roadHeadingChi2Gate) {
                return RoadHeadingResult(accepted = false, reason = RoadHeadingReason.CHI2_REJECT, chi2 = chi2)
            }
            val east0 = eastM
            val north0 = northM
            val up0 = upM
            if (!josephUpdate(p, hRow, residual, rMeas, 1, dx, joseph)) {
                numericalOk = false
                return RoadHeadingResult(accepted = false, reason = RoadHeadingReason.NUMERICAL, chi2 = chi2)
            }
            val dYaw = dx[8]
            inject()
            eastM = east0
            northM = north0
            upM = up0
            val newHeading = wrapHeadingRad(heading + dYaw)
            val mag = when {
                speedHintMps != null && speedHintMps.isFinite() && speedHintMps >= 0.0 -> speedHintMps
                else -> hypot(ve, vn).let { if (it >= 0.05) it else speed }
            }
            ve = mag * sin(newHeading)
            vn = mag * cos(newHeading)
            if (config.coastMode == CoastMode.YAW_SPEED_HOLD) {
                coastHeadingRad = newHeading
                heldSpeedMps = mag
                vu = 0.0
            }
            lastHeadingRad = newHeading
            lastRoadHeading = true
            return RoadHeadingResult(accepted = true, reason = RoadHeadingReason.ACCEPTED, chi2 = chi2)
        }
    }

    fun consume(frame: SensorFrame) {
        if (!frame.quality.available) {
            return
        }
        when (frame.kind) {
            SensorKind.ACCELEROMETER -> {
                val v = (frame.payload as VectorPayload).vector
                ingestAccel(frame.timestamp, v.x, v.y, v.z, v.frame)
            }
            SensorKind.GYROSCOPE -> {
                noteQualityFlags(frame.quality.flags)
                val v = (frame.payload as VectorPayload).vector
                ingestGyro(frame.timestamp, v.x, v.y, v.z, v.frame)
            }
            SensorKind.GNSS_FIX -> {
                val fix = (frame.payload as FixPayload).fix
                ingestGnss(
                    CoastFix(
                        timestamp = frame.timestamp,
                        latitudeDeg = fix.latitude.value,
                        longitudeDeg = fix.longitude.value,
                        speedMps = fix.speedMps?.value,
                        headingRad = fix.bearingRad?.value,
                        horizontalAccuracyM = fix.horizontalAccuracyM.value,
                        altitudeM = fix.altitudeM,
                    ),
                )
            }
            SensorKind.MAGNETOMETER, SensorKind.GNSS_STATUS, SensorKind.RAW_GNSS -> Unit
        }
    }

    fun reset(reason: ResetReason) {
        synchronized(lock) {
            initialized = false
            numericalOk = true
            eastM = 0.0
            northM = 0.0
            upM = 0.0
            ve = 0.0
            vn = 0.0
            vu = 0.0
            q = Quat.IDENTITY
            ba = Vec3.ZERO
            bg = Vec3.ZERO
            lastTrustedGnssNs = -1L
            lastAcceptedUniqueNs = -1L
            lastAcceptedUniqueLat = 0.0
            lastAcceptedUniqueLon = 0.0
            imuStill = false
            lastImuNs = -1L
            accelHistCount = 0
            lastZupt = false
            lastNhc = false
            lastRoadHeading = false
            lastPseudo = false
            lastDisplacement = false
            lastDisplacementGated = false
            lastAcceptedDisplacementNs = -1L
            imuGap = false
            lastStopProbability = 0.0
            lastBump = false
            clones.clear()
            coastedSinceFix = false
            reacquiredFixes = 0
            lastGatedGnssNs = -1L
            speedBeforeCoast = 0.0
            coastSnapshotTaken = false
            heldSpeedMps = 0.0
            coastHeadingRad = 0.0
            lastAcceptedGnssSpeedMps = null
            lastAcceptedGnssSpeedNs = -1L
            lastReportedGnssSpeedMps = null
            lastReportedGnssSpeedNs = -1L
            consecutiveGnssGates = 0
            gnssGateInflated = false
            resumeSpeedMps = 0.0
            coastStopped = false
            quietSinceNs = -1L
            noisySinceNs = -1L
            burstUntilNs = -1L
            resumeInhibitUntilNs = -1L
            coastStopArmed = !config.coastStopRequireStoppedPrefix
            stopCalibrated = false
            coastStopDisarmed = false
            effectiveStopAccelVar = config.coastStopAccelVar
            effectiveStopGyroRadps = config.coastStopGyroRadps
            headingPickWeak = false
            headingPickForced = false
            coastElapsedS = 0.0
            lastGnssReseed = false
            lastGnssAdmit = ""
            lastWouldAdmitWithoutReseed = false
            gnssTrail.clear()
            vib.clear()
            prefixStoppedVars.clear()
            prefixMovingVars.clear()
            prefixStoppedGyros.clear()
            prefixMovingGyros.clear()
            p.zero()
            if (reason == ResetReason.USER || reason == ResetReason.REMOUNT) {
                gnssHeld = false
            }
        }
    }

    fun poseAt(now: Nanoseconds): NavigationState? {
        synchronized(lock) {
            if (!initialized) {
                return null
            }
            predictTo(now.value)
            if (!initialized || !numericalOk) {
                return null
            }
            val ageS = gnssAgeS(now.value)
            val coasting = isCoasting(now.value)
            val horizVar = max(p[0, 0] + p[1, 1], 0.0)
            val horizontal95 = 2.0 * sqrt(horizVar)
            val heading95 = 2.0 * sqrt(max(p[EskfDim.ITH + 2, EskfDim.ITH + 2], 0.0))
            val speed = hypot(ve, vn)
            val heading = wrapHeadingRad(headingRad(speed))
            val (lat, lon, alt) = Wgs84.enuToGeodetic(
                originLatDeg,
                originLonDeg,
                originAltM,
                eastM,
                northM,
                upM,
            )
            if (coasting) {
                coastedSinceFix = true
                reacquiredFixes = 0
            }
            val gatedRecently = lastGatedGnssNs >= 0L &&
                (now.value - lastGatedGnssNs) / NS_PER_S < config.gatedRecentS
            val degraded = lastHorizAccM > config.degradedAccuracyM || gatedRecently
            val mode = when {
                horizontal95 > config.lowConfidenceRadiusM -> NavigationMode.LOW_CONFIDENCE
                coasting -> NavigationMode.DEAD_RECKONING
                coastedSinceFix -> NavigationMode.REACQUIRING
                degraded -> NavigationMode.GNSS_DEGRADED
                else -> NavigationMode.GNSS_FUSED
            }
            val riskFlags = buildSet {
                if (coasting) add(RISK_STALE_GNSS)
                if (lastHorizAccM > config.degradedAccuracyM && !coasting) add(RISK_POOR_ACCURACY)
                if (gatedRecently) add(RISK_GATED_FIX)
                if (gnssGateInflated) add(RISK_GNSS_GATE_INFLATE)
                if (mode == NavigationMode.REACQUIRING) add(RISK_REACQUIRING)
            }
            val imuAgeS = if (lastImuNs < 0L) Double.POSITIVE_INFINITY else {
                (now.value - lastImuNs).coerceAtLeast(0L) / NS_PER_S
            }
            val flags = buildSet {
                add(FLAG_ESKF)
                if (gnssHeld) add(FLAG_GPS_HELD)
                if (lastZupt) add(FLAG_ZUPT)
                if (lastNhc) add(FLAG_NHC)
                if (lastRoadHeading) add(FLAG_ROAD_HEADING)
                if (lastPseudo) add(FLAG_MOTION_PSEUDO)
                if (lastDisplacement) add(FLAG_DISPLACEMENT_PSEUDO)
                if (lastDisplacementGated) add(FLAG_DISPLACEMENT_GATED)
                if (imuGap) add(FLAG_IMU_GAP)
                if (imuAgeS > 0.5) add(FLAG_NO_IMU)
                if (gnssGateInflated) add(FLAG_GNSS_GATE_INFLATE)
                if (holdCourseActive()) add(FLAG_WEAK_HEADING_HOLD)
                if (coastStopDisarmed) add(FLAG_COAST_STOP_DISARMED)
                if (lastGnssReseed) add(FLAG_GNSS_RESEED)
                if (lastWouldAdmitWithoutReseed) add(FLAG_GNSS_WOULD_ADMIT)
            }
            val score = if (coasting) {
                (1.0 / (1.0 + ageS)).coerceIn(0.0, 1.0)
            } else {
                (1.0 / (1.0 + lastHorizAccM / 20.0)).coerceIn(0.0, 1.0)
            }
            outputSequence += 1L
            return NavigationState(
                sequence = outputSequence - 1L,
                timestamp = now,
                mode = mode,
                position = GeoPoint(
                    latitude = LatitudeDeg(lat),
                    longitude = LongitudeDeg(lon),
                    altitudeM = lastAltM ?: alt,
                ),
                motion = Motion(
                    speed = MetresPerSecond(speed.coerceAtLeast(0.0)),
                    heading = HeadingRadians(heading),
                ),
                uncertainty = Uncertainty(
                    horizontal95 = Metres(horizontal95.coerceAtLeast(0.5)),
                    heading95Rad = heading95.coerceAtLeast(1e-3),
                    isCalibrated = lastImuNs >= 0L && lastTrustedGnssNs >= 0L,
                ),
                gnssHealth = GnssHealth(
                    score = score,
                    lastTrustedFixAgeS = ageS,
                    riskFlags = riskFlags,
                ),
                mapMatch = MapMatch(MapMatchStatus.NO_MAP, 0.0),
                health = ComponentHealth(
                    sensorOk = imuAgeS < 1.0 || !coasting,
                    modelOk = lastPseudo || lastDisplacement,
                    filterOk = numericalOk,
                    mapOk = false,
                    flags = flags,
                ),
                provenance = Provenance(
                    coreVersion = CORE_VERSION,
                    configHash = CONFIG_HASH,
                ),
            )
        }
    }

    internal fun velocityEnu(): Vec3 = synchronized(lock) { Vec3(ve, vn, vu) }

    internal fun heldSpeedForTest(): Double = synchronized(lock) { heldSpeedMps }

    internal fun coastStoppedForTest(): Boolean = synchronized(lock) { coastStopped }

    internal fun coastStopArmedForTest(): Boolean = synchronized(lock) { coastStopArmed }

    internal fun headingPickWeakForTest(): Boolean = synchronized(lock) { headingPickWeak }

    internal fun lastGnssAdmitForTest(): String = synchronized(lock) { lastGnssAdmit }

    internal fun lastWouldAdmitWithoutReseedForTest(): Boolean = synchronized(lock) { lastWouldAdmitWithoutReseed }

    internal fun plantReportedGnssSpeedForTest(speed: Double, tNs: Long) {
        synchronized(lock) {
            lastReportedGnssSpeedMps = speed
            lastReportedGnssSpeedNs = tNs
        }
    }

    internal fun positionEnu(): Vec3 = synchronized(lock) { Vec3(eastM, northM, upM) }

    internal fun horizontalVariance(): Double = synchronized(lock) { p[0, 0] + p[1, 1] }

    internal fun attitude(): Quat = synchronized(lock) { q }

    internal fun seedForTest(
        timestamp: Nanoseconds,
        latitudeDeg: Double,
        longitudeDeg: Double,
        velocityEnu: Vec3,
        quat: Quat,
        posStdM: Double,
        frame: VectorFrame = VectorFrame.VEHICLE_FLU,
        headingRad: Double = 0.0,
    ) {
        synchronized(lock) {
            imuFrame = frame
            originLatDeg = latitudeDeg
            originLonDeg = longitudeDeg
            originAltM = 0.0
            eastM = 0.0
            northM = 0.0
            upM = 0.0
            ve = velocityEnu.x
            vn = velocityEnu.y
            vu = velocityEnu.z
            q = quat.normalized()
            ba = Vec3.ZERO
            bg = Vec3.ZERO
            timeNs = timestamp.value
            lastTrustedGnssNs = timestamp.value
            lastAcceptedUniqueNs = timestamp.value
            lastAcceptedUniqueLat = latitudeDeg
            lastAcceptedUniqueLon = longitudeDeg
            lastHeadingRad = headingRad
            lastHorizAccM = posStdM
            lastAcceptedGnssSpeedMps = hypot(velocityEnu.x, velocityEnu.y)
            lastAcceptedGnssSpeedNs = timestamp.value
            lastReportedGnssSpeedMps = lastAcceptedGnssSpeedMps
            lastReportedGnssSpeedNs = timestamp.value
            gnssTrail.clear()
            gnssTrail.addLast(
                TrailFix(latitudeDeg, longitudeDeg, timestamp.value, hypot(velocityEnu.x, velocityEnu.y), headingRad),
            )
            initialized = true
            numericalOk = true
            lastDisplacement = false
            lastDisplacementGated = false
            lastRoadHeading = false
            lastAcceptedDisplacementNs = -1L
            clones.clear()
            setInitialP(posStdM)
            recordClone()
        }
    }

    internal fun plantEnuForTest(position: Vec3? = null, velocity: Vec3? = null) {
        synchronized(lock) {
            if (position != null) {
                eastM = position.x
                northM = position.y
                upM = position.z
            }
            if (velocity != null) {
                ve = velocity.x
                vn = velocity.y
                vu = velocity.z
            }
        }
    }

    private fun onImu(tNs: Long) {
        if (!initialized) {
            lastImuNs = tNs
            return
        }
        predictTo(tNs)
        lastImuNs = tNs
        if (numericalOk) {
            if (config.coastStopDetect) {
                val accel = lastAccel
                val gyro = lastGyro
                if (accel != null && gyro != null) {
                    pushVib(tNs, accel.norm(), gyro.norm())
                    if (!isCoasting(tNs)) {
                        observePrefixVariance()
                    }
                }
            }
            maybeConstraints()
        }
    }

    private fun initializeFromGnss(fix: CoastFix) {
        originLatDeg = fix.latitudeDeg
        originLonDeg = fix.longitudeDeg
        originAltM = fix.altitudeM ?: 0.0
        lastAltM = fix.altitudeM
        lastHorizAccM = fix.horizontalAccuracyM
        eastM = 0.0
        northM = 0.0
        upM = 0.0
        val heading = fix.headingRad ?: lastHeadingRad
        lastHeadingRad = wrapHeadingRad(heading)
        val speed = if (imuStill) 0.0 else (fix.speedMps ?: 0.0)
        ve = speed * sin(lastHeadingRad)
        vn = speed * cos(lastHeadingRad)
        vu = 0.0
        lastAcceptedGnssSpeedMps = speed
        lastAcceptedGnssSpeedNs = fix.timestamp.value
        lastReportedGnssSpeedMps = speed
        lastReportedGnssSpeedNs = fix.timestamp.value
        val accel = lastAccel
        q = if (accel != null) {
            attitudeFromGravityAndHeading(accel, lastHeadingRad, imuFrame) ?: yawOnlyAttitude(lastHeadingRad, imuFrame)
        } else {
            yawOnlyAttitude(lastHeadingRad, imuFrame)
        }
        ba = Vec3.ZERO
        bg = Vec3.ZERO
        timeNs = fix.timestamp.value
        lastTrustedGnssNs = fix.timestamp.value
        initialized = true
        numericalOk = true
        clones.clear()
        setInitialP(max(fix.horizontalAccuracyM, 3.0))
        rememberFix(fix)
        noteAcceptedUnique(fix)
        recordClone()
    }

    private fun setInitialP(posStdM: Double) {
        p.zero()
        val ps = posStdM * posStdM
        val vs = config.initVelStdMps * config.initVelStdMps
        val tilt = (config.initTiltStdRad * config.initTiltStdRad)
        val yaw = (config.initYawStdRad * config.initYawStdRad)
        val baVar = config.initAccelBiasStd * config.initAccelBiasStd
        val bgVar = config.initGyroBiasStd * config.initGyroBiasStd
        p[0, 0] = ps
        p[1, 1] = ps
        p[2, 2] = ps * 2.25
        p[3, 3] = vs
        p[4, 4] = vs
        p[5, 5] = vs
        p[6, 6] = tilt
        p[7, 7] = tilt
        p[8, 8] = yaw
        p[9, 9] = baVar
        p[10, 10] = baVar
        p[11, 11] = baVar
        p[12, 12] = bgVar
        p[13, 13] = bgVar
        p[14, 14] = bgVar
    }

    private fun predictTo(tNs: Long) {
        if (!initialized || tNs <= timeNs) {
            return
        }
        val dt = (tNs - timeNs) / NS_PER_S
        if (dt > config.maxIntegrateS) {
            imuGap = true
            noteCoastEntry(tNs)
            coastVelocity(dt)
            inflateForGap(dt)
            timeNs = tNs
            recordClone()
            return
        }
        val gyro = lastGyro
        val accel = lastAccel
        if (gyro == null || accel == null) {
            noteCoastEntry(tNs)
            coastVelocity(dt)
            timeNs = tNs
            recordClone()
            return
        }
        noteCoastEntry(tNs)
        val yawHold = config.coastMode == CoastMode.YAW_SPEED_HOLD && isCoasting(tNs)
        val fNav = if (yawHold) {
            yawSpeedHold(dt, gyro, accel, tNs)
            Vec3.ZERO
        } else {
            strapdown(dt, gyro, accel)
        }
        predictCovariance(dt, fNav)
        if (yawHold) {
            growCoastHalo(dt)
        }
        timeNs = tNs
        reanchorIfNeeded()
        if (!q.isFinite() || !pIsHealthy(p) || !eastM.isFinite() || !ve.isFinite()) {
            reset(ResetReason.NUMERICAL)
            return
        }
        recordClone()
    }

    private fun strapdown(dt: Double, gyroMeas: Vec3, accelMeas: Vec3): Vec3 {
        val lat = currentLatitudeDeg()
        val alt = currentAltitudeM()
        val next = NFrameMechanization.advance(
            NFrameState(q, Vec3(ve, vn, vu), Vec3(eastM, northM, upM)),
            gyroMeas - bg,
            accelMeas - ba,
            dt,
            lat,
            alt,
        )
        q = next.state.attitude
        ve = next.state.velocityEnu.x
        vn = next.state.velocityEnu.y
        vu = next.state.velocityEnu.z
        eastM = next.state.positionEnu.x
        northM = next.state.positionEnu.y
        upM = next.state.positionEnu.z
        pushAccelHist(hypot3(next.navAccel.x, next.navAccel.y, next.navAccel.z))
        return next.specificForceNav
    }

    /**
     * Reduced-order coast for [CoastMode.YAW_SPEED_HOLD].
     *
     * Specific force (m/s², IMU body frame minus accel bias) is not integrated
     * into velocity. Horizontal speed is held in m/s. Vertical velocity is 0.
     *
     * Body gyro (rad/s, IMU frame minus gyro bias) is rotated into n-frame ENU
     * with the current attitude. The ENU-up component is the heading rate.
     * Navigation heading is clockwise from north (0 = north, positive toward
     * east), matching persist: `heading += omega_up * dt`. Horizontal velocity
     * is the held speed rotated by that heading. Position is metres ENU.
     *
     * Attitude still follows the body gyro so ZUPT, NHC, and later road
     * updates have a quaternion to land on.
     */
    private fun yawSpeedHold(dt: Double, gyroMeas: Vec3, accelMeas: Vec3, tNs: Long) {
        val omegaBody = gyroMeas - bg
        val omegaNav = q.toRotation() * omegaBody
        val omegaUp = if (holdCourseActive()) 0.0 else omegaNav.z
        if (!coastStopped) {
            coastHeadingRad = wrapHeadingRad(coastHeadingRad + omegaUp * dt)
        }
        q = NFrameMechanization.integrateAttitude(q, omegaBody, dt)
        val lat = currentLatitudeDeg()
        val alt = currentAltitudeM()
        val fNav = q.toRotation() * (accelMeas - ba)
        val aNav = NFrameMechanization.navAccelFromSpecificForce(fNav, lat, alt)
        if (coastStopped) {
            heldSpeedMps = 0.0
            ve = 0.0
            vn = 0.0
            vu = 0.0
            lastHeadingRad = coastHeadingRad
            pushAccelHist(hypot3(aNav.x, aNav.y, aNav.z))
            return
        }
        if (burstUntilNs > 0L && tNs < burstUntilNs) {
            val aFwd = aNav.x * sin(coastHeadingRad) + aNav.y * cos(coastHeadingRad)
            val acc = aFwd.coerceIn(-config.coastRestartAccelClipMps2, config.coastRestartAccelClipMps2)
            heldSpeedMps = (heldSpeedMps + acc * dt).coerceAtLeast(0.0)
        } else if (burstUntilNs > 0L && tNs >= burstUntilNs) {
            burstUntilNs = -1L
        }
        if (!coastStopped && config.coastSpeedDecay && config.coastSpeedDecayTauS > 0.0) {
            val alpha = 1.0 - exp(-dt / config.coastSpeedDecayTauS)
            heldSpeedMps = (heldSpeedMps + (config.coastSpeedDecayTargetMps - heldSpeedMps) * alpha)
                .coerceAtLeast(0.0)
        }
        ve = heldSpeedMps * sin(coastHeadingRad)
        vn = heldSpeedMps * cos(coastHeadingRad)
        vu = 0.0
        eastM += ve * dt
        northM += vn * dt
        lastHeadingRad = coastHeadingRad
        pushAccelHist(hypot3(aNav.x, aNav.y, aNav.z))
    }

    private fun growCoastHalo(dt: Double) {
        if (config.coastHonestP) {
            coastElapsedS += dt
            val speed = if (coastSnapshotTaken) heldSpeedMps else hypot(ve, vn)
            val sigma = hypot(config.coastSpeedRwMps, speed * config.coastHeadingRwRadps)
            val t0 = (coastElapsedS - dt).coerceAtLeast(0.0)
            val add = 2.0 * sigma * sigma * t0 * dt + (sigma * dt) * (sigma * dt)
            p[0, 0] += add
            p[1, 1] += add
            val yaw = config.coastHeadingRwRadps * dt
            val i = EskfDim.ITH + 2
            p[i, i] += yaw * yaw
        } else {
            val grow = config.coastPosGrowMps * dt
            p[0, 0] += grow * grow
            p[1, 1] += grow * grow
        }
        if (holdCourseActive()) {
            val yaw = config.weakHeadingGrowRadps * dt
            val i = EskfDim.ITH + 2
            p[i, i] += yaw * yaw
        }
        symmetrize(p)
    }

    private fun holdCourseActive(): Boolean {
        return config.weakHeadingPolicy == WeakHeadingPolicy.HOLD_COURSE &&
            headingPickWeak &&
            isCoasting(timeNs)
    }

    private fun gnssAgeS(atNs: Long): Double {
        return if (lastTrustedGnssNs < 0L) {
            (atNs - timeNs).coerceAtLeast(0L) / NS_PER_S
        } else {
            (atNs - lastTrustedGnssNs).coerceAtLeast(0L) / NS_PER_S
        }
    }

    private fun isCoasting(atNs: Long): Boolean = gnssHeld || gnssAgeS(atNs) > config.staleAfterS

    private fun noteCoastEntry(atNs: Long) {
        if (isCoasting(atNs)) {
            captureCoastSnapshot()
        } else if (coastSnapshotTaken) {
            coastSnapshotTaken = false
        }
    }

    private fun captureCoastSnapshot() {
        if (coastSnapshotTaken) {
            return
        }
        speedBeforeCoast = hypot(ve, vn)
        heldSpeedMps = chooseHeldSpeed()
        coastHeadingRad = if (speedBeforeCoast >= 0.5) {
            atan2(ve, vn)
        } else {
            lastHeadingRad
        }
        coastSnapshotTaken = true
        resumeSpeedMps = heldSpeedMps
        coastElapsedS = 0.0
        if (config.coastMode == CoastMode.YAW_SPEED_HOLD) {
            vu = 0.0
            ve = heldSpeedMps * sin(coastHeadingRad)
            vn = heldSpeedMps * cos(coastHeadingRad)
        }
        if (config.coastStopDetect) {
            finalizeStopCalibration()
        }
    }

    private fun chooseHeldSpeed(): Double {
        if (config.coastLatchGnssSpeed) {
            val reported = lastReportedGnssSpeedMps
            val stamp = lastReportedGnssSpeedNs
            if (reported != null && reported >= 0.0 && stamp >= 0L) {
                val ageS = (timeNs - stamp).coerceAtLeast(0L) / NS_PER_S
                if (ageS <= config.coastLatchGnssMaxS) {
                    return reported
                }
            }
            return speedBeforeCoast
        }
        val accepted = lastAcceptedGnssSpeedMps
        return if (accepted != null && accepted >= 0.0) accepted else speedBeforeCoast
    }

    private fun recordReportedGnssSpeed(fix: CoastFix) {
        val speed = fix.speedMps ?: return
        if (fix.horizontalAccuracyM > config.maxGnssAccuracyM) {
            return
        }
        lastReportedGnssSpeedMps = speed
        lastReportedGnssSpeedNs = fix.timestamp.value
    }

    private fun syncHeldSpeedFromFilter() {
        if (config.coastMode != CoastMode.YAW_SPEED_HOLD || !coastSnapshotTaken) {
            return
        }
        heldSpeedMps = hypot(ve, vn)
        if (!coastStopped && heldSpeedMps >= 0.5) {
            coastHeadingRad = atan2(ve, vn)
        }
    }

    private fun coastVelocity(dt: Double) {
        eastM += ve * dt
        northM += vn * dt
        upM += vu * dt
        p[0, 0] += (config.coastPosGrowMps * dt) * (config.coastPosGrowMps * dt)
        p[1, 1] += (config.coastPosGrowMps * dt) * (config.coastPosGrowMps * dt)
        symmetrize(p)
    }

    private fun inflateForGap(dt: Double) {
        val grow = (config.coastPosGrowMps * dt)
        p[0, 0] += grow * grow
        p[1, 1] += grow * grow
        p[3, 3] += 1.0
        p[4, 4] += 1.0
        symmetrize(p)
    }

    private fun predictCovariance(dt: Double, fNav: Vec3) {
        val c = q.toRotation()
        val lat = currentLatitudeDeg()
        val alt = currentAltitudeM()
        fillStrapdownPhi(
            phi,
            dt,
            fNav,
            c,
            NFrameMechanization.gravityGradientUpPerMetre(lat, alt),
        )
        matMul(tmp, phi, p)
        matMulABt(tmp2, tmp, phi)
        p.copyFrom(tmp2)
        addImuProcessNoise(
            p,
            dt,
            config.accelNoise,
            config.gyroNoise,
            config.accelBiasRw,
            config.gyroBiasRw,
        )
        symmetrize(p)
    }

    private fun applyGnss(fix: CoastFix) {
        val (e, n, u) = Wgs84.geodeticToEnu(
            originLatDeg,
            originLonDeg,
            originAltM,
            fix.latitudeDeg,
            fix.longitudeDeg,
            fix.altitudeM ?: originAltM + upM,
        )
        residual[0] = e - eastM
        residual[1] = n - northM
        residual[2] = u - upM
        val sigma = max(fix.horizontalAccuracyM, 1.0)
        val horizInnov = hypot(residual[0], residual[1])
        val pHoriz = sqrt(max(p[0, 0] + p[1, 1], 0.0))
        val gate = 6.0 * (sigma + pHoriz)
        val wouldAdmit = horizInnov <= gate
        lastWouldAdmitWithoutReseed = wouldAdmit
        if (imuStill) {
            val reported = fix.speedMps ?: 0.0
            if (reported > 1.0 || horizInnov > 8.0) {
                lastTrustedGnssNs = fix.timestamp.value
                return
            }
        }
        val gapS = if (lastTrustedGnssNs < 0L) 0.0 else (fix.timestamp.value - lastTrustedGnssNs) / NS_PER_S
        val uniqueGapS = if (lastAcceptedUniqueNs < 0L) {
            0.0
        } else {
            (fix.timestamp.value - lastAcceptedUniqueNs) / NS_PER_S
        }
        val fused = !isCoasting(fix.timestamp.value)
        val wantReseed = config.gnssReseedAfterS > 0.0 &&
            uniqueGapS >= config.gnssReseedAfterS &&
            canReseed(fix) &&
            (!fused || config.gnssReseedWhileFused)
        if (wantReseed) {
            reseedFromGnss(fix, e, n, u)
            noteAcceptedUnique(fix)
            return
        }
        lastGnssReseed = false
        var inflatedThisFix = false
        if (horizInnov > gate) {
            lastGatedGnssNs = fix.timestamp.value
            consecutiveGnssGates += 1
            lastGnssAdmit = GNSS_GATE_REJECT
            if (coastedSinceFix) {
                reacquiredFixes = 0
            }
            val pSmall = pHoriz < config.gnssGateInflateMaxSigmaM
            if (config.gnssGateInflate && consecutiveGnssGates >= config.gnssGateRejectsBeforeInflate && pSmall) {
                inflateGnssGateLock(horizInnov)
                gnssGateInflated = true
                inflatedThisFix = true
                val opened = 6.0 * (sigma + sqrt(max(p[0, 0] + p[1, 1], 0.0)))
                if (horizInnov > opened) {
                    return
                }
            } else {
                return
            }
        }
        lastGnssAdmit = GNSS_GATE_ADMIT
        if (!inflatedThisFix) {
            gnssGateInflated = false
        }
        consecutiveGnssGates = 0
        fillPosH()
        val su = max(sigma * 1.5, 3.0)
        rMeas[0] = sigma * sigma
        rMeas[1] = 0.0
        rMeas[2] = 0.0
        rMeas[3] = 0.0
        rMeas[4] = sigma * sigma
        rMeas[5] = 0.0
        rMeas[6] = 0.0
        rMeas[7] = 0.0
        rMeas[8] = su * su
        if (!josephUpdate(p, hRow, residual, rMeas, 3, dx, joseph)) {
            numericalOk = false
            return
        }
        inject()
        if (gapS > config.staleAfterS) {
            coastedSinceFix = true
            reacquiredFixes = 0
        }
        lastTrustedGnssNs = fix.timestamp.value
        lastHorizAccM = fix.horizontalAccuracyM
        if (!gnssHeld) {
            coastSnapshotTaken = false
        }
        coastElapsedS = 0.0
        val acceptedSpeed = fix.speedMps
        if (acceptedSpeed != null && acceptedSpeed >= config.gnssReseedSlowMps) {
            lastAcceptedGnssSpeedMps = acceptedSpeed
            lastAcceptedGnssSpeedNs = fix.timestamp.value
        }
        if (coastedSinceFix) {
            reacquiredFixes += 1
            if (reacquiredFixes >= config.reacquireFixes) {
                coastedSinceFix = false
                reacquiredFixes = 0
            }
        }
        lastAltM = fix.altitudeM ?: lastAltM
        fix.headingRad?.let { lastHeadingRad = wrapHeadingRad(it) }
        val speed = fix.speedMps
        val heading = fix.headingRad
        if (speed != null && heading != null && speed >= 0.4 && !imuStill) {
            applyGnssVelocity(speed, heading)
        }
        noteAcceptedUnique(fix)
    }

    private fun noteAcceptedUnique(fix: CoastFix) {
        val isNew = lastAcceptedUniqueNs < 0L ||
            Wgs84.distanceMetres(
                lastAcceptedUniqueLat,
                lastAcceptedUniqueLon,
                fix.latitudeDeg,
                fix.longitudeDeg,
            ) > 1e-3
        if (!isNew) {
            return
        }
        lastAcceptedUniqueNs = fix.timestamp.value
        lastAcceptedUniqueLat = fix.latitudeDeg
        lastAcceptedUniqueLon = fix.longitudeDeg
    }

    private fun canReseed(fix: CoastFix): Boolean {
        val speed = reseedSpeed(fix) ?: return false
        if (speed >= config.gnssReseedSlowMps) {
            if (fix.headingRad == null && headingFrom10m() == null) {
                return false
            }
        }
        return true
    }

    /**
     * Column speed if it is a moving fix. Otherwise unique-pair finite
     * difference. A 0 m/s column after a long unique hop is not a stop.
     * Persist uses that hop speed.
     */
    private fun reseedSpeed(fix: CoastFix): Double? {
        val derived = derivedUniqueSpeed(fix)
        val column = fix.speedMps
        if (column != null && column >= config.gnssReseedSlowMps) {
            return column
        }
        if (derived != null && derived >= config.gnssReseedSlowMps) {
            return derived
        }
        return column ?: derived
    }

    private fun derivedUniqueSpeed(fix: CoastFix): Double? {
        if (gnssTrail.size < 2) {
            return null
        }
        val prev = gnssTrail.elementAt(gnssTrail.size - 2)
        val dt = (fix.timestamp.value - prev.tNs) / NS_PER_S
        if (dt <= 0.0) {
            return null
        }
        val dist = Wgs84.distanceMetres(prev.latDeg, prev.lonDeg, fix.latitudeDeg, fix.longitudeDeg)
        return dist / dt
    }

    private fun reseedFromGnss(fix: CoastFix, east: Double, north: Double, up: Double) {
        val speed = reseedSpeed(fix) ?: return
        val heading = reseedHeading(fix, speed)
        val baKeep = ba
        val bgKeep = bg
        eastM = east
        northM = north
        upM = up
        ve = speed * sin(heading)
        vn = speed * cos(heading)
        vu = 0.0
        yawAboutUpTo(heading)
        ba = baKeep
        bg = bgKeep
        lastHeadingRad = heading
        heldSpeedMps = speed
        resumeSpeedMps = speed
        coastHeadingRad = heading
        speedBeforeCoast = speed
        coastSnapshotTaken = false
        coastElapsedS = 0.0
        lastTrustedGnssNs = fix.timestamp.value
        lastHorizAccM = fix.horizontalAccuracyM
        lastAcceptedGnssSpeedMps = speed
        lastAcceptedGnssSpeedNs = fix.timestamp.value
        lastReportedGnssSpeedMps = speed
        lastReportedGnssSpeedNs = fix.timestamp.value
        lastAltM = fix.altitudeM ?: lastAltM
        consecutiveGnssGates = 0
        gnssGateInflated = false
        lastGatedGnssNs = -1L
        coastedSinceFix = false
        reacquiredFixes = 0
        lastGnssReseed = true
        lastGnssAdmit = GNSS_RESEED_AFTER_GAP
        setInitialP(max(fix.horizontalAccuracyM, 3.0))
        if (speed < config.gnssReseedSlowMps) {
            val yaw = config.gnssReseedSlowYawStdRad
            p[EskfDim.ITH + 2, EskfDim.ITH + 2] = max(p[EskfDim.ITH + 2, EskfDim.ITH + 2], yaw * yaw)
        }
        timeNs = fix.timestamp.value
        recordClone()
    }

    private fun reseedHeading(fix: CoastFix, speed: Double): Double {
        if (speed < config.gnssReseedSlowMps) {
            return wrapHeadingRad(lastHeadingRad)
        }
        val column = fix.speedMps
        if (column == null || column < config.gnssReseedSlowMps) {
            headingFrom10m()?.let { return it }
        }
        fix.headingRad?.let { return wrapHeadingRad(it) }
        return headingFrom10m() ?: wrapHeadingRad(lastHeadingRad)
    }

    private fun headingFrom10m(): Double? {
        if (gnssTrail.size < 2) {
            return null
        }
        val end = gnssTrail.last()
        var acc = 0.0
        for (index in gnssTrail.size - 2 downTo 0) {
            val a = gnssTrail.elementAt(index)
            val b = gnssTrail.elementAt(index + 1)
            acc += Wgs84.distanceMetres(a.latDeg, a.lonDeg, b.latDeg, b.lonDeg)
            if (acc >= config.gnssReseedHeadingMotionM) {
                val (north, east) = Wgs84.northEastMetres(a.latDeg, a.lonDeg, end.latDeg, end.lonDeg)
                if (north * north + east * east < 1e-6) {
                    return null
                }
                return atan2(east, north)
            }
        }
        return null
    }

    private fun rememberFix(fix: CoastFix) {
        val speed = fix.speedMps ?: 0.0
        val heading = fix.headingRad ?: lastHeadingRad
        val next = TrailFix(fix.latitudeDeg, fix.longitudeDeg, fix.timestamp.value, speed, heading)
        val last = gnssTrail.lastOrNull()
        if (last == null || Wgs84.distanceMetres(last.latDeg, last.lonDeg, next.latDeg, next.lonDeg) > 1e-3) {
            gnssTrail.addLast(next)
            while (gnssTrail.size > TRAIL_CAP) {
                gnssTrail.removeFirst()
            }
        } else {
            gnssTrail.removeLast()
            gnssTrail.addLast(next)
        }
    }

    private fun yawAboutUpTo(headingRad: Double) {
        val current = headingFromAttitude()
        val dYaw = wrapPi(headingRad - current)
        if (abs(dYaw) < 1e-12) {
            return
        }
        val half = 0.5 * dYaw
        val dq = Quat(cos(half), 0.0, 0.0, sin(half))
        q = dq.times(q).normalized()
    }

    private fun headingFromAttitude(): Double {
        val fwd = q.rotate(bodyForward(imuFrame))
        if (hypot(fwd.x, fwd.y) > 1e-6) {
            return atan2(fwd.x, fwd.y)
        }
        return lastHeadingRad
    }

    private fun applyGnssVelocity(speed: Double, heading: Double) {
        residual[0] = speed * sin(heading) - ve
        residual[1] = speed * cos(heading) - vn
        residual[2] = -vu
        fillVelH()
        val sv = max(0.5, 0.15 * speed)
        rMeas[0] = sv * sv
        rMeas[1] = 0.0
        rMeas[2] = 0.0
        rMeas[3] = 0.0
        rMeas[4] = sv * sv
        rMeas[5] = 0.0
        rMeas[6] = 0.0
        rMeas[7] = 0.0
        rMeas[8] = (sv * 1.5) * (sv * 1.5)
        if (josephUpdate(p, hRow, residual, rMeas, 3, dx, joseph)) {
            inject()
        }
    }

    private fun maybeConstraints() {
        lastZupt = false
        lastNhc = false
        val accel = lastAccel ?: return
        val gyro = lastGyro ?: return
        if (config.coastStopDetect &&
            config.coastMode == CoastMode.YAW_SPEED_HOLD &&
            isCoasting(timeNs) &&
            !(burstUntilNs > 0L && timeNs < burstUntilNs)
        ) {
            updateCoastStopDetector()
        } else if (!isCoasting(timeNs) && coastStopped) {
            coastStopped = false
            lastStopProbability = 0.0
            quietSinceNs = -1L
            noisySinceNs = -1L
            burstUntilNs = -1L
            resumeInhibitUntilNs = -1L
        }
        val c = q.toRotation()
        val aNav = NFrameMechanization.navAccelFromSpecificForce(
            c * (accel - ba),
            currentLatitudeDeg(),
            currentAltitudeM(),
        )
        val omega = (gyro - bg).norm()
        val speed = hypot(ve, vn)
        val aHor = hypot(aNav.x, aNav.y)
        val still = aNav.norm() < config.zuptAccelMps2 &&
            omega < config.zuptGyroRadps &&
            accelVariance() < config.zuptAccelVar &&
            aHor < config.zuptAccelMps2
        val skipStillZupt = isCoasting(timeNs) &&
            speedBeforeCoast >= config.zuptHeldSkipMps &&
            !imuStill
        val stillZupt = (still && speed < 1.5 && !skipStillZupt) || imuStill
        val detectorZupt = coastStopped && lastStopProbability >= config.zuptStopProbability
        val wantZupt = lastStopProbability >= config.zuptStopProbability || stillZupt
        if (wantZupt || detectorZupt) {
            applyZupt(force = lastStopProbability >= config.zuptStopProbability || stillZupt || detectorZupt)
            return
        }
        if (config.coastMode == CoastMode.YAW_SPEED_HOLD && isCoasting(timeNs)) {
            return
        }
        if (imuFrame != VectorFrame.VEHICLE_FLU) {
            return
        }
        val aVeh = bodyToVehicle(imuFrame) * c.timesT(aNav)
        if (lastBump || abs(aVeh.y) > config.nhcDropLateralMps2) {
            return
        }
        if (speed >= config.nhcMinSpeedMps) {
            applyNhc()
        }
    }

    private fun applyMotionPseudo(meas: MotionPseudoMeasurement) {
        lastStopProbability = meas.stopProbability
        lastBump = meas.bump
        if (meas.idle || meas.stopProbability >= config.zuptStopProbability) {
            lastPseudo = true
            applyZupt(force = true)
            return
        }
        if (meas.bump) {
            return
        }
        if (!config.studentForwardSpeed) {
            return
        }
        val ageS = if (lastTrustedGnssNs < 0L) {
            Double.POSITIVE_INFINITY
        } else {
            (timeNs - lastTrustedGnssNs).coerceAtLeast(0L) / NS_PER_S
        }
        if (!(gnssHeld || ageS > config.staleAfterS)) {
            return
        }
        val sigma = sqrt(exp(meas.logSpeedVariance)).coerceAtLeast(config.studentSpeedSigmaFloorMps)
        val c = q.toRotation()
        val rvn = bodyToVehicle(imuFrame) * c.transpose()
        val vNav = Vec3(ve, vn, vu)
        val vVeh = rvn * vNav
        residual[0] = meas.forwardSpeed.value - vVeh.x
        val minusRvSkew = negated(rvn * Mat3.skew(vNav))
        hRow.fill(0.0)
        fillVehicleVelRows(rvn, minusRvSkew, rows = intArrayOf(0), m = 1)
        rMeas[0] = sigma * sigma
        val chi2 = innovationChiSquared(p, hRow, residual, rMeas, 1, joseph) ?: return
        if (chi2 > config.studentSpeedChi2Gate) {
            return
        }
        lastPseudo = true
        applyForwardSpeed(meas.forwardSpeed.value, sigma)
    }

    private fun applyDisplacement(meas: DisplacementPseudoMeasurement) {
        val clone = cloneAtOrBefore(meas.windowStart.value) ?: return
        val dpEnu = Vec3(eastM - clone.eastM, northM - clone.northM, upM - clone.upM)
        val predicted = clone.enuToHacf * dpEnu
        val m = if (meas.twoDimensional) 2 else 3
        residual[0] = meas.dxM - predicted.x
        residual[1] = meas.dyM - predicted.y
        if (m == 3) {
            residual[2] = meas.dzM - predicted.z
        }
        fillDisplacementH(clone.enuToHacf, m)
        val overlap = lastAcceptedDisplacementNs >= 0L &&
            (timeNs - lastAcceptedDisplacementNs) < ImuMotionConstants.WINDOW_NS
        val scale = if (overlap) config.displacementOverlapRScale else 1.0
        val sx = displacementSigmaM(meas.logSigmaX)
        val sy = displacementSigmaM(meas.logSigmaY)
        val sz = displacementSigmaM(meas.logSigmaZ)
        rMeas.fill(0.0)
        if (m == 2) {
            rMeas[0] = sx * sx * scale
            rMeas[3] = sy * sy * scale
        } else {
            rMeas[0] = sx * sx * scale
            rMeas[4] = sy * sy * scale
            rMeas[8] = sz * sz * scale
        }
        val chi2 = innovationChiSquared(p, hRow, residual, rMeas, m, joseph) ?: return
        lastDisplacement = true
        if (chi2 > config.displacementChi2Gate) {
            lastDisplacementGated = true
            inflateDisplacementReject()
            return
        }
        lastDisplacementGated = false
        if (!josephUpdate(p, hRow, residual, rMeas, m, dx, joseph)) {
            numericalOk = false
            return
        }
        inject()
        lastAcceptedDisplacementNs = timeNs
    }

    private fun fillDisplacementH(enuToHacf: Mat3, m: Int) {
        hRow.fill(0.0)
        for (i in 0 until m) {
            val row = enuToHacf.row(i)
            val off = i * EskfDim.N
            hRow[off + EskfDim.IP] = row.x
            hRow[off + EskfDim.IP + 1] = row.y
            hRow[off + EskfDim.IP + 2] = row.z
        }
    }

    private fun displacementSigmaM(logSigma: Double): Double {
        val clamped = logSigma.coerceIn(LinearDpConstants.LOG_SIGMA_MIN, LinearDpConstants.LOG_SIGMA_MAX)
        return exp(clamped).coerceIn(0.01, 50.0)
    }

    private fun inflateDisplacementReject() {
        val grow = config.displacementGateGrowM
        val add = grow * grow
        p[0, 0] += add
        p[1, 1] += add
        p[2, 2] += add
        symmetrize(p)
    }

    private fun inflateGnssGateLock(horizInnovM: Double) {
        val sigma = max(horizInnovM, 1.0)
        val add = sigma * sigma
        p[0, 0] += add
        p[1, 1] += add
        symmetrize(p)
    }

    private fun recordClone() {
        if (!initialized || !numericalOk) {
            return
        }
        val g = lastAccel ?: Vec3(0.0, 0.0, ImuMotionConstants.GRAVITY_MPS2)
        val rDev = try {
            hacfRotation(g)
        } catch (_: IllegalArgumentException) {
            Mat3.IDENTITY
        }
        val enuToHacf = rDev * q.toRotation().transpose()
        clones.addLast(PoseClone(timeNs, eastM, northM, upM, enuToHacf))
        while (clones.size > CLONE_CAP) {
            clones.removeFirst()
        }
    }

    private fun cloneAtOrBefore(tNs: Long): PoseClone? {
        var best: PoseClone? = null
        for (row in clones) {
            if (row.tNs > tNs) {
                break
            }
            best = row
        }
        return best
    }

    private fun pushVib(tNs: Long, accelMag: Double, gyroNorm: Double) {
        vib.addLast(VibSample(tNs, accelMag, gyroNorm))
        val cut = tNs - (config.coastStopHoldS * NS_PER_S).toLong()
        while (vib.isNotEmpty() && vib.first().tNs < cut) {
            vib.removeFirst()
        }
    }

    private fun vibStats(): Pair<Double, Double>? {
        if (vib.size < 8) {
            return null
        }
        val spanS = (vib.last().tNs - vib.first().tNs) / NS_PER_S
        if (spanS < config.coastStopHoldS * 0.85) {
            return null
        }
        var magSum = 0.0
        var gyroSum = 0.0
        for (sample in vib) {
            magSum += sample.accelMag
            gyroSum += sample.gyroNorm
        }
        val n = vib.size.toDouble()
        val magMean = magSum / n
        var varSum = 0.0
        for (sample in vib) {
            val d = sample.accelMag - magMean
            varSum += d * d
        }
        return (varSum / n) to (gyroSum / n)
    }

    private fun observePrefixVariance() {
        if (gnssAgeS(timeNs) > config.staleAfterS) {
            return
        }
        val stats = vibStats() ?: return
        val speed = lastReportedGnssSpeedMps ?: lastAcceptedGnssSpeedMps ?: hypot(ve, vn)
        if (speed < config.coastStopStoppedMaxMps) {
            if (prefixStoppedVars.size < PREFIX_VAR_CAP) {
                prefixStoppedVars.add(stats.first)
                prefixStoppedGyros.add(stats.second)
            }
        } else if (speed > config.coastStopMovingMinMps) {
            if (prefixMovingVars.size < PREFIX_VAR_CAP) {
                prefixMovingVars.add(stats.first)
                prefixMovingGyros.add(stats.second)
            }
        }
        if (!config.coastStopRequireStoppedPrefix &&
            !stopCalibrated &&
            prefixStoppedVars.size >= 3 &&
            prefixMovingVars.size >= 3
        ) {
            stopCalibrated = true
            val stoppedMed = medianOf(prefixStoppedVars)
            val movingMed = medianOf(prefixMovingVars)
            if (movingMed > 2.0 * max(stoppedMed, 0.01)) {
                effectiveStopAccelVar = (0.5 * (stoppedMed + movingMed)).coerceIn(0.02, 0.12)
                coastStopArmed = true
            } else {
                coastStopArmed = false
            }
        }
    }

    private fun finalizeStopCalibration() {
        if (stopCalibrated || !config.coastStopRequireStoppedPrefix) {
            return
        }
        stopCalibrated = true
        if (prefixStoppedVars.isEmpty() || prefixMovingVars.isEmpty()) {
            coastStopArmed = false
            coastStopDisarmed = true
            return
        }
        val p10 = percentileOf(prefixMovingVars, 0.10)
        effectiveStopAccelVar = (config.coastStopMovingK * p10).coerceAtLeast(1e-8)
        if (prefixStoppedGyros.isNotEmpty()) {
            effectiveStopGyroRadps = min(config.coastStopGyroRadps, percentileOf(prefixStoppedGyros, 0.90))
        }
        coastStopArmed = true
        coastStopDisarmed = false
    }

    private fun updateCoastStopDetector() {
        finalizeStopCalibration()
        if (!coastStopArmed) {
            return
        }
        val stats = vibStats() ?: return
        val enterQuiet = stats.first < effectiveStopAccelVar && stats.second <= effectiveStopGyroRadps
        val leaveQuiet = stats.first > effectiveStopAccelVar * 2.5 ||
            stats.second > effectiveStopGyroRadps * 2.0
        if (!coastStopped) {
            if (enterQuiet && timeNs >= resumeInhibitUntilNs) {
                coastStopped = true
                lastStopProbability = 0.95
            }
        } else if (leaveQuiet) {
            if (noisySinceNs < 0L) {
                noisySinceNs = timeNs
            }
            if ((timeNs - noisySinceNs) / NS_PER_S >= config.coastStopRestartDebounceS) {
                resumeFromCoastStop()
            }
        } else {
            noisySinceNs = -1L
        }
    }

    private fun resumeFromCoastStop() {
        coastStopped = false
        lastStopProbability = 0.0
        noisySinceNs = -1L
        resumeInhibitUntilNs = timeNs + (config.coastStopHoldS * NS_PER_S).toLong()
        when (config.coastRestart) {
            CoastRestart.HELD_SPEED -> {
                heldSpeedMps = resumeSpeedMps
                burstUntilNs = -1L
            }
            CoastRestart.ACCEL_BURST -> {
                heldSpeedMps = 0.0
                burstUntilNs = timeNs + (config.coastRestartBurstS * NS_PER_S).toLong()
            }
        }
        ve = heldSpeedMps * sin(coastHeadingRad)
        vn = heldSpeedMps * cos(coastHeadingRad)
        vu = 0.0
    }

    private fun applyZupt(force: Boolean) {
        if (!force && hypot3(ve, vn, vu) < 1e-4) {
            return
        }
        residual[0] = -ve
        residual[1] = -vn
        residual[2] = -vu
        fillVelH()
        val r = config.zuptVelStdMps * config.zuptVelStdMps
        rMeas[0] = r
        rMeas[1] = 0.0
        rMeas[2] = 0.0
        rMeas[3] = 0.0
        rMeas[4] = r
        rMeas[5] = 0.0
        rMeas[6] = 0.0
        rMeas[7] = 0.0
        rMeas[8] = r
        if (josephUpdate(p, hRow, residual, rMeas, 3, dx, joseph)) {
            val east0 = eastM
            val north0 = northM
            val up0 = upM
            val q0 = q
            inject()
            if (coastStopped || imuStill) {
                eastM = east0
                northM = north0
                upM = up0
                q = q0
                ve = 0.0
                vn = 0.0
                vu = 0.0
            }
            lastZupt = true
            syncHeldSpeedFromFilter()
        }
    }

    private fun applyNhc() {
        val c = q.toRotation()
        val rvn = bodyToVehicle(imuFrame) * c.transpose()
        val vNav = Vec3(ve, vn, vu)
        val vVeh = rvn * vNav
        residual[0] = -vVeh.y
        residual[1] = -vVeh.z
        fillVehicleVelRows(rvn, Mat3.ZERO, rows = intArrayOf(1, 2), m = 2)
        val r = config.nhcVelStdMps * config.nhcVelStdMps
        rMeas[0] = r
        rMeas[1] = 0.0
        rMeas[2] = 0.0
        rMeas[3] = r
        if (josephUpdate(p, hRow, residual, rMeas, 2, dx, joseph)) {
            inject()
            lastNhc = true
        }
    }

    private fun applyForwardSpeed(forwardMps: Double, stdMps: Double) {
        val c = q.toRotation()
        val rvn = bodyToVehicle(imuFrame) * c.transpose()
        val vNav = Vec3(ve, vn, vu)
        val vVeh = rvn * vNav
        residual[0] = forwardMps - vVeh.x
        val minusRvSkew = negated(rvn * Mat3.skew(vNav))
        hRow.fill(0.0)
        fillVehicleVelRows(rvn, minusRvSkew, rows = intArrayOf(0), m = 1)
        val sigma = if (stdMps.isFinite() && stdMps > 0.0) stdMps else config.speedPseudoStdMps
        val r = sigma * sigma
        rMeas[0] = r
        if (josephUpdate(p, hRow, residual, rMeas, 1, dx, joseph)) {
            inject()
            syncHeldSpeedFromFilter()
        }
    }

    private fun fillVehicleVelRows(rvn: Mat3, hTheta: Mat3, rows: IntArray, m: Int) {
        hRow.fill(0.0)
        for (r in rows.indices) {
            val axis = rows[r]
            val rv = rvn.row(axis)
            val th = hTheta.row(axis)
            val off = r * EskfDim.N
            hRow[off + EskfDim.IV] = rv.x
            hRow[off + EskfDim.IV + 1] = rv.y
            hRow[off + EskfDim.IV + 2] = rv.z
            hRow[off + EskfDim.ITH] = th.x
            hRow[off + EskfDim.ITH + 1] = th.y
            hRow[off + EskfDim.ITH + 2] = th.z
        }
        require(m == rows.size)
    }

    private fun fillPosH() {
        hRow.fill(0.0)
        for (i in 0..2) {
            hRow[i * EskfDim.N + EskfDim.IP + i] = 1.0
        }
    }

    private fun fillVelH() {
        hRow.fill(0.0)
        for (i in 0..2) {
            hRow[i * EskfDim.N + EskfDim.IV + i] = 1.0
        }
    }

    private fun inject() {
        eastM += dx[0]
        northM += dx[1]
        upM += dx[2]
        ve += dx[3]
        vn += dx[4]
        vu += dx[5]
        val dTheta = Vec3(dx[6], dx[7], dx[8])
        // Solà (283)/(319) injects δθ. This sibling uses global left-multiply of q{−δθ}.
        val dq = Quat(1.0, -0.5 * dTheta.x, -0.5 * dTheta.y, -0.5 * dTheta.z).normalized()
        q = dq.times(q).normalized()
        ba = clampBias(ba + Vec3(dx[9], dx[10], dx[11]), config.maxAccelBias)
        bg = clampBias(bg + Vec3(dx[12], dx[13], dx[14]), config.maxGyroBias)
        dx.fill(0.0)
    }

    private fun headingRad(speed: Double): Double {
        if (speed >= 0.5) {
            return atan2(ve, vn)
        }
        val fwd = q.rotate(bodyForward(imuFrame))
        if (hypot(fwd.x, fwd.y) > 1e-6) {
            return atan2(fwd.x, fwd.y)
        }
        return lastHeadingRad
    }

    private fun wrapPi(rad: Double): Double {
        var heading = rad
        val tau = 2.0 * PI
        heading -= tau * kotlin.math.floor((heading + PI) / tau)
        return heading
    }

    private fun currentLatitudeDeg(): Double {
        return Wgs84.enuToGeodetic(
            originLatDeg,
            originLonDeg,
            originAltM,
            eastM,
            northM,
            upM,
        ).first
    }

    private fun currentAltitudeM(): Double = originAltM + upM

    private fun reanchorIfNeeded() {
        if (hypot(eastM, northM) < config.reanchorM) {
            return
        }
        val (lat, lon, alt) = Wgs84.enuToGeodetic(
            originLatDeg,
            originLonDeg,
            originAltM,
            eastM,
            northM,
            upM,
        )
        originLatDeg = lat
        originLonDeg = lon
        originAltM = alt
        eastM = 0.0
        northM = 0.0
        upM = 0.0
        clones.clear()
    }

    private fun pushAccelHist(a: Double) {
        accelHist[accelHistIdx] = a
        accelHistIdx = (accelHistIdx + 1) % ACCEL_HIST
        if (accelHistCount < ACCEL_HIST) {
            accelHistCount += 1
        }
    }

    private fun accelVariance(): Double {
        if (accelHistCount < 4) {
            return 0.0
        }
        var mean = 0.0
        for (i in 0 until accelHistCount) {
            mean += accelHist[i]
        }
        mean /= accelHistCount
        var varSum = 0.0
        for (i in 0 until accelHistCount) {
            val d = accelHist[i] - mean
            varSum += d * d
        }
        return varSum / accelHistCount
    }

    companion object {
        const val STALE_AFTER_S: Double = 2.0
        const val OUTPUT_HZ: Double = 10.0
        const val FLAG_ESKF: String = "eskf"
        const val FLAG_GPS_HELD: String = "gps_held"
        const val FLAG_ZUPT: String = "zupt"
        const val FLAG_NHC: String = "nhc"
        const val FLAG_ROAD_HEADING: String = "road_heading"
        const val FLAG_MOTION_PSEUDO: String = "motion_pseudo"
        const val FLAG_DISPLACEMENT_PSEUDO: String = "displacement_pseudo"
        const val FLAG_DISPLACEMENT_GATED: String = "displacement_gated"
        const val FLAG_IMU_GAP: String = "imu_gap"
        const val FLAG_NO_IMU: String = "no_imu"
        const val FLAG_GNSS_GATE_INFLATE: String = "gnss_gate_inflate"
        const val FLAG_WEAK_HEADING_HOLD: String = "weak_heading_hold"
        const val FLAG_COAST_STOP_DISARMED: String = "coast_stop_disarmed"
        const val FLAG_HEADING_PICK_WEAK: String = "gyro_heading_pick_weak"
        const val FLAG_GNSS_RESEED: String = "gnss_reseed_after_gap"
        const val FLAG_GNSS_WOULD_ADMIT: String = "gnss_gate_would_admit"
        const val GNSS_RESEED_AFTER_GAP: String = "gnss_reseed_after_gap"
        const val GNSS_GATE_ADMIT: String = "gnss_gate_admit"
        const val GNSS_GATE_REJECT: String = "gnss_gate_reject"
        const val RISK_STALE_GNSS: String = "stale_gnss"
        const val RISK_POOR_ACCURACY: String = "poor_accuracy"
        const val RISK_GATED_FIX: String = "gated_fix"
        const val RISK_GNSS_GATE_INFLATE: String = "gnss_gate_inflate"
        const val RISK_REACQUIRING: String = "reacquiring"
        const val CONFIG_ID: String =
            "eskf.v2.stale_s=2.output_hz=10.wgs84.somigliana_2_139.nframe_enu.phi_2_139.q_pv.zupt_skip_coast.nhc_off_unspecified.joseph.tlio_dp_chi2_11.345.modes_v2_degraded_30m_reacquire_3.coast_strapdown.gate_inflate_5"
        val CONFIG_HASH: String = sha256Hex(CONFIG_ID)
        private const val NS_PER_S: Double = 1_000_000_000.0
        private const val ACCEL_HIST: Int = 32
        private const val CLONE_CAP: Int = 200
        private const val PREFIX_VAR_CAP: Int = 40
        private const val TRAIL_CAP: Int = 40
    }
}

private data class PoseClone(
    val tNs: Long,
    val eastM: Double,
    val northM: Double,
    val upM: Double,
    val enuToHacf: Mat3,
)

/**
 * Coast model while GNSS is held or older than [DeadReckoningFilter.STALE_AFTER_S].
 *
 * [STRAPDOWN] integrates specific force into velocity (v1/v2).
 * [YAW_SPEED_HOLD] holds horizontal speed (m/s) and yaws from the ENU-up gyro
 * component (rad/s). Attitude is still quaternion-integrated. Frames: IMU body
 * into n-frame ENU. Heading is clockwise from north, units rad.
 */
enum class CoastMode {
    STRAPDOWN,
    YAW_SPEED_HOLD,
}

/**
 * How speed returns after a vibration stop during [CoastMode.YAW_SPEED_HOLD].
 * [HELD_SPEED] restores [InsConfig] snapshot speed from before the stop.
 * [ACCEL_BURST] integrates forward-axis nav accel for [InsConfig.coastRestartBurstS]
 * (clip [InsConfig.coastRestartAccelClipMps2]) then holds.
 */
enum class CoastRestart {
    HELD_SPEED,
    ACCEL_BURST,
}

enum class WeakHeadingPolicy {
    INTEGRATE,
    HOLD_COURSE,
}

data class RoadHeadingResult(
    val accepted: Boolean,
    val reason: String,
    val chi2: Double? = null,
)

object RoadHeadingReason {
    const val ACCEPTED: String = "accepted"
    const val NOT_COASTING: String = "not_coasting"
    const val NOT_INITIALIZED: String = "not_initialized"
    const val INVALID_STD: String = "invalid_std"
    const val CHI2_REJECT: String = "chi2_reject"
    const val NUMERICAL: String = "numerical"
}

data class InsConfig(
    val accelNoise: Double = 0.20,
    val gyroNoise: Double = 0.015,
    val accelBiasRw: Double = 0.003,
    val gyroBiasRw: Double = 3.0e-5,
    val maxIntegrateS: Double = 0.40,
    val maxGnssAccuracyM: Double = 80.0,
    val initVelStdMps: Double = 1.5,
    val initTiltStdRad: Double = 3.0 * PI / 180.0,
    val initYawStdRad: Double = 25.0 * PI / 180.0,
    val initAccelBiasStd: Double = 0.40,
    val initGyroBiasStd: Double = 1.0 * PI / 180.0,
    val zuptAccelMps2: Double = 0.45,
    val zuptGyroRadps: Double = 0.05,
    val zuptAccelVar: Double = 0.12,
    val zuptStopProbability: Double = 0.75,
    val zuptVelStdMps: Double = 0.03,
    val zuptHeldSkipMps: Double = 1.5,
    val nhcMinSpeedMps: Double = 1.2,
    val nhcDropLateralMps2: Double = 2.0,
    val nhcVelStdMps: Double = 0.35,
    val speedPseudoStdMps: Double = 0.50,
    val maxAccelBias: Double = 2.5,
    val maxGyroBias: Double = 0.12,
    val reanchorM: Double = 25_000.0,
    val coastPosGrowMps: Double = 2.5,
    val lowConfidenceRadiusM: Double = 120.0,
    /** Accepted fix accuracy above this reports GNSS_DEGRADED (ADR 006). */
    val degradedAccuracyM: Double = 30.0,
    /** A fix rejected by the innovation gate keeps GNSS_DEGRADED for this long. */
    val gatedRecentS: Double = 5.0,
    /** Consecutive accepted fixes after a coast before REACQUIRING returns to GNSS_FUSED. */
    val reacquireFixes: Int = 3,
    val displacementChi2Gate: Double = LinearDpConstants.CHI2_99_3DOF,
    val displacementOverlapRScale: Double = LinearDpConstants.OVERLAP_R_SCALE,
    val displacementGateGrowM: Double = 1.0,
    /**
     * Reduced-order coast versus full strapdown. Default [CoastMode.STRAPDOWN]
     * keeps v1/v2 replay hashes. [CoastMode.YAW_SPEED_HOLD] holds last GNSS
     * speed and yaws from the gravity-vertical gyro while coasting.
     */
    val coastMode: CoastMode = CoastMode.STRAPDOWN,
    /** Consecutive gated GNSS fixes before P_h is inflated. */
    val gnssGateRejectsBeforeInflate: Int = 5,
    /** sqrt(P_e + P_n) below this (metres) counts as over-confident for inflation. */
    val gnssGateInflateMaxSigmaM: Double = 50.0,
    /** When false, gated GNSS never inflates P_h. Default on matches v3. */
    val gnssGateInflate: Boolean = true,
    /**
     * Vibration stop detector during [CoastMode.YAW_SPEED_HOLD] coast.
     * Default off so gravity-only v3 fixtures keep held speed.
     *
     * Rolling 1 s variance of specific-force magnitude |a| ((m/s²)²) plus mean
     * gyro norm (rad/s). A quiet window ZUPTs (velocity 0, position held).
     * Fixed defaults 0.04 (m/s²)² and 0.08 rad/s are round phone-idle
     * constants from pre-mask moving vs stopped |a| variance on a GNSS-known
     * prefix, not fit inside a mask. Optional prefix calibration may replace
     * the accel threshold with the midpoint of stopped vs moving medians,
     * clipped to [0.02, 0.12]. Overlapping classes disarm.
     */
    val coastStopDetect: Boolean = false,
    val coastStopAccelVar: Double = 0.04,
    val coastStopGyroRadps: Double = 0.08,
    val coastStopHoldS: Double = 1.0,
    val coastStopRestartDebounceS: Double = 0.3,
    val coastRestart: CoastRestart = CoastRestart.HELD_SPEED,
    val coastRestartBurstS: Double = 2.0,
    val coastRestartAccelClipMps2: Double = 3.0,
    /** 1-dof chi-square gate for [DeadReckoningFilter.applyRoadHeading]. 6.63 is 99th percentile. */
    val roadHeadingChi2Gate: Double = 6.63,
    /**
     * When the heading-gyro pick is weak (few_hops / insufficient_fixes / weak_corr),
     * [HOLD_COURSE] zeros yaw rate and grows P_heading. [INTEGRATE] is v3/v4.
     */
    val weakHeadingPolicy: WeakHeadingPolicy = WeakHeadingPolicy.INTEGRATE,
    /** Extra heading random walk (rad/s) added to P_yaw while HOLD_COURSE is active. */
    val weakHeadingGrowRadps: Double = 0.05,
    /**
     * When true, YAW_SPEED_HOLD latches last reported GNSS speed if its timestamp
     * is within [coastLatchGnssMaxS] of coast start. Otherwise uses filter speed.
     * Reported speed includes accuracy-ok fixes that the position gate rejected.
     * Default off keeps v3/v4 last-accepted-any-age latch.
     */
    val coastLatchGnssSpeed: Boolean = false,
    val coastLatchGnssMaxS: Double = 2.0,
    /**
     * Second-attempt stop detector: calibrate |a| variance and gyro from the
     * pre-mask prefix only. Default off. Live phone IMU is 100 Hz; IO-VNBD is 10 Hz.
     */
    val coastStopRequireStoppedPrefix: Boolean = false,
    val coastStopMovingK: Double = 0.5,
    val coastStopMovingMinMps: Double = 3.0,
    val coastStopStoppedMaxMps: Double = 0.5,
    /**
     * Re-initialize from a GNSS fix when the gap since the last accepted
     * unique-fix is at least this many seconds. 0 disables. Live phone GNSS
     * is about 1 Hz, so the default stays off. Replay passes
     * `--gnss-reseed-after-s` for the sparse v6 row.
     */
    val gnssReseedAfterS: Double = 0.0,
    val gnssReseedHeadingMotionM: Double = 10.0,
    val gnssReseedSlowMps: Double = 1.0,
    val gnssReseedSlowYawStdRad: Double = 30.0 * PI / 180.0,
    /**
     * When false (default), a unique-gap reseed is skipped while GNSS is
     * still fused. 1 Hz streams stay on the Joseph path.
     */
    val gnssReseedWhileFused: Boolean = false,
    /**
     * Bias-like coast position covariance: after T seconds,
     * sqrt(P_h) grows as hypot(sigma_v, v * sigma_heading) * T.
     * Default off keeps v3/v5 halo growth.
     */
    val coastHonestP: Boolean = false,
    val coastSpeedRwMps: Double = 3.0,
    val coastHeadingRwRadps: Double = 0.1,
    /**
     * Learned forward-speed pseudo-measurement during coast. Default off.
     * Stop/idle probability still forces ZUPT. When on, 1-dof chi-square 3.841
     * and sigma floored at [studentSpeedSigmaFloorMps].
     */
    val studentForwardSpeed: Boolean = false,
    val studentSpeedChi2Gate: Double = 3.841,
    val studentSpeedSigmaFloorMps: Double = 2.0,
    /** Decay held speed toward [coastSpeedDecayTargetMps] with time constant tau. */
    val coastSpeedDecay: Boolean = false,
    val coastSpeedDecayTauS: Double = 30.0,
    val coastSpeedDecayTargetMps: Double = 0.0,
    /**
     * GNSS age (s) before DEAD_RECKONING unless GNSS is held. Default 2
     * keeps replay hashes. The live phone filter uses 8 so indoor gaps
     * of a few seconds stay GNSS or Assisted, not a fake tunnel.
     */
    val staleAfterS: Double = 2.0,
)

/** GNSS sample at the PoseStore / filter boundary. Units: deg, m/s, rad, metres. */
data class CoastFix(
    val timestamp: Nanoseconds,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val speedMps: Double? = null,
    val headingRad: Double? = null,
    val horizontalAccuracyM: Double,
    val altitudeM: Double? = null,
) {
    init {
        require(latitudeDeg.isFinite() && longitudeDeg.isFinite())
        require(horizontalAccuracyM.isFinite() && horizontalAccuracyM >= 0.0)
        speedMps?.let { require(it.isFinite() && it >= 0.0) }
        headingRad?.let { require(it.isFinite()) }
        altitudeM?.let { require(it.isFinite()) }
    }
}

internal fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

private fun clampBias(v: Vec3, limit: Double): Vec3 = Vec3(
    v.x.coerceIn(-limit, limit),
    v.y.coerceIn(-limit, limit),
    v.z.coerceIn(-limit, limit),
)

private operator fun Mat3.unaryMinus(): Mat3 = negated(this)

private operator fun Mat3.times(scale: Double): Mat3 = Mat3(
    r00 * scale, r01 * scale, r02 * scale,
    r10 * scale, r11 * scale, r12 * scale,
    r20 * scale, r21 * scale, r22 * scale,
)

private fun negated(m: Mat3): Mat3 = m * -1.0

private fun hypot3(x: Double, y: Double, z: Double): Double = hypot(hypot(x, y), z)

private fun medianOf(values: List<Double>): Double {
    val sorted = values.sorted()
    val n = sorted.size
    require(n > 0)
    return if (n % 2 == 1) {
        sorted[n / 2]
    } else {
        0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
    }
}

private fun percentileOf(values: List<Double>, p: Double): Double {
    val sorted = values.sorted()
    val n = sorted.size
    require(n > 0)
    if (n == 1) {
        return sorted[0]
    }
    val x = p.coerceIn(0.0, 1.0) * (n - 1).toDouble()
    val i = floor(x).toInt()
    val f = x - i
    return if (i >= n - 1) {
        sorted[n - 1]
    } else {
        sorted[i] * (1.0 - f) + sorted[i + 1] * f
    }
}

private data class TrailFix(
    val latDeg: Double,
    val lonDeg: Double,
    val tNs: Long,
    val speedMps: Double,
    val headingRad: Double,
)

private data class VibSample(
    val tNs: Long,
    val accelMag: Double,
    val gyroNorm: Double,
)
