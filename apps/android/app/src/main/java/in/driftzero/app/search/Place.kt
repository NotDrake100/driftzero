package `in`.driftzero.app.search

import `in`.driftzero.app.geo.GeoPoint

data class Place(
    val id: String,
    val name: String,
    val subtitle: String,
    val point: GeoPoint,
    val countryCode: String?,
    val city: String?,
    val source: String,
)

sealed class SearchOutcome {
    data class Found(val places: List<Place>) : SearchOutcome()
    data object Empty : SearchOutcome()
    data class Failed(val reason: SearchFailure) : SearchOutcome()
}

enum class SearchFailure {
    Network,
    RateLimited,
    Parse,
    Unavailable,
}
