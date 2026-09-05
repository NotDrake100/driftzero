package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * Fixture graph only. Not an IO-VNBD or SIH screening row.
 */
class MapCoastConstraintTest {
    @Test
    fun fixtureEdgesNearFindsTheLoadedRoad() {
        val graph = RoadFixtures.singleRoad()
        val mid = Wgs84.offsetMetres(RoadFixtures.ORIGIN_LAT, RoadFixtures.ORIGIN_LON, 40.0, 0.0)
        val near = graph.edgesNear(mid.first, mid.second, 20.0)
        assertEquals(listOf("road"), near.map { it.id })
        assertTrue(RoadGraph.empty().edgesNear(0.0, 0.0, 20.0).isEmpty())
    }

    @Test
    fun noMapDoesNotInflateOrHeal() {
        val action = MapCoastConstraint.decide(
            match = MapMatchResult(MapMatch(MapMatchStatus.NO_MAP, 0.0)),
            headingRad = 0.0,
            speedMps = 12.0,
            observed = null,
            candidates = emptyList(),
        )
        assertEquals(RoadHeadingSkipReason.NOT_MATCHED, action.heading.skipReason)
        assertNull(action.heal)
        assertFalse(action.unconstrained)
    }

    @Test
    fun unmatchedLoadedGraphLeavesUnconstrained() {
        val action = MapCoastConstraint.decide(
            match = MapMatchResult(MapMatch(MapMatchStatus.UNMATCHED, 0.0)),
            headingRad = 0.0,
            speedMps = 12.0,
            observed = distinctiveObserved(),
            candidates = listOf(RoadDna.fromEdge(RoadFixtures.rightAngleRoad().edges.first())),
        )
        assertNull(action.heading.prior)
        assertNull(action.heal)
        assertTrue(action.unconstrained)
    }

    @Test
    fun ambiguousParallelFixtureDoesNotHeal() {
        val graph = RoadFixtures.parallelRoads(sepM = 30.0)
        val last = HmmRoadMatcher().matchSequence(
            (1..6).map { i ->
                RoadFixtures.atOffset(i * 25.0, 15.0, timestampNs = i * 1_000_000_000L)
            },
            graph,
        ).last()
        assertEquals(MapMatchStatus.AMBIGUOUS, last.match.status)
        val action = MapCoastConstraint.decide(
            match = last,
            headingRad = 0.0,
            speedMps = 12.0,
            observed = distinctiveObserved(),
            candidates = graph.edges.map { RoadDna.fromEdge(it) },
        )
        assertNull(action.heading.prior)
        assertNull(action.heal)
        assertTrue(action.unconstrained)
    }

    @Test
    fun matchedFixtureMayRequestHeadingAndAlongTrackHeal() {
        val graph = RoadFixtures.rightAngleRoad()
        val edge = graph.edges.first()
        val match = matchedElbow(graph)
        val action = MapCoastConstraint.decide(
            match = match,
            headingRad = PI / 2.0,
            speedMps = 12.0,
            observed = distinctiveObserved(),
            candidates = listOf(RoadDna.fromEdge(edge)),
        )
        assertNotNull(action.heading.prior)
        assertNotNull(action.heal)
        assertFalse(action.unconstrained)
        assertTrue(action.heal!!.offsetM < 0.0)
    }

    @Test
    fun sessionHealOnFixtureRightAngleIsAlongTrackOnly() {
        val originLat = RoadFixtures.ORIGIN_LAT
        val originLon = RoadFixtures.ORIGIN_LON
        val graph = RoadFixtures.rightAngleRoad(northM = 1270.0, eastM = 200.0)
        val filter = DeadReckoningFilter(
            InsConfig(nhcMinSpeedMps = 100.0, lowConfidenceRadiusM = 10_000.0),
        )
        val (trueLat, trueLon) = Wgs84.offsetMetres(originLat, originLon, 1270.0, 0.0)
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = originLat,
            longitudeDeg = originLon,
            velocityEnu = Vec3(12.0, 0.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 25.0,
            headingRad = PI / 2.0,
        )
        filter.plantEnuForTest(position = Vec3(50.0, 1270.0, 0.0), velocity = Vec3(12.0, 0.0, 0.0))
        filter.setGnssHeld(true)
        val session = MapCoastSession()
        session.setGraph(graph)
        val start = Wgs84.offsetMetres(originLat, originLon, 0.0, 0.0)
        val mid = Wgs84.offsetMetres(originLat, originLon, 500.0, 0.0)
        val late = Wgs84.offsetMetres(originLat, originLon, 1320.0, 0.0)
        val afterTurn = Wgs84.offsetMetres(originLat, originLon, 1320.0, 20.0)
        session.observe(start.first, start.second, 0.0)
        session.observe(mid.first, mid.second, 0.0)
        session.observe(late.first, late.second, 0.0)
        session.observe(afterTurn.first, afterTurn.second, PI / 2.0)
        val pose = filter.poseAt(Nanoseconds(0L))!!
        val before = PosePoint(pose.position.latitude.value, pose.position.longitude.value)
        val report = session.apply(filter, matchedElbow(graph), pose, nowNs = 0L)
        assertTrue("fixture heal ${report.healAccepted} heading=${report.headingAccepted}", report.healAccepted)
        assertFalse(report.action.unconstrained)
        val after = filter.poseAt(Nanoseconds(100_000_000L))!!
        assertTrue(after.health.flags.contains(DeadReckoningFilter.FLAG_ALONG_TRACK))
        val err = Wgs84.distanceMetres(
            after.position.latitude.value,
            after.position.longitude.value,
            trueLat,
            trueLon,
        )
        val gate = 0.10 * 1320.0
        assertTrue(
            "fixture proof: error $err m must be < 10% of 1320 m ($gate). Not an IO-VNBD claim.",
            err < gate,
        )
        val sideways = abs(after.position.latitude.value - trueLat) * 111_320.0
        assertTrue("must not jump off the road, north residual $sideways", sideways < 15.0)
        val movedNorth = abs(after.position.latitude.value - before.latitudeDeg) * 111_320.0
        assertTrue("along-track only, north move $movedNorth", movedNorth < 15.0)
    }

