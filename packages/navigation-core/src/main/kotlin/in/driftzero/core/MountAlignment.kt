package `in`.driftzero.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phone-to-vehicle mount alignment and remount monitoring (PRD FR-02, SIH-17).
 *
 * Frames:
 * - Phone: [VectorFrame.ANDROID_DEVICE], x right, y toward the top of the screen,
 *   z out of the screen.
 * - Vehicle: x forward, y left, z up.
 *
 * Default completion is [Mat3.ANDROID_Y_FORWARD] (phone +Y vehicle forward, phone +Z up).
 * A still phone on a flat dash therefore has gravity on phone +Z.
 *
 * Accelerometer samples are m/s^2. Gyroscope samples are rad/s. Angles are radians.
 * Timestamps are integer nanoseconds on one monotonic domain ([Nanoseconds] /
 * [SensorFrame.timestamp]).
 *
 * Causal. Missing or invalid data uses explicit result types. Failures are never
 * turned into zeros. No Android types. [DeadReckoningFilter] is not modified here.
 *
 * Phone path: [MountSession] owns still capture, yaw-from-motion, the profile,
 * and remount watch. PoseStore calls it before ingesting IMU. ALIGNED /
 * ALIGNED_HIGH emit [VectorFrame.VEHICLE_FLU] after [MountProfile.toVehicleAccel]
 * / [toVehicleGyro]. Pending and STATIONARY_ONLY keep [VectorFrame.ANDROID_DEVICE]
 * so NHC stays a guess until yaw is resolved.
 *
 * Filter hook (not applied in this file):
 * - Skip NHC while [imuFrame] is [VectorFrame.ANDROID_DEVICE]
 * - On [MisalignmentUpdate.Remount], [NavigationEngine.reset] ([ResetReason.REMOUNT])
 *
 * First-run still UI stays on [StationaryCalibrator] / [MountMonitor]. This file is
 * the rotation profile those types do not produce.
 */

internal data class StationaryConfig(
    /** Minimum capture span, nanoseconds. */
    val minDurationNs: Long = 2_000_000_000L,
    val minSampleCount: Int = 50,
    /** RMS of per-axis sample standard deviations, m/s^2. */
    val maxAccelStdMps2: Double = 0.40,
    /** RMS of per-axis sample standard deviations, rad/s. */
    val maxGyroStdRadps: Double = 0.06,
    /** Nominal gravity magnitude used only as a stillness check, m/s^2. */
    val gravityNomMps2: Double = Wgs84.STANDARD_G,
    /** Allowed |mean(accel)| deviation from [gravityNomMps2], m/s^2. */
    val gravityAbsToleranceMps2: Double = 2.0,
) {
    init {
        require(minDurationNs > 0L) { "minDurationNs must be positive" }
        require(minSampleCount >= 2) { "minSampleCount must be at least 2 to estimate variance" }
        require(maxAccelStdMps2 > 0.0 && maxAccelStdMps2.isFinite()) { "maxAccelStdMps2 must be finite and positive" }
        require(maxGyroStdRadps > 0.0 && maxGyroStdRadps.isFinite()) { "maxGyroStdRadps must be finite and positive" }
        require(gravityNomMps2 > 0.0 && gravityNomMps2.isFinite()) { "gravityNomMps2 must be finite and positive" }
        require(gravityAbsToleranceMps2 > 0.0 && gravityAbsToleranceMps2.isFinite()) {
            "gravityAbsToleranceMps2 must be finite and positive"
        }
    }
}

internal sealed class StationaryResult {
    /**
     * Successful still capture.
     * @param gravityPhone mean accelerometer reading in the phone frame, m/s^2
     * @param gyroBias mean gyroscope reading in the phone frame, rad/s
     * @param accelStd RMS component sample std, m/s^2
     * @param gyroStd RMS component sample std, rad/s
     * @param durationNs last minus first accepted timestamp
     */
    data class Ok(
        val gravityPhone: Vec3,
        val gyroBias: Vec3,
        val accelStd: Double,
        val gyroStd: Double,
        val sampleCount: Int,
        val durationNs: Long,
    ) : StationaryResult()

    data class Insufficient(val reason: String) : StationaryResult()

    data class Moving(val reason: String) : StationaryResult()
}

internal sealed class SampleAccept {
    data object Accepted : SampleAccept()

    /** Sample is well-formed but does not contribute (for example, not a straight accel event). */
    data class Ignored(val reason: String) : SampleAccept()

    data class Rejected(val reason: String) : SampleAccept()
}

/**
 * Accumulates accelerometer (m/s^2) and gyroscope (rad/s) samples while the vehicle is still.
 *
 * The caller may gate on an external idle detector. This type also applies gyro/accel
 * variance and mean-specific-force checks so a moving stream cannot become a profile.
 * [Vec3] construction already rejects non-finite components.
 */
