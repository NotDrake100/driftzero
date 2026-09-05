package `in`.driftzero.core

import kotlin.math.abs

/**
 * Phone placement from gravity direction and vibration energy. Does not
 * assume dash / ANDROID_Y_FORWARD as the only product path. Yaw-from-motion
 * still resolves vehicle +X without a dash assumption.
 *
 * Gravity is phone-frame specific force, m/s^2. Vibration energy is RMS of
 * |a - lowpass(a)|, m/s^2. Remount (gravity-direction change) is HANDHELD.
 */
enum class PhonePlacement {
    UNKNOWN,
    DASH_FLAT,
    CUP_OR_VENT,
    PASSENGER_SEAT,
    HANDHELD,
}

internal data class PlacementObservation(
    val gravityPhone: Vec3?,
    val vibrationEnergy: Double?,
    val remount: Boolean = false,
    val still: Boolean = false,
)

object MountPlacement {
    const val RECALIBRATE_COPY: String = "Phone orientation changed. Recalibrating vehicle frame."

    /** Low dash vibration after still, m/s^2 RMS of high-pass accel. */
    const val DASH_VIB_MAX: Double = 0.55

    /** Seat cushion is softer than a dash. */
    const val SEAT_VIB_MIN: Double = 0.90

    /** In-vehicle vibration floor for a cup or vent. */
    const val VEHICLE_VIB_MIN: Double = 0.35

    internal fun classify(obs: PlacementObservation): PhonePlacement {
        if (obs.remount) {
            return PhonePlacement.HANDHELD
        }
        val g = obs.gravityPhone ?: return PhonePlacement.UNKNOWN
        val norm = g.norm()
        if (norm < 1.0) {
            return PhonePlacement.UNKNOWN
        }
        val ax = abs(g.x) / norm
        val ay = abs(g.y) / norm
        val az = abs(g.z) / norm
        val vib = obs.vibrationEnergy
        val portrait = ay > ax && ay > az * 0.70
        val flat = az >= 0.70 && az >= ay && az >= ax
        return when {
            portrait && vib != null && vib >= VEHICLE_VIB_MIN -> PhonePlacement.CUP_OR_VENT
            portrait && vib == null -> PhonePlacement.CUP_OR_VENT
            flat && vib != null && vib >= SEAT_VIB_MIN -> PhonePlacement.PASSENGER_SEAT
            flat && (vib == null || vib <= DASH_VIB_MAX) && obs.still -> PhonePlacement.DASH_FLAT
            flat && vib != null && vib <= DASH_VIB_MAX -> PhonePlacement.DASH_FLAT
            flat && vib != null && vib > DASH_VIB_MAX -> PhonePlacement.PASSENGER_SEAT
            !flat && !portrait && vib != null && vib >= SEAT_VIB_MIN -> PhonePlacement.PASSENGER_SEAT
            else -> PhonePlacement.UNKNOWN
        }
    }

    fun driverLine(placement: PhonePlacement): String? = when (placement) {
        PhonePlacement.UNKNOWN -> null
        PhonePlacement.DASH_FLAT -> "dash"
        PhonePlacement.CUP_OR_VENT -> "cup or vent"
        PhonePlacement.PASSENGER_SEAT -> "passenger seat"
        PhonePlacement.HANDHELD -> "handheld"
    }
}
