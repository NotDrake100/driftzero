package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.PI

class HmmRoadMatcherTest {
    @Test
    fun emptyGraphIsNoMap() {
        val matcher = HmmRoadMatcher()
        val result = matcher.update(
            RoadFixtures.atOffset(20.0, 2.0),
            RoadGraph.empty("none"),
        )
        assertEquals(MapMatchStatus.NO_MAP, result.match.status)
        assertEquals(0.0, result.match.confidence, 0.0)
        assertNull(result.displayPose)
    }

    @Test
    fun trackOnWestRoadMatchesWest() {
        val graph = RoadFixtures.parallelRoads()
        val matcher = HmmRoadMatcher()
        val states = (1..8).map { i ->
            RoadFixtures.atOffset(
                northM = i * 20.0,
                eastM = 2.0,
                timestampNs = i * 1_000_000_000L,
            )
        }
        val path = matcher.matchSequence(states, graph)
        assertEquals(8, path.size)
        path.forEach { result ->
            assertEquals(MapMatchStatus.MATCHED, result.match.status)
            assertEquals("west", result.match.roadSegmentId)
            assertTrue(result.match.confidence >= 0.55)
            assertNotNull(result.displayPose)
        }
    }

    @Test
    fun nearestOutlierDoesNotJumpToDisconnectedParallelRoad() {
        val graph = RoadFixtures.parallelRoads()
        val matcher = HmmRoadMatcher()
        val states = listOf(
            RoadFixtures.atOffset(20.0, 2.0, timestampNs = 1_000_000_000L),
            RoadFixtures.atOffset(40.0, 2.0, timestampNs = 2_000_000_000L),
            RoadFixtures.atOffset(60.0, 2.0, timestampNs = 3_000_000_000L),
            RoadFixtures.atOffset(80.0, 17.0, timestampNs = 4_000_000_000L),
            RoadFixtures.atOffset(100.0, 2.0, timestampNs = 5_000_000_000L),
            RoadFixtures.atOffset(120.0, 2.0, timestampNs = 6_000_000_000L),
        )
        val path = matcher.matchSequence(states, graph)
        val ids = path.map { it.match.roadSegmentId }
        assertTrue(ids.toString(), ids.all { it == "west" })
        val outlier = path[3]
        assertEquals("west", outlier.match.roadSegmentId)
        assertNotNull(outlier.displayPose)
        val snappedEast = outlier.displayPose!!.position.longitude.value
        val rawEast = states[3].state.position.longitude.value
        assertTrue(
            "display pose is a centerline projection, not the ESKF point",
            abs(snappedEast - rawEast) > 1e-7,
        )
    }

    @Test
    fun midCorridorWithLargeUncertaintyStaysAmbiguous() {
        val graph = RoadFixtures.parallelRoads()
        val matcher = HmmRoadMatcher()
        val states = (1..5).map { i ->
            RoadFixtures.atOffset(
                northM = i * 25.0,
                eastM = 10.0,
                headingRad = 0.0,
                horizontal95M = 40.0,
                timestampNs = i * 1_000_000_000L,
            )
        }
        val path = matcher.matchSequence(states, graph)
        val last = path.last()
        assertEquals(MapMatchStatus.AMBIGUOUS, last.match.status)
        assertTrue(last.match.confidence < 0.55)
        assertTrue(last.candidateEntropy > 0.2)
    }

    @Test
    fun headingSelectsDirectionOnTwoWayCenterline() {
        val graph = RoadFixtures.parallelRoads(bidirectional = true)
        val matcher = HmmRoadMatcher()
        val northbound = (1..6).map { i ->
            RoadFixtures.atOffset(
                northM = i * 20.0,
                eastM = 2.0,
                headingRad = 0.0,
                timestampNs = i * 1_000_000_000L,
            )
        }
        val southbound = (1..6).map { i ->
            RoadFixtures.atOffset(
                northM = 180.0 - i * 20.0,
                eastM = 2.0,
                headingRad = PI,
                timestampNs = i * 1_000_000_000L,
            )
        }
        val north = matcher.matchSequence(northbound, graph)
        matcher.reset()
        val south = matcher.matchSequence(southbound, graph)
        assertTrue(north.all { it.match.roadSegmentId == "west:fwd" })
        assertTrue(south.all { it.match.roadSegmentId == "west:rev" })
    }

    @Test
    fun farFromRoadsIsUnmatched() {
        val graph = RoadFixtures.parallelRoads()
        val matcher = HmmRoadMatcher()
        val result = matcher.update(
            RoadFixtures.atOffset(50.0, 400.0, horizontal95M = 10.0),
            graph,
        )
        assertEquals(MapMatchStatus.UNMATCHED, result.match.status)
        assertEquals(0.0, result.match.confidence, 0.0)
        assertNull(result.displayPose)
    }

    @Test
    fun displayPoseDoesNotOverwriteFilterLatLon() {
        val graph = RoadFixtures.parallelRoads()
        val matcher = HmmRoadMatcher()
        val snap = RoadFixtures.atOffset(40.0, 3.0)
        val result = matcher.update(snap, graph)
        val overlaid = snap.state.withMapMatch(result)
        assertEquals(snap.state.position.latitude.value, overlaid.position.latitude.value, 0.0)
        assertEquals(snap.state.position.longitude.value, overlaid.position.longitude.value, 0.0)
        assertEquals(snap.state.motion.heading.value, overlaid.motion.heading.value, 0.0)
        assertEquals(result.match.status, overlaid.mapMatch.status)
        assertNotEquals(MapMatchStatus.NO_MAP, overlaid.mapMatch.status)
        val display = result.displayPose
        assertNotNull(display)
        assertTrue(abs(display!!.position.longitude.value - snap.state.position.longitude.value) > 0.0)
    }

