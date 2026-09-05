package `in`.driftzero.app.ui

import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.maps.AreaPackId
import `in`.driftzero.app.maps.AreaPackManifest
import `in`.driftzero.app.maps.AreaPackState
import `in`.driftzero.app.maps.GeoBbox
import `in`.driftzero.app.pose.LocationGrant
import `in`.driftzero.app.pose.NavicMonitor
import `in`.driftzero.app.pose.NavicSnapshot
import `in`.driftzero.app.pose.PoseStore
import `in`.driftzero.core.BlackoutInputs
import `in`.driftzero.core.BlackoutRisk
import `in`.driftzero.core.CORE_VERSION
import `in`.driftzero.core.ComponentHealth
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.DriftBudgetMath
import `in`.driftzero.core.GnssTrust
import `in`.driftzero.core.IntegritySnapshot
import `in`.driftzero.core.MountPlacement
import `in`.driftzero.core.PhonePlacement
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
import `in`.driftzero.core.MountQuality
import `in`.driftzero.core.MountSession
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.Provenance
import `in`.driftzero.core.RoadHeadingAid
import `in`.driftzero.core.RoadHeadingDecision
import `in`.driftzero.core.RoadHeadingPrior
import `in`.driftzero.core.RoadHeadingSkipReason
import `in`.driftzero.core.Uncertainty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun noLocationReasonNamesPreciseOffAndNoGps() {
        assertEquals("Precise location is off", StatusCopy.preciseLocationOff())
        assertEquals(
            "No GPS yet. Turn on Precise location. Wait outdoors.",
            StatusCopy.noGpsYet(),
        )
        assertEquals(
            "Precise location is off",
            StatusCopy.noLocationReason(LocationGrant.COARSE, hasPose = false),
        )
        assertEquals(
            "No GPS yet. Turn on Precise location. Wait outdoors.",
            StatusCopy.noLocationReason(LocationGrant.FINE, hasPose = false),
        )
        assertNull(StatusCopy.noLocationReason(LocationGrant.NONE, hasPose = false))
        assertNull(StatusCopy.noLocationReason(LocationGrant.FINE, hasPose = true))
        assertNull(StatusCopy.noLocationReason(LocationGrant.COARSE, hasPose = true))
        assertFalse(StatusCopy.noGpsYet().contains("—"))
        assertFalse(StatusCopy.preciseLocationOff().contains("Waiting for fix"))
    }

    @Test
    fun lastTrustedAndRadiusAndHeading() {
        assertEquals("12 s ago", StatusCopy.lastTrustedAgo(12.0))
        assertEquals("No fix 12 s", StatusCopy.noFix(12.0))
        assertEquals("140 m coasted", StatusCopy.coasted(140.2))
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
        assertEquals("Routing needs network", StatusCopy.routingStatus(network = false))
        assertNull(StatusCopy.routingStatus(network = true))
        assertNull(StatusCopy.routingStatus(network = false, localRouter = true))
    }

    @Test
    fun mountNamesQualityAndOmitsWhenNull() {
        assertNull(StatusCopy.mount(null))
        assertNull(StatusCopy.mount(null, 0.92))
        assertEquals("pending, drive straight", StatusCopy.mount(MountQuality.PENDING))
        assertEquals("still only", StatusCopy.mount(MountQuality.STATIONARY_ONLY))
        assertEquals("aligned 92%", StatusCopy.mount(MountQuality.ALIGNED, 0.92))
        assertEquals("aligned 92%", StatusCopy.mount(MountQuality.ALIGNED_HIGH, 0.92))
        assertEquals("aligned", StatusCopy.mount(MountQuality.ALIGNED, null))
        assertEquals("aligned", StatusCopy.mount(MountQuality.ALIGNED_HIGH, Double.NaN))
    }

    @Test
    fun magCapturedUnusedIsNamedNotFused() {
        assertEquals("Accel, gyro ok", StatusCopy.sensors(emptySet()))
        assertFalse(StatusCopy.sensors(emptySet()).contains("Mag"))
        assertEquals(
            "Mag: captured, unused",
            StatusCopy.sensors(setOf(PoseStore.FLAG_MAG_CAPTURED_UNUSED)),
        )
        assertEquals(
            "No IMU. Mag: captured, unused",
            StatusCopy.sensors(setOf(DeadReckoningFilter.FLAG_NO_IMU, PoseStore.FLAG_MAG_CAPTURED_UNUSED)),
        )
        val named = StatusCopy.sensors(setOf(PoseStore.FLAG_MAG_CAPTURED_UNUSED))
        assertFalse(named.contains("fused", ignoreCase = true))
    }

    @Test
    fun roadHeadingOmitsWhenNull() {
        assertNull(StatusCopy.roadHeading(null))
        assertEquals("on", StatusCopy.roadHeading(StatusCopy.RoadAidState.ON))
        assertEquals(
            "off (near junction)",
            StatusCopy.roadHeading(StatusCopy.RoadAidState.OFF_NEAR_JUNCTION),
        )
    }

    @Test
    fun reasonLinePrefersRemountAndSharesLampCopy() {
        assertNull(StatusCopy.reasonLine(null))
        assertNull(StatusCopy.reasonLine(null, mountReason = null, flags = emptySet()))
        assertEquals("GNSS held", StatusCopy.reasonLine(ModeReason.Held))
        assertEquals(
            MountSession.REMOUNT_USER_REASON,
            StatusCopy.reasonLine(ModeReason.Held, mountReason = MountSession.REMOUNT_USER_REASON),
        )
        assertEquals(
            MountSession.REMOUNT_USER_REASON,
            StatusCopy.reasonLine(null, flags = setOf(PoseStore.FLAG_MOUNT_REMOUNT)),
        )
        val remount = StatusCopy.reasonLine(
            ModeReason.NoFix(14.0),
            mountReason = MountSession.REMOUNT_USER_REASON,
        )
        assertEquals(MountSession.REMOUNT_USER_REASON, remount)
        assertEquals("Phone moved. Hold still 5 s.", remount)
        assertEquals(StatusCopy.WEAK_GPS, StatusCopy.reasonLine(ModeReason.NoFix(14.0)))
        assertEquals(StatusCopy.WEAK_GPS, StatusCopy.reasonLine(ModeReason.Reacquiring))
        assertEquals(
            "No fix for 14 s",
            StatusCopy.reasonLine(ModeReason.NoFix(14.0), lab = true),
        )
        assertEquals("Area pack", StatusCopy.sheetLabel("Area pack"))
        assertEquals("Area pack", StatusCopy.sheetLabel("  Area   pack  "))
        assertFalse(StatusCopy.sheetLabel("Area pack").contains("\n"))
        assertEquals(112, StatusCopy.SHEET_LABEL_MIN_DP)
    }

    @Test
    fun roadAidMapsDecisionToSheetState() {
        assertNull(StatusCopy.roadAid(null))
        assertEquals(
            StatusCopy.RoadAidState.ON,
            StatusCopy.roadAid(
                RoadHeadingDecision(
                    prior = RoadHeadingPrior(0.0, RoadHeadingAid.MIN_STD_RAD),
                    skipReason = null,
                ),
            ),
        )
        assertEquals(
            StatusCopy.RoadAidState.OFF_NEAR_JUNCTION,
            StatusCopy.roadAid(
                RoadHeadingDecision(prior = null, skipReason = RoadHeadingSkipReason.NEAR_JUNCTION),
            ),
        )
        assertNull(
            StatusCopy.roadAid(
                RoadHeadingDecision(prior = null, skipReason = RoadHeadingSkipReason.NOT_MATCHED),
            ),
        )
    }

    @Test
    fun ofOmitsMountRoadAndMagUntilWired() {
        val rows = StatusCopy.of(
            state = sample(NavigationMode.GNSS_FUSED),
            lastGnssSeenNs = 600_000_000L,
            nowNs = 1_000_000_000L,
            studentLoaded = false,
            navic = NavicSnapshot.NONE,
            pack = null,
            packBytes = null,
            p95Ms = 108.4,
        )
        assertNull(rows.find { it.first == "Mount" })
        assertNull(rows.find { it.first == "Road heading" })
        assertNull(rows.find { it.first == "Reason" })
        assertNull(rows.find { it.first == "Model" })
        assertNull(rows.find { it.first == "NavIC" })
        assertNull(rows.find { it.first == "Output rate" })
        assertNull(rows.find { it.first == "Sensors" })
        assertNull(rows.find { it.first == "Last trusted fix" })
        assertNull(rows.find { it.first == "Map match" })
        assertEquals("Area pack", rows.find { it.first == "Area pack" }?.let { it.first })
        assertFalse(rows.any { it.second.contains("Mag") })
        assertFalse(rows.any { it.second.contains("fused", ignoreCase = true) })
        assertFalse(rows.any { it.first == "Demo" || it.second.contains("Demo:") })
    }

    @Test
    fun ofAddsMountMagRoadAndRemountReason() {
        val rows = StatusCopy.of(
            state = sample(
                NavigationMode.GNSS_FUSED,
                flags = setOf(PoseStore.FLAG_MAG_CAPTURED_UNUSED, PoseStore.FLAG_MOUNT_REMOUNT),
            ),
            lastGnssSeenNs = 600_000_000L,
            nowNs = 1_000_000_000L,
            studentLoaded = false,
            navic = NavicSnapshot.NONE,
            pack = null,
            packBytes = null,
            p95Ms = null,
            mountQuality = MountQuality.ALIGNED,
            mountYawConfidence = 0.92,
            mountReason = MountSession.REMOUNT_USER_REASON,
            roadAid = StatusCopy.RoadAidState.OFF_NEAR_JUNCTION,
            lab = true,
        )
        assertEquals("Reason" to MountSession.REMOUNT_USER_REASON, rows.find { it.first == "Reason" })
        assertEquals("Mount" to "aligned 92%", rows.find { it.first == "Mount" })
        assertEquals("Sensors" to "Mag: captured, unused", rows.find { it.first == "Sensors" })
        assertEquals("Road heading" to "off (near junction)", rows.find { it.first == "Road heading" })
        val onRows = StatusCopy.of(
            state = sample(NavigationMode.GNSS_FUSED),
            lastGnssSeenNs = null,
            nowNs = 1_000_000_000L,
            studentLoaded = false,
            navic = NavicSnapshot.NONE,
            pack = null,
            packBytes = null,
            p95Ms = null,
            mountQuality = MountQuality.STATIONARY_ONLY,
            roadAid = StatusCopy.RoadAidState.ON,
            lab = true,
        )
        assertEquals("Mount" to "still only", onRows.find { it.first == "Mount" })
        assertEquals("Road heading" to "on", onRows.find { it.first == "Road heading" })
        assertEquals("pending, drive straight", StatusCopy.mount(MountQuality.PENDING))
    }

    @Test
    fun integrityRowsUseDriverWordsAndLabRemain() {
        val drift = DriftBudgetMath.evaluate(
            horizontal95M = 8.4,
            timestampNs = 1_000_000_000L,
            speedMps = 12.0,
            travelledM = 400.0,
        )
        val blackout = BlackoutRisk.evaluate(
            BlackoutInputs(
                usedSats = 3,
                meanUsedCn0DbHz = 18.0,
                horizontalAccuracyM = 40.0,
                tunnelAheadM = 280.0,
                mapPresent = true,
            ),
        )
        val snap = IntegritySnapshot(
            drift = drift,
            blackout = blackout,
            gnssQuarantined = true,
            placement = PhonePlacement.PASSENGER_SEAT,
        )
        val rows = StatusCopy.of(
            state = sample(NavigationMode.GNSS_FUSED),
            lastGnssSeenNs = 600_000_000L,
            nowNs = 1_000_000_000L,
            studentLoaded = false,
            navic = NavicSnapshot.NONE,
            pack = null,
            packBytes = null,
            p95Ms = null,
            integrity = snap,
        )
        assertEquals("Navigation confidence" to "93%", rows.find { it.first == "Navigation confidence" })
        assertEquals("Integrity lamp" to "High confidence", rows.find { it.first == "Integrity lamp" })
        assertNull(rows.find { it.first == "Integrity" })
        assertEquals("Tunnel" to "Tunnel 280 m ahead", rows.find { it.first == "Tunnel" })
        assertEquals("GNSS trust" to GnssTrust.GNSS_ANOMALY_COPY, rows.find { it.first == "GNSS trust" })
        assertEquals("Placement" to "passenger seat", rows.find { it.first == "Placement" })
        assertEquals("unknown", StatusCopy.placement(PhonePlacement.UNKNOWN))
        assertNull(rows.find { it.first == "SIH remain" })
        val lab = StatusCopy.of(
            state = sample(NavigationMode.GNSS_FUSED),
            lastGnssSeenNs = null,
            nowNs = 1_000_000_000L,
            studentLoaded = false,
            navic = NavicSnapshot.NONE,
            pack = null,
            packBytes = null,
            p95Ms = null,
            lab = true,
            integrity = snap,
        )
        assertTrue(lab.any { it.first == "SIH remain" })
        assertTrue(lab.any { it.first == "Blackout raw" })
        assertFalse(rows.any { it.second.contains("—") })
        assertEquals(MountPlacement.RECALIBRATE_COPY, StatusCopy.placement(PhonePlacement.HANDHELD))
    }

    private fun sample(
        mode: NavigationMode,
        flags: Set<String> = emptySet(),
    ): NavigationState = NavigationState(
        sequence = 0L,
        timestamp = Nanoseconds(1_000_000_000L),
        mode = mode,
        position = GeoPoint(LatitudeDeg(18.5362), LongitudeDeg(73.8938)),
        motion = Motion(MetresPerSecond(10.0), HeadingRadians(0.0)),
        uncertainty = Uncertainty(Metres(12.0), 0.2, isCalibrated = false),
        gnssHealth = GnssHealth(0.5, 0.4, emptySet()),
        mapMatch = MapMatch(MapMatchStatus.NO_MAP, 0.0),
        health = ComponentHealth(sensorOk = true, modelOk = false, filterOk = true, mapOk = false, flags = flags),
        provenance = Provenance(CORE_VERSION, "a".repeat(64)),
    )
}
