package `in`.driftzero.app.ui

import `in`.driftzero.core.GuidanceRoute
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class TravelSearchClient(
    private val online: () -> Boolean = { true },
    private val localRoute: ((from: TravelLatLng, to: TravelLatLng) -> GuidanceRoute?)? = null,
    private val fetch: (url: String) -> String = ::httpGet,
) {
    fun search(query: String, nearLat: Double?, nearLon: Double?): PlaceQuery {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            return PlaceQuery.Empty
        }
        if (!online()) {
            return PlaceQuery.Network
        }
        val photonResult = fetchPlaces(photonUrl(trimmed, nearLat, nearLon), photon = true)
        if (photonResult is PlaceQuery.Hits) {
            return photonResult
        }
        val nominatimResult = fetchPlaces(nominatimUrl(trimmed, nearLat, nearLon), photon = false)
        if (nominatimResult is PlaceQuery.Hits) {
            return nominatimResult
        }
        if (photonResult is PlaceQuery.Network && nominatimResult is PlaceQuery.Network) {
            return PlaceQuery.Network
        }
        if (photonResult is PlaceQuery.Parse && nominatimResult is PlaceQuery.Parse) {
            return PlaceQuery.Parse
        }
        return PlaceQuery.Empty
    }

    fun reverse(latitudeDeg: Double, longitudeDeg: Double): PlaceQuery {
        if (latitudeDeg !in -90.0..90.0 || longitudeDeg !in -180.0..180.0) {
            return PlaceQuery.Empty
        }
        if (!online()) {
            return PlaceQuery.Network
        }
        val photonResult = fetchPlaces(photonReverseUrl(latitudeDeg, longitudeDeg), photon = true)
        if (photonResult is PlaceQuery.Hits) {
            return photonResult
        }
        val nominatimResult = fetchReverseNominatim(nominatimReverseUrl(latitudeDeg, longitudeDeg))
        if (nominatimResult is PlaceQuery.Hits) {
            return nominatimResult
        }
        if (photonResult is PlaceQuery.Network && nominatimResult is PlaceQuery.Network) {
            return PlaceQuery.Network
        }
        return PlaceQuery.Empty
    }

    fun route(from: TravelLatLng, to: TravelLatLng): RouteQuery {
        if (localRoute != null) {
            localRoute.invoke(from, to)?.let { built ->
                return RouteQuery.Ok(built.toTravelRoute(), fromLocal = true)
            }
            if (!online()) {
                return RouteQuery.Failed
            }
        } else if (!online()) {
            return RouteQuery.Network
        }
        val url = osrmUrl(from, to)
        val body = try {
            fetch(url)
        } catch (_: Exception) {
            return RouteQuery.Network
        }
        val parsed = parseOsrmRoute(body) ?: return RouteQuery.Failed
        return RouteQuery.Ok(parsed, fromLocal = false)
    }

    private fun fetchPlaces(url: String, photon: Boolean): PlaceQuery {
        val body = try {
            fetch(url)
        } catch (_: Exception) {
            return PlaceQuery.Network
        }
        val places = if (photon) {
            parsePhotonPlaces(body)
        } else {
            parseNominatimPlaces(body)
        } ?: return PlaceQuery.Parse
        return if (places.isEmpty()) PlaceQuery.Empty else PlaceQuery.Hits(places)
    }

    private fun fetchReverseNominatim(url: String): PlaceQuery {
        val body = try {
            fetch(url)
        } catch (_: Exception) {
            return PlaceQuery.Network
        }
        val place = parseNominatimReverse(body) ?: return PlaceQuery.Parse
        return PlaceQuery.Hits(listOf(place))
    }
}

internal fun photonUrl(query: String, nearLat: Double?, nearLon: Double?): String {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val base = "${StreetMapConfig.PHOTON_API}?q=$encoded&limit=5&lang=en"
    val bias = searchBiasLatLon(nearLat, nearLon, null, null) ?: return base
    return "$base&lat=${bias.first}&lon=${bias.second}" +
        "&zoom=${StreetMapConfig.SEARCH_BIAS_ZOOM}" +
        "&location_bias_scale=${StreetMapConfig.SEARCH_BIAS_SCALE}"
}

internal fun nominatimUrl(query: String, nearLat: Double?, nearLon: Double?): String {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val base = "${StreetMapConfig.NOMINATIM_SEARCH}?q=$encoded&format=jsonv2&limit=5"
    val bias = searchBiasLatLon(nearLat, nearLon, null, null) ?: return base
    val span = StreetMapConfig.SEARCH_BIAS_SPAN_DEG
    val south = (bias.first - span).coerceIn(-90.0, 90.0)
    val north = (bias.first + span).coerceIn(-90.0, 90.0)
    val west = (bias.second - span).coerceIn(-180.0, 180.0)
    val east = (bias.second + span).coerceIn(-180.0, 180.0)
    return "$base&viewbox=$west,$north,$east,$south&bounded=0"
}

internal fun photonReverseUrl(latitudeDeg: Double, longitudeDeg: Double): String {
    return "${StreetMapConfig.PHOTON_REVERSE}?lat=$latitudeDeg&lon=$longitudeDeg"
}

internal fun nominatimReverseUrl(latitudeDeg: Double, longitudeDeg: Double): String {
    return "${StreetMapConfig.NOMINATIM_REVERSE}?lat=$latitudeDeg&lon=$longitudeDeg&format=jsonv2"
}

internal fun osrmUrl(from: TravelLatLng, to: TravelLatLng): String {
    return StreetMapConfig.OSRM_ROUTE +
        "${from.longitudeDeg},${from.latitudeDeg};${to.longitudeDeg},${to.latitudeDeg}" +
        "?overview=full&geometries=geojson&steps=true"
}

internal fun lineStringGeoJson(points: List<TravelLatLng>): String {
    val coords = points.joinToString(",") { "[${it.longitudeDeg},${it.latitudeDeg}]" }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}]}"""
}

internal fun pointGeoJson(point: TravelLatLng): String {
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[${point.longitudeDeg},${point.latitudeDeg}]},"properties":{}}]}"""
}

/** One closed ring. Callers pass a ring whose last vertex repeats the first. */
internal fun polygonGeoJson(ring: List<TravelLatLng>): String {
    if (ring.size < 4) {
        return EMPTY_FEATURE_COLLECTION
    }
    val coords = ring.joinToString(",") { "[${it.longitudeDeg},${it.latitudeDeg}]" }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coords]]},"properties":{}}]}"""
}

internal const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

private fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8_000
    conn.readTimeout = 8_000
    conn.setRequestProperty("User-Agent", StreetMapConfig.USER_AGENT)
    conn.setRequestProperty("Accept", "application/json")
    conn.requestMethod = "GET"
    try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.readText().orEmpty()
        if (code !in 200..299) {
            throw TravelHttpException(code)
        }
        return body
    } finally {
        conn.disconnect()
    }
}

internal class TravelHttpException(val code: Int) : RuntimeException("http $code")
