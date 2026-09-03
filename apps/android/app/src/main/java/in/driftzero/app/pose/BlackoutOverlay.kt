package `in`.driftzero.app.pose

import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.Wgs84

/**
 * Main-screen blackout geometry. The product is the gap between the last
 * trusted GNSS and the coasting puck. Lifetimes are causal: they use only
 * [nowNs] and a recorded start.
 */
object BlackoutOverlay {
    const val CORRECTION_LIFETIME_NS: Long = 3_000_000_000L
    const val DEMO_SIGNAL_LOSS_HOLD_S: Double = 30.0

    fun isOutageMode(mode: NavigationMode): Boolean = when (mode) {
        NavigationMode.DEAD_RECKONING,
        NavigationMode.REACQUIRING,
        NavigationMode.LOW_CONFIDENCE -> true
        NavigationMode.GNSS_FUSED,
        NavigationMode.GNSS_DEGRADED -> false
    }

    /**
     * Path length while GNSS is gone. [previousM] is the running sum along
     * fused samples. A missing [from] adds nothing. Leaving an outage mode
     * returns 0 so the next coast starts clean.
     */
    fun accumulateCoastM(
        previousM: Double,
        from: PosePoint?,
        to: PosePoint,
        mode: NavigationMode,
    ): Double {
        if (!isOutageMode(mode)) {
            return 0.0
        }
        if (from == null) {
            return previousM.coerceAtLeast(0.0)
        }
        if (!from.latitudeDeg.isFinite() || !from.longitudeDeg.isFinite() ||
            !to.latitudeDeg.isFinite() || !to.longitudeDeg.isFinite()
        ) {
            return previousM.coerceAtLeast(0.0)
        }
        val step = Wgs84.distanceMetres(
            from.latitudeDeg,
            from.longitudeDeg,
            to.latitudeDeg,
            to.longitudeDeg,
        )
        if (!step.isFinite() || step < 0.0) {
            return previousM.coerceAtLeast(0.0)
        }
        return previousM.coerceAtLeast(0.0) + step
    }

    fun ghostVisible(mode: NavigationMode, lastTrusted: PosePoint?): Boolean =
        isOutageMode(mode) && lastTrusted != null &&
            lastTrusted.latitudeDeg.isFinite() && lastTrusted.longitudeDeg.isFinite()

    fun correctionRemainingNs(
        startedNs: Long?,
        nowNs: Long,
        lifetimeNs: Long = CORRECTION_LIFETIME_NS,
    ): Long {
        if (startedNs == null || lifetimeNs <= 0L || nowNs < startedNs) {
            return 0L
        }
        return (lifetimeNs - (nowNs - startedNs)).coerceAtLeast(0L)
    }

    fun correctionVisible(
        startedNs: Long?,
        nowNs: Long,
        lifetimeNs: Long = CORRECTION_LIFETIME_NS,
    ): Boolean = correctionRemainingNs(startedNs, nowNs, lifetimeNs) > 0L
}

data class CorrectionStroke(
    val from: PosePoint,
    val to: PosePoint,
    val startedNs: Long,
)