internal class StationaryCapture(
    private val config: StationaryConfig = StationaryConfig(),
) {
    private val accelStats = Welford3()
    private val gyroStats = Welford3()
    private var firstNs: Long? = null
    private var lastNs: Long? = null
    private var invalidReason: String? = null

    fun add(
        timestampNs: Long,
        accelPhoneMps2: Vec3,
        gyroPhoneRadps: Vec3,
    ): SampleAccept {
        val invalid = invalidReason
        if (invalid != null) {
            return SampleAccept.Rejected(invalid)
        }
        val order = checkMonotonic(timestampNs, lastNs)
        if (order != null) {
            invalidReason = order
            return SampleAccept.Rejected(order)
        }
        if (firstNs == null) {
            firstNs = timestampNs
        }
        lastNs = timestampNs
        accelStats.add(accelPhoneMps2)
        gyroStats.add(gyroPhoneRadps)
        return SampleAccept.Accepted
    }

    fun result(): StationaryResult {
        val invalid = invalidReason
        if (invalid != null) {
            return StationaryResult.Insufficient(invalid)
        }
        val start = firstNs
        val end = lastNs
        if (start == null || end == null || accelStats.count == 0) {
            return StationaryResult.Insufficient("no samples")
        }
        val durationNs = end - start
        if (accelStats.count < config.minSampleCount) {
            return StationaryResult.Insufficient(
                "sampleCount ${accelStats.count} below minimum ${config.minSampleCount}",
            )
        }
        if (durationNs < config.minDurationNs) {
            return StationaryResult.Insufficient(
                "durationNs $durationNs below minimum ${config.minDurationNs}",
            )
        }
        val accelStd = accelStats.rmsSampleStd()
        val gyroStd = gyroStats.rmsSampleStd()
        if (accelStd is Moment.Unavailable) {
            return StationaryResult.Insufficient(accelStd.reason)
        }
        if (gyroStd is Moment.Unavailable) {
            return StationaryResult.Insufficient(gyroStd.reason)
        }
        val accelStdOk = (accelStd as Moment.Ok).value
        val gyroStdOk = (gyroStd as Moment.Ok).value
        if (accelStdOk > config.maxAccelStdMps2) {
            return StationaryResult.Moving(
                "accelStd $accelStdOk m/s^2 above ${config.maxAccelStdMps2}",
            )
        }
        if (gyroStdOk > config.maxGyroStdRadps) {
            return StationaryResult.Moving(
                "gyroStd $gyroStdOk rad/s above ${config.maxGyroStdRadps}",
            )
        }
        val gravity = accelStats.mean
        val meanNorm = gravity.norm()
        val gravityErr = abs(meanNorm - config.gravityNomMps2)
        if (gravityErr > config.gravityAbsToleranceMps2) {
            return StationaryResult.Moving(
                "mean specific-force magnitude $meanNorm m/s^2 is $gravityErr from gravity",
            )
        }
        return StationaryResult.Ok(
            gravityPhone = gravity,
            gyroBias = gyroStats.mean,
            accelStd = accelStdOk,
            gyroStd = gyroStdOk,
            sampleCount = accelStats.count,
            durationNs = durationNs,
        )
    }
}

/** Roll and pitch from gravity in the phone frame, radians. */
internal data class RollPitch(val rollRad: Double, val pitchRad: Double)

internal sealed class GravityAttitude {
    data class Ok(
        val rollPitch: RollPitch,
        /** Rotation that maps [gravityPhone] onto vehicle +z, using the ANDROID_Y_FORWARD yaw completion. */
        val rotationPhoneToVehicle: Mat3,
    ) : GravityAttitude()

    data class Invalid(val reason: String) : GravityAttitude()
}

/**
 * Roll and pitch that map [gravityPhone] (m/s^2, phone frame) onto vehicle +z.
 *
 * `roll = atan2(gy, gz)`, `pitch = atan2(-gx, hypot(gy, gz))`.
 * A near-zero gravity vector is [GravityAttitude.Invalid], not a zero angle.
 */
internal fun rollPitchFromGravity(gravityPhone: Vec3): GravityAttitude {
    val g = gravityPhone.norm()
    if (g < NORM_EPS) {
        return GravityAttitude.Invalid("near-zero gravityPhone (m/s^2)")
    }
    val roll = atan2(gravityPhone.y, gravityPhone.z)
    val pitch = atan2(-gravityPhone.x, hypot(gravityPhone.y, gravityPhone.z))
    return when (val leveled = rotationPhoneToVehicleFromGravity(gravityPhone, yawAboutVehicleZRad = 0.0)) {
        is RotationFromGravity.Ok ->
            GravityAttitude.Ok(RollPitch(roll, pitch), leveled.rotationPhoneToVehicle)
        is RotationFromGravity.Invalid -> GravityAttitude.Invalid(leveled.reason)
    }
}

internal sealed class RotationFromGravity {
    data class Ok(val rotationPhoneToVehicle: Mat3) : RotationFromGravity()

    data class Invalid(val reason: String) : RotationFromGravity()
}

/**
 * Rotation phone to vehicle that maps [gravityPhone] (m/s^2) onto vehicle +z.
 *
 * Remaining yaw about vehicle +z is the [Mat3.ANDROID_Y_FORWARD] completion
 * ([bodyForward] of [VectorFrame.ANDROID_DEVICE], phone +Y, projected onto the
 * horizontal plane becomes vehicle +X) plus [yawAboutVehicleZRad] (radians).
 */
internal fun rotationPhoneToVehicleFromGravity(
    gravityPhone: Vec3,
    yawAboutVehicleZRad: Double = 0.0,
): RotationFromGravity {
    if (!yawAboutVehicleZRad.isFinite()) {
        return RotationFromGravity.Invalid("non-finite yawAboutVehicleZRad (rad)")
    }
    val zPhone = gravityPhone.normalized()
        ?: return RotationFromGravity.Invalid("near-zero gravityPhone (m/s^2)")
    val xPhone = projectUnit(zPhone, bodyForward(VectorFrame.ANDROID_DEVICE))
        ?: projectUnit(zPhone, bodyForward(VectorFrame.VEHICLE_FLU))
        ?: return RotationFromGravity.Invalid("could not complete yaw from gravity")
    val yPhone = zPhone.cross(xPhone).normalized()
        ?: return RotationFromGravity.Invalid("degenerate phone Y from gravity")
    val xOrtho = yPhone.cross(zPhone).normalized()
        ?: return RotationFromGravity.Invalid("degenerate phone X from gravity")
    val leveled = mat3FromRows(xOrtho, yPhone, zPhone)
    val rotation = rotationZ(yawAboutVehicleZRad) * leveled
    if (!rotation.isFinite()) {
        return RotationFromGravity.Invalid("non-finite rotation")
    }
    return RotationFromGravity.Ok(rotation)
}

