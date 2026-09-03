package `in`.driftzero.app.ui

import `in`.driftzero.app.settings.SpeedUnit
import `in`.driftzero.core.RouteStep
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every number the driver reads is formatted here. Nouns and numbers, no
 * placeholder dashes. Missing speed stays hidden rather than printing 0.
 */
object InstrumentFormat {
    const val SHOW_SPEED_MPS = 1.4
    const val HIDE_SPEED_MPS = 0.8
    private const val MPS_TO_KMH = 3.6
    private const val MPS_TO_MPH = 2.2369362920544

    fun shouldShowSpeed(speedMps: Double?, currentlyShown: Boolean): Boolean {
        if (speedMps == null || !speedMps.isFinite() || speedMps < 0.0) {
            return false
        }
        if (speedMps >= SHOW_SPEED_MPS) {
            return true
        }
        if (speedMps <= HIDE_SPEED_MPS) {
            return false
        }
        return currentlyShown
    }

    fun formatSpeed(speedMps: Double, unit: SpeedUnit): String {
        val (value, suffix) = when (unit) {
            SpeedUnit.KMH -> speedMps * MPS_TO_KMH to "km/h"
            SpeedUnit.MPH -> speedMps * MPS_TO_MPH to "mph"
        }
        if (value < 0.5) {
            return "0 $suffix"
        }
        if (value < 10.0) {
            return String.format(Locale.US, "%.1f %s", value, suffix)
        }
        return "${value.roundToInt()} $suffix"
    }

    fun formatDistance(metres: Double): String {
        if (metres < 1000.0) {
            return "${metres.roundToInt()} m"
        }
        return String.format(Locale.US, "%.1f km", metres / 1000.0)
    }

    fun formatEta(seconds: Double): String {
        val totalMin = maxOf(1, (seconds / 60.0).roundToInt())
        if (totalMin < 60) {
            return "$totalMin min"
        }
        val hours = totalMin / 60
        val minutes = totalMin % 60
        return if (minutes == 0) "$hours hr" else "$hours hr $minutes min"
    }

    /** Seconds with one decimal under 10 s, whole seconds under 60 s, then `m min s s`. */
    fun formatSeconds(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0)
        if (s < 10.0) {
            return String.format(Locale.US, "%.1f s", s)
        }
        if (s < 60.0) {
            return "${s.roundToInt()} s"
        }
        val whole = s.roundToInt()
        return "${whole / 60} min ${whole % 60} s"
    }

    /** Whole metres under 1 km, then one decimal km. Never below 1 m. */
    fun formatRadius(metres: Double): String {
        val m = metres.coerceAtLeast(1.0)
        return if (m < 1000.0) "${m.roundToInt()} m" else String.format(Locale.US, "%.1f km", m / 1000.0)
    }

    fun formatHeadingDeg(headingRad: Double): String {
        val deg = ((Math.toDegrees(headingRad) % 360.0) + 360.0) % 360.0
        return "${deg.roundToInt() % 360} deg"
    }

    fun formatAngleDeg(rad: Double): String = "${Math.toDegrees(abs(rad)).roundToInt()} deg"

    fun formatBytes(bytes: Long): String {
        if (bytes < 1_000_000L) {
            return "${(bytes / 1000.0).roundToInt()} kB"
        }
        if (bytes < 1_000_000_000L) {
            return "${(bytes / 1_000_000.0).roundToInt()} MB"
        }
        return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    }

    fun formatConfidence(value: Double): String = String.format(Locale.US, "%.2f", value.coerceIn(0.0, 1.0))

    /** Unit interval to a whole percent. `0.92` reads `92%`. */
    fun formatPercent(fraction: Double): String =
        "${(fraction.coerceIn(0.0, 1.0) * 100.0).roundToInt()}%"

    fun formatClock(wallMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = wallMs.coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
    }

    fun formatTripKm(metres: Double): String {
        val km = (metres / 1000.0).coerceAtLeast(0.0)
        return String.format(Locale.US, "%.1f", km)
    }
}

data class TravelPlace(
    val name: String,
    val detail: String,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
)

data class TravelRoute(
    val points: List<TravelLatLng>,
    val distanceM: Double,
    val durationS: Double,
    val steps: List<RouteStep> = emptyList(),
)

data class TravelLatLng(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
)

sealed class PlaceQuery {
    data class Hits(val places: List<TravelPlace>) : PlaceQuery()
    data object Empty : PlaceQuery()
    data object Network : PlaceQuery()
    data object Parse : PlaceQuery()
}

sealed class RouteQuery {
    data class Ok(val route: TravelRoute) : RouteQuery()
    data object Failed : RouteQuery()
    data object Network : RouteQuery()
}
