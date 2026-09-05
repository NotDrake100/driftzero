package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * Fixture graph on [DeadReckoningEngine]. Official Replay constructs the
 * engine without a graph. Not an IO-VNBD row.
 */
class DeadReckoningEngineTest {
    @Test
    fun engineWithoutGraphDoesNotSetRoadHeading() {
        val filter = coastingFilter(headingRad = 30.0 * PI / 180.0)
        val engine = DeadReckoningEngine(filter)
        val pose = engine.ingestForReplay(accel(100_000_000L))
        assertNotNull(pose)
        assertFalse(pose!!.health.flags.contains(DeadReckoningFilter.FLAG_ROAD_HEADING))
        assertFalse(pose.health.flags.contains(DeadReckoningFilter.FLAG_MAP_UNCONSTRAINED))
        assertEquals(MapMatchStatus.NO_MAP, pose.mapMatch.status)
    }

    @Test
    fun fixtureMatchedGraphAppliesHeadingWhileHeld() {
        val residual = 25.0 * PI / 180.0
        val filter = coastingFilter(headingRad = residual)
        val graph = RoadFixtures.singleRoad()
        val engine = DeadReckoningEngine(
            filter = filter,
            matcher = FixedMatcher(matchedRoad(headingRad = 0.0)),
            graph = graph,
        )
        val start = filter.positionEnu()
        val pose = engine.ingestForReplay(accel(100_000_000L))
        assertNotNull(pose)
        assertEquals(MapMatchStatus.MATCHED, pose!!.mapMatch.status)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_ROAD_HEADING))
        assertTrue(
            "heading should move toward edge, was ${pose.motion.heading.value}",
            abs(pose.motion.heading.value) < residual,
        )
        val after = filter.positionEnu()
        assertEquals("road heading must not snap cross-track", start.x, after.x, 1e-6)
        assertTrue("100 ms coast may advance along track, north=${after.y}", after.y >= start.y - 1e-6)
    }

    @Test
    fun fixtureUnmatchedGraphInflatesAndDoesNotSnap() {
        val filter = coastingFilter(headingRad = 0.0)
        val engine = DeadReckoningEngine(
            filter = filter,
            matcher = FixedMatcher(
                MapMatchResult(MapMatch(MapMatchStatus.UNMATCHED, 0.0), packageId = "fixture-single"),
            ),
            graph = RoadFixtures.singleRoad(),
        )
        val start = filter.positionEnu()
        val p0 = filter.horizontalVariance()
        val pose = engine.ingestForReplay(accel(100_000_000L))
        assertNotNull(pose)
        assertEquals(MapMatchStatus.UNMATCHED, pose!!.mapMatch.status)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_MAP_UNCONSTRAINED))
        assertFalse(pose.health.flags.contains(DeadReckoningFilter.FLAG_ALONG_TRACK))
        assertTrue(filter.horizontalVariance() > p0)
        val after = filter.positionEnu()
        assertEquals("unmatched must not snap east", start.x, after.x, 1e-6)
        assertTrue("100 ms coast may advance along track, north=${after.y}", after.y >= start.y - 1e-6)
    }

    private fun coastingFilter(headingRad: Double): DeadReckoningFilter {
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = RoadFixtures.ORIGIN_LAT,
            longitudeDeg = RoadFixtures.ORIGIN_LON,
            velocityEnu = Vec3(0.0, 12.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            headingRad = headingRad,
        )
        filter.setGnssHeld(true)
        return filter
    }

    private fun accel(tNs: Long): SensorFrame {
        val g = Wgs84.gravityMps2(RoadFixtures.ORIGIN_LAT)
        return SensorFrame(
            sourceId = "fixture",
            sequence = 1L,
            timestamp = Nanoseconds(tNs),
            clockDomain = ClockDomain.DATASET_DECLARED,
            kind = SensorKind.ACCELEROMETER,
            quality = Quality(available = true, accuracyCode = 3),
            payload = VectorPayload(
                Vector3Payload(0.0, 0.0, g, "m/s^2", VectorFrame.UNSPECIFIED),
            ),
        )
    }

    private fun matchedRoad(headingRad: Double): MapMatchResult = MapMatchResult(
        match = MapMatch(MapMatchStatus.MATCHED, 0.95, roadSegmentId = "road"),
        displayPose = DisplayPose(
            position = GeoPoint(LatitudeDeg(RoadFixtures.ORIGIN_LAT), LongitudeDeg(RoadFixtures.ORIGIN_LON)),
            heading = HeadingRadians(wrapHeadingRad(headingRad)),
            alongTrackM = 10.0,
            crossTrackAbs = Metres(0.5),
        ),
        bestPosterior = 0.95,
        secondPosterior = 0.05,
    )

    private class FixedMatcher(
        private val result: MapMatchResult,
    ) : RoadMatcher {
        override fun update(state: FilterSnapshot, graph: RoadGraph): MapMatchResult = result

        override fun reset() = Unit
    }
}
