package `in`.driftzero.app.ui

import `in`.driftzero.core.Wgs84
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Metre-true polygons for the halo and heading cone, and the zoom bands the
 * camera follows. All angles in radians, clockwise from north.
 */
object MapGeometry {
    const val HALO_VERTICES = 48
    const val HALO_MIN_RADIUS_M = 3.0
    const val CONE_LENGTH_DP = 36.0
    const val CONE_MIN_HALF_ANGLE_RAD = 5.0 * PI / 180.0
    const val CONE_MAX_HALF_ANGLE_RAD = 60.0 * PI / 180.0
    const val CONE_HIDE_SPEED_MPS = 0.5

    /** Earth circumference over the 512 px tile MapLibre uses at zoom 0. */
    private const val EQUATOR_M_PER_DP_Z0 = 40075016.686 / 512.0

    /**
     * Ground metres per density-independent pixel at [zoom]. MapLibre zoom is
     * the 512 px tile convention, so zoom 15 here is the 256 px tile zoom 16.
     */
    fun metresPerDp(latitudeDeg: Double, zoom: Double): Double =
        EQUATOR_M_PER_DP_Z0 * cos(latitudeDeg * PI / 180.0) / 2.0.pow(zoom)

    fun circle(
        latitudeDeg: Double,
        longitudeDeg: Double,
        radiusM: Double,
        vertices: Int = HALO_VERTICES,
    ): List<TravelLatLng> {
        val r = radiusM.coerceAtLeast(HALO_MIN_RADIUS_M)
        return List(vertices + 1) { i ->
            val a = 2.0 * PI * (i % vertices) / vertices
            val (lat, lon) = Wgs84.offsetMetres(latitudeDeg, longitudeDeg, northM = r * cos(a), eastM = r * sin(a))
            TravelLatLng(lat, lon)
        }
    }

    /** Closed wedge: centre, arc from heading minus half angle to plus, back to centre. */
    fun wedge(
        latitudeDeg: Double,
        longitudeDeg: Double,
        headingRad: Double,
        halfAngleRad: Double,
        lengthM: Double,
        arcSteps: Int = 8,
    ): List<TravelLatLng> {
        val half = halfAngleRad.coerceIn(CONE_MIN_HALF_ANGLE_RAD, CONE_MAX_HALF_ANGLE_RAD)
        val centre = TravelLatLng(latitudeDeg, longitudeDeg)
        val points = ArrayList<TravelLatLng>(arcSteps + 3)
        points += centre
        for (i in 0..arcSteps) {
            val a = headingRad - half + (2.0 * half * i / arcSteps)
            val (lat, lon) = Wgs84.offsetMetres(latitudeDeg, longitudeDeg, northM = lengthM * cos(a), eastM = lengthM * sin(a))
            points += TravelLatLng(lat, lon)
        }
        points += centre
        return points
    }

    fun coneVisible(speedMps: Double, heading95Rad: Double): Boolean =
        !(speedMps < CONE_HIDE_SPEED_MPS && heading95Rad > CONE_MAX_HALF_ANGLE_RAD)

    /** Follow-camera zoom by speed. Only changes when the band changes so it never hunts. */
    fun zoomForSpeed(speedMps: Double, current: Double?): Double {
        val band = when {
            speedMps < 15.0 -> StreetMapConfig.STREET_ZOOM
            else -> 15.0
        }
        if (current == null) {
            return band
        }
        // Hysteresis: hold the current band until speed leaves it by 1 m/s.
        val holds = when (current) {
            StreetMapConfig.STREET_ZOOM -> speedMps < 16.0
            15.0 -> speedMps > 14.0
            else -> false
        }
        return if (holds) current else band
    }
}
