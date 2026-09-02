package `in`.driftzero.app.pose

import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.DisplacementModel
import `in`.driftzero.core.DisplacementPseudoMeasurement
import `in`.driftzero.core.GeoPoint
import `in`.driftzero.core.GraphEdge
import `in`.driftzero.core.GraphNode
import `in`.driftzero.core.ImuMotionConstants
import `in`.driftzero.core.LatitudeDeg
import `in`.driftzero.core.LongitudeDeg
import `in`.driftzero.core.MapMatchStatus
import `in`.driftzero.core.MotionPseudoRuntime
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.RoadGraph
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
