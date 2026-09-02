package `in`.driftzero.app.ui

import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.maps.AreaPackId
import `in`.driftzero.app.maps.AreaPackManifest
import `in`.driftzero.app.maps.AreaPackState
import `in`.driftzero.app.maps.GeoBbox
import `in`.driftzero.app.pose.NavicMonitor
import `in`.driftzero.app.pose.NavicSnapshot
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.MapMatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusCopyTest {
    @Test
    fun gnssAgeIsNullUntilAFixArrives() {
        assertNull(StatusCopy.gnssAgeS(null, 2_000_000_000L))
        assertEquals(0.4, StatusCopy.gnssAgeS(600_000_000L, 1_000_000_000L)!!, 1e-9)
    }

    @Test
    fun lastTrustedAndRadiusAndHeading() {
        assertEquals("12 s ago", StatusCopy.lastTrustedAgo(12.0))
        assertEquals("12 m 95%", StatusCopy.radius95(12.4))
        assertEquals("247 deg, 6 deg 95%", StatusCopy.heading(Math.toRadians(247.0), Math.toRadians(6.0)))
    }

    @Test
    fun mapMatchNamesEachStatus() {
        assertEquals("Matched 0.91", StatusCopy.mapMatch(MapMatchStatus.MATCHED, 0.912))
        assertEquals("Ambiguous 0.52", StatusCopy.mapMatch(MapMatchStatus.AMBIGUOUS, 0.52))
        assertEquals("Off road", StatusCopy.mapMatch(MapMatchStatus.UNMATCHED, 0.1))
        assertEquals("No area pack", StatusCopy.mapMatch(MapMatchStatus.NO_MAP, 0.0))
    }

    @Test
    fun sensorsAndModelFollowFlags() {
        assertEquals("Accel, gyro ok", StatusCopy.sensors(emptySet()))
        assertEquals("No IMU", StatusCopy.sensors(setOf(DeadReckoningFilter.FLAG_NO_IMU)))
        assertEquals("ZUPT, NHC", StatusCopy.sensors(setOf(DeadReckoningFilter.FLAG_ZUPT, DeadReckoningFilter.FLAG_NHC)))
        assertEquals("Speed student", StatusCopy.model(setOf(DeadReckoningFilter.FLAG_MOTION_PSEUDO), studentLoaded = true))
        assertEquals("Heuristic speed", StatusCopy.model(setOf(DeadReckoningFilter.FLAG_MOTION_PSEUDO), studentLoaded = false))
        assertEquals("Filter only", StatusCopy.model(emptySet(), studentLoaded = false))
    }

    @Test
    fun navicIsChipsetVisibilityNotIntegrity() {
        assertEquals("Not reported by chipset", StatusCopy.navic(NavicSnapshot.NONE))
        val seen = NavicMonitor.tally(
            listOf(
                `in`.driftzero.app.pose.GnssSatRow(NavicMonitor.IRNSS, usedInFix = true),
                `in`.driftzero.app.pose.GnssSatRow(NavicMonitor.IRNSS, usedInFix = true),
                `in`.driftzero.app.pose.GnssSatRow(NavicMonitor.IRNSS, usedInFix = false),
            ),
        )
        assertEquals("3 visible, 2 used", StatusCopy.navic(seen))
    }

    @Test
    fun areaPackAndOutputRate() {
        assertEquals("None. Streets and routing from network", StatusCopy.areaPack(null, null))
        val pack = AreaPack(
            AreaPackManifest(
                id = AreaPackId("example-india"),
                bbox = requireNotNull(GeoBbox.of(6.0, 68.0, 36.0, 97.0)),
            ),
            AreaPackState.Ready,
        )
        assertEquals("example-india, 412 MB", StatusCopy.areaPack(pack, 412_000_000L))
        assertEquals("p95 gap 108 ms", StatusCopy.outputRate(108.4))
        assertNull(StatusCopy.outputRate(null))
        assertTrue(StatusCopy.outputRate(-1.0) == null)
    }
}