internal data class YawFromMotionConfig(
    val neededEvents: Int = 3,
    /** Maximum |gyro · gravity_hat|, rad/s, to treat the interval as straight. */
    val maxGravityAlignedGyroRadps: Double = 0.10,
    /** Minimum horizontal specific-force magnitude to count as accel/brake, m/s^2. */
    val minHorizontalAccelMps2: Double = 0.35,
    /** GNSS speed-change magnitude required before the sign is trusted, m/s. */
    val speedDeltaAbsMinMps: Double = 0.25,
) {
    init {
        require(neededEvents >= 1) { "neededEvents must be at least 1" }
        require(maxGravityAlignedGyroRadps > 0.0 && maxGravityAlignedGyroRadps.isFinite()) {
            "maxGravityAlignedGyroRadps must be finite and positive"
        }
        require(minHorizontalAccelMps2 > 0.0 && minHorizontalAccelMps2.isFinite()) {
            "minHorizontalAccelMps2 must be finite and positive"
        }
        require(speedDeltaAbsMinMps > 0.0 && speedDeltaAbsMinMps.isFinite()) {
            "speedDeltaAbsMinMps must be finite and positive"
        }
    }
}

internal sealed class YawResult {
    /**
     * @param yawRad correction about vehicle +z relative to the ANDROID_Y_FORWARD completion, radians
     * @param confidence in `[0, 1]`, agreement of signed events
     */
    data class YawEstimate(
        val yawRad: Double,
        val confidence: Double,
        val eventCount: Int,
    ) : YawResult()

    data class Pending(val eventCount: Int, val needed: Int) : YawResult()
}

/**
 * Accumulates straight-line accel/brake events and resolves yaw about gravity.
 *
 * Horizontal specific-force in the phone frame points along vehicle forward during
 * acceleration and opposite during braking. The 180-degree ambiguity is resolved only
 * when a GNSS speed delta is present. Without a speed-delta sign the result stays
 * [YawResult.Pending], even if several unsigned events agree.
 */
internal class YawFromMotion(
    private val gravityPhoneMps2: Vec3,
    private val config: YawFromMotionConfig = YawFromMotionConfig(),
) {
    private data class StoredEvent(
        val timestampNs: Long,
        val horizUnitPhone: Vec3,
        val speedDeltaMps: Double?,
    )

    private val events = ArrayList<StoredEvent>()
    private var lastNs: Long? = null
    private var invalidReason: String? = null

    fun add(
        timestampNs: Long,
        accelPhoneMps2: Vec3,
        gyroPhoneRadps: Vec3,
        gnssSpeedDeltaMps: Double?,
    ): SampleAccept {
        val invalid = invalidReason
        if (invalid != null) {
            return SampleAccept.Rejected(invalid)
        }
        val order = checkMonotonic(timestampNs, lastNs)
        if (order != null) {
            invalidReason = order
            return SampleAccept.Rejected(order)
        }
        lastNs = timestampNs
        if (gnssSpeedDeltaMps != null && !gnssSpeedDeltaMps.isFinite()) {
            return SampleAccept.Ignored("non-finite gnssSpeedDeltaMps (m/s)")
        }
        val gHat = gravityPhoneMps2.normalized()
        if (gHat == null) {
            invalidReason = "degenerate gravityPhone (m/s^2)"
            return SampleAccept.Rejected(invalidReason!!)
        }
        val yawRate = abs(gyroPhoneRadps.dot(gHat))
        if (yawRate > config.maxGravityAlignedGyroRadps) {
            return SampleAccept.Ignored("gyro yaw rate $yawRate rad/s is not straight")
        }
        val horiz = accelPhoneMps2 - gHat * accelPhoneMps2.dot(gHat)
        val horizNorm = horiz.norm()
        if (horizNorm < config.minHorizontalAccelMps2) {
            return SampleAccept.Ignored("horizontal accel $horizNorm m/s^2 below ${config.minHorizontalAccelMps2}")
        }
        val horizUnit = horiz.normalized()
            ?: return SampleAccept.Ignored("degenerate horizontal specific-force")
        events.add(StoredEvent(timestampNs, horizUnit, gnssSpeedDeltaMps))
        return SampleAccept.Accepted
    }

    fun estimate(): YawResult {
        val invalid = invalidReason
        if (invalid != null) {
            return YawResult.Pending(eventCount = 0, needed = config.neededEvents)
        }
        val signed = ArrayList<Vec3>()
        for (event in events) {
            val delta = event.speedDeltaMps
            if (delta == null || !delta.isFinite() || abs(delta) < config.speedDeltaAbsMinMps) {
                continue
            }
            signed.add(if (delta > 0.0) event.horizUnitPhone else -event.horizUnitPhone)
        }
        if (signed.size < config.neededEvents) {
            return YawResult.Pending(eventCount = signed.size, needed = config.neededEvents)
        }
        val gHat = gravityPhoneMps2.normalized()
            ?: return YawResult.Pending(0, config.neededEvents)
        val seed = signed[0]
        val majority = ArrayList<Vec3>()
        val minority = ArrayList<Vec3>()
        for (dir in signed) {
            if (dir.dot(seed) >= 0.0) {
                majority.add(dir)
            } else {
                minority.add(dir)
            }
        }
        val chosen = if (majority.size >= minority.size) majority else minority
        if (chosen.size < config.neededEvents) {
            return YawResult.Pending(eventCount = chosen.size, needed = config.neededEvents)
        }
        var sum = Vec3.ZERO
        for (dir in chosen) {
            sum += dir
        }
        val forward = sum.normalized()
            ?: return YawResult.Pending(chosen.size, config.neededEvents)
        val defaultForward = projectUnit(gHat, bodyForward(VectorFrame.ANDROID_DEVICE))
            ?: projectUnit(gHat, bodyForward(VectorFrame.VEHICLE_FLU))
            ?: return YawResult.Pending(chosen.size, config.neededEvents)
        val sinYaw = defaultForward.cross(forward).dot(gHat)
        val cosYaw = defaultForward.dot(forward)
        val yawRad = atan2(sinYaw, cosYaw)
        if (!yawRad.isFinite()) {
            return YawResult.Pending(chosen.size, config.neededEvents)
        }
        var agree = 0.0
        for (dir in chosen) {
            agree += dir.dot(forward).coerceIn(-1.0, 1.0)
        }
        val confidence = (agree / chosen.size.toDouble()).coerceIn(0.0, 1.0)
        return YawResult.YawEstimate(
            yawRad = yawRad,
            confidence = confidence,
            eventCount = chosen.size,
        )
    }
}

