package `in`.driftzero.app.map

/**
 * Default visual map. Koregaon Park, Pune is the first-open camera
 * until a trusted device fix is available. Style URI is OpenFreeMap liberty
 * (see docs/adr/004-openfreemap-liberty-default.md).
 */
object MapDefaults {
    const val LIBERTY_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"
    const val DEFAULT_LATITUDE_DEG = 18.5362
    const val DEFAULT_LONGITUDE_DEG = 73.8938
    const val DEFAULT_ZOOM = 15.5
}
