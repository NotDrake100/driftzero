package `in`.driftzero.app.search

import `in`.driftzero.app.geo.GeoPoint
import org.json.JSONArray
import org.json.JSONObject

object NominatimParser {
    fun parse(json: String): List<Place> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = JSONArray(trimmed)
        val places = ArrayList<Place>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val lat = item.optDouble("lat", Double.NaN)
            val lon = item.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue
            val display = item.optString("display_name")
            val name = firstNonBlank(item.optString("name"), display.substringBefore(',')) ?: continue
            val address = item.optJSONObject("address")
            val city = addressCity(address)
            val countryCode = blankToNull(address?.optString("country_code"))?.uppercase()
                ?: blankToNull(item.optString("country_code"))?.uppercase()
            val subtitle = display
                .split(',')
                .drop(1)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .joinToString(", ")
            places += Place(
                id = "nominatim:${item.opt("place_id") ?: i}",
                name = name,
                subtitle = subtitle,
                point = GeoPoint(latitudeDeg = lat, longitudeDeg = lon),
                countryCode = countryCode,
                city = city,
                source = "nominatim",
            )
        }
        return places
    }

    private fun addressCity(address: JSONObject?): String? {
        if (address == null) return null
        return firstNonBlank(
            address.optString("city"),
            address.optString("town"),
            address.optString("village"),
            address.optString("suburb"),
        )
    }

    private fun firstNonBlank(vararg values: String): String? =
        values.firstOrNull { it.isNotBlank() && it != "null" }

    private fun blankToNull(value: String?): String? =
        value?.takeIf { it.isNotBlank() && it != "null" }
}