internal enum class ProfileQuality {
    STATIONARY_ONLY,
    ALIGNED,
    ALIGNED_HIGH,
}

/**
 * Persisted phone-to-vehicle mount.
 *
 * @param rotationPhoneToVehicle maps phone-frame vectors to the vehicle frame
 * @param gravityPhone mean still accelerometer reading, m/s^2, phone frame
 * @param gyroBias mean still gyroscope reading, rad/s, phone frame
 * @param yawConfidence in `[0, 1]`; zero when yaw was not resolved
 * @param createdNs profile creation time on the sensor clock, integer nanoseconds
 */
internal data class MountProfile(
    val rotationPhoneToVehicle: Mat3,
    val gravityPhone: Vec3,
    val gyroBias: Vec3,
    val yawConfidence: Double,
    val createdNs: Long,
    val quality: ProfileQuality,
) {
    init {
        require(rotationPhoneToVehicle.isFinite()) { "rotationPhoneToVehicle must be finite" }
        require(yawConfidence.isFinite() && yawConfidence >= 0.0 && yawConfidence <= 1.0) {
            "yawConfidence must be in [0, 1]"
        }
        require(createdNs >= 0L) { "createdNs must be non-negative" }
    }

    /** Rotate phone-frame acceleration (m/s^2) into the vehicle frame. Filter hook. */
    fun toVehicleAccel(accelPhoneMps2: Vec3): Vec3 = rotationPhoneToVehicle * accelPhoneMps2

    /**
     * Subtract [gyroBias] in the phone frame (rad/s) then rotate into the vehicle frame.
     * Filter hook.
     */
    fun toVehicleGyro(gyroPhoneRadps: Vec3): Vec3 = rotationPhoneToVehicle * (gyroPhoneRadps - gyroBias)

    fun toContractMap(): Map<String, Any?> =
        linkedMapOf(
            "schema_version" to SCHEMA_VERSION,
            "rotation_phone_to_vehicle" to rowMajor(rotationPhoneToVehicle).toList(),
            "gravity_phone_mps2" to vec3Map(gravityPhone),
            "gyro_bias_radps" to vec3Map(gyroBias),
            "yaw_confidence" to yawConfidence,
            "created_ns" to createdNs,
            "quality" to quality.name,
        )

    fun toJson(): String = ContractJson.stringify(toContractMap())

    companion object {
        const val SCHEMA_VERSION: String = "mount_profile_1.0.0"
        const val ALIGNED_HIGH_MIN_CONFIDENCE: Double = 0.80

        fun fromContractMap(map: Map<String, Any?>): ProfileParse {
            return try {
                parseProfile(map)
            } catch (error: IllegalArgumentException) {
                ProfileParse.Invalid(error.message ?: "invalid mount profile")
            }
        }

        fun fromJson(json: String): ProfileParse {
            val map = try {
                ContractJson.parseObject(json)
            } catch (error: IllegalArgumentException) {
                return ProfileParse.Invalid(error.message ?: "invalid JSON")
            }
            return fromContractMap(map)
        }

        private fun parseProfile(map: Map<String, Any?>): ProfileParse {
            val schema = ContractJson.asString(map["schema_version"], "schema_version")
            if (schema != SCHEMA_VERSION) {
                return ProfileParse.Invalid("unsupported schema_version '$schema'")
            }
            val rotationRaw = map["rotation_phone_to_vehicle"]
                ?: return ProfileParse.Invalid("missing rotation_phone_to_vehicle")
            val rotationList = rotationRaw as? List<*>
                ?: return ProfileParse.Invalid("rotation_phone_to_vehicle is not an array")
            if (rotationList.size != 9) {
                return ProfileParse.Invalid(
                    "rotation_phone_to_vehicle size ${rotationList.size} != 9",
                )
            }
            val values = DoubleArray(9)
            for (i in 0 until 9) {
                values[i] = ContractJson.asDouble(rotationList[i], "rotation_phone_to_vehicle[$i]")
            }
            val rotation = mat3FromRowMajor(values)
                ?: return ProfileParse.Invalid("rotation_phone_to_vehicle is not a finite 3x3")
            val gravityObj = map["gravity_phone_mps2"]
                ?: return ProfileParse.Invalid("missing gravity_phone_mps2")
            val biasObj = map["gyro_bias_radps"]
                ?: return ProfileParse.Invalid("missing gyro_bias_radps")
            val gravity = readVec3(gravityObj, "gravity_phone_mps2")
            val bias = readVec3(biasObj, "gyro_bias_radps")
            val conf = ContractJson.asDouble(map["yaw_confidence"], "yaw_confidence")
            if (conf < 0.0 || conf > 1.0) {
                return ProfileParse.Invalid("yaw_confidence $conf is outside [0, 1]")
            }
            val createdNs = ContractJson.asLong(map["created_ns"], "created_ns")
            if (createdNs < 0L) {
                return ProfileParse.Invalid("created_ns is negative")
            }
            val qualityRaw = ContractJson.asString(map["quality"], "quality")
            val quality = try {
                ProfileQuality.valueOf(qualityRaw)
            } catch (error: IllegalArgumentException) {
                return ProfileParse.Invalid("unknown quality '$qualityRaw'")
            }
            return ProfileParse.Ok(
                MountProfile(
                    rotationPhoneToVehicle = rotation,
                    gravityPhone = gravity,
                    gyroBias = bias,
                    yawConfidence = conf,
                    createdNs = createdNs,
                    quality = quality,
                ),
            )
        }

        private fun readVec3(raw: Any?, field: String): Vec3 {
            val obj = ContractJson.asObject(raw, field)
            return Vec3(
                ContractJson.asDouble(obj["x"], "$field.x"),
                ContractJson.asDouble(obj["y"], "$field.y"),
                ContractJson.asDouble(obj["z"], "$field.z"),
            )
        }
    }
}

