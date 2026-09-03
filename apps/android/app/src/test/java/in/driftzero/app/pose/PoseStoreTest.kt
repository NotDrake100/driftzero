package `in`.driftzero.app.pose

import `in`.driftzero.app.ui.StatusCopy
import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.CoastMode
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.DisplacementModel
import `in`.driftzero.core.DisplacementPseudoMeasurement
import `in`.driftzero.core.DisplayPose
import `in`.driftzero.core.FilterSnapshot
import `in`.driftzero.core.GeoPoint
import `in`.driftzero.core.GraphEdge
import `in`.driftzero.core.GraphNode
import `in`.driftzero.core.HeadingRadians
import `in`.driftzero.core.ImuMotionConstants
import `in`.driftzero.core.LatitudeDeg
import `in`.driftzero.core.LongitudeDeg
import `in`.driftzero.core.MapMatch
import `in`.driftzero.core.MapMatchResult
import `in`.driftzero.core.MapMatchStatus
import `in`.driftzero.core.Metres
import `in`.driftzero.core.MotionPseudoRuntime
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.RoadGraph
import `in`.driftzero.core.RoadHeadingSkipReason
import `in`.driftzero.core.RoadMatcher
import `in`.driftzero.core.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class PoseStoreTest {
    @Test
    fun simulateGpsOffStopsIngestAndCoasts() {
        var now = 0L
        val store = PoseStore(
            filter = DeadReckoningFilter(),
            clockNs = { now },
        )
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                speedMps = 10.0,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        store.setSimulateGpsOff(true)
        now = 1_000_000_000L
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(1_000_000_000L),
                latitudeDeg = 1.0,
                longitudeDeg = 1.0,
                speedMps = 1.0,
                headingRad = 1.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        store.tick()
        val pose = store.state.value
        assertNotNull(pose)
        assertEquals(NavigationMode.DEAD_RECKONING, pose!!.mode)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_ESKF))
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_GPS_HELD))
        assertTrue(pose.health.filterOk)
        assertFalse(pose.health.flags.any { it.contains("cv_stub") })
        assertTrue(pose.position.latitude.value > 0.0)
        assertTrue(pose.position.latitude.value < 0.01)
        assertTrue(abs(pose.position.longitude.value) < 0.01)
    }

    @Test
    fun lastGnssSeenIgnoresHeldFixes() {
        var now = 0L
        val store = PoseStore(
            filter = DeadReckoningFilter(),
            clockNs = { now },
        )
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                speedMps = 10.0,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        assertEquals(0L, store.lastGnssSeenNs.value)
        assertEquals(1, store.rawTrail().size)
        store.setSimulateGpsOff(true)
        now = 2_000_000_000L
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(2_000_000_000L),
                latitudeDeg = 1.0,
                longitudeDeg = 1.0,
                speedMps = 1.0,
                headingRad = 1.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        assertEquals(0L, store.lastGnssSeenNs.value)
        assertEquals(1, store.rawTrail().size)
        assertEquals(2.0, store.holdElapsedS()!!, 1e-6)
        assertTrue(store.holdDistanceM() != null)
        assertEquals(0.0, store.lastTrustedFix.value!!.latitudeDeg, 1e-9)
        assertEquals(0.0, store.lastTrustedFix.value!!.longitudeDeg, 1e-9)
        store.tick()
        assertTrue(store.coastedDistanceM.value >= 0.0)
        assertTrue(BlackoutOverlay.ghostVisible(store.state.value!!.mode, store.lastTrustedFix.value))
    }

    @Test
    fun tickWithNoFixStaysEmpty() {
        val store = PoseStore(
            filter = DeadReckoningFilter(),
            clockNs = { 1_000_000_000L },
        )
        store.tick()
        assertNull(store.state.value)
    }

    @Test
    fun simulateGpsOffClearsNavicChip() {
        var now = 0L
        val store = PoseStore(
            filter = DeadReckoningFilter(),
            clockNs = { now },
        )
        store.navic.ingest(
            listOf(
                GnssSatRow("IRNSS", usedInFix = true),
                GnssSatRow("GPS", usedInFix = true),
            ),
        )
        assertEquals("NavIC 1", store.navic.visibility.value.chipLabel)
        store.setSimulateGpsOff(true)
        assertNull(store.navic.visibility.value.chipLabel)
    }

    @Test
    fun idleImuOnTickInjectsZuptIntoFilter() {
        var now = 0L
        val store = PoseStore(
            filter = DeadReckoningFilter(),
            clockNs = { now },
        )
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                speedMps = 10.0,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        store.setSimulateGpsOff(true)
        val g = ImuMotionConstants.GRAVITY_MPS2
        val dt = 20_000_000L
        for (i in 0 until 50) {
            now = i * dt
            val t = Nanoseconds(now)
            store.ingestAccel(t, 0.0, 0.0, g)
            store.ingestGyro(t, 0.0, 0.0, 0.0)
        }
        now = 1_000_000_000L
        store.tick()
        val pose = store.state.value
        assertNotNull(pose)
        assertTrue(pose!!.health.modelOk)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_MOTION_PSEUDO))
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_ZUPT))
        assertEquals(0.0, pose.motion.speed.value, 0.35)
    }

    @Test
    fun tickWithDisplacementStudentSetsDisplacementFlag() {
        var now = 0L
        val student = DisplacementModel { window ->
            DisplacementPseudoMeasurement(
                dxM = 5.0,
                dyM = 0.0,
                dzM = 0.0,
                logSigmaX = ln(0.4),
                logSigmaY = ln(0.4),
                logSigmaZ = ln(0.4),
                windowStart = window.samples.first().timestamp,
            )
        }
        val store = PoseStore(
            filter = DeadReckoningFilter(),
            motion = MotionPseudoRuntime(displacement = student),
            clockNs = { now },
        )
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                speedMps = 0.0,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        store.setSimulateGpsOff(true)
        val g = ImuMotionConstants.GRAVITY_MPS2
        val dt = 20_000_000L
        for (i in 0 until 50) {
            now = i * dt
            val t = Nanoseconds(now)
            store.ingestAccel(t, 0.0, 0.0, g)
            store.ingestGyro(t, 0.0, 0.0, 0.0)
        }
        now = 1_000_000_000L
        store.tick()
        val pose = store.state.value
        assertNotNull(pose)
        assertTrue(pose!!.health.flags.contains(DeadReckoningFilter.FLAG_DISPLACEMENT_PSEUDO))
        assertTrue(pose.health.modelOk)
    }

    @Test
    fun roadGraphOverlaysMatchWithoutMovingEskf() {
        var now = 0L
        val store = PoseStore(
            filter = DeadReckoningFilter(),
            clockNs = { now },
        )
        val graph = parallelFixture()
        store.setRoadGraph(graph)
        val origin = graph.nodes.values.first()
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = origin.latitude.value,
                longitudeDeg = origin.longitude.value,
                speedMps = 12.0,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        store.tick()
        val pose = store.state.value
        assertNotNull(pose)
        assertEquals(origin.latitude.value, pose!!.position.latitude.value, 1e-5)
        assertEquals(origin.longitude.value, pose.position.longitude.value, 1e-5)
        assertTrue(pose.mapMatch.status != MapMatchStatus.NO_MAP)
        assertTrue(pose.mapMatch.displayLatitudeDeg != null || pose.mapMatch.status == MapMatchStatus.UNMATCHED)
    }

    @Test
    fun gnssAfterThirtySecondGapReacquiresFarFix() {
        var now = 0L
        val store = PoseStore(
            filter = DeadReckoningFilter(),
            clockNs = { now },
        )
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                speedMps = 8.3,
                headingRad = 1.5707963267948966,
                horizontalAccuracyM = 5.0,
            ),
        )
        assertEquals(NavigationMode.GNSS_FUSED, store.state.value!!.mode)
        // 0.1 s steps stay under maxIntegrateS so P does not get the
        // large-gap inflate. Matches the phone 10 Hz tick during a tunnel.
        repeat(300) {
            now += 100_000_000L
            store.tick()
        }
        assertEquals(NavigationMode.DEAD_RECKONING, store.state.value!!.mode)
        assertTrue(store.state.value!!.gnssHealth.lastTrustedFixAgeS > DeadReckoningFilter.STALE_AFTER_S)
        val resume = Wgs84.offsetMetres(0.0, 0.0, 0.0, 258.0)
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(now),
                latitudeDeg = resume.first,
                longitudeDeg = resume.second,
                speedMps = 8.3,
                headingRad = 1.5707963267948966,
                horizontalAccuracyM = 5.0,
            ),
        )
        val pose = store.state.value!!
        assertTrue(
            "expected live GNSS after gap, was ${pose.mode}",
            pose.mode == NavigationMode.REACQUIRING ||
                pose.mode == NavigationMode.GNSS_FUSED ||
                pose.mode == NavigationMode.GNSS_DEGRADED,
        )
        assertTrue(pose.gnssHealth.lastTrustedFixAgeS < DeadReckoningFilter.STALE_AFTER_S)
    }

    @Test
    fun gnssTeleportAfterHeldLastFixReacquires() {
        var now = 0L
        val store = PoseStore(
            filter = DeadReckoningFilter(),
            clockNs = { now },
        )
        fun fix(lat: Double, lon: Double): CoastFix = CoastFix(
            timestamp = Nanoseconds(now),
            latitudeDeg = lat,
            longitudeDeg = lon,
            speedMps = 8.3,
            headingRad = 1.5707963267948966,
            horizontalAccuracyM = 5.0,
        )
        store.ingestGnss(fix(0.0, 0.0))
        assertEquals(NavigationMode.GNSS_FUSED, store.state.value!!.mode)
        // Emulator geo gap keeps publishing the last Location. Age stays
        // fresh, so the stale-only inflate does not run.
        repeat(30) {
            now += 1_000_000_000L
            store.ingestGnss(fix(0.0, 0.0))
            store.tick()
        }
        assertTrue(store.state.value!!.gnssHealth.lastTrustedFixAgeS <= DeadReckoningFilter.STALE_AFTER_S)
        val resume = Wgs84.offsetMetres(0.0, 0.0, 0.0, 258.0)
        now += 1_000_000_000L
        store.ingestGnss(fix(resume.first, resume.second))
        val pose = store.state.value!!
        assertTrue(
            "expected live GNSS after held-fix jump, was ${pose.mode}",
            pose.mode == NavigationMode.REACQUIRING ||
                pose.mode == NavigationMode.GNSS_FUSED ||
                pose.mode == NavigationMode.GNSS_DEGRADED,
        )
        assertTrue(pose.gnssHealth.lastTrustedFixAgeS < DeadReckoningFilter.STALE_AFTER_S)
    }

    @Test
    fun liveFilterConfigIsYawSpeedHold() {
        assertEquals(CoastMode.YAW_SPEED_HOLD, PoseStore.LIVE_INS_CONFIG.coastMode)
        assertEquals(PoseStore.LIVE_STALE_AFTER_S, PoseStore.LIVE_INS_CONFIG.staleAfterS, 1e-9)
        val filter = PoseStore.liveFilter()
        assertNotNull(filter)
        var now = 0L
        val store = PoseStore(filter = PoseStore.liveFilter(), clockNs = { now })
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                speedMps = 10.0,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        store.setSimulateGpsOff(true)
        now = 1_000_000_000L
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(1_000_000_000L),
                latitudeDeg = 1.0,
                longitudeDeg = 1.0,
                speedMps = 1.0,
                headingRad = 1.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        store.tick()
        val pose = store.state.value
        assertNotNull(pose)
        assertEquals(NavigationMode.DEAD_RECKONING, pose!!.mode)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_GPS_HELD))
        assertTrue(pose.position.latitude.value > 0.0)
        assertTrue(pose.position.latitude.value < 0.01)
    }

    @Test
    fun roadAidOnWhenMatcherReturnsPriorWhileHeld() {
        var now = 0L
        val matcher = ScriptedMatcher(matchedResult(nearJunction = false))
        val store = PoseStore(
            filter = PoseStore.liveFilter(),
            clockNs = { now },
            matcher = matcher,
            graph = parallelFixture(),
        )
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                speedMps = 12.0,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        store.setSimulateGpsOff(true)
        now = 1_000_000_000L
        store.tick()
        val decision = store.roadDecision.value
        assertNotNull(decision)
        assertNotNull(decision!!.prior)
        assertEquals(StatusCopy.RoadAidState.ON, StatusCopy.roadAid(decision))
    }

    @Test
    fun roadAidOffNearJunctionWhileHeld() {
        var now = 0L
        val matcher = ScriptedMatcher(matchedResult(nearJunction = true))
        val store = PoseStore(
            filter = PoseStore.liveFilter(),
            clockNs = { now },
            matcher = matcher,
            graph = parallelFixture(),
        )
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                speedMps = 12.0,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        store.setSimulateGpsOff(true)
        now = 1_000_000_000L
        store.tick()
        val decision = store.roadDecision.value
        assertNotNull(decision)
        assertNull(decision!!.prior)
        assertEquals(RoadHeadingSkipReason.NEAR_JUNCTION, decision.skipReason)
        assertEquals(StatusCopy.RoadAidState.OFF_NEAR_JUNCTION, StatusCopy.roadAid(decision))
    }

    @Test
    fun yawConfidenceReadsProfileJson() {
        assertNull(PoseStore.yawConfidenceFromProfileJson(null))
        assertNull(PoseStore.yawConfidenceFromProfileJson("{}"))
        assertEquals(0.91, PoseStore.yawConfidenceFromProfileJson("""{"yaw_confidence":0.91}""")!!, 1e-9)
        assertNull(PoseStore.yawConfidenceFromProfileJson("""{"yaw_confidence":1.4}"""))
    }

    @Test
    fun stillImuRejectsJumpingGnssAndHoldsSpeed() {
        var now = 0L
        val store = PoseStore(filter = PoseStore.liveFilter(), clockNs = { now })
        val g = ImuMotionConstants.GRAVITY_MPS2
        val dt = 20_000_000L
        for (i in 0..70) {
            now = i * dt
            val t = Nanoseconds(now)
            store.ingestAccel(t, 0.0, 0.0, g)
            store.ingestGyro(t, 0.0, 0.0, 0.0)
            if (i % 5 == 0) {
                store.tick()
            }
        }
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(now),
                latitudeDeg = 18.52,
                longitudeDeg = 73.85,
                speedMps = 6.4,
                headingRad = 1.0,
                horizontalAccuracyM = 16.0,
            ),
        )
        store.tick()
        val origin = store.state.value!!
        assertEquals(0.0, origin.motion.speed.value, 0.35)
        val jump = Wgs84.offsetMetres(18.52, 73.85, 12.0, 0.0)
        repeat(5) { step ->
            for (i in 1..50) {
                now += dt
                val t = Nanoseconds(now)
                store.ingestAccel(t, 0.0, 0.0, g)
                store.ingestGyro(t, 0.0, 0.0, 0.0)
                if (i % 5 == 0) {
                    store.tick()
                }
            }
            store.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(now),
                    latitudeDeg = jump.first,
                    longitudeDeg = jump.second,
                    speedMps = 6.4,
                    headingRad = 1.0,
                    horizontalAccuracyM = 22.0,
                ),
            )
            store.tick()
            val pose = store.state.value!!
            assertEquals("speed after jump $step", 0.0, pose.motion.speed.value, 0.35)
            val moved = Wgs84.distanceMetres(
                origin.position.latitude.value,
                origin.position.longitude.value,
                pose.position.latitude.value,
                pose.position.longitude.value,
            )
            assertTrue("position drifted ${moved}m after jump $step", moved < 3.0)
            assertTrue(pose.mode != NavigationMode.DEAD_RECKONING)
            assertTrue(pose.mode != NavigationMode.REACQUIRING)
        }
    }

    @Test
    fun drivingImuDoesNotFireStillZupt() {
        var now = 0L
        val store = PoseStore(filter = PoseStore.liveFilter(), clockNs = { now })
        val g = ImuMotionConstants.GRAVITY_MPS2
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 18.52,
                longitudeDeg = 73.85,
                speedMps = 10.0,
                headingRad = 1.5707963267948966,
                horizontalAccuracyM = 5.0,
            ),
        )
        val dt = 20_000_000L
        for (i in 1..70) {
            now = i * dt
            val t = Nanoseconds(now)
            val wobble = if (i % 2 == 0) 2.0 else -2.0
            store.ingestAccel(t, wobble, 0.4, g + wobble)
            store.ingestGyro(t, 0.15, 0.1, 0.35)
            if (i % 5 == 0) {
                val moved = Wgs84.offsetMetres(18.52, 73.85, 0.0, 10.0 * (now / 1_000_000_000.0))
                store.ingestGnss(
                    CoastFix(
                        timestamp = Nanoseconds(now),
                        latitudeDeg = moved.first,
                        longitudeDeg = moved.second,
                        speedMps = 10.0,
                        headingRad = 1.5707963267948966,
                        horizontalAccuracyM = 5.0,
                    ),
                )
                store.tick()
            }
        }
        val pose = store.state.value!!
        assertTrue("driving speed was ${pose.motion.speed.value}", pose.motion.speed.value > 3.0)
        val travelled = Wgs84.distanceMetres(
            18.52,
            73.85,
            pose.position.latitude.value,
            pose.position.longitude.value,
        )
        assertTrue("driving should move, travelled ${travelled}m", travelled > 5.0)
    }

    @Test
    fun liveFourSecondGapIsNotDeadReckoning() {
        var now = 0L
        val store = PoseStore(filter = PoseStore.liveFilter(), clockNs = { now })
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 18.52,
                longitudeDeg = 73.85,
                speedMps = 4.0,
                headingRad = 0.0,
                horizontalAccuracyM = 8.0,
            ),
        )
        now = 4_000_000_000L
        store.tick()
        val mid = store.state.value!!
        assertTrue(mid.mode != NavigationMode.DEAD_RECKONING)
        assertTrue(mid.mode != NavigationMode.REACQUIRING)
        now = 9_000_000_000L
        store.tick()
        assertEquals(NavigationMode.DEAD_RECKONING, store.state.value!!.mode)
    }

    private fun matchedResult(nearJunction: Boolean): MapMatchResult =
        MapMatchResult(
            match = MapMatch(MapMatchStatus.MATCHED, 0.95, roadSegmentId = "road"),
            displayPose = DisplayPose(
                position = GeoPoint(LatitudeDeg(0.0), LongitudeDeg(0.0)),
                heading = HeadingRadians(0.0),
                alongTrackM = 10.0,
                crossTrackAbs = Metres(0.5),
            ),
            bestPosterior = 0.95,
            secondPosterior = 0.05,
            nearJunction = nearJunction,
        )

    private class ScriptedMatcher(
        private val result: MapMatchResult,
    ) : RoadMatcher {
        override fun update(state: FilterSnapshot, graph: RoadGraph): MapMatchResult = result

        override fun reset() = Unit
    }

    private fun parallelFixture(): RoadGraph {
        fun edge(id: String, from: Long, to: Long, eastM: Double): GraphEdge {
            val start = Wgs84.offsetMetres(51.5, -0.12, 0.0, eastM)
            val end = Wgs84.offsetMetres(51.5, -0.12, 200.0, eastM)
            val points = listOf(
                GeoPoint(LatitudeDeg(start.first), LongitudeDeg(start.second)),
                GeoPoint(LatitudeDeg(end.first), LongitudeDeg(end.second)),
            )
            return GraphEdge(id, from, to, points, 200.0, doubleArrayOf(0.0), "residential")
        }
        val west = edge("west", 1L, 2L, 0.0)
        val east = edge("east", 3L, 4L, 20.0)
        val nodes = listOf(west, east).flatMap { e ->
            listOf(
                GraphNode(e.fromNodeId, e.points.first().latitude, e.points.first().longitude),
                GraphNode(e.toNodeId, e.points.last().latitude, e.points.last().longitude),
            )
        }.associateBy { it.id }
        return RoadGraph("fixture-parallel", nodes, listOf(west, east))
    }
}
