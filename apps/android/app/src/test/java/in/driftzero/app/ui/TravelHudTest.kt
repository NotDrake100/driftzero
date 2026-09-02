package `in`.driftzero.app.ui

import `in`.driftzero.core.ComponentHealth
import `in`.driftzero.core.CORE_VERSION
import `in`.driftzero.core.GeoPoint
import `in`.driftzero.core.GnssHealth
import `in`.driftzero.core.HeadingRadians
import `in`.driftzero.core.LatitudeDeg
import `in`.driftzero.core.LongitudeDeg
import `in`.driftzero.core.MapMatch
import `in`.driftzero.core.MapMatchStatus
import `in`.driftzero.core.Metres
import `in`.driftzero.core.MetresPerSecond
import `in`.driftzero.core.Motion
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.Provenance
import `in`.driftzero.core.Uncertainty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelHudTest {
    @Test
    fun deadReckoningFixClearsGpsOn() {
        val fix = sample(NavigationMode.DEAD_RECKONING).toTravelFix(providerEnabled = true)
        assertFalse(fix.gpsProviderOn)
        assertEquals(10.0, fix.speedMps!!, 0.0)
    }

    @Test
    fun fusedFixKeepsProviderFlag() {
        val fix = sample(NavigationMode.GNSS_FUSED).toTravelFix(providerEnabled = true)
        assertTrue(fix.gpsProviderOn)
    }

    @Test
    fun gpsOnRequiresPermissionProviderAndFix() {
        assertTrue(TravelHud.gpsOn(permissionGranted = true, providerEnabled = true, hasFix = true))
        assertFalse(TravelHud.gpsOn(permissionGranted = true, providerEnabled = false, hasFix = true))
    }

    @Test
    fun speedHiddenWithoutActiveRoute() {
        assertFalse(TravelHud.shouldShowSpeed(10.0, routeActive = false, currentlyShown = false))
        assertFalse(TravelHud.shouldShowSpeed(10.0, routeActive = false, currentlyShown = true))
    }

    @Test
    fun speedOnRouteUsesMotionHysteresis() {
        assertTrue(TravelHud.shouldShowSpeed(5.0, routeActive = true, currentlyShown = false))
        assertFalse(TravelHud.shouldShowSpeed(0.2, routeActive = true, currentlyShown = false))
        assertTrue(TravelHud.shouldShowSpeed(1.0, routeActive = true, currentlyShown = true))
        assertFalse(TravelHud.shouldShowSpeed(null, routeActive = true, currentlyShown = true))
    }

    private fun sample(mode: NavigationMode): NavigationState = NavigationState(
        sequence = 0L,
        timestamp = Nanoseconds(1_000_000_000L),
        mode = mode,
        position = GeoPoint(LatitudeDeg(18.5362), LongitudeDeg(73.8938)),
        motion = Motion(MetresPerSecond(10.0), HeadingRadians(0.0)),
        uncertainty = Uncertainty(Metres(12.0), 0.2, isCalibrated = false),
        gnssHealth = GnssHealth(0.5, 3.0),
        mapMatch = MapMatch(MapMatchStatus.NO_MAP, 0.0),
        health = ComponentHealth(
            sensorOk = true,
            modelOk = false,
            filterOk = false,
            mapOk = false,
        ),
        provenance = Provenance(CORE_VERSION, "a".repeat(64)),
    )
}
