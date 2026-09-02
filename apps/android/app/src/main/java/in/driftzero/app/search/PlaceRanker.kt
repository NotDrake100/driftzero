package `in`.driftzero.app.search

object PlaceRanker {
    fun preferIndiaPune(places: List<Place>): List<Place> {
        return places.sortedWith(
            compareBy<Place> { indiaScore(it) }
                .thenBy { puneScore(it) },
        )
    }

    private fun indiaScore(place: Place): Int {
        val code = place.countryCode?.uppercase()
        return when {
            code == "IN" || code == "INDIA" -> 0
            code.isNullOrBlank() -> 1
            else -> 2
        }
    }

    private fun puneScore(place: Place): Int {
        val haystack = listOfNotNull(place.city, place.subtitle, place.name).joinToString(" ").lowercase()
        return if (haystack.contains("pune") || haystack.contains("pimpri")) 0 else 1
    }
}
