package `in`.driftzero.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Phone-to-vehicle mount alignment and remount monitoring (PRD FR-02, REQ-17).
 *
 * Frames (documented on every public vector):
 * - Phone: Android device axes, x right, y toward the top of the screen, z out of the screen.
 * - Vehicle: x forward, y left, z up.
 *
 * Output of a completed alignment is [MountProfile.rotationPhoneToVehicle] ([Mat3]) plus
 * [ProfileQuality]. Accelerometer samples are m/s^2. Gyroscope samples are rad/s. Angles
 * are radians. Timestamps are integer nanoseconds on a single monotonic domain.
 *
 * Causal: each helper uses only samples already supplied. No future-aware smoothing.
 * Missing or invalid data uses explicit result types. Failures are never turned into zeros.
 *
 * Production path. No Android imports. [DeadReckoningFilter] is not modified here; the
 * filter hook is [MountProfile.toVehicleAccel], [MountProfile.toVehicleGyro], and
 * [MisalignmentMonitor.update] mapping [MisalignmentUpdate.Remount] onto
 * `ResetReason.REMOUNT` when that engine API is wired.
 */

data class StationaryConfig(
    /** Minimum capture span, nanoseconds. */
    val minDurationNs: Long = 2_000_000_000L,
    val minSampleCount: Int = 50,
    /** RMS of per-axis sample standard deviations, m/s^2. */
    val maxAccelStdMps2: Double = 0.40,
    /** RMS of per-axis sample standard deviations, rad/s. */
    val maxGyroStdRadps: Double = 0.06,
    /** Nominal gravity magnitude used only as a stillness check, m/s^2. */
    val gravityNomMps2: Double = STANDARD_GRAVITY_MPS2,
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

sealed class StationaryResult {
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

sealed class SampleAccept {
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
 */
class StationaryCapture(
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
        if (!accelPhoneMps2.isFinite()) {
            invalidReason = "non-finite accelerometer sample (m/s^2)"
            return SampleAccept.Rejected(invalidReason!!)
        }
        if (!gyroPhoneRadps.isFinite()) {
            invalidReason = "non-finite gyroscope sample (rad/s)"
            return SampleAccept.Rejected(invalidReason!!)
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
                "mean specific-force magnitude $meanNorm m/s^2 is ${gravityErr} from gravity",
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

/** Roll and pitch in the [bodyToVehicle] convention, radians. */
data class RollPitch(val rollRad: Double, val pitchRad: Double)

sealed class GravityAttitude {
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
 * `roll = atan2(gy, gz)`, `pitch = atan2(-gx, hypot(gy, gz))` in the [bodyToVehicle]
 * convention. A near-zero gravity vector is [GravityAttitude.Invalid], not a zero angle.
 */
fun rollPitchFromGravity(gravityPhone: Vec3): GravityAttitude {
    if (!gravityPhone.isFinite()) {
        return GravityAttitude.Invalid("non-finite gravityPhone (m/s^2)")
    }
    val g = gravityPhone.norm()
    if (g < VEC_DEGENERATE_NORM) {
        return GravityAttitude.Invalid("near-zero gravityPhone (m/s^2)")
    }
    val roll = atan2(gravityPhone.y, gravityPhone.z)
    val pitch = atan2(-gravityPhone.x, hypot(gravityPhone.y, gravityPhone.z))
    val leveled = rotationPhoneToVehicleFromGravity(gravityPhone, yawAboutVehicleZRad = 0.0)
    return when (leveled) {
        is RotationFromGravity.Ok ->
            GravityAttitude.Ok(RollPitch(roll, pitch), leveled.rotationPhoneToVehicle)
        is RotationFromGravity.Invalid -> GravityAttitude.Invalid(leveled.reason)
    }
}

sealed class RotationFromGravity {
    data class Ok(val rotationPhoneToVehicle: Mat3) : RotationFromGravity()

    data class Invalid(val reason: String) : RotationFromGravity()
}

/**
 * Rotation phone to vehicle that maps [gravityPhone] (m/s^2) onto vehicle +z.
 *
 * The remaining yaw about vehicle +z is the ANDROID_Y_FORWARD completion (phone -Z projected
 * onto the horizontal plane becomes vehicle +X) plus [yawAboutVehicleZRad] (radians).
 */
fun rotationPhoneToVehicleFromGravity(
    gravityPhone: Vec3,
    yawAboutVehicleZRad: Double = 0.0,
): RotationFromGravity {
    if (!gravityPhone.isFinite()) {
        return RotationFromGravity.Invalid("non-finite gravityPhone (m/s^2)")
    }
    if (!yawAboutVehicleZRad.isFinite()) {
        return RotationFromGravity.Invalid("non-finite yawAboutVehicleZRad (rad)")
    }
    val zPhone = when (val u = gravityPhone.unit()) {
        is UnitVec.Ok -> u.value
        is UnitVec.Degenerate -> return RotationFromGravity.Invalid(u.reason)
    }
    val xPhone = when (val x = horizontalHint(zPhone, ANDROID_Y_FORWARD_PHONE_FORWARD)) {
        is UnitVec.Ok -> x.value
        is UnitVec.Degenerate ->
            when (val fallback = horizontalHint(zPhone, Vec3.PLUS_X)) {
                is UnitVec.Ok -> fallback.value
                is UnitVec.Degenerate -> return RotationFromGravity.Invalid(fallback.reason)
            }
    }
    val yCross = zPhone.cross(xPhone)
    val yPhone = when (val y = yCross.unit()) {
        is UnitVec.Ok -> y.value
        is UnitVec.Degenerate -> return RotationFromGravity.Invalid(y.reason)
    }
    val xOrtho = yPhone.cross(zPhone)
    val xUnit = when (val x = xOrtho.unit()) {
        is UnitVec.Ok -> x.value
        is UnitVec.Degenerate -> return RotationFromGravity.Invalid(x.reason)
    }
    val leveled = Mat3.fromRows(xUnit, yPhone, zPhone)
    val rotation = Mat3.rotationZ(yawAboutVehicleZRad) * leveled
    if (!rotation.isFinite()) {
        return RotationFromGravity.Invalid("non-finite rotation")
    }
    return RotationFromGravity.Ok(rotation)
}

/** Phone-frame direction that ANDROID_Y_FORWARD treats as vehicle forward (into the screen). */
val ANDROID_Y_FORWARD_PHONE_FORWARD: Vec3 = Vec3(0.0, 0.0, -1.0)

data class YawFromMotionConfig(
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

sealed class YawResult {
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
class YawFromMotion(
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
        if (!accelPhoneMps2.isFinite()) {
            return SampleAccept.Ignored("non-finite accelerometer sample (m/s^2)")
        }
        if (!gyroPhoneRadps.isFinite()) {
            return SampleAccept.Ignored("non-finite gyroscope sample (rad/s)")
        }
        if (gnssSpeedDeltaMps != null && !gnssSpeedDeltaMps.isFinite()) {
            return SampleAccept.Ignored("non-finite gnssSpeedDeltaMps (m/s)")
        }
        val gHat = when (val u = gravityPhoneMps2.unit()) {
            is UnitVec.Ok -> u.value
            is UnitVec.Degenerate -> {
                invalidReason = "degenerate gravityPhone (m/s^2): ${u.reason}"
                return SampleAccept.Rejected(invalidReason!!)
            }
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
        val horizUnit = when (val u = horiz.unit()) {
            is UnitVec.Ok -> u.value
            is UnitVec.Degenerate -> return SampleAccept.Ignored(u.reason)
        }
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
        val gHat = when (val u = gravityPhoneMps2.unit()) {
            is UnitVec.Ok -> u.value
            is UnitVec.Degenerate -> return YawResult.Pending(0, config.neededEvents)
        }
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
        val forward = when (val u = sum.unit()) {
            is UnitVec.Ok -> u.value
            is UnitVec.Degenerate -> return YawResult.Pending(chosen.size, config.neededEvents)
        }
        val defaultForward = when (val hint = horizontalHint(gHat, ANDROID_Y_FORWARD_PHONE_FORWARD)) {
            is UnitVec.Ok -> hint.value
            is UnitVec.Degenerate ->
                when (val fallback = horizontalHint(gHat, Vec3.PLUS_X)) {
                    is UnitVec.Ok -> fallback.value
                    is UnitVec.Degenerate -> return YawResult.Pending(chosen.size, config.neededEvents)
                }
        }
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

enum class ProfileQuality {
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
 * @param createdNs profile creation time on the sensor clock
 */
data class MountProfile(
    val rotationPhoneToVehicle: Mat3,
    val gravityPhone: Vec3,
    val gyroBias: Vec3,
    val yawConfidence: Double,
    val createdNs: Long,
    val quality: ProfileQuality,
) {
    init {
        require(rotationPhoneToVehicle.isFinite()) { "rotationPhoneToVehicle must be finite" }
        require(gravityPhone.isFinite()) { "gravityPhone must be finite (m/s^2)" }
        require(gyroBias.isFinite()) { "gyroBias must be finite (rad/s)" }
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
            "rotation_phone_to_vehicle" to rotationPhoneToVehicle.toRowMajor().toList(),
            "gravity_phone_mps2" to ContractMaps.vec3Map(gravityPhone),
            "gyro_bias_radps" to ContractMaps.vec3Map(gyroBias),
            "yaw_confidence" to yawConfidence,
            "created_ns" to createdNs,
            "quality" to quality.name,
        )

    fun toJson(): String = ContractMaps.encodeObject(toContractMap())

    companion object {
        const val SCHEMA_VERSION: String = "mount_profile_1.0.0"
        const val ALIGNED_HIGH_MIN_CONFIDENCE: Double = 0.80

        fun fromContractMap(map: Map<String, Any?>): ProfileParse {
            val schema = ContractMaps.requireString(map, "schema_version")
            if (schema is ContractMaps.Required.Invalid) {
                return ProfileParse.Invalid(schema.reason)
            }
            if ((schema as ContractMaps.Required.Ok).value != SCHEMA_VERSION) {
                return ProfileParse.Invalid("unsupported schema_version '${schema.value}'")
            }
            val rotationList = ContractMaps.requireDoubleList(map, "rotation_phone_to_vehicle", 9)
            if (rotationList is ContractMaps.Required.Invalid) {
                return ProfileParse.Invalid(rotationList.reason)
            }
            val rotation =
                Mat3.fromRowMajor((rotationList as ContractMaps.Required.Ok).value.toDoubleArray())
                    ?: return ProfileParse.Invalid("rotation_phone_to_vehicle is not a finite 3x3")
            val gravity = ContractMaps.requireVec3(map, "gravity_phone_mps2")
            if (gravity is ContractMaps.Required.Invalid) {
                return ProfileParse.Invalid(gravity.reason)
            }
            val bias = ContractMaps.requireVec3(map, "gyro_bias_radps")
            if (bias is ContractMaps.Required.Invalid) {
                return ProfileParse.Invalid(bias.reason)
            }
            val confidence = ContractMaps.requireFiniteDouble(map, "yaw_confidence")
            if (confidence is ContractMaps.Required.Invalid) {
                return ProfileParse.Invalid(confidence.reason)
            }
            val created = ContractMaps.requireLong(map, "created_ns")
            if (created is ContractMaps.Required.Invalid) {
                return ProfileParse.Invalid(created.reason)
            }
            val qualityName = ContractMaps.requireString(map, "quality")
            if (qualityName is ContractMaps.Required.Invalid) {
                return ProfileParse.Invalid(qualityName.reason)
            }
            val qualityRaw = (qualityName as ContractMaps.Required.Ok).value
            val quality =
                try {
                    ProfileQuality.valueOf(qualityRaw)
                } catch (error: IllegalArgumentException) {
                    return ProfileParse.Invalid("unknown quality '$qualityRaw'")
                }
            val conf = (confidence as ContractMaps.Required.Ok).value
            if (conf < 0.0 || conf > 1.0) {
                return ProfileParse.Invalid("yaw_confidence $conf is outside [0, 1]")
            }
            val createdNs = (created as ContractMaps.Required.Ok).value
            if (createdNs < 0L) {
                return ProfileParse.Invalid("created_ns is negative")
            }
            return ProfileParse.Ok(
                MountProfile(
                    rotationPhoneToVehicle = rotation,
                    gravityPhone = (gravity as ContractMaps.Required.Ok).value,
                    gyroBias = (bias as ContractMaps.Required.Ok).value,
                    yawConfidence = conf,
                    createdNs = createdNs,
                    quality = quality,
                ),
            )
        }

        fun fromJson(json: String): ProfileParse {
            return when (val parsed = ContractMaps.parseObject(json)) {
                is ContractMaps.Parse.Ok -> fromContractMap(parsed.value)
                is ContractMaps.Parse.Invalid -> ProfileParse.Invalid(parsed.reason)
            }
        }
    }
}

sealed class ProfileParse {
    data class Ok(val profile: MountProfile) : ProfileParse()

    data class Invalid(val reason: String) : ProfileParse()
}

sealed class ProfileCompose {
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
fun composeMountProfile(
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

data class MisalignmentConfig(
    /** Gravity-direction change that starts the remount hold, radians. Default about 8 degrees. */
    val thresholdRad: Double = 8.0 * PI / 180.0,
    /** Return below this angle (radians) resets the hold. Hysteresis against potholes. */
    val recoverRad: Double = 5.0 * PI / 180.0,
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

sealed class MisalignmentUpdate {
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
class MisalignmentMonitor(
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
        if (!lowPassedAccelPhoneMps2.isFinite()) {
            return MisalignmentUpdate.Rejected("non-finite low-passed accel (m/s^2)")
        }
        if (fired) {
            return MisalignmentUpdate.Observing
        }
        val expectedG = current.gravityPhone.norm()
        val measured = lowPassedAccelPhoneMps2.norm()
        if (abs(measured - expectedG) > config.maxSpecificForceDeviationMps2) {
            return MisalignmentUpdate.Observing
        }
        val angle =
            when (val a = angleBetween(lowPassedAccelPhoneMps2, current.gravityPhone)) {
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
 * Hand-rolled contract maps, same style as the JSON schemas under `contracts/`:
 * snake_case keys, explicit missing-field errors, no extra dependencies.
 */
object ContractMaps {
    sealed class Required<out T> {
        data class Ok<T>(val value: T) : Required<T>()

        data class Invalid(val reason: String) : Required<Nothing>()
    }

    sealed class Parse {
        data class Ok(val value: Map<String, Any?>) : Parse()

        data class Invalid(val reason: String) : Parse()
    }

    fun vec3Map(v: Vec3): Map<String, Double> = linkedMapOf("x" to v.x, "y" to v.y, "z" to v.z)

    fun requireString(map: Map<String, Any?>, key: String): Required<String> {
        val value = map[key] ?: return Required.Invalid("missing $key")
        return if (value is String && value.isNotEmpty()) {
            Required.Ok(value)
        } else {
            Required.Invalid("$key is not a non-empty string")
        }
    }

    fun requireFiniteDouble(map: Map<String, Any?>, key: String): Required<Double> {
        val value = map[key] ?: return Required.Invalid("missing $key")
        val number = asFiniteDouble(value) ?: return Required.Invalid("$key is not a finite number")
        return Required.Ok(number)
    }

    fun requireLong(map: Map<String, Any?>, key: String): Required<Long> {
        val value = map[key] ?: return Required.Invalid("missing $key")
        val number = asLong(value) ?: return Required.Invalid("$key is not an integer")
        return Required.Ok(number)
    }

    fun requireVec3(map: Map<String, Any?>, key: String): Required<Vec3> {
        val value = map[key] ?: return Required.Invalid("missing $key")
        if (value !is Map<*, *>) {
            return Required.Invalid("$key is not an object")
        }
        val typed = LinkedHashMap<String, Any?>()
        for ((k, v) in value) {
            if (k !is String) {
                return Required.Invalid("$key has a non-string key")
            }
            typed[k] = v
        }
        val x = requireFiniteDouble(typed, "x")
        val y = requireFiniteDouble(typed, "y")
        val z = requireFiniteDouble(typed, "z")
        if (x is Required.Invalid) {
            return Required.Invalid("$key.${x.reason}")
        }
        if (y is Required.Invalid) {
            return Required.Invalid("$key.${y.reason}")
        }
        if (z is Required.Invalid) {
            return Required.Invalid("$key.${z.reason}")
        }
        return Required.Ok(
            Vec3(
                (x as Required.Ok).value,
                (y as Required.Ok).value,
                (z as Required.Ok).value,
            ),
        )
    }

    fun requireDoubleList(
        map: Map<String, Any?>,
        key: String,
        expectedSize: Int,
    ): Required<List<Double>> {
        val value = map[key] ?: return Required.Invalid("missing $key")
        if (value !is List<*>) {
            return Required.Invalid("$key is not an array")
        }
        if (value.size != expectedSize) {
            return Required.Invalid("$key size ${value.size} != $expectedSize")
        }
        val out = ArrayList<Double>(expectedSize)
        for ((index, item) in value.withIndex()) {
            val number = asFiniteDouble(item) ?: return Required.Invalid("$key[$index] is not a finite number")
            out.add(number)
        }
        return Required.Ok(out)
    }

    fun encodeObject(map: Map<String, Any?>): String {
        val out = StringBuilder()
        writeValue(out, map)
        return out.toString()
    }

    fun parseObject(json: String): Parse {
        return try {
            val parser = JsonParser(json)
            val value = parser.parseValue()
            parser.skipWs()
            if (!parser.exhausted()) {
                return Parse.Invalid("trailing content after JSON object")
            }
            if (value !is Map<*, *>) {
                return Parse.Invalid("top-level JSON is not an object")
            }
            val typed = LinkedHashMap<String, Any?>()
            for ((k, v) in value) {
                if (k !is String) {
                    return Parse.Invalid("object key is not a string")
                }
                typed[k] = v
            }
            Parse.Ok(typed)
        } catch (error: IllegalArgumentException) {
            Parse.Invalid(error.message ?: "invalid JSON")
        }
    }

    private fun writeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value)
            is Long -> out.append(value)
            is Int -> out.append(value)
            is Double -> {
                require(value.isFinite()) { "refusing to encode non-finite double" }
                out.append(value)
            }
            is Float -> {
                require(value.isFinite()) { "refusing to encode non-finite float" }
                out.append(value)
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) {
                        out.append(',')
                    }
                    writeValue(out, item)
                }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    require(k is String) { "contract map keys must be strings" }
                    if (!first) {
                        out.append(',')
                    }
                    first = false
                    writeString(out, k)
                    out.append(':')
                    writeValue(out, v)
                }
                out.append('}')
            }
            else -> throw IllegalArgumentException("unsupported JSON value ${value::class.simpleName}")
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        out.append('"')
    }

    private fun asFiniteDouble(value: Any?): Double? =
        when (value) {
            is Double -> value.takeIf { it.isFinite() }
            is Float -> value.toDouble().takeIf { it.isFinite() }
            is Long -> value.toDouble()
            is Int -> value.toDouble()
            else -> null
        }

    private fun asLong(value: Any?): Long? =
        when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Double ->
                if (value.isFinite() && value == kotlin.math.floor(value) &&
                    value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()
                ) {
                    value.toLong()
                } else {
                    null
                }
            else -> null
        }

    private class JsonParser(private val text: String) {
        private var i = 0

        fun exhausted(): Boolean = i >= text.length

        fun skipWs() {
            while (i < text.length && text[i].isWhitespace()) {
                i += 1
            }
        }

        fun parseValue(): Any? {
            skipWs()
            if (i >= text.length) {
                throw IllegalArgumentException("unexpected end of JSON")
            }
            return when (text[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("unexpected '${text[i]}' at $i")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek('}')) {
                i += 1
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                out[key] = parseValue()
                skipWs()
                if (peek('}')) {
                    i += 1
                    return out
                }
                expect(',')
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek(']')) {
                i += 1
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (peek(']')) {
                    i += 1
                    return out
                }
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (i < text.length) {
                val ch = text[i]
                i += 1
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= text.length) {
                            throw IllegalArgumentException("unterminated escape")
                        }
                        val esc = text[i]
                        i += 1
                        out.append(
                            when (esc) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                else -> throw IllegalArgumentException("unsupported escape '$esc'")
                            },
                        )
                    }
                    else -> out.append(ch)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun parseNumber(): Any {
            val start = i
            if (peek('-')) {
                i += 1
            }
            if (i >= text.length) {
                throw IllegalArgumentException("invalid number")
            }
            if (text[i] == '0') {
                i += 1
            } else if (text[i] in '1'..'9') {
                while (i < text.length && text[i] in '0'..'9') {
                    i += 1
                }
            } else {
                throw IllegalArgumentException("invalid number")
            }
            var fractional = false
            if (peek('.')) {
                fractional = true
                i += 1
                val fracStart = i
                while (i < text.length && text[i] in '0'..'9') {
                    i += 1
                }
                if (i == fracStart) {
                    throw IllegalArgumentException("invalid number fraction")
                }
            }
            if (peek('e') || peek('E')) {
                fractional = true
                i += 1
                if (peek('+') || peek('-')) {
                    i += 1
                }
                val expStart = i
                while (i < text.length && text[i] in '0'..'9') {
                    i += 1
                }
                if (i == expStart) {
                    throw IllegalArgumentException("invalid number exponent")
                }
            }
            val raw = text.substring(start, i)
            if (!fractional) {
                return raw.toLong()
            }
            val d = raw.toDouble()
            if (!d.isFinite()) {
                throw IllegalArgumentException("non-finite JSON number")
            }
            return d
        }

        private fun parseLiteral(match: String, value: Any?): Any? {
            if (text.regionMatches(i, match, 0, match.length)) {
                i += match.length
                return value
            }
            throw IllegalArgumentException("expected $match")
        }

        private fun expect(ch: Char) {
            skipWs()
            if (i >= text.length || text[i] != ch) {
                throw IllegalArgumentException("expected '$ch' at $i")
            }
            i += 1
        }

        private fun peek(ch: Char): Boolean = i < text.length && text[i] == ch
    }
}

private fun horizontalHint(gravityUnit: Vec3, hint: Vec3): UnitVec {
    val projected = hint - gravityUnit * hint.dot(gravityUnit)
    return projected.unit()
}

internal fun checkMonotonic(timestampNs: Long, lastNs: Long?): String? {
    if (timestampNs < 0L) {
        return "timestampNs is negative"
    }
    if (lastNs != null && timestampNs <= lastNs) {
        return "non-monotonic timestamp $timestampNs after $lastNs"
    }
    return null
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

