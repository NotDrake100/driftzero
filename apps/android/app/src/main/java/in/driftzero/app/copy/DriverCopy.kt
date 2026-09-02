package `in`.driftzero.app.copy

import kotlin.math.roundToInt

/**
 * Driver-facing copy. Keep this free of contest IDs, firmware labels,
 * and estimator enum names such as LOW_CONFIDENCE.
 */
object DriverCopy {
    const val WORDMARK = "DriftZero"
    const val SEARCH_PLACEHOLDER = "Where to?"
    const val START = "Start"
    const val GPS_ON = "GPS on"
    const val GPS_ESTIMATING = "No GPS, estimating"

    /**
     * @param hasTrustedFix true only when a recent, usable GNSS fix exists
     */
    fun gpsHealthLabel(hasTrustedFix: Boolean): String =
        if (hasTrustedFix) GPS_ON else GPS_ESTIMATING

    /**
     * @param speedMps speed in metres/second, or null when unknown (never treat missing as 0)
     * @return kilometres/hour label, or null when speed is unknown/invalid
     */
    fun speedLabelOrNull(speedMps: Double?): String? {
        if (speedMps == null) return null
        if (!speedMps.isFinite() || speedMps < 0.0) return null
        val kilometresPerHour = speedMps * 3.6
        return "${kilometresPerHour.roundToInt()} km/h"
    }
}
