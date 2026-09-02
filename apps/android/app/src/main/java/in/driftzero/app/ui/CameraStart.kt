package `in`.driftzero.app.ui

/**
 * First camera: live GPS, else a stored last fix, else a world overview.
 * Never pins a demo city when those are missing.
 */
data class CameraStart(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val zoom: Double,
) {
    val isStreetLevel: Boolean get() = zoom >= StreetMapConfig.STREET_ZOOM - 0.5
}

object CameraStartResolver {
    fun resolve(liveGps: TravelLatLng?, lastFix: TravelLatLng?): CameraStart {
        val point = validOrNull(liveGps) ?: validOrNull(lastFix)
        return if (point != null) {
            CameraStart(point.latitudeDeg, point.longitudeDeg, StreetMapConfig.STREET_ZOOM)
        } else {
            CameraStart(
                StreetMapConfig.WORLD_LAT_DEG,
                StreetMapConfig.WORLD_LON_DEG,
                StreetMapConfig.WORLD_ZOOM,
            )
        }
    }

    fun validOrNull(point: TravelLatLng?): TravelLatLng? {
        if (point == null) {
            return null
        }
        return validOrNull(point.latitudeDeg, point.longitudeDeg)
    }

    fun validOrNull(latitudeDeg: Double?, longitudeDeg: Double?): TravelLatLng? {
        if (latitudeDeg == null || longitudeDeg == null) {
            return null
        }
        if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) {
            return null
        }
        if (latitudeDeg !in -90.0..90.0 || longitudeDeg !in -180.0..180.0) {
            return null
        }
        return TravelLatLng(latitudeDeg, longitudeDeg)
    }
}

internal fun searchBiasLatLon(
    fixLat: Double?,
    fixLon: Double?,
    cameraLat: Double?,
    cameraLon: Double?,
    cameraZoom: Double? = null,
): Pair<Double, Double>? {
    val fix = CameraStartResolver.validOrNull(fixLat, fixLon)
    if (fix != null) {
        return fix.latitudeDeg to fix.longitudeDeg
    }
    val camera = CameraStartResolver.validOrNull(cameraLat, cameraLon) ?: return null
    if (cameraZoom != null && cameraZoom < StreetMapConfig.SEARCH_CAMERA_MIN_ZOOM) {
        return null
    }
    return camera.latitudeDeg to camera.longitudeDeg
}

internal fun routeOrigin(
    poseLat: Double?,
    poseLon: Double?,
    mapOrigin: TravelLatLng?,
): TravelLatLng? {
    return CameraStartResolver.validOrNull(poseLat, poseLon) ?: mapOrigin
}

internal fun searchBiasKey(near: Pair<Double, Double>?): String {
    if (near == null) {
        return ""
    }
    val latCenti = kotlin.math.floor(near.first * 100.0).toInt()
    val lonCenti = kotlin.math.floor(near.second * 100.0).toInt()
    return "$latCenti,$lonCenti"
}
