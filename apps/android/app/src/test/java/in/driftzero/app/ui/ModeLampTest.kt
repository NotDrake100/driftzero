package `in`.driftzero.app.ui

import `in`.driftzero.core.ComponentHealth
import `in`.driftzero.core.CORE_VERSION
import `in`.driftzero.core.DeadReckoningFilter
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeLampTest {
    @Test
    fun everyModeHasAWordAndOnlyCoastingModesDash() {
        assertEquals(LampWord.GNSS, lampFor(NavigationMode.GNSS_FUSED).word)
        assertEquals(LampTone.OK, lampFor(NavigationMode.GNSS_FUSED).tone)
        assertFalse(lampFor(NavigationMode.GNSS_FUSED).dashed)
        assertEquals(LampTone.CAUTION, lampFor(NavigationMode.GNSS_DEGRADED).tone)
        assertFalse(lampFor(NavigationMode.GNSS_DEGRADED).dashed)
        assertTrue(lampFor(NavigationMode.DEAD_RECKONING).dashed)
        assertTrue(lampFor(NavigationMode.REACQUIRING).dashed)
        assertEquals(LampTone.ALERT, lampFor(NavigationMode.LOW_CONFIDENCE).tone)
        assertTrue(lampFor(NavigationMode.LOW_CONFIDENCE).coasting)
    }

    @Test
    fun noPermissionAndNoPoseAreNamedNotHidden() {
        val denied = lampFor(null, locationPermission = false)
        assertEquals(LampWord.NO_PERMISSION, denied.word)
        assertNull(denied.ageS)
        val waiting = lampFor(null, locationPermission = true)
        assertEquals(LampWord.WAITING_FIX, waiting.word)
        val fused = lampFor(sample(NavigationMode.GNSS_FUSED, ageS = 0.4), locationPermission = true)
        assertEquals(0.4, fused.ageS!!, 1e-9)
    }

    @Test
    fun reasonsFollowFilterFlags() {
        assertNull(modeReason(sample(NavigationMode.GNSS_FUSED)))
        assertEquals(
            ModeReason.NoFix(14.0),
            modeReason(sample(NavigationMode.DEAD_RECKONING, ageS = 14.0)),
        )
        assertEquals(
            ModeReason.Held,
            modeReason(sample(NavigationMode.DEAD_RECKONING, flags = setOf(DeadReckoningFilter.FLAG_GPS_HELD))),
        )
        assertEquals(
            ModeReason.ImuGap,
            modeReason(sample(NavigationMode.DEAD_RECKONING, flags = setOf(DeadReckoningFilter.FLAG_NO_IMU))),
        )
        assertEquals(
            ModeReason.RadiusLimit(130.0, 120.0),
            modeReason(sample(NavigationMode.LOW_CONFIDENCE, radiusM = 130.0)),
        )
        assertEquals(
            ModeReason.FixAccuracyOver(30.0),
            modeReason(sample(NavigationMode.GNSS_DEGRADED, risk = setOf(DeadReckoningFilter.RISK_POOR_ACCURACY))),
        )
        assertEquals(
            ModeReason.GatedFix,
            modeReason(sample(NavigationMode.GNSS_DEGRADED, risk = setOf(DeadReckoningFilter.RISK_GATED_FIX))),
        )
        assertEquals(ModeReason.Reacquiring, modeReason(sample(NavigationMode.REACQUIRING)))
    }

    private fun sample(
        mode: NavigationMode,
        ageS: Double = 0.5,
        radiusM: Double = 12.0,
        flags: Set<String> = emptySet(),
        risk: Set<String> = emptySet(),
    ): NavigationState = NavigationState(
        sequence = 0L,
        timestamp = Nanoseconds(1_000_000_000L),
        mode = mode,
        position = GeoPoint(LatitudeDeg(18.5362), LongitudeDeg(73.8938)),
        motion = Motion(MetresPerSecond(10.0), HeadingRadians(0.0)),
        uncertainty = Uncertainty(Metres(radiusM), 0.2, isCalibrated = false),
        gnssHealth = GnssHealth(0.5, ageS, risk),
        mapMatch = MapMatch(MapMatchStatus.NO_MAP, 0.0),
        health = ComponentHealth(sensorOk = true, modelOk = false, filterOk = true, mapOk = false, flags = flags),
        provenance = Provenance(CORE_VERSION, "a".repeat(64)),
    )
}
