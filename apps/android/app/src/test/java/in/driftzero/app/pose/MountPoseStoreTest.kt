package `in`.driftzero.app.pose

import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.MountQuality
import `in`.driftzero.core.MountSession
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.ResetReason
import `in`.driftzero.core.VectorFrame
import `in`.driftzero.core.Wgs84
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MountPoseStoreTest {
    @Test
    fun pendingThenAlignedAfterStillAndStraightAccel() {
        var now = 0L
        val store = store { now }
        seedGnss(store, speedMps = 0.0)
        now = feedStill(store, startNs = 0L)
        assertEquals(MountQuality.STATIONARY_ONLY, store.mountQuality.value)
        assertEquals(VectorFrame.ANDROID_DEVICE, store.lastAccelEmit.value!!.frame)

        now += DT_NS
        store.ingestGnss(fix(now, speedMps = 5.0))
        now = feedStraightAccel(store, startNs = now, phoneAy = 1.2)

        assertEquals(MountQuality.ALIGNED_HIGH, store.mountQuality.value)
        val emit = store.lastAccelEmit.value!!
        assertEquals(VectorFrame.VEHICLE_FLU, emit.frame)
        assertEquals(1.2, emit.x, 1.0e-6)
        assertEquals(0.0, emit.y, 1.0e-6)
    }

    @Test
    fun frameStaysAndroidDeviceWhilePending() {
        var now = 0L
        val store = store { now }
        store.ingestGyro(Nanoseconds(0L), 0.0, 0.0, 0.0)
        store.ingestAccel(Nanoseconds(0L), 0.0, 0.0, G)
        assertEquals(MountQuality.PENDING, store.mountQuality.value)
        assertEquals(VectorFrame.ANDROID_DEVICE, store.lastAccelEmit.value!!.frame)
        assertEquals(VectorFrame.ANDROID_DEVICE, store.lastGyroEmit.value!!.frame)
    }

    @Test
    fun yawAmbiguityUsesSpeedDeltaSign() {
        var now = 0L
        val store = store { now }
        seedGnss(store, speedMps = 0.0)
        now = feedStill(store, startNs = 0L)
        now += DT_NS
        store.ingestGnss(fix(now, speedMps = 5.0))
        now = feedStraightAccel(store, startNs = now, phoneAy = -1.2)

        assertEquals(MountQuality.ALIGNED_HIGH, store.mountQuality.value)
        val emit = store.lastAccelEmit.value!!
        assertEquals(VectorFrame.VEHICLE_FLU, emit.frame)
        assertEquals(1.2, emit.x, 1.0e-6)
        assertEquals(0.0, emit.y, 1.0e-6)
        assertTrue(abs(emit.z - G) < 1.0e-6)
    }

    @Test
    fun remountReturnsPendingAndResetsFilter() {
        var now = 0L
        val store = store { now }
        seedGnss(store, speedMps = 0.0)
        now = feedStill(store, startNs = 0L)
        now += DT_NS
        store.ingestGnss(fix(now, speedMps = 5.0))
        now = feedStraightAccel(store, startNs = now, phoneAy = 1.2)
        assertEquals(MountQuality.ALIGNED_HIGH, store.mountQuality.value)
        assertNotNull(store.state.value)

        repeat(20) {
            now += DT_NS
            store.ingestGyro(Nanoseconds(now), 0.0, 0.0, 0.0)
            store.ingestAccel(Nanoseconds(now), 0.0, 0.0, G)
        }
        val tiltY = G * sin(Math.toRadians(30.0))
        val tiltZ = G * cos(Math.toRadians(30.0))
        val tiltEnd = now + 5_000_000_000L
        var sawRemount = false
        while (now <= tiltEnd) {
            now += DT_NS
            store.ingestGyro(Nanoseconds(now), 0.2, 0.1, 0.4)
            store.ingestAccel(Nanoseconds(now), 0.0, tiltY, tiltZ)
            if (store.lastResetReason() == ResetReason.REMOUNT) {
                sawRemount = true
                break
            }
        }
        assertTrue(sawRemount)
        assertEquals(MountQuality.PENDING, store.mountQuality.value)
        assertEquals(ResetReason.REMOUNT, store.lastResetReason())
        assertEquals(MountSession.REMOUNT_USER_REASON, store.mountReason.value)
        assertEquals(VectorFrame.ANDROID_DEVICE, store.lastAccelEmit.value!!.frame)
        assertNull(store.state.value)
    }

    @Test
    fun remountDoesNotFireWhileStillEvenWithGnssJumps() {
        var now = 0L
        val store = store { now }
        seedGnss(store, speedMps = 0.0)
        now = feedStill(store, startNs = 0L)
        now += DT_NS
        store.ingestGnss(fix(now, speedMps = 5.0))
        now = feedStraightAccel(store, startNs = now, phoneAy = 1.2)
        assertEquals(MountQuality.ALIGNED_HIGH, store.mountQuality.value)
        repeat(40) {
            now += DT_NS
            store.ingestGyro(Nanoseconds(now), 0.0, 0.0, 0.0)
            store.ingestAccel(Nanoseconds(now), 0.0, 0.0, G)
        }
        val lat0 = 18.52
        val lon0 = 73.85
        repeat(8) { i ->
            now += 1_000_000_000L
            store.ingestGyro(Nanoseconds(now), 0.0, 0.0, 0.0)
            store.ingestAccel(Nanoseconds(now), 0.0, 0.0, G)
            store.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(now),
                    latitudeDeg = lat0 + i * 0.002,
                    longitudeDeg = lon0 + i * 0.002,
                    speedMps = 12.0,
                    headingRad = 0.0,
                    horizontalAccuracyM = 25.0,
                ),
            )
        }
        assertTrue(store.lastResetReason() != ResetReason.REMOUNT)
        assertNull(store.mountReason.value)
        assertEquals(MountQuality.ALIGNED_HIGH, store.mountQuality.value)
    }

    @Test
    fun magnetometerIsStoredUnusedAndDoesNotChangePose() {
        var now = 0L
        val store = store { now }
        seedGnss(store, speedMps = 8.0)
        store.tick()
        val before = store.state.value!!
        assertFalse(store.magCapturedUnused.value)
        store.ingestMagnetometer(Nanoseconds(now), 12.0, -4.0, 42.0, accuracyCode = 3)
        assertTrue(store.magCapturedUnused.value)
        val mag = store.lastMagnetometer.value!!
        assertEquals(12.0, mag.xUt, 0.0)
        assertEquals(-4.0, mag.yUt, 0.0)
        assertEquals(42.0, mag.zUt, 0.0)
        assertEquals(VectorFrame.ANDROID_DEVICE, mag.frame)
        store.tick()
        val after = store.state.value!!
        assertEquals(before.position.latitude.value, after.position.latitude.value, 0.0)
        assertEquals(before.position.longitude.value, after.position.longitude.value, 0.0)
        assertEquals(before.motion.speed.value, after.motion.speed.value, 0.0)
        assertEquals(before.motion.heading.value, after.motion.heading.value, 0.0)
        assertEquals(MountQuality.PENDING, store.mountQuality.value)
        assertTrue(after.health.flags.contains(PoseStore.FLAG_MAG_CAPTURED_UNUSED))
    }

    @Test
    fun persistedProfileRestoresVehicleFrame() {
        val mem = RecordingMountStore()
        var now = 0L
        val first = PoseStore(
            filter = DeadReckoningFilter(),
            clockNs = { now },
            profiles = mem,
        )
        seedGnss(first, speedMps = 0.0)
        now = feedStill(first, startNs = 0L)
        now += DT_NS
        first.ingestGnss(fix(now, speedMps = 5.0))
        feedStraightAccel(first, startNs = now, phoneAy = 1.2)
        assertEquals(MountQuality.ALIGNED_HIGH, first.mountQuality.value)
        assertNotNull(mem.json)

        var now2 = 10_000_000_000L
        val second = PoseStore(
            filter = DeadReckoningFilter(),
            clockNs = { now2 },
            profiles = mem,
        )
        assertEquals(MountQuality.ALIGNED_HIGH, second.mountQuality.value)
        second.ingestGyro(Nanoseconds(now2), 0.0, 0.0, 0.0)
        second.ingestAccel(Nanoseconds(now2), 0.0, 1.2, G)
        assertEquals(VectorFrame.VEHICLE_FLU, second.lastAccelEmit.value!!.frame)
        assertEquals(1.2, second.lastAccelEmit.value!!.x, 1.0e-6)
    }

    private fun store(clock: () -> Long): PoseStore =
        PoseStore(filter = DeadReckoningFilter(), clockNs = clock)

    private fun seedGnss(store: PoseStore, speedMps: Double) {
        store.ingestGnss(fix(0L, speedMps))
    }

    private fun feedStill(store: PoseStore, startNs: Long): Long {
        var t = startNs
        repeat(80) {
            store.ingestGyro(Nanoseconds(t), 0.0, 0.0, 0.0)
            store.ingestAccel(Nanoseconds(t), 0.0, 0.0, G)
            t += DT_NS
        }
        return t
    }

    private fun feedStraightAccel(store: PoseStore, startNs: Long, phoneAy: Double): Long {
        var t = startNs
        repeat(3) {
            t += DT_NS
            store.ingestGyro(Nanoseconds(t), 0.0, 0.0, 0.0)
            store.ingestAccel(Nanoseconds(t), 0.0, phoneAy, G)
        }
        return t
    }

    private fun fix(timestampNs: Long, speedMps: Double): CoastFix =
        CoastFix(
            timestamp = Nanoseconds(timestampNs),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            speedMps = speedMps,
            headingRad = 0.0,
            horizontalAccuracyM = 5.0,
        )

    private class RecordingMountStore : MountProfileStore {
        var json: String? = null

        override fun loadJson(): String? = json

        override fun saveJson(json: String?) {
            this.json = json
        }
    }

    companion object {
        private val G: Double = Wgs84.STANDARD_G
        private const val DT_NS: Long = 40_000_000L
    }
}