    @Test
    fun contractMapKeepsRoadSegmentIdOffThePositionObject() {
        val graph = RoadFixtures.parallelRoads()
        val matcher = HmmRoadMatcher()
        val snap = RoadFixtures.atOffset(30.0, 1.5, timestampNs = 2_000_000_000L)
        val overlaid = snap.state.withMapMatch(matcher.update(snap, graph))
        val map = ContractMaps.navigationState(overlaid)
        @Suppress("UNCHECKED_CAST")
        val position = map["position"] as Map<String, Any?>
        assertEquals(overlaid.position.latitude.value, position["latitude_deg"])
        assertEquals(overlaid.position.longitude.value, position["longitude_deg"])
        @Suppress("UNCHECKED_CAST")
        val match = map["map_match"] as Map<String, Any?>
        assertEquals(overlaid.mapMatch.roadSegmentId, match["road_segment_id"])
        assertEquals(overlaid.mapMatch.status.name, match["status"])
    }

    @Test
    fun onlineUpdateAgreesWithBatchOnCleanTrack() {
        val graph = RoadFixtures.parallelRoads()
        val states = (1..6).map { i ->
            RoadFixtures.atOffset(i * 20.0, 18.0, timestampNs = i * 1_000_000_000L)
        }
        val batch = HmmRoadMatcher().matchSequence(states, graph)
        val live = HmmRoadMatcher()
        val online = states.map { live.update(it, graph) }
        assertEquals(batch.last().match.roadSegmentId, online.last().match.roadSegmentId)
        assertEquals("east", online.last().match.roadSegmentId)
    }

    @Test
    fun twoMetreThinningDoesNotHopWhileStationary() {
        val graph = RoadFixtures.parallelRoads()
        val matcher = HmmRoadMatcher()
        val lock = (1..4).map { i ->
            RoadFixtures.atOffset(i * 20.0, 2.0, timestampNs = i * 1_000_000_000L)
        }
        matcher.matchSequence(lock, graph)
        val jitter = (1..12).map { i ->
            RoadFixtures.atOffset(
                northM = 80.0 + (i % 3) * 0.4,
                eastM = if (i == 7) 17.0 else 2.2,
                timestampNs = (4L + i) * 1_000_000_000L,
            )
        }
        val held = jitter.map { matcher.update(it, graph) }
        assertTrue(held.all { it.match.roadSegmentId == "west" })
    }

    @Test
    fun latticeBreakReacquiresOnADisconnectedRoad() {
        val graph = RoadFixtures.parallelRoads()
        val matcher = HmmRoadMatcher()
        matcher.matchSequence(
            (1..4).map { i ->
                RoadFixtures.atOffset(i * 20.0, 2.0, timestampNs = i * 1_000_000_000L)
            },
            graph,
        )
        val far = matcher.update(
            RoadFixtures.atOffset(50.0, 400.0, horizontal95M = 10.0, timestampNs = 10_000_000_000L),
            graph,
        )
        assertEquals(MapMatchStatus.UNMATCHED, far.match.status)
        val back = (1..5).map { i ->
            matcher.update(
                RoadFixtures.atOffset(
                    northM = i * 20.0,
                    eastM = 18.0,
                    timestampNs = (11L + i) * 1_000_000_000L,
                ),
                graph,
            )
        }
        assertEquals("east", back.last().match.roadSegmentId)
    }

    @Test
    fun paperSigmaEstimatorUsesMadScale() {
        val residual = HmmMatchConfig.PAPER_SIGMA_Z_M / 1.4826
        val sigma = HmmMatchConfig.sigmaFromResidualsM(List(11) { residual })
        assertEquals(HmmMatchConfig.PAPER_SIGMA_Z_M, sigma, 1e-9)
        val beta = HmmMatchConfig.betaFromDeltasM(listOf(1.386294361, 1.386294361))
        assertEquals(2.0, beta, 1e-6)
    }

    @Test
    fun puckUsesDisplayOnlyWhenMatched() {
        val graph = RoadFixtures.parallelRoads()
        val snap = RoadFixtures.atOffset(40.0, 3.0)
        val matched = snap.state.withMapMatch(HmmRoadMatcher().update(snap, graph))
        assertEquals(MapMatchStatus.MATCHED, matched.mapMatch.status)
        assertTrue(abs(matched.puckLongitudeDeg() - matched.position.longitude.value) > 0.0)
        assertEquals(matched.position.latitude.value, snap.state.position.latitude.value, 0.0)
        val ambiguous = snap.state.withMapMatch(
            MapMatchResult(
                match = MapMatch(MapMatchStatus.AMBIGUOUS, 0.4, "west"),
                displayPose = DisplayPose(
                    position = GeoPoint(LatitudeDeg(51.51), LongitudeDeg(-0.11)),
                    heading = HeadingRadians(0.0),
                    alongTrackM = 10.0,
                    crossTrackAbs = Metres(3.0),
                ),
            ),
        )
        assertEquals(ambiguous.position.latitude.value, ambiguous.puckLatitudeDeg(), 0.0)
        assertEquals(ambiguous.position.longitude.value, ambiguous.puckLongitudeDeg(), 0.0)
    }

    @Test
    fun contractMapOmitsDisplayCoordinates() {
        val graph = RoadFixtures.parallelRoads()
        val snap = RoadFixtures.atOffset(30.0, 1.5)
        val overlaid = snap.state.withMapMatch(HmmRoadMatcher().update(snap, graph))
        @Suppress("UNCHECKED_CAST")
        val match = ContractMaps.navigationState(overlaid)["map_match"] as Map<String, Any?>
        assertNull(match["display_latitude_deg"])
        assertNull(match["display_longitude_deg"])
        assertTrue(match.containsKey("road_segment_id"))
    }
}