internal sealed class ProfileParse {
    data class Ok(val profile: MountProfile) : ProfileParse()

    data class Invalid(val reason: String) : ProfileParse()
}

internal sealed class ProfileCompose {
    data class Ok(val profile: MountProfile) : ProfileCompose()

    data class Invalid(val reason: String) : ProfileCompose()
}

/**
 * Build a [MountProfile] from a finished still capture and a yaw result.
 *
 * [YawResult.Pending] yields [ProfileQuality.STATIONARY_ONLY] with the ANDROID_Y_FORWARD
 * yaw completion (yaw correction 0). A resolved estimate becomes [ProfileQuality.ALIGNED]
 * or [ProfileQuality.ALIGNED_HIGH].
 */
internal fun composeMountProfile(
    stationary: StationaryResult.Ok,
    yaw: YawResult,
    createdNs: Long,
): ProfileCompose {
    if (createdNs < 0L) {
        return ProfileCompose.Invalid("createdNs is negative")
    }
    val yawRad: Double
    val confidence: Double
    val quality: ProfileQuality
    when (yaw) {
        is YawResult.Pending -> {
            yawRad = 0.0
            confidence = 0.0
            quality = ProfileQuality.STATIONARY_ONLY
        }
        is YawResult.YawEstimate -> {
            if (!yaw.yawRad.isFinite()) {
                return ProfileCompose.Invalid("non-finite yawRad (rad)")
            }
            if (!yaw.confidence.isFinite() || yaw.confidence < 0.0 || yaw.confidence > 1.0) {
                return ProfileCompose.Invalid("yaw confidence is not in [0, 1]")
            }
            yawRad = yaw.yawRad
            confidence = yaw.confidence
            quality =
                if (confidence >= MountProfile.ALIGNED_HIGH_MIN_CONFIDENCE) {
                    ProfileQuality.ALIGNED_HIGH
                } else {
                    ProfileQuality.ALIGNED
                }
        }
    }
    return when (val rotation = rotationPhoneToVehicleFromGravity(stationary.gravityPhone, yawRad)) {
        is RotationFromGravity.Ok ->
            ProfileCompose.Ok(
                MountProfile(
                    rotationPhoneToVehicle = rotation.rotationPhoneToVehicle,
                    gravityPhone = stationary.gravityPhone,
                    gyroBias = stationary.gyroBias,
                    yawConfidence = confidence,
                    createdNs = createdNs,
                    quality = quality,
                ),
            )
        is RotationFromGravity.Invalid -> ProfileCompose.Invalid(rotation.reason)
    }
}

internal data class MisalignmentConfig(
    /** Gravity-direction change that starts the remount hold, radians. Default about 25 degrees. */
    val thresholdRad: Double = 25.0 * PI / 180.0,
    /** Return below this angle (radians) resets the hold. Hysteresis against potholes. */
    val recoverRad: Double = 12.0 * PI / 180.0,
    /** Time the angle must stay above [thresholdRad], nanoseconds. Default 3 s. */
    val holdNs: Long = 3_000_000_000L,
    /**
     * Skip the remount test when ||accel| - |profile gravity|| exceeds this, m/s^2.
     * Hard acceleration or a bump is not a gravity-direction observation.
     */
    val maxSpecificForceDeviationMps2: Double = 1.5,
) {
    init {
        require(thresholdRad > 0.0 && thresholdRad.isFinite()) { "thresholdRad must be finite and positive" }
        require(recoverRad > 0.0 && recoverRad.isFinite()) { "recoverRad must be finite and positive" }
        require(recoverRad < thresholdRad) { "recoverRad must be below thresholdRad" }
        require(holdNs > 0L) { "holdNs must be positive" }
        require(maxSpecificForceDeviationMps2 > 0.0 && maxSpecificForceDeviationMps2.isFinite()) {
            "maxSpecificForceDeviationMps2 must be finite and positive"
        }
    }
}

internal sealed class MisalignmentUpdate {
    data object Observing : MisalignmentUpdate()

    data class Remount(val detectedAtNs: Long, val angleRad: Double) : MisalignmentUpdate()

    data class Rejected(val reason: String) : MisalignmentUpdate()
}

/**
 * Watches low-passed phone-frame acceleration for a sustained gravity-direction change.
 *
 * A pothole or a 0.3 s spike does not emit [MisalignmentUpdate.Remount]. [clear] after a
 * new [MountProfile] is installed. Causal. No Android types.
 */
