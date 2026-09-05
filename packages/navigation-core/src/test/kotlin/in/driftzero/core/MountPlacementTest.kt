package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MountPlacementTest {
    @Test
    fun dashFlatIsGzDominantLowVibAfterStill() {
        val placement = MountPlacement.classify(
            PlacementObservation(
                gravityPhone = Vec3(0.0, 0.0, Wgs84.STANDARD_G),
                vibrationEnergy = 0.20,
                still = true,
            ),
        )
        assertEquals(PhonePlacement.DASH_FLAT, placement)
        assertEquals("dash", MountPlacement.driverLine(placement))
    }

    @Test
    fun cupOrVentIsPortraitWithVehicleVibration() {
        val placement = MountPlacement.classify(
            PlacementObservation(
                gravityPhone = Vec3(0.0, Wgs84.STANDARD_G, 0.4),
                vibrationEnergy = 0.50,
            ),
        )
        assertEquals(PhonePlacement.CUP_OR_VENT, placement)
        assertEquals("cup or vent", MountPlacement.driverLine(placement))
    }

    @Test
    fun passengerSeatIsFlatWithHigherVibration() {
        val placement = MountPlacement.classify(
            PlacementObservation(
                gravityPhone = Vec3(0.2, 0.3, Wgs84.STANDARD_G),
                vibrationEnergy = 1.40,
                still = true,
            ),
        )
        assertEquals(PhonePlacement.PASSENGER_SEAT, placement)
        assertEquals("passenger seat", MountPlacement.driverLine(placement))
    }

    @Test
    fun remountIsHandheld() {
        val placement = MountPlacement.classify(
            PlacementObservation(
                gravityPhone = Vec3(0.0, 0.0, Wgs84.STANDARD_G),
                vibrationEnergy = 0.10,
                remount = true,
                still = true,
            ),
        )
        assertEquals(PhonePlacement.HANDHELD, placement)
        assertEquals(MountPlacement.RECALIBRATE_COPY, MountPlacement.RECALIBRATE_COPY)
    }

    @Test
    fun missingGravityIsUnknown() {
        assertEquals(
            PhonePlacement.UNKNOWN,
            MountPlacement.classify(PlacementObservation(gravityPhone = null, vibrationEnergy = 0.4)),
        )
    }

    @Test
    fun stillThenStraightGnssDeltaLeavesUnknown() {
        val moving = MountSession()
        var t = 0L
        val dt = 40_000_000L
        val g = Wgs84.STANDARD_G
        repeat(10) {
            moving.onGyro(t, 0.0, 0.0, 0.0)
            moving.onAccel(t, 0.0, 0.4, g, gnssSpeedDeltaMps = 1.2, gnssAccepted = true)
            t += dt
        }
        assertEquals(MountQuality.PENDING, moving.quality())
        assertEquals(PhonePlacement.UNKNOWN, moving.placement())

        val session = MountSession()
        t = 0L
        repeat(80) {
            session.onGyro(t, 0.0, 0.0, 0.0)
            session.onAccel(t, 0.0, 0.0, g, gnssSpeedDeltaMps = null, gnssAccepted = false, phoneStill = true)
            t += dt
        }
        assertEquals(MountQuality.STATIONARY_ONLY, session.quality())
        assertEquals(PhonePlacement.DASH_FLAT, session.placement())
        repeat(3) {
            session.onGyro(t, 0.0, 0.0, 0.0)
            session.onAccel(t, 0.0, 1.2, g, gnssSpeedDeltaMps = 2.4, gnssAccepted = true)
            t += dt
        }
        assertEquals(MountQuality.ALIGNED_HIGH, session.quality())
        assertEquals(PhonePlacement.DASH_FLAT, session.placement())
        assertTrue(session.quality().emitsVehicleFrame())
    }
}
