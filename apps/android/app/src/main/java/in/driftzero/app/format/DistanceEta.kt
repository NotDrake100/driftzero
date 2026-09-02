package `in`.driftzero.app.format

import kotlin.math.roundToInt

/**
 * Plain-language distance and travel time for a driver.
 * Units: metres in, seconds in. Never emits placeholder dashes.
 */
object DistanceEta {
    fun formatDistance(distanceMeters: Double): String {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "distanceMeters must be a finite non-negative number"
        }
        return when {
            distanceMeters < 1000.0 -> "${distanceMeters.roundToInt()} m"
            distanceMeters < 10_000.0 -> {
                val tenths = (distanceMeters / 100.0).roundToInt()
                val km = tenths / 10.0
                if (tenths % 10 == 0) "${tenths / 10} km" else "$km km"
            }
            else -> "${(distanceMeters / 1000.0).roundToInt()} km"
        }
    }

    fun formatDuration(durationSeconds: Double): String {
        require(durationSeconds.isFinite() && durationSeconds >= 0.0) {
            "durationSeconds must be a finite non-negative number"
        }
        val totalMinutes = (durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0 -> "$totalMinutes min"
            minutes == 0 -> if (hours == 1) "1 hr" else "$hours hr"
            else -> if (hours == 1) "1 hr $minutes min" else "$hours hr $minutes min"
        }
    }

    /**
     * GPS speed for the chrome chip.
     * @return null when there is no usable speed, so the UI can hide the chip.
     */
    fun formatSpeedKmh(speedMetersPerSecond: Double?): String? {
        if (speedMetersPerSecond == null || !speedMetersPerSecond.isFinite() || speedMetersPerSecond < 0.0) {
            return null
        }
        return "${(speedMetersPerSecond * 3.6).roundToInt()} km/h"
    }
}