internal class MisalignmentMonitor(
    private val config: MisalignmentConfig = MisalignmentConfig(),
) {
    private var profile: MountProfile? = null
    private var lastNs: Long? = null
    private var suspectStartNs: Long? = null
    private var fired: Boolean = false

    fun setProfile(profile: MountProfile) {
        clear()
        this.profile = profile
    }

    fun clear() {
        profile = null
        lastNs = null
        suspectStartNs = null
        fired = false
    }

    /**
     * @param timestampNs integer nanoseconds on the sensor clock
     * @param lowPassedAccelPhoneMps2 causally low-passed accelerometer reading, phone frame, m/s^2
     */
    fun update(
        timestampNs: Long,
        lowPassedAccelPhoneMps2: Vec3,
    ): MisalignmentUpdate {
        val current = profile ?: return MisalignmentUpdate.Rejected("no mount profile")
        val order = checkMonotonic(timestampNs, lastNs)
        if (order != null) {
            return MisalignmentUpdate.Rejected(order)
        }
        lastNs = timestampNs
        if (fired) {
            return MisalignmentUpdate.Observing
        }
        val expectedG = current.gravityPhone.norm()
        val measured = lowPassedAccelPhoneMps2.norm()
        if (abs(measured - expectedG) > config.maxSpecificForceDeviationMps2) {
            return MisalignmentUpdate.Observing
        }
        val angle = when (val a = angleBetween(lowPassedAccelPhoneMps2, current.gravityPhone)) {
            is AngleBetween.Ok -> a.radians
            is AngleBetween.Degenerate -> return MisalignmentUpdate.Rejected(a.reason)
        }
        if (angle > config.thresholdRad) {
            val start = suspectStartNs
            if (start == null) {
                suspectStartNs = timestampNs
                return MisalignmentUpdate.Observing
            }
            if (timestampNs - start >= config.holdNs) {
                fired = true
                return MisalignmentUpdate.Remount(detectedAtNs = timestampNs, angleRad = angle)
            }
            return MisalignmentUpdate.Observing
        }
        if (angle < config.recoverRad) {
            suspectStartNs = null
        }
        return MisalignmentUpdate.Observing
    }
}

/**
 * Public quality for the phone path. [PENDING] means no still capture yet.
 * [STATIONARY_ONLY] has gravity and gyro bias but unresolved yaw (NHC off).
 * [ALIGNED] / [ALIGNED_HIGH] may emit [VectorFrame.VEHICLE_FLU].
 */
enum class MountQuality {
    PENDING,
    STATIONARY_ONLY,
    ALIGNED,
    ALIGNED_HIGH,
}

/** True when accel/gyro should be rotated into the vehicle frame. */
fun MountQuality.emitsVehicleFrame(): Boolean =
    this == MountQuality.ALIGNED || this == MountQuality.ALIGNED_HIGH

/**
 * One IMU sample after mount processing.
 *
 * Accel components are m/s^2. Gyro components are rad/s. Both are expressed
 * in [frame]. [timestampNs] is the sensor clock, integer nanoseconds.
 */
data class MountImuEmit(
    val timestampNs: Long,
    val frame: VectorFrame,
    val x: Double,
    val y: Double,
    val z: Double,
    val remount: Boolean,
    val quality: MountQuality,
    val profileChanged: Boolean,
)

/**
 * Causal phone-to-vehicle mount session (ADR 007, SIH-17).
 *
 * Still phase uses [StationaryCapture] (accelerometer m/s^2, gyroscope rad/s).
 * Yaw uses [YawFromMotion] with a GNSS speed-delta sign, only while GNSS is
 * accepted. Remount uses [MisalignmentMonitor] on low-passed phone accel.
 *
 * No Android types. [DeadReckoningFilter] is not imported.
 */
class MountSession {
    private val lock = Any()
    private var capture = StationaryCapture()
    private var stillOk: StationaryResult.Ok? = null
    private var yaw: YawFromMotion? = null
    private var profile: MountProfile? = null
    private val monitor = MisalignmentMonitor()
    private var lastGyroPhoneRadps: Vec3? = null
    private var lpAccelPhoneMps2: Vec3? = null
    private var quality: MountQuality = MountQuality.PENDING

    fun quality(): MountQuality = synchronized(lock) { quality }

    /** Contract JSON (`mount_profile_1.0.0`), or null when [MountQuality.PENDING]. */
    fun profileJson(): String? = synchronized(lock) { profile?.toJson() }

    /**
     * Restore a persisted [MountProfile]. Invalid JSON leaves the session pending.
     * @return true when a profile was installed
     */
    fun restoreFromJson(json: String): Boolean =
        synchronized(lock) {
            when (val parsed = MountProfile.fromJson(json)) {
                is ProfileParse.Invalid -> false
                is ProfileParse.Ok -> {
                    installProfile(
                        parsed.profile,
                        startYawIfStationary = parsed.profile.quality == ProfileQuality.STATIONARY_ONLY,
                    )
                    true
                }
            }
        }