    @Test
    fun noteMapUnconstrainedGrowsHaloAndDoesNotMovePose() {
        val filter = DeadReckoningFilter(
            InsConfig(nhcMinSpeedMps = 100.0, lowConfidenceRadiusM = 10_000.0),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 10.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            headingRad = 0.0,
        )
        filter.setGnssHeld(true)
        val before = filter.poseAt(Nanoseconds(0L))!!
        val p0 = filter.horizontalVariance()
        assertTrue(filter.noteMapUnconstrained())
        val after = filter.poseAt(Nanoseconds(0L))!!
        assertEquals(before.position.latitude.value, after.position.latitude.value, 1e-12)
        assertEquals(before.position.longitude.value, after.position.longitude.value, 1e-12)
        assertTrue(filter.horizontalVariance() > p0)
        assertTrue(after.uncertainty.heading95Rad > before.uncertainty.heading95Rad)
        assertTrue(after.health.flags.contains(DeadReckoningFilter.FLAG_MAP_UNCONSTRAINED))
        assertFalse(after.health.flags.contains(DeadReckoningFilter.FLAG_ALONG_TRACK))
    }

    @Test
    fun sessionDoesNotHealWhenUnmatched() {
        val graph = RoadFixtures.rightAngleRoad()
        val filter = DeadReckoningFilter(
            InsConfig(nhcMinSpeedMps = 100.0, lowConfidenceRadiusM = 10_000.0),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = RoadFixtures.ORIGIN_LAT,
            longitudeDeg = RoadFixtures.ORIGIN_LON,
            velocityEnu = Vec3(0.0, 12.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 8.0,
            headingRad = 0.0,
        )
        filter.setGnssHeld(true)
        val session = MapCoastSession()
        session.setGraph(graph)
        val start = filter.positionEnu()
        val p0 = filter.horizontalVariance()
        val pose = filter.poseAt(Nanoseconds(0L))!!
        val report = session.apply(
            filter,
            MapMatchResult(MapMatch(MapMatchStatus.UNMATCHED, 0.0)),
            pose,
            nowNs = 0L,
        )
        assertTrue(report.action.unconstrained)
        assertTrue(report.inflated)
        assertFalse(report.healAccepted)
        assertFalse(report.headingAccepted)
        val after = filter.positionEnu()
        assertEquals(start.x, after.x, 1e-9)
        assertEquals(start.y, after.y, 1e-9)
        assertTrue(filter.horizontalVariance() > p0)
    }

    private fun distinctiveObserved(): RoadDnaSignature = RoadDna.extract(
        headingsRad = doubleArrayOf(0.0, 0.0, 0.0, PI / 2.0),
        distancesM = doubleArrayOf(500.0, 500.0, 320.0, 20.0),
    )

    private fun matchedElbow(graph: RoadGraph): MapMatchResult {
        val edge = graph.edges.first()
        return MapMatchResult(
            match = MapMatch(MapMatchStatus.MATCHED, 0.95, roadSegmentId = edge.id),
            displayPose = DisplayPose(
                position = edge.points.first(),
                heading = HeadingRadians(PI / 2.0),
                alongTrackM = 1270.0,
                crossTrackAbs = Metres(2.0),
            ),
            bestPosterior = 0.95,
            secondPosterior = 0.05,
            packageId = graph.packageId,
        )
    }

    private data class PosePoint(val latitudeDeg: Double, val longitudeDeg: Double)
}
