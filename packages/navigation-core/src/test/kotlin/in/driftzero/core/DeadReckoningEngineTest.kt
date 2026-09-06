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
    fun replayEnginePersistAndMapHaveOneMatcherUpdatePerEpoch() {
        val matcher = FixedMatcher(matchedRoad(headingRad = 0.0))
        val graph = RoadFixtures.singleRoad()
        val frames = buildList {
            add(SensorFrame(
                sourceId = "fixture", sequence = 0L, timestamp = Nanoseconds(0L),
                clockDomain = ClockDomain.DATASET_DECLARED, kind = SensorKind.GNSS_FIX,
                quality = Quality(available = true, accuracyCode = 3),
                payload = FixPayload(GnssFixPayload(
                    latitude = LatitudeDeg(RoadFixtures.ORIGIN_LAT),
                    longitude = LongitudeDeg(RoadFixtures.ORIGIN_LON),
                    providerTimeMs = 0L,
                    horizontalAccuracyM = Metres(5.0), speedMps = MetresPerSecond(12.0),
                    bearingRad = HeadingRadians(0.0),
                )),
            ))
            for (i in 1L..10L) {
                add(accel(i * 100_000_000L))
                add(accel(i * 100_000_000L))
            }
        }
        val states = Replay.runFilter(
            frames, coastingFilter(0.0), mask = GnssMaskInterval(1L, 2_000_000_000L),
            persistSpeedPseudo = true, useEngine = true, roadGraph = graph, roadMatcher = matcher,
        )
        assertEquals(states.size, matcher.updates)
        assertEquals(states.size, states.map { it.timestamp.value }.distinct().size)
        assertTrue(states.all { it.mapMatch.status == MapMatchStatus.MATCHED })
        assertTrue(states.drop(1).all { DeadReckoningFilter.FLAG_ROAD_HEADING in it.health.flags })
    }

    @Test
    fun mapFeedbackSessionDoesNotApplySameEpochTwice() {
        val filter = coastingFilter(0.2)
        val graph = RoadFixtures.singleRoad()
        val matcher = FixedMatcher(matchedRoad(0.0))
        val session = MapCoastSession().also { it.setGraph(graph) }
        val pose = filter.poseAt(Nanoseconds(100_000_000L))!!
        val match = session.match(pose, matcher, graph)!!
        val first = session.apply(filter, match, pose, pose.timestamp.value)
        val variance = filter.horizontalVariance()
        val heading = filter.poseAt(pose.timestamp)!!.motion.heading.value
        session.match(pose, matcher, graph)
        val second = session.apply(filter, match, pose, pose.timestamp.value)
        assertEquals(1, matcher.updates)
        assertTrue(first.headingAccepted)
        assertFalse(second.headingAccepted)
        assertFalse(second.healAccepted)
        assertEquals(variance, filter.horizontalVariance(), 0.0)
        assertEquals(heading, filter.poseAt(pose.timestamp)!!.motion.heading.value, 0.0)
        session.reset()
        session.match(pose, matcher, graph)
        assertEquals(2, matcher.updates)
    }

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
        var updates = 0
        override fun update(state: FilterSnapshot, graph: RoadGraph): MapMatchResult {
            updates++
            return result
        }

        override fun reset() = Unit
    }
}