    /**
     * Accelerometer sample, phone frame, m/s^2.
     *
     * [gnssSpeedDeltaMps] is the signed GNSS speed change (m/s) from the last
     * two accepted fixes. Pass null when GNSS is held, stale, or missing.
     * [gnssAccepted] must be false inside a blackout so yaw stays [YawResult.Pending].
     */
    fun onAccel(
        timestampNs: Long,
        accelXMps2: Double,
        accelYMps2: Double,
        accelZMps2: Double,
        gnssSpeedDeltaMps: Double?,
        gnssAccepted: Boolean,
        phoneStill: Boolean = false,
    ): MountImuEmit =
        synchronized(lock) {
            if (!accelXMps2.isFinite() || !accelYMps2.isFinite() || !accelZMps2.isFinite()) {
                return passthrough(timestampNs, accelXMps2, accelYMps2, accelZMps2, remount = false, changed = false)
            }
            val accelPhone = Vec3(accelXMps2, accelYMps2, accelZMps2)
            val before = quality
            var remount = false
            if (stillOk == null) {
                advanceStill(timestampNs, accelPhone)
            } else if (quality == MountQuality.STATIONARY_ONLY) {
                advanceYaw(timestampNs, accelPhone, gnssSpeedDeltaMps, gnssAccepted)
            }
            val watching = profile
            if (watching != null && !phoneStill) {
                when (val update = monitor.update(timestampNs, lowPass(accelPhone))) {
                    is MisalignmentUpdate.Remount -> {
                        enterPending()
                        remount = true
                    }
                    else -> Unit
                }
            }
            emitPhone(
                timestampNs = timestampNs,
                phone = accelPhone,
                gyro = false,
                remount = remount,
                profileChanged = remount || quality != before,
            )
        }

    /**
     * Gyroscope sample, phone frame, rad/s. Bias is subtracted in the phone
     * frame then rotated only when quality is ALIGNED / ALIGNED_HIGH.
     */
    fun onGyro(
        timestampNs: Long,
        gyroXRadps: Double,
        gyroYRadps: Double,
        gyroZRadps: Double,
    ): MountImuEmit =
        synchronized(lock) {
            if (!gyroXRadps.isFinite() || !gyroYRadps.isFinite() || !gyroZRadps.isFinite()) {
                return passthrough(timestampNs, gyroXRadps, gyroYRadps, gyroZRadps, remount = false, changed = false)
            }
            val gyroPhone = Vec3(gyroXRadps, gyroYRadps, gyroZRadps)
            lastGyroPhoneRadps = gyroPhone
            emitPhone(
                timestampNs = timestampNs,
                phone = gyroPhone,
                gyro = true,
                remount = false,
                profileChanged = false,
            )
        }

    private fun advanceStill(timestampNs: Long, accelPhone: Vec3) {
        val gyro = lastGyroPhoneRadps ?: return
        when (capture.add(timestampNs, accelPhone, gyro)) {
            is SampleAccept.Rejected -> {
                capture = StationaryCapture()
            }
            else -> {
                when (val result = capture.result()) {
                    is StationaryResult.Ok -> {
                        stillOk = result
                        when (val composed = composeMountProfile(result, YawResult.Pending(0, 3), timestampNs)) {
                            is ProfileCompose.Ok ->
                                installProfile(composed.profile, startYawIfStationary = true)
                            is ProfileCompose.Invalid -> {
                                stillOk = null
                                capture = StationaryCapture()
                            }
                        }
                    }
                    is StationaryResult.Moving -> {
                        capture = StationaryCapture()
                    }
                    is StationaryResult.Insufficient -> {
                        if (result.reason.contains("non-monotonic") || result.reason.contains("negative")) {
                            capture = StationaryCapture()
                        }
                    }
                }
            }
        }
    }

    private fun advanceYaw(
        timestampNs: Long,
        accelPhone: Vec3,
        gnssSpeedDeltaMps: Double?,
        gnssAccepted: Boolean,
    ) {
        val tracker = yaw ?: return
        val still = stillOk ?: return
        val gyro = lastGyroPhoneRadps ?: Vec3.ZERO
        val delta = if (gnssAccepted) gnssSpeedDeltaMps else null
        when (tracker.add(timestampNs, accelPhone, gyro, delta)) {
            is SampleAccept.Rejected -> {
                yaw = YawFromMotion(still.gravityPhone)
            }
            else -> {
                when (val estimate = tracker.estimate()) {
                    is YawResult.YawEstimate -> {
                        when (val composed = composeMountProfile(still, estimate, timestampNs)) {
                            is ProfileCompose.Ok ->
                                installProfile(composed.profile, startYawIfStationary = false)
                            is ProfileCompose.Invalid -> Unit
                        }
                    }
                    is YawResult.Pending -> Unit
                }
            }
        }
    }

    private fun installProfile(next: MountProfile, startYawIfStationary: Boolean) {
        profile = next
        quality = next.quality.toMountQuality()
        stillOk =
            StationaryResult.Ok(
                gravityPhone = next.gravityPhone,
                gyroBias = next.gyroBias,
                accelStd = 0.01,
                gyroStd = 0.001,
                sampleCount = 50,
                durationNs = 2_000_000_000L,
            )
        monitor.setProfile(next)
        lpAccelPhoneMps2 = null
        yaw =
            if (startYawIfStationary && next.quality == ProfileQuality.STATIONARY_ONLY) {
                YawFromMotion(next.gravityPhone)
            } else {
                null
            }
    }

    private fun enterPending() {
        capture = StationaryCapture()
        stillOk = null
        yaw = null
        profile = null
        monitor.clear()
        lpAccelPhoneMps2 = null
        quality = MountQuality.PENDING
    }

    private fun lowPass(sample: Vec3): Vec3 {
        val prev = lpAccelPhoneMps2
        val next =
            if (prev == null) {
                sample
            } else {
                Vec3(
                    prev.x * (1.0 - LOW_PASS_ALPHA) + sample.x * LOW_PASS_ALPHA,
                    prev.y * (1.0 - LOW_PASS_ALPHA) + sample.y * LOW_PASS_ALPHA,
                    prev.z * (1.0 - LOW_PASS_ALPHA) + sample.z * LOW_PASS_ALPHA,
                )
            }
        lpAccelPhoneMps2 = next
        return next
    }

