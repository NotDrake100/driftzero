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
 * Optional [RoadMatcher] writes [NavigationState.mapMatch]. When MATCHED and
 * coasting, [RoadHeadingFeedback] applies a heading-only prior. It does not
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
        emitIfDue(frame.timestamp)?.let { _states.tryEmit(it) }
    }

    override fun states(): Flow<NavigationState> = _states.asSharedFlow()

    override fun reset(reason: ResetReason) {
        filter.reset(reason)
        matcher?.reset()
        lastEmitNs = -1L
    }

    fun ingestMotionPseudo(meas: MotionPseudoMeasurement, timestamp: Nanoseconds) {
        filter.ingestMotionPseudo(meas, timestamp)
        emitIfDue(timestamp)?.let { _states.tryEmit(it) }
    }

    fun ingestDisplacementPseudo(meas: DisplacementPseudoMeasurement, timestamp: Nanoseconds) {
        filter.ingestDisplacementPseudo(meas, timestamp)
        emitIfDue(timestamp)?.let { _states.tryEmit(it) }
    }

    /**
     * Synchronous consume for JVM replay. Same 10 Hz cadence and student
     * inject as [consume]. Default Replay stays consume-only.
     */
    fun ingestForReplay(frame: SensorFrame): NavigationState? {
        filter.consume(frame)
        motion.ingestFrame(frame)
        return emitIfDue(frame.timestamp)
    }

    private fun emitIfDue(timestamp: Nanoseconds): NavigationState? {
        val t = timestamp.value
        if (lastEmitNs >= 0L && t - lastEmitNs < PERIOD_NS) {
            return null
        }
        motion.inferAt(timestamp)?.let { filter.ingestMotionPseudo(it, timestamp) }
        motion.inferDisplacementAt(timestamp)?.let { filter.ingestDisplacementPseudo(it, timestamp) }
        val raw = filter.poseAt(timestamp) ?: return null
        lastEmitNs = t
        return overlayMatch(raw)
    }

    private fun overlayMatch(state: NavigationState): NavigationState {
        return RoadHeadingFeedback.apply(
            filter = filter,
            pose = state,
            matcher = matcher,
            graph = graph,
            coasting = filter.isCoastingAt(state.timestamp),
            now = state.timestamp,
        ).pose
    }

    companion object {
        private val PERIOD_NS: Long =
            (1_000_000_000.0 / DeadReckoningFilter.OUTPUT_HZ).toLong()
    }
}
