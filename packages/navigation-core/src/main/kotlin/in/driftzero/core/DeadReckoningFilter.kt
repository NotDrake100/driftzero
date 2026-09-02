package `in`.driftzero.core

import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
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

    fun setGnssHeld(held: Boolean) {
        synchronized(lock) {
            gnssHeld = held
        }
    }

    fun isGnssHeld(): Boolean = synchronized(lock) { gnssHeld }

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
            lastPseudo = true
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
            lastImuNs = -1L
            accelHistCount = 0
            lastZupt = false
            lastNhc = false
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
            p.zero()
            if (reason == ResetReason.USER) {
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
            val ageS = if (lastTrustedGnssNs < 0L) {
                (now.value - timeNs).coerceAtLeast(0L) / NS_PER_S
            } else {
                (now.value - lastTrustedGnssNs).coerceAtLeast(0L) / NS_PER_S
            }
            val coasting = gnssHeld || ageS > STALE_AFTER_S
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
                if (lastPseudo) add(FLAG_MOTION_PSEUDO)
                if (lastDisplacement) add(FLAG_DISPLACEMENT_PSEUDO)
                if (lastDisplacementGated) add(FLAG_DISPLACEMENT_GATED)
                if (imuGap) add(FLAG_IMU_GAP)
                if (imuAgeS > 0.5) add(FLAG_NO_IMU)
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
            lastHeadingRad = headingRad
            lastHorizAccM = posStdM
            initialized = true
            numericalOk = true
            lastDisplacement = false
            lastDisplacementGated = false
            lastAcceptedDisplacementNs = -1L
            clones.clear()
            setInitialP(posStdM)
            recordClone()
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
        val speed = fix.speedMps ?: 0.0
        ve = speed * sin(lastHeadingRad)
        vn = speed * cos(lastHeadingRad)
        vu = 0.0
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
            coastVelocity(dt)
            inflateForGap(dt)
            timeNs = tNs
            recordClone()
            return
        }
        val gyro = lastGyro
        val accel = lastAccel
        if (gyro == null || accel == null) {
            coastVelocity(dt)
            timeNs = tNs
            recordClone()
            return
        }
        val fNav = strapdown(dt, gyro, accel)
        predictCovariance(dt, fNav)
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
        val gate = 6.0 * (sigma + sqrt(max(p[0, 0] + p[1, 1], 0.0)))
        if (horizInnov > gate) {
            lastGatedGnssNs = fix.timestamp.value
            if (coastedSinceFix) {
                reacquiredFixes = 0
            }
            return
        }
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
        val gapS = if (lastTrustedGnssNs < 0L) 0.0 else (fix.timestamp.value - lastTrustedGnssNs) / NS_PER_S
        if (gapS > STALE_AFTER_S) {
            coastedSinceFix = true
            reacquiredFixes = 0
        }
        lastTrustedGnssNs = fix.timestamp.value
        lastHorizAccM = fix.horizontalAccuracyM
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
        if (speed != null && heading != null && speed >= 0.4) {
            applyGnssVelocity(speed, heading)
        }
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
        val wantZupt = lastStopProbability >= config.zuptStopProbability || (still && speed < 1.5)
        if (wantZupt) {
            applyZupt(force = lastStopProbability >= config.zuptStopProbability || still)
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
            applyZupt(force = true)
            return
        }
        if (meas.bump) {
            return
        }
        val ageS = if (lastTrustedGnssNs < 0L) {
            Double.POSITIVE_INFINITY
        } else {
            (timeNs - lastTrustedGnssNs).coerceAtLeast(0L) / NS_PER_S
        }
        if (!(gnssHeld || ageS > STALE_AFTER_S)) {
            return
        }
        val sigma = sqrt(exp(meas.logSpeedVariance)).coerceIn(0.05, 8.0)
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
            inject()
            lastZupt = true
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
        const val FLAG_MOTION_PSEUDO: String = "motion_pseudo"
        const val FLAG_DISPLACEMENT_PSEUDO: String = "displacement_pseudo"
        const val FLAG_DISPLACEMENT_GATED: String = "displacement_gated"
        const val FLAG_IMU_GAP: String = "imu_gap"
        const val FLAG_NO_IMU: String = "no_imu"
        const val RISK_STALE_GNSS: String = "stale_gnss"
        const val RISK_POOR_ACCURACY: String = "poor_accuracy"
        const val RISK_GATED_FIX: String = "gated_fix"
        const val RISK_REACQUIRING: String = "reacquiring"
        const val CONFIG_ID: String =
            "eskf.v1.stale_s=2.output_hz=10.wgs84.somigliana_2_139.nframe_enu.phi_2_139.q_pv.zupt.nhc.joseph.tlio_dp_chi2_11.345.modes_v2_degraded_30m_reacquire_3"
        val CONFIG_HASH: String = sha256Hex(CONFIG_ID)
        private const val NS_PER_S: Double = 1_000_000_000.0
        private const val ACCEL_HIST: Int = 32
        private const val CLONE_CAP: Int = 200
    }
}

private data class PoseClone(
    val tNs: Long,
    val eastM: Double,
    val northM: Double,
    val upM: Double,
    val enuToHacf: Mat3,
)

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
