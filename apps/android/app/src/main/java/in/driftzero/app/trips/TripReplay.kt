package `in`.driftzero.app.trips

import `in`.driftzero.app.pose.PoseStore
import `in`.driftzero.core.DeadReckoningEngine
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.GnssMaskInterval
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.ReplayJsonl
import `in`.driftzero.core.ReplayLoadResult
import `in`.driftzero.core.ReplaySensorSource
import `in`.driftzero.core.SensorFrame
import `in`.driftzero.core.SensorKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Replays a recorded SensorFrame JSONL through the existing
 * [ReplaySensorSource]. Live GNSS is not a second source. Hold intervals
 * use [GnssMaskInterval] so the filter never sees those GNSS rows.
 * Frames flagged [TripFrames.FLAG_GNSS_HELD] are also dropped.
 */
object TripReplay {
    private val PERIOD_NS: Long =
        (1_000_000_000.0 / DeadReckoningFilter.OUTPUT_HZ).toLong()

    fun playInto(
        store: PoseStore,
        source: ReplaySensorSource,
        masks: List<GnssMaskInterval> = emptyList(),
    ): Int {
        store.beginReplay()
        try {
            var lastTickNs = -1L
            var consumed = 0
            for (frame in source.frames) {
                if (masks.any { it.drops(frame) } || isScoreOnlyGnss(frame)) {
                    continue
                }
                store.ingestSensor(frame)
                consumed += 1
                val t = frame.timestamp.value
                if (lastTickNs < 0L || t - lastTickNs >= PERIOD_NS) {
                    store.tickAt(Nanoseconds(t))
                    lastTickNs = t
                }
            }
            return consumed
        } finally {
            store.endReplay()
        }
    }

    fun runEngine(
        source: ReplaySensorSource,
        masks: List<GnssMaskInterval> = emptyList(),
    ): List<NavigationState> {
        val engine = DeadReckoningEngine()
        val out = ArrayList<NavigationState>()
        runBlocking {
            val job = launch {
                engine.states().collect { state -> out += state }
            }
            for (frame in source.frames) {
                if (masks.any { it.drops(frame) } || isScoreOnlyGnss(frame)) {
                    continue
                }
                engine.consume(frame)
            }
            job.cancel()
        }
        return out
    }

    fun loadReady(trip: TripSummary): ReplayLoadResult = ReplayJsonl.load(trip.sensorsFile.toPath())

    internal fun isScoreOnlyGnss(frame: SensorFrame): Boolean {
        if (frame.kind != SensorKind.GNSS_FIX &&
            frame.kind != SensorKind.GNSS_STATUS &&
            frame.kind != SensorKind.RAW_GNSS
        ) {
            return false
        }
        return frame.quality.flags.contains(TripFrames.FLAG_GNSS_HELD)
    }
}
