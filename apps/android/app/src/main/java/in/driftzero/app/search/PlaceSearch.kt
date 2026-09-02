package `in`.driftzero.app.search

import `in`.driftzero.app.geo.GeoPoint
import `in`.driftzero.app.net.HttpException
import `in`.driftzero.app.net.TextGetter
import `in`.driftzero.app.net.encodeQuery

class PlaceSearch(
    private val http: TextGetter,
    private val photonBaseUrl: String = "https://photon.komoot.io/api/",
    private val nominatimBaseUrl: String = "https://nominatim.openstreetmap.org/search",
) {
    fun search(query: String, bias: GeoPoint): SearchOutcome {
        val trimmed = query.trim()
        if (trimmed.length < 3) return SearchOutcome.Empty
        val photon = runCatching { searchPhoton(trimmed, bias) }
        photon.getOrNull()?.let { places ->
            val ranked = PlaceRanker.preferIndiaPune(places)
            if (ranked.isNotEmpty()) return SearchOutcome.Found(ranked)
        }
        val photonFailure = photon.exceptionOrNull()?.toSearchFailure()
        return try {
            val nominatim = PlaceRanker.preferIndiaPune(searchNominatim(trimmed, bias))
            if (nominatim.isEmpty()) {
                photonFailure?.let { SearchOutcome.Failed(it) } ?: SearchOutcome.Empty
            } else {
                SearchOutcome.Found(nominatim)
            }
        } catch (error: Exception) {
            SearchOutcome.Failed(photonFailure ?: error.toSearchFailure())
        }
    }

    private fun searchPhoton(query: String, bias: GeoPoint): List<Place> {
        val url =
            "${photonBaseUrl}?q=${encodeQuery(query)}" +
                "&lat=${bias.latitudeDeg}&lon=${bias.longitudeDeg}&limit=8"
        return PhotonParser.parse(http.get(url))
    }

    private fun searchNominatim(query: String, bias: GeoPoint): List<Place> {
        val url =
            "${nominatimBaseUrl}?q=${encodeQuery(query)}" +
                "&format=jsonv2&addressdetails=1&countrycodes=in&limit=8" +
                "&viewbox=73.60,18.75,74.15,18.35&bounded=0" +
                "&lat=${bias.latitudeDeg}&lon=${bias.longitudeDeg}"
        return NominatimParser.parse(http.get(url))
    }

    private fun Throwable.toSearchFailure(): SearchFailure {
        val http = this as? HttpException
        return when {
            http?.statusCode == 429 -> SearchFailure.RateLimited
            http != null && http.statusCode >= 500 -> SearchFailure.Unavailable
            this is java.io.IOException -> SearchFailure.Network
            else -> SearchFailure.Parse
        }
    }
}
