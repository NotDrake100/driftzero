package `in`.driftzero.app.search

import `in`.driftzero.app.geo.GeoPoint
import org.json.JSONObject

object PhotonParser {
    fun parse(json: String): List<Place> {
        val root = JSONObject(json)
        val features = root.optJSONArray("features") ?: return emptyList()
        val places = ArrayList<Place>(features.length())
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val name = firstNonBlank(
                properties.optString("name"),
                properties.optString("street"),
                properties.optString("city"),
            ) ?: continue
            val city = blankToNull(properties.optString("city"))
            val countryCode = blankToNull(properties.optString("countrycode"))
                ?: blankToNull(properties.optString("country"))
            val subtitle = listOf(
                blankToNull(properties.optString("street")),
                blankToNull(properties.optString("locality")),
                city,
                blankToNull(properties.optString("state")),
            ).filterNotNull().distinct().joinToString(", ")
            val osmType = properties.optString("osm_type")
            val osmId = properties.opt("osm_id")?.toString().orEmpty()
            places += Place(
                id = "photon:$osmType:$osmId:$i",
                name = name,
                subtitle = subtitle,
                point = GeoPoint(latitudeDeg = lat, longitudeDeg = lon),
                countryCode = countryCode,
                city = city,
                source = "photon",
            )
        }
        return places
    }

    private fun firstNonBlank(vararg values: String): String? =
        values.firstOrNull { it.isNotBlank() && it != "null" }

    private fun blankToNull(value: String): String? =
        value.takeIf { it.isNotBlank() && it != "null" }
}
