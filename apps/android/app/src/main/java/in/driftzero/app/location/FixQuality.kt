package `in`.driftzero.app.location

/**
 * Decides whether a GNSS sample is trusted enough to say "GPS on".
 *
 * Timestamps are elapsed-realtime nanoseconds (Android [android.location.Location.getElapsedRealtimeNanos]).
 * Accuracy is horizontal metres as reported by the provider. Missing accuracy is not treated as zero.
 */
object FixQuality {
    const val MAX_HORIZONTAL_ACCURACY_M = 50.0
    const val MAX_AGE_NS = 15_000_000_000L

    fun hasTrustedFix(
        accuracyM: Float?,
        elapsedRealtimeNs: Long,
        nowElapsedRealtimeNs: Long,
    ): Boolean {
        if (accuracyM == null || !accuracyM.isFinite() || accuracyM < 0f) {
            return false
        }
        if (accuracyM > MAX_HORIZONTAL_ACCURACY_M) {
            return false
        }
        val ageNs = nowElapsedRealtimeNs - elapsedRealtimeNs
        return ageNs in 0..MAX_AGE_NS
    }
}
