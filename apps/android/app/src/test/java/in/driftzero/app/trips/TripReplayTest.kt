package `in`.driftzero.app.trips

import `in`.driftzero.app.pose.PoseStore
import `in`.driftzero.core.ClockDomain
import `in`.driftzero.core.ContractWrite
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.FixPayload
import `in`.driftzero.core.GnssFixPayload
import `in`.driftzero.core.GnssMaskInterval
import `in`.driftzero.core.HeadingRadians
import `in`.driftzero.core.LatitudeDeg
import `in`.driftzero.core.LongitudeDeg
import `in`.driftzero.core.Metres
import `in`.driftzero.core.MetresPerSecond
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.Quality
import `in`.driftzero.core.ReplayJsonl
import `in`.driftzero.core.ReplayLoadResult
import `in`.driftzero.core.SensorFrame
import `in`.driftzero.core.SensorKind
import `in`.driftzero.core.Vector3Payload
import `in`.driftzero.core.VectorFrame
import `in`.driftzero.core.VectorPayload
import `in`.driftzero.core.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs

class TripReplayTest {
    @Test
    fun replaySensorSourceFeedsPoseStoreAtRecordedTime() {
        val root = createTempDirectory("trip-replay").toFile()
        try {
            val sensors = File(root, "sensors.jsonl")
            writeTrip(sensors, maskDecoy = true)
            val loaded = ReplayJsonl.load(sensors.toPath())
            assertTrue(loaded is ReplayLoadResult.Ready)
            val source = (loaded as ReplayLoadResult.Ready).source
            val store = PoseStore(filter = DeadReckoningFilter(), clockNs = { 99_000_000_000L })
            val consumed = TripReplay.playInto(
                store,
                source,
                listOf(GnssMaskInterval(1_000_000_000L, 2_000_000_000L)),
            )
            assertTrue(consumed > 0)
            val pose = store.state.value
            assertNotNull(pose)
            assertEquals(2_000_000_000L, pose!!.timestamp.value)
            assertTrue(abs(pose.position.latitude.value) < 1.0)
            assertTrue(store.fusedTrail().isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeTrip(path: File, maskDecoy: Boolean) {
        val lines = ArrayList<String>()
        lines += ContractWrite.stringify(
            linkedMapOf(
                "declared_rate_hz" to 50.0,
                "clock_domain" to ClockDomain.ANDROID_ELAPSED_REALTIME.contractName(),
                "frame" to VectorFrame.ANDROID_DEVICE.contractName(),
                "source_id" to "phone",
            ),
        )
        var sequence = 0L
        var t = 0L
        val g = Wgs84.gravityMps2(0.0)
        while (t <= 2_000_000_000L) {
            lines += ContractWrite.sensorLine(accel(sequence++, t, g))
            lines += ContractWrite.sensorLine(gyro(sequence++, t))
            if (t % 200_000_000L == 0L) {
                val lat = if (maskDecoy && t >= 1_000_000_000L) 10.0 else 0.0
                lines += ContractWrite.sensorLine(gnss(sequence++, t, lat, 0.0))
            }
            t += 20_000_000L
        }
        path.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun accel(sequence: Long, timestampNs: Long, gravity: Double): SensorFrame = SensorFrame(
        sourceId = "phone",
        sequence = sequence,
        timestamp = Nanoseconds(timestampNs),
        clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
        kind = SensorKind.ACCELEROMETER,
        quality = Quality(available = true, accuracyCode = 2),
        payload = VectorPayload(Vector3Payload(0.0, 0.0, gravity, "m/s^2", VectorFrame.ANDROID_DEVICE)),
    )

    private fun gyro(sequence: Long, timestampNs: Long): SensorFrame = SensorFrame(
        sourceId = "phone",
        sequence = sequence,
        timestamp = Nanoseconds(timestampNs),
        clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
        kind = SensorKind.GYROSCOPE,
        quality = Quality(available = true, accuracyCode = 2),
        payload = VectorPayload(Vector3Payload(0.0, 0.0, 0.0, "rad/s", VectorFrame.ANDROID_DEVICE)),
    )

    private fun gnss(sequence: Long, timestampNs: Long, lat: Double, lon: Double): SensorFrame = SensorFrame(
        sourceId = "phone",
        sequence = sequence,
        timestamp = Nanoseconds(timestampNs),
        clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
        kind = SensorKind.GNSS_FIX,
        quality = Quality(available = true, accuracyCode = 2),
        payload = FixPayload(
            GnssFixPayload(
                latitude = LatitudeDeg(lat),
                longitude = LongitudeDeg(lon),
                horizontalAccuracyM = Metres(4.0),
                providerTimeMs = timestampNs / 1_000_000L,
                speedMps = MetresPerSecond(8.0),
                bearingRad = HeadingRadians(0.0),
            ),
        ),
    )
}
