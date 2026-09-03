package `in`.driftzero.app.pose

import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.VectorFrame

/**
 * Last magnetometer copy from [PhoneImuSource].
 *
 * Units: microtesla (uT), same as Android `TYPE_MAGNETIC_FIELD` and
 * [in.driftzero.core.SensorKind.MAGNETOMETER]. Frame is
 * [VectorFrame.ANDROID_DEVICE] (x right, y toward the top of the screen,
 * z out of the screen). Fusion does not use this sample.
 */
data class MagnetometerSample(
    val timestamp: Nanoseconds,
    val xUt: Double,
    val yUt: Double,
    val zUt: Double,
    val frame: VectorFrame = VectorFrame.ANDROID_DEVICE,
)
