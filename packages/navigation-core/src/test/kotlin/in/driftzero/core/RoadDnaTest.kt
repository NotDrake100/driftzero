package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class RoadDnaTest {
    @Test
    fun trackerKeepsOnlyLatestCap() {
        val tracker = RoadDnaTracker()
        var lat = 0.0
        repeat(RoadDnaTracker.MAX_SAMPLES + 40) { i ->
            lat += 0.0001
            tracker.add(lat, 0.0, 0.0)
        }
        val sig = tracker.signature()
        assertNotNull(sig)
        val stepM = Wgs84.distanceMetres(0.0, 0.0, 0.0001, 0.0)
        assertTrue(
            "capped tracker travelled ${sig!!.travelledM} should stay near ${RoadDnaTracker.MAX_SAMPLES} samples",
            sig.travelledM < (RoadDnaTracker.MAX_SAMPLES + 1) * stepM,
        )
    }

    @Test
    fun ninetyDegreeTurnExtractsAsTurn() {
        val headings = doubleArrayOf(0.0, 0.0, 0.0, PI / 2.0)
        val lengths = doubleArrayOf(400.0, 400.0, 470.0, 20.0)
        val sig = RoadDna.extract(headings, lengths)
        assertTrue(sig.events.any { it.kind == RoadDnaKind.TURN && it.magnitude > 1.2 })
    }

    @Test
    fun straightOnlySignatureDoesNotHeal() {
        val observed = RoadDna.extract(
            doubleArrayOf(0.0, 0.0, 0.0),
            doubleArrayOf(100.0, 100.0, 100.0),
        )
        val candidate = RoadDna.extract(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(280.0, 20.0),
        )
        assertNull(RoadDna.decideHeal(observed, listOf(candidate)))
    }

    @Test
    fun ambiguousCandidatesDoNotHeal() {
        val observed = RoadDna.extract(
            doubleArrayOf(0.0, 0.0, PI / 2.0),
            doubleArrayOf(100.0, 100.0, 20.0),
        )
        val a = RoadDna.extract(doubleArrayOf(0.0, PI / 2.0), doubleArrayOf(90.0, 20.0))
        val b = RoadDna.extract(doubleArrayOf(0.0, PI / 2.0), doubleArrayOf(95.0, 20.0))
        assertNull(RoadDna.decideHeal(observed, listOf(a, b)))
    }

    /**
     * Fixture proof, not an IO-VNBD claim. Map corner at 1270 m. DR odometer
     * is 1320 m. After a unique DNA match the along-track error is under
     * 10 percent of 1320 m.
     */
    @Test
    fun distinctiveNinetyDegreeCurveSelfHealsUnderTenPercentOfDrOdometer() {
        val originLat = RoadFixtures.ORIGIN_LAT
        val originLon = RoadFixtures.ORIGIN_LON
        val graph = RoadFixtures.rightAngleRoad(northM = 1270.0, eastM = 200.0)
        val edge = graph.edges.first()
        val mapSig = RoadDna.fromEdge(edge)
        val observed = RoadDna.extract(
            headingsRad = doubleArrayOf(0.0, 0.0, 0.0, PI / 2.0),
            distancesM = doubleArrayOf(500.0, 500.0, 320.0, 20.0),
        )
        assertEquals(1340.0, observed.travelledM, 1.0)
        val heal = RoadDna.decideHeal(observed, listOf(mapSig))
        assertNotNull(heal)
        val offset = heal!!.offsetM
        assertTrue("offset $offset should pull DR back toward 1270", offset < 0.0)
        assertTrue(abs(offset) < 80.0)

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
        val result = filter.applyAlongTrack(offset, heal.stdM)
        assertTrue("along-track ${result.reason} chi2=${result.chi2}", result.accepted)
        val pose = filter.poseAt(Nanoseconds(100_000_000L))!!
        val err = Wgs84.distanceMetres(
            pose.position.latitude.value,
            pose.position.longitude.value,
            trueLat,
            trueLon,
        )
        val gate = 0.10 * 1320.0
        assertTrue(
            "fixture proof: error $err m must be < 10% of 1320 m ($gate). Not an IO-VNBD claim.",
            err < gate,
        )
        val sideways = abs(pose.position.latitude.value - trueLat) * 111_320.0
        assertTrue("must not jump off the road, north residual $sideways", sideways < 15.0)
    }
}

class OfficialExampleCoastFixtureTest {
    /**
     * Fixture proof of the official 5 m over 50 m example under yaw-speed-hold
     * with a known heading. Not an IO-VNBD row.
     */
    @Test
    fun yawSpeedHoldFiftyMetresStaysUnderFive() {
        val g = Wgs84.gravityMps2(0.0)
        val speed = 50.0 / 3.6
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                coastHonestP = true,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, speed, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 3.0,
            headingRad = 0.0,
        )
        filter.setGnssHeld(true)
        val dtNs = 100_000_000L
        val steps = 36
        for (i in 1..steps) {
            filter.ingestGyro(Nanoseconds(i * dtNs), 0.0, 0.0, 0.0)
            filter.ingestAccel(Nanoseconds(i * dtNs), 0.0, 0.0, g)
        }
        val pose = filter.poseAt(Nanoseconds(steps * dtNs))!!
        val travelled = Wgs84.distanceMetres(
            0.0,
            0.0,
            pose.position.latitude.value,
            pose.position.longitude.value,
        )
        val want = speed * (steps * 0.10)
        val err = abs(travelled - want)
        assertTrue("fixture 50 m coast error $err m travelled $travelled want $want", err < 5.0)
        assertTrue(travelled > 40.0)
    }
}