    private fun emitPhone(
        timestampNs: Long,
        phone: Vec3,
        gyro: Boolean,
        remount: Boolean,
        profileChanged: Boolean,
    ): MountImuEmit {
        val current = profile
        if (quality.emitsVehicleFrame() && current != null) {
            val vehicle = if (gyro) current.toVehicleGyro(phone) else current.toVehicleAccel(phone)
            return MountImuEmit(
                timestampNs = timestampNs,
                frame = VectorFrame.VEHICLE_FLU,
                x = vehicle.x,
                y = vehicle.y,
                z = vehicle.z,
                remount = remount,
                quality = quality,
                profileChanged = profileChanged,
            )
        }
        return MountImuEmit(
            timestampNs = timestampNs,
            frame = VectorFrame.ANDROID_DEVICE,
            x = phone.x,
            y = phone.y,
            z = phone.z,
            remount = remount,
            quality = quality,
            profileChanged = profileChanged,
        )
    }

    private fun passthrough(
        timestampNs: Long,
        x: Double,
        y: Double,
        z: Double,
        remount: Boolean,
        changed: Boolean,
    ): MountImuEmit =
        MountImuEmit(
            timestampNs = timestampNs,
            frame = if (quality.emitsVehicleFrame()) VectorFrame.VEHICLE_FLU else VectorFrame.ANDROID_DEVICE,
            x = x,
            y = y,
            z = z,
            remount = remount,
            quality = quality,
            profileChanged = changed,
        )

    companion object {
        const val REMOUNT_USER_REASON: String = "Phone moved. Hold still 5 s."
        private const val LOW_PASS_ALPHA: Double = 0.1
    }
}

private fun ProfileQuality.toMountQuality(): MountQuality =
    when (this) {
        ProfileQuality.STATIONARY_ONLY -> MountQuality.STATIONARY_ONLY
        ProfileQuality.ALIGNED -> MountQuality.ALIGNED
        ProfileQuality.ALIGNED_HIGH -> MountQuality.ALIGNED_HIGH
    }

private const val NORM_EPS: Double = 1.0e-12

private fun vec3Map(v: Vec3): Map<String, Double> = linkedMapOf("x" to v.x, "y" to v.y, "z" to v.z)

private fun projectUnit(gravityUnit: Vec3, hint: Vec3): Vec3? {
    val projected = hint - gravityUnit * hint.dot(gravityUnit)
    return projected.normalized()
}

private fun checkMonotonic(timestampNs: Long, lastNs: Long?): String? {
    if (timestampNs < 0L) {
        return "timestampNs is negative"
    }
    if (lastNs != null && timestampNs <= lastNs) {
        return "non-monotonic timestamp $timestampNs after $lastNs"
    }
    return null
}

private sealed class AngleBetween {
    data class Ok(val radians: Double) : AngleBetween()

    data class Degenerate(val reason: String) : AngleBetween()
}

private fun angleBetween(a: Vec3, b: Vec3): AngleBetween {
    val ua = a.normalized() ?: return AngleBetween.Degenerate("near-zero first vector")
    val ub = b.normalized() ?: return AngleBetween.Degenerate("near-zero second vector")
    val c = ua.dot(ub).coerceIn(-1.0, 1.0)
    return AngleBetween.Ok(acos(c))
}

private fun mat3FromRows(row0: Vec3, row1: Vec3, row2: Vec3): Mat3 =
    Mat3(
        row0.x, row0.y, row0.z,
        row1.x, row1.y, row1.z,
        row2.x, row2.y, row2.z,
    )

private fun rowMajor(m: Mat3): DoubleArray =
    doubleArrayOf(m.r00, m.r01, m.r02, m.r10, m.r11, m.r12, m.r20, m.r21, m.r22)

private fun mat3FromRowMajor(values: DoubleArray): Mat3? {
    if (values.size != 9) {
        return null
    }
    val m = Mat3(
        values[0], values[1], values[2],
        values[3], values[4], values[5],
        values[6], values[7], values[8],
    )
    return if (m.isFinite()) m else null
}

/** Right-handed rotation about vehicle +z, [angleRad] in radians. */
private fun rotationZ(angleRad: Double): Mat3 {
    val c = cos(angleRad)
    val s = sin(angleRad)
    return Mat3(
        c, -s, 0.0,
        s, c, 0.0,
        0.0, 0.0, 1.0,
    )
}

private sealed class Moment {
    data class Ok(val value: Double) : Moment()

    data class Unavailable(val reason: String) : Moment()
}

private class Welford3 {
    var count: Int = 0
        private set
    var mean: Vec3 = Vec3.ZERO
        private set
    private var m2: Vec3 = Vec3.ZERO

    fun add(sample: Vec3) {
        count += 1
        val delta = sample - mean
        mean += delta * (1.0 / count.toDouble())
        val delta2 = sample - mean
        m2 = Vec3(m2.x + delta.x * delta2.x, m2.y + delta.y * delta2.y, m2.z + delta.z * delta2.z)
    }

    fun rmsSampleStd(): Moment {
        if (count < 2) {
            return Moment.Unavailable("need at least 2 samples for a standard deviation")
        }
        val denom = (count - 1).toDouble()
        val vx = m2.x / denom
        val vy = m2.y / denom
        val vz = m2.z / denom
        if (vx < 0.0 || vy < 0.0 || vz < 0.0) {
            return Moment.Unavailable("negative variance from Welford accumulator")
        }
        if (!vx.isFinite() || !vy.isFinite() || !vz.isFinite()) {
            return Moment.Unavailable("non-finite variance")
        }
        return Moment.Ok(sqrt((vx + vy + vz) / 3.0))
    }
}
