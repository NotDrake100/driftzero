package `in`.driftzero.app.pose

import `in`.driftzero.app.ui.StatusCopy
import `in`.driftzero.core.BlackoutRisk
import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.CoastMode
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.OptionalScalar
import `in`.driftzero.core.DisplacementModel
import `in`.driftzero.core.DisplacementPseudoMeasurement
import `in`.driftzero.core.DisplayPose
import `in`.driftzero.core.FilterSnapshot
import `in`.driftzero.core.GeoPoint
import `in`.driftzero.core.GraphEdge
import `in`.driftzero.core.GraphNode
import `in`.driftzero.core.HeadingRadians
import `in`.driftzero.core.ImuMotionConstants
import `in`.driftzero.core.InsConfig
import `in`.driftzero.core.LatitudeDeg
import `in`.driftzero.core.LongitudeDeg
import `in`.driftzero.core.MapCoastSession
import `in`.driftzero.core.MapMatch
import `in`.driftzero.core.MapMatchResult
import `in`.driftzero.core.MapMatchStatus
import `in`.driftzero.core.Metres
import `in`.driftzero.core.MotionPseudoRuntime
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.RoadGraph
import `in`.driftzero.core.RoadHeadingSkipReason
import `in`.driftzero.core.ShadowMap
import `in`.driftzero.core.ShadowMapStore
import `in`.driftzero.core.RoadMatcher
import `in`.driftzero.core.Wgs84
import `in`.driftzero.core.wrapHeadingRad
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
        assertTrue(PoseStore.LIVE_INS_CONFIG.coastHonestP)
        assertTrue(PoseStore.LIVE_INS_CONFIG.studentForwardSpeed)
        assertTrue(PoseStore.LIVE_INS_CONFIG.coastLatchGnssSpeed)
        assertTrue(PoseStore.LIVE_INS_CONFIG.gnssQuarantine)
        assertFalse(InsConfig().gnssQuarantine)
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
    fun unmatchedLoadedGraphInflatesUncertaintyWhileHeld() {
        var now = 0L
        val matcher = ScriptedMatcher(
            MapMatchResult(MapMatch(MapMatchStatus.UNMATCHED, 0.0), packageId = "fixture-parallel"),
        )
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
        val before = store.state.value!!.uncertainty.horizontal95.value
        store.setSimulateGpsOff(true)
        now = 1_000_000_000L
        store.tick()
        val pose = store.state.value!!
        assertEquals(MapMatchStatus.UNMATCHED, pose.mapMatch.status)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_MAP_UNCONSTRAINED))
        assertFalse(pose.health.flags.contains(DeadReckoningFilter.FLAG_ALONG_TRACK))
        assertNull(store.roadDecision.value!!.prior)
        assertTrue(
            "unmatched coast should grow the halo, before=$before after=${pose.uncertainty.horizontal95.value}",
            pose.uncertainty.horizontal95.value > before,
        )
    }

    @Test
    fun replayCoastAppliesHeadingAidWhenMatched() {
        var now = 0L
        val store = PoseStore(
            filter = PoseStore.liveFilter(),
            clockNs = { now },
            matcher = ScriptedMatcher(matchedResult(nearJunction = false)),
            graph = parallelFixture(),
        )
        store.beginReplay()
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
        store.tickAt(Nanoseconds(now))
        val decision = store.roadDecision.value
        assertNotNull(decision)
        assertNotNull(decision!!.prior)
        assertEquals(StatusCopy.RoadAidState.ON, StatusCopy.roadAid(decision))
        assertTrue(store.state.value!!.health.flags.contains(DeadReckoningFilter.FLAG_ROAD_HEADING))
    }

    @Test
    fun roadDnaHealOnLiveCoastUsesFixtureSession() {
        var now = 0L
        val session = MapCoastSession()
        val graph = rightAngleFixture()
        val store = PoseStore(
            filter = PoseStore.liveFilter(),
            clockNs = { now },
            matcher = ScriptedMatcher(matchedElbow(graph)),
            graph = graph,
            mapCoast = session,
        )
        val originLat = 51.5
        val originLon = -0.12
        val start = Wgs84.offsetMetres(originLat, originLon, 0.0, 0.0)
        val mid = Wgs84.offsetMetres(originLat, originLon, 500.0, 0.0)
        val late = Wgs84.offsetMetres(originLat, originLon, 1320.0, 0.0)
        val afterTurn = Wgs84.offsetMetres(originLat, originLon, 1320.0, 20.0)
        session.observe(start.first, start.second, 0.0)
        session.observe(mid.first, mid.second, 0.0)
        session.observe(late.first, late.second, 0.0)
        session.observe(afterTurn.first, afterTurn.second, 1.5707963267948966)
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = originLat,
                longitudeDeg = originLon,
                speedMps = 12.0,
                headingRad = 1.5707963267948966,
                horizontalAccuracyM = 8.0,
            ),
        )
        store.setSimulateGpsOff(true)
        now = 1_000_000_000L
        store.tick()
        val flags = store.state.value!!.health.flags
        assertTrue(
            "fixture live path should accept heading or along-track, flags=$flags",
            flags.contains(DeadReckoningFilter.FLAG_ROAD_HEADING) ||
                flags.contains(DeadReckoningFilter.FLAG_ALONG_TRACK),
        )
        assertFalse(flags.contains(DeadReckoningFilter.FLAG_MAP_UNCONSTRAINED))
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
    fun offerImuDrainsOnTickNotOnCallback() {
        var now = 0L
        val store = PoseStore(filter = PoseStore.liveFilter(), clockNs = { now })
        val g = ImuMotionConstants.GRAVITY_MPS2
        store.offerImu(QueuedImuKind.ACCEL, Nanoseconds(0L), 0.0, 0.0, g)
        store.offerImu(QueuedImuKind.GYRO, Nanoseconds(0L), 0.0, 0.0, 0.0)
        assertNull(store.state.value)
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
        now = 100_000_000L
        store.tick()
        assertNotNull(store.state.value)
    }

    @Test
    fun loggedGravityDoesNotEnterFilterAsAccel() {
        var now = 0L
        val store = PoseStore(filter = PoseStore.liveFilter(), clockNs = { now })
        val g = ImuMotionConstants.GRAVITY_MPS2
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 18.52,
                longitudeDeg = 73.85,
                speedMps = 0.0,
                headingRad = 0.0,
                horizontalAccuracyM = 8.0,
            ),
        )
        now = 1_000_000_000L
        store.tick()
        val start = store.state.value!!
        var i = 0
        while (i < 20) {
            now = 1_000_000_000L + i * 100_000_000L
            store.offerImu(QueuedImuKind.GRAVITY, Nanoseconds(now), 40.0, 0.0, g)
            store.offerImu(QueuedImuKind.ACCEL, Nanoseconds(now), 0.0, 0.0, g)
            store.offerImu(QueuedImuKind.GYRO, Nanoseconds(now), 0.0, 0.0, 0.0)
            store.tick()
            i += 1
        }
        val end = store.state.value!!
        val moved = Wgs84.distanceMetres(
            start.position.latitude.value,
            start.position.longitude.value,
            end.position.latitude.value,
            end.position.longitude.value,
        )
        assertTrue("gravity log-only must not accelerate, moved ${moved}m", moved < 8.0)
    }

    @Test
    fun shadowJsonIsPendingNotWrittenOnPublish() {
        val dir = java.nio.file.Files.createTempDirectory("dz-shadow").toFile()
        val file = java.io.File(dir, "shadow_map.json")
        var now = 0L
        val store = PoseStore(
            filter = PoseStore.liveFilter(),
            clockNs = { now },
            shadowStore = `in`.driftzero.core.ShadowMapStore(file),
        )
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
        store.tick()
        assertFalse(file.exists())
        val json = store.takePendingShadowJson()
        assertNotNull(json)
        assertTrue(json!!.contains("shadow_map_1.0.0"))
        store.writeShadowJson(json)
        assertTrue(file.exists())
        dir.deleteRecursively()
    }

    @Test
    fun highBlackoutRiskArmsLiveCoastLatch() {
        var now = 0L
        val store = PoseStore(filter = PoseStore.liveFilter(), clockNs = { now })
        store.navic.ingest(
            listOf(
                GnssSatRow("GPS", usedInFix = true, cn0DbHz = 18.0),
                GnssSatRow("GPS", usedInFix = true, cn0DbHz = 18.0),
                GnssSatRow("GPS", usedInFix = true, cn0DbHz = 18.0),
            ),
        )
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 18.52,
                longitudeDeg = 73.85,
                speedMps = 12.0,
                headingRad = 0.0,
                horizontalAccuracyM = 40.0,
            ),
        )
        val snap = store.integrity.value
        assertNotNull(snap)
        assertNotNull(snap!!.blackout)
        assertTrue(snap.blackout!!.preconditioning)
        assertTrue(store.coastPreconditionArmed())
        now = 5_000_000_000L
        store.tick()
        assertTrue(store.coastPreconditionArmed())
    }

    @Test
    fun tunnelLookAheadArmsCoastWhileGnssHealthy() {
        var now = 0L
        val store = PoseStore(
            filter = PoseStore.liveFilter(),
            clockNs = { now },
            matcher = ScriptedMatcher(matchedResult(nearJunction = false, roadId = "approach", alongM = 160.0)),
            graph = tunnelFixture(),
        )
        store.navic.ingest(
            List(8) { GnssSatRow("GPS", usedInFix = true, cn0DbHz = 36.0) },
        )
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 51.5,
                longitudeDeg = -0.12,
                speedMps = 12.0,
                headingRad = 0.0,
                horizontalAccuracyM = 6.0,
            ),
        )
        val snap = store.integrity.value
        assertNotNull(snap)
        val blackout = snap!!.blackout!!
        val risk = blackout.risk as OptionalScalar.Available
        assertTrue(risk.value < BlackoutRisk.PRECONDITION_RISK)
        assertTrue(blackout.preconditioning)
        assertTrue(store.coastPreconditionArmed())
        assertTrue(store.state.value!!.mode != NavigationMode.DEAD_RECKONING)
    }

    @Test
    fun shadowLookAheadArmsCoastWhileGnssHealthy() {
        val dir = java.nio.file.Files.createTempDirectory("dz-shadow-ahead").toFile()
        val file = java.io.File(dir, ShadowMapStore.FILE_NAME)
        val seeded = ShadowMap()
        val (aheadLat, aheadLon) = Wgs84.offsetMetres(18.52, 73.85, 100.0, 0.0)
        repeat(4) {
            seeded.observe(aheadLat, aheadLon, lost = true, accuracyM = 40.0)
        }
        file.writeText(seeded.toJson())
        var now = 0L
        val store = PoseStore(
            filter = PoseStore.liveFilter(),
            clockNs = { now },
            shadowStore = ShadowMapStore(file),
        )
        store.navic.ingest(
            List(8) { GnssSatRow("GPS", usedInFix = true, cn0DbHz = 36.0) },
        )
        store.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 18.52,
                longitudeDeg = 73.85,
                speedMps = 12.0,
                headingRad = 0.0,
                horizontalAccuracyM = 6.0,
            ),
        )
        val snap = store.integrity.value
        assertNotNull(snap)
        assertTrue(snap!!.blackout!!.preconditioning)
        assertTrue(store.coastPreconditionArmed())
        dir.deleteRecursively()
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

    @Test
    fun holdLogsScoreOnlyGnssWithoutFilterJump() {
        val root = java.nio.file.Files.createTempDirectory("hold-log").toFile()
        try {
            val pending = ArrayList<Runnable>()
            val recorder = `in`.driftzero.app.trips.TripRecorder(
                dir = java.io.File(root, "trip-hold"),
                id = "trip-hold",
                startWallMs = 0L,
                schedule = { pending += it },
            )
            recorder.start()
            pending.clear()
            var now = 0L
            val store = PoseStore(filter = DeadReckoningFilter(), clockNs = { now })
            store.attachRecorder(recorder)
            store.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(0L),
                    latitudeDeg = 18.52,
                    longitudeDeg = 73.85,
                    speedMps = 12.0,
                    headingRad = 0.4,
                    horizontalAccuracyM = 5.0,
                ),
            )
            store.setSimulateGpsOff(true)
            now = 1_000_000_000L
            store.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(1_000_000_000L),
                    latitudeDeg = 18.62,
                    longitudeDeg = 73.95,
                    speedMps = 11.0,
                    headingRad = 0.5,
                    horizontalAccuracyM = 5.0,
                ),
            )
            store.tick()
            pending.forEach { it.run() }
            val pose = store.state.value
            assertNotNull(pose)
            assertTrue(abs(pose!!.position.latitude.value - 18.52) < 0.02)
            val loaded = `in`.driftzero.core.ReplayJsonl.load(
                java.io.File(recorder.dir, `in`.driftzero.app.trips.TripRecorder.SENSORS).toPath(),
            )
            assertTrue(loaded is `in`.driftzero.core.ReplayLoadResult.Ready)
            val frames = (loaded as `in`.driftzero.core.ReplayLoadResult.Ready).source.frames
            val gnss = frames.filter { it.kind == `in`.driftzero.core.SensorKind.GNSS_FIX }
            assertEquals(2, gnss.size)
            assertTrue(gnss[0].quality.flags.isEmpty())
            assertTrue(gnss[1].quality.flags.contains(`in`.driftzero.app.trips.TripFrames.FLAG_GNSS_HELD))
            val held = (gnss[1].payload as `in`.driftzero.core.FixPayload).fix
            assertEquals(11.0, held.speedMps!!.value, 1e-9)
            assertEquals(0.5, held.bearingRad!!.value, 1e-9)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun matchedResult(
        nearJunction: Boolean,
        roadId: String = "road",
        alongM: Double = 10.0,
    ): MapMatchResult =
        MapMatchResult(
            match = MapMatch(MapMatchStatus.MATCHED, 0.95, roadSegmentId = roadId),
            displayPose = DisplayPose(
                position = GeoPoint(LatitudeDeg(0.0), LongitudeDeg(0.0)),
                heading = HeadingRadians(0.0),
                alongTrackM = alongM,
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

    private fun matchedElbow(graph: RoadGraph): MapMatchResult {
        val edge = graph.edges.first()
        return MapMatchResult(
            match = MapMatch(MapMatchStatus.MATCHED, 0.95, roadSegmentId = edge.id),
            displayPose = DisplayPose(
                position = edge.points.first(),
                heading = HeadingRadians(1.5707963267948966),
                alongTrackM = 1270.0,
                crossTrackAbs = Metres(2.0),
            ),
            bestPosterior = 0.95,
            secondPosterior = 0.05,
            packageId = graph.packageId,
        )
    }

    private fun rightAngleFixture(): RoadGraph {
        val originLat = 51.5
        val originLon = -0.12
        val origin = Wgs84.offsetMetres(originLat, originLon, 0.0, 0.0)
        val corner = Wgs84.offsetMetres(originLat, originLon, 1270.0, 0.0)
        val end = Wgs84.offsetMetres(originLat, originLon, 1270.0, 200.0)
        val points = listOf(
            GeoPoint(LatitudeDeg(origin.first), LongitudeDeg(origin.second)),
            GeoPoint(LatitudeDeg(corner.first), LongitudeDeg(corner.second)),
            GeoPoint(LatitudeDeg(end.first), LongitudeDeg(end.second)),
        )
        val headings = DoubleArray(points.size - 1)
        var length = 0.0
        for (i in 0 until points.size - 1) {
            val (n, e) = Wgs84.northEastMetres(
                points[i].latitude.value,
                points[i].longitude.value,
                points[i + 1].latitude.value,
                points[i + 1].longitude.value,
            )
            length += kotlin.math.hypot(n, e)
            headings[i] = wrapHeadingRad(kotlin.math.atan2(e, n))
        }
        val edge = GraphEdge(
            id = "elbow",
            fromNodeId = 1L,
            toNodeId = 2L,
            points = points,
            lengthM = length,
            segmentHeadingsRad = headings,
            highway = "residential",
        )
        val nodes = listOf(
            GraphNode(1L, points.first().latitude, points.first().longitude),
            GraphNode(2L, points.last().latitude, points.last().longitude),
        ).associateBy { it.id }
        return RoadGraph("fixture-right-angle", nodes, listOf(edge))
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

    private fun tunnelFixture(): RoadGraph {
        val originLat = 51.5
        val originLon = -0.12
        val approachM = 200.0
        val tunnelM = 120.0
        fun segment(id: String, from: Long, to: Long, startNorth: Double, endNorth: Double, tunnel: Boolean): GraphEdge {
            val start = Wgs84.offsetMetres(originLat, originLon, startNorth, 0.0)
            val end = Wgs84.offsetMetres(originLat, originLon, endNorth, 0.0)
            val points = listOf(
                GeoPoint(LatitudeDeg(start.first), LongitudeDeg(start.second)),
                GeoPoint(LatitudeDeg(end.first), LongitudeDeg(end.second)),
            )
            return GraphEdge(
                id = id,
                fromNodeId = from,
                toNodeId = to,
                points = points,
                lengthM = endNorth - startNorth,
                segmentHeadingsRad = doubleArrayOf(0.0),
                highway = "residential",
                tunnel = tunnel,
                oneway = true,
            )
        }
        val approach = segment("approach", 1L, 2L, 0.0, approachM, tunnel = false)
        val bore = segment("bore", 2L, 3L, approachM, approachM + tunnelM, tunnel = true)
        val nodes = listOf(approach, bore).flatMap { e ->
            listOf(
                GraphNode(e.fromNodeId, e.points.first().latitude, e.points.first().longitude),
                GraphNode(e.toNodeId, e.points.last().latitude, e.points.last().longitude),
            )
        }.associateBy { it.id }
        return RoadGraph("fixture-road-tunnel", nodes, listOf(approach, bore))
    }
}
