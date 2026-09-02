package `in`.driftzero.app.product

import `in`.driftzero.app.geo.GeoPoint

/**
 * Pune demo helpers.
 *
 * The driving path itself must come from destination search plus a live
 * (or fallback) origin. These values are not a pre-baked route pair.
 */
object DemoPlaces {
    /** Pune city centre used to bias Photon / Nominatim. */
    val PUNE_BIAS = GeoPoint(latitudeDeg = 18.5204, longitudeDeg = 73.8567)

    /**
     * Used only when the phone has no GPS fix yet.
     * Photon lookup 2026-09-02: Starbucks, North Main Road, Koregaon Park.
     */
    val FALLBACK_ORIGIN = GeoPoint(latitudeDeg = 18.5392674, longitudeDeg = 73.8866937)

    const val FALLBACK_ORIGIN_LABEL = "Starbucks, Koregaon Park"

    /** Suggestion chip query. Results still come from Photon/Nominatim. */
    const val DEMO_DESTINATION_QUERY = "PES Modern College of Engineering, Pune"
}
