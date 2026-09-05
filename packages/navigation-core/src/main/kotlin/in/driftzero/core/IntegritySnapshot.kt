package `in`.driftzero.core

/**
 * Live integrity rows for PoseStore and the status sheet. Not part of
 * [NavigationState] required fields.
 */
data class IntegritySnapshot(
    val drift: DriftBudget? = null,
    val blackout: BlackoutAssessment? = null,
    val gnssQuarantined: Boolean = false,
    val gnssRestored: Boolean = false,
    val placement: PhonePlacement = PhonePlacement.UNKNOWN,
    val travelledM: Double = 0.0,
)
