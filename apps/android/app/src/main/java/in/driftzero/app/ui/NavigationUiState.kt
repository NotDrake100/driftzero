package `in`.driftzero.app.ui

import `in`.driftzero.app.location.LiveFix

/**
 * First-screen product state. Destination search is collected here;
 * geocoding and routing belong to the product-behavior owner.
 */
data class NavigationUiState(
    val destinationQuery: String = "",
    val liveFix: LiveFix? = null,
    val locationPermissionGranted: Boolean = false,
    val startRequested: Boolean = false,
) {
    val canStart: Boolean
        get() = destinationQuery.trim().isNotEmpty()
}
