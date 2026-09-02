package `in`.driftzero.app.ui

import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * When the travel chrome shows speed and distance on an active route.
 * Missing speed stays hidden. Never formats a placeholder dash readout.
 */
object TravelHud {
    const val SHOW_SPEED_MPS = 1.4
    const val HIDE_SPEED_MPS = 0.8

    fun gpsOn(permissionGranted: Boolean, providerEnabled: Boolean, hasFix: Boolean): Boolean {
        return permissionGranted && providerEnabled && hasFix
    }

    fun shouldShowSpeed(
        speedMps: Double?,
        routeActive: Boolean,
        currentlyShown: Boolean,
    ): Boolean {
        if (!routeActive) {
            return false
        }
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

    fun formatSpeedKmh(speedMps: Double): String {
        val kmh = speedMps * 3.6
        if (kmh < 0.5) {
            return "0 km/h"
        }
        if (kmh < 10.0) {
            return String.format(Locale.US, "%.1f km/h", kmh)
        }
        return "${kmh.roundToInt()} km/h"
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
}

data class TravelFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val accuracyM: Double?,
    val gpsProviderOn: Boolean,
)

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
)

internal fun NavigationState.toTravelFix(providerEnabled: Boolean): TravelFix {
    val coasting = mode == NavigationMode.DEAD_RECKONING
    return TravelFix(
        latitudeDeg = position.latitude.value,
        longitudeDeg = position.longitude.value,
        speedMps = motion.speed.value,
        bearingDeg = motion.heading.value * 180.0 / Math.PI,
        accuracyM = uncertainty.horizontal95.value,
        gpsProviderOn = providerEnabled && !coasting,
    )
}

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
