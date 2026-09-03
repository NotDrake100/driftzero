package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class RoadHeadingAidTest {
    @Test
    fun matchedHighPosteriorWithHeadingResidualReturnsEdgeBearing() {
        val graph = RoadFixtures.singleRoad()
        val residual = 30.0 * PI / 180.0
        val last = HmmRoadMatcher().matchSequence(
            (1..6).map { i ->
                RoadFixtures.atOffset(
                    northM = i * 20.0,
                    eastM = 0.0,
                    headingRad = residual,
                    timestampNs = i * 1_000_000_000L,
                )
            },
            graph,
        ).last()
        assertEquals(MapMatchStatus.MATCHED, last.match.status)
        assertTrue(last.bestPosterior >= 0.9)
        assertTrue(!last.nearJunction)
        val decision = RoadHeadingAid.decide(last, filterHeadingRad = residual, speedMps = 12.0)
        val prior = decision.prior
        assertNotNull(prior)
        assertNull(decision.skipReason)
        assertEquals(0.0, prior!!.edgeBearingRad, 0.05)
        assertTrue(abs(prior.edgeBearingRad - residual) > 0.4)
        assertNull(prior.alongTrackSpeedHintMps)
        assertEquals(RoadHeadingAid.MIN_STD_RAD, prior.stdRad, 1e-12)
        val innov = RoadHeadingAid.innovationRad(residual, prior.edgeBearingRad)
        assertEquals(-residual, innov, 0.05)
    }

    @Test
    fun ambiguousParallelRoadsYieldNullPrior() {
        val graph = RoadFixtures.parallelRoads(sepM = 30.0)
        val last = HmmRoadMatcher().matchSequence(
            (1..6).map { i ->
                RoadFixtures.atOffset(i * 25.0, 15.0, timestampNs = i * 1_000_000_000L)
            },
            graph,
        ).last()
        assertEquals(MapMatchStatus.AMBIGUOUS, last.match.status)
        val decision = RoadHeadingAid.decide(last, 0.0, 12.0)
        assertNull(decision.prior)
        assertEquals(RoadHeadingSkipReason.NOT_MATCHED, decision.skipReason)
    }

    @Test
    fun junctionFixtureYieldsNearJunction() {
        val graph = RoadFixtures.tJunction()
        val near = HmmRoadMatcher().update(
            RoadFixtures.atOffset(0.0, -15.0, headingRad = PI / 2.0, timestampNs = 1_000_000_000L),
            graph,
        )
        val decision = RoadHeadingAid.decide(near, PI / 2.0, 12.0)
        assertNull(decision.prior)
        assertEquals(RoadHeadingSkipReason.NEAR_JUNCTION, decision.skipReason)
    }

    @Test
    fun slowVehicleYieldsNullEvenWhenMatched() {
        val graph = RoadFixtures.singleRoad()
        val last = HmmRoadMatcher().matchSequence(
            (1..5).map { i ->
                RoadFixtures.atOffset(i * 20.0, 0.0, timestampNs = i * 1_000_000_000L)
            },
            graph,
        ).last()
        assertEquals(MapMatchStatus.MATCHED, last.match.status)
        val decision = RoadHeadingAid.decide(last, 0.0, speedMps = 0.4)
        assertNull(decision.prior)
        assertEquals(RoadHeadingSkipReason.SPEED_BELOW_MIN, decision.skipReason)
    }

    @Test
    fun unmatchedAndNoMapYieldNotMatched() {
        val unmatched = RoadHeadingAid.decide(
            MapMatchResult(MapMatch(MapMatchStatus.UNMATCHED, 0.0)),
            filterHeadingRad = 0.0,
            speedMps = 12.0,
        )
        assertEquals(RoadHeadingSkipReason.NOT_MATCHED, unmatched.skipReason)
        val noMap = RoadHeadingAid.decide(
            MapMatchResult(MapMatch(MapMatchStatus.NO_MAP, 0.0)),
            0.0,
            12.0,
        )
        assertEquals(RoadHeadingSkipReason.NOT_MATCHED, noMap.skipReason)
    }

    @Test
    fun stdShrinksWithPosteriorAndFloorsAtThreeDeg() {
        val loose = RoadHeadingAid.stdRad(0.55)
        val tight = RoadHeadingAid.stdRad(0.9)
        val floor = RoadHeadingAid.stdRad(1.0)
        assertTrue(loose > tight)
        assertEquals(RoadHeadingAid.MIN_STD_RAD, tight, 1e-12)
        assertEquals(RoadHeadingAid.MIN_STD_RAD, floor, 1e-12)
        val expectedLoose = RoadHeadingAid.STD_SCALE_RAD * (1.0 - 0.55) / 0.55
        assertEquals(expectedLoose, loose, 1e-12)
        assertEquals(3.0 * PI / 180.0, RoadHeadingAid.MIN_STD_RAD, 0.0)
    }

    @Test
    fun missingDisplayPoseIsMissingEdgeBearing() {
        val result = MapMatchResult(
            match = MapMatch(MapMatchStatus.MATCHED, 0.9, "road"),
            bestPosterior = 0.9,
            secondPosterior = 0.05,
            displayPose = null,
        )
        val decision = RoadHeadingAid.decide(result, 0.0, 12.0)
        assertNull(decision.prior)
        assertEquals(RoadHeadingSkipReason.MISSING_EDGE_BEARING, decision.skipReason)
    }
}
