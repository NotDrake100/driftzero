package `in`.driftzero.app.trips

import `in`.driftzero.app.pose.PoseStore
import `in`.driftzero.core.ClockDomain
import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.ReplayJsonl
import `in`.driftzero.core.ReplayLoadResult
import `in`.driftzero.core.SensorKind
import `in`.driftzero.core.VectorFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TripRecorderTest {
    @Test
    fun ringDropsOldestBeforeFlush() {
        val root = createTempDirectory("trip-ring").toFile()
        try {
            val pending = ArrayList<Runnable>()
            val recorder = TripRecorder(
                dir = File(root, "trip-1"),
                id = "trip-1",
                startWallMs = 1_800_000L,
                schedule = { pending += it },
                sensorCap = 2,
                stateCap = 2,
            )
            recorder.start()
            pending.clear()
            recorder.offerAccel(Nanoseconds(0L), 0.0, 0.0, 9.8)
            recorder.offerGyro(Nanoseconds(10_000_000L), 0.0, 0.0, 0.0)
            recorder.offerAccel(Nanoseconds(20_000_000L), 0.1, 0.0, 9.8)
            assertEquals(3, pending.size)
            pending.forEach { it.run() }
            val loaded = ReplayJsonl.load(File(recorder.dir, TripRecorder.SENSORS).toPath())
            assertTrue(loaded is ReplayLoadResult.Ready)
            val ready = loaded as ReplayLoadResult.Ready
            assertEquals(2, ready.stats.framesRead)
            assertEquals(SensorKind.GYROSCOPE, ready.source.frames[0].kind)
            assertEquals(SensorKind.ACCELEROMETER, ready.source.frames[1].kind)
            assertEquals(20_000_000L, ready.source.frames[1].timestamp.value)
            assertEquals(ClockDomain.ANDROID_ELAPSED_REALTIME, ready.source.header.clockDomain)
            assertEquals(VectorFrame.ANDROID_DEVICE, ready.source.header.imuFrame)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun poseStoreOffersDoNotWriteOnCallerThread() {
        val root = createTempDirectory("trip-copy").toFile()
        try {
            val pending = ArrayList<Runnable>()
            val recorder = TripRecorder(
                dir = File(root, "trip-2"),
                id = "trip-2",
                startWallMs = 0L,
                schedule = { pending += it },
            )
            recorder.start()
            pending.clear()
            var now = 0L
            val store = PoseStore(filter = DeadReckoningFilter(), clockNs = { now })
            store.attachRecorder(recorder)
            store.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(0L),
                    latitudeDeg = 0.0,
                    longitudeDeg = 0.0,
                    speedMps = 8.0,
                    headingRad = 0.0,
                    horizontalAccuracyM = 5.0,
                ),
            )
            now = 100_000_000L
            store.tick()
            val sensors = File(recorder.dir, TripRecorder.SENSORS)
            val states = File(recorder.dir, TripRecorder.STATES)
            assertTrue(!states.exists() || states.length() == 0L)
            assertTrue(pending.isNotEmpty())
            pending.forEach { it.run() }
            val loaded = ReplayJsonl.load(sensors.toPath())
            assertTrue(loaded is ReplayLoadResult.Ready)
            val ready = loaded as ReplayLoadResult.Ready
            assertTrue(ready.source.frames.any { it.kind == SensorKind.GNSS_FIX })
            assertTrue(states.isFile && states.readText().isNotBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestRecordsMeasuredHzFromTimestampDeltas() {
        val root = createTempDirectory("trip-rate").toFile()
        try {
            val pending = ArrayList<Runnable>()
            val recorder = TripRecorder(
                dir = File(root, "trip-rate"),
                id = "trip-rate",
                startWallMs = 0L,
                schedule = { pending += it },
            )
            recorder.start()
            pending.clear()
            var t = 0L
            repeat(11) {
                recorder.offerAccel(Nanoseconds(t), 0.0, 0.0, 9.8)
                recorder.offerGyro(Nanoseconds(t), 0.0, 0.0, 0.0)
                t += 10_000_000L
            }
            pending.forEach { it.run() }
            val summary = recorder.stop()
            assertEquals(TripRecorder.REQUESTED_SENSOR_DELAY, summary.requestedSensorDelay)
            assertEquals(100.0, summary.measuredAccelHz!!, 1e-6)
            assertEquals(100.0, summary.measuredGyroHz!!, 1e-6)
            assertEquals(11, summary.accelSampleCount)
            val header = File(recorder.dir, TripRecorder.SENSORS).readLines().first()
            assertTrue(header.contains("SENSOR_DELAY_FASTEST"))
            assertTrue(header.contains("declared_rate_hz"))
            val manifest = File(recorder.dir, TripRecorder.MANIFEST).readText()
            assertTrue(manifest.contains("measured_accel_hz"))
            assertTrue(manifest.contains("hold_intervals") || manifest.contains("gnss_held") || manifest.contains("measured_accel_hz"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun gravityFrameIsLoggedAndReplayable() {
        val root = createTempDirectory("trip-gravity").toFile()
        try {
            val pending = ArrayList<Runnable>()
            val recorder = TripRecorder(
                dir = File(root, "trip-g"),
                id = "trip-g",
                startWallMs = 0L,
                schedule = { pending += it },
            )
            recorder.start()
            pending.clear()
            recorder.offerSensor(TripFrames.gravity(0L, Nanoseconds(0L), 0.0, 0.0, 9.81))
            recorder.offerGyro(Nanoseconds(10_000_000L), 0.0, 0.0, 0.0)
            pending.forEach { it.run() }
            val loaded = ReplayJsonl.load(File(recorder.dir, TripRecorder.SENSORS).toPath())
            assertTrue(loaded is ReplayLoadResult.Ready)
            val ready = loaded as ReplayLoadResult.Ready
            assertTrue(ready.source.frames.any { it.kind == SensorKind.GRAVITY })
        } finally {
            root.deleteRecursively()
        }
    }
}
