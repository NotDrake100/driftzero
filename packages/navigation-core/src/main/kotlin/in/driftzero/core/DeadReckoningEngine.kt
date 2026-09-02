package `in`.driftzero.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [NavigationEngine] over [DeadReckoningFilter]. Emits [NavigationState] near
 * [DeadReckoningFilter.OUTPUT_HZ] from sensor timestamps, not wall clock.
 *
 * [MotionPseudoRuntime] infers on the emit cadence and injects
 * [MotionPseudoMeasurement] and optional [DisplacementPseudoMeasurement]
 * into the filter. The filter does not own the student.
 *
 * Optional [RoadMatcher] writes [NavigationState.mapMatch] only. It does not
 * replace the ESKF lat/lon.
 */
class DeadReckoningEngine(
    private val filter: DeadReckoningFilter = DeadReckoningFilter(),
    private val motion: MotionPseudoRuntime = MotionPseudoRuntime(),
    private val matcher: RoadMatcher? = null,
    private val graph: RoadGraph? = null,
) : NavigationEngine {
    private val _states = MutableSharedFlow<NavigationState>(extraBufferCapacity = 16)
    private var lastEmitNs: Long = -1L

    override suspend fun consume(frame: SensorFrame) {
        filter.consume(frame)
        motion.ingestFrame(frame)
        maybeEmit(frame.timestamp)
    }

    override fun states(): Flow<NavigationState> = _states.asSharedFlow()

    override fun reset(reason: ResetReason) {
        filter.reset(reason)
        matcher?.reset()
        lastEmitNs = -1L
    }

    fun ingestMotionPseudo(meas: MotionPseudoMeasurement, timestamp: Nanoseconds) {
        filter.ingestMotionPseudo(meas, timestamp)
        maybeEmit(timestamp)
    }

    fun ingestDisplacementPseudo(meas: DisplacementPseudoMeasurement, timestamp: Nanoseconds) {
        filter.ingestDisplacementPseudo(meas, timestamp)
        maybeEmit(timestamp)
    }

    private fun maybeEmit(timestamp: Nanoseconds) {
        val t = timestamp.value
        if (lastEmitNs >= 0L && t - lastEmitNs < PERIOD_NS) {
            return
        }
        motion.inferAt(timestamp)?.let { filter.ingestMotionPseudo(it, timestamp) }
        motion.inferDisplacementAt(timestamp)?.let { filter.ingestDisplacementPseudo(it, timestamp) }
        val raw = filter.poseAt(timestamp) ?: return
        lastEmitNs = t
        _states.tryEmit(overlayMatch(raw))
    }

    private fun overlayMatch(state: NavigationState): NavigationState {
        val activeMatcher = matcher
        val activeGraph = graph
        if (activeMatcher == null || activeGraph == null || activeGraph.isEmpty()) {
            return state
        }
        return state.withMapMatch(activeMatcher.update(FilterSnapshot(state), activeGraph))
    }

    companion object {
        private val PERIOD_NS: Long =
            (1_000_000_000.0 / DeadReckoningFilter.OUTPUT_HZ).toLong()
    }
}
