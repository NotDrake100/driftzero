package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorRouteFixtureTest {
    @Test
    fun officialFiftyMetrePathIsFifty() {
        val trip = EmulatorRouteFixture.officialFiftyMetreNorth()
        assertEquals(12, trip.gapStartS)
        assertEquals(9, trip.gapS)
        assertEquals(50.0, trip.pathM, 0.6)
        assertEquals(5.0, trip.speedMps, 1e-6)
        val lastBefore = trip.emitted.last { it.timestampNs < trip.resumeNs }
        val chord = EmulatorRouteFixture.pathMetres(
            lastBefore,
            EmulatorRouteFixture.GeoFix(trip.truthLatitudeDeg, trip.truthLongitudeDeg, trip.resumeNs),
        )
        assertEquals(trip.pathM, chord, 0.05)
    }

    @Test
    fun scorerRatioIsErrorOverPath() {
        val origin = EmulatorRouteFixture.GeoFix(
            EmulatorRouteFixture.FIXTURE_ORIGIN_LAT_DEG,
            EmulatorRouteFixture.FIXTURE_ORIGIN_LON_DEG,
            0L,
        )
        val (truthLat, truthLon) = Wgs84.offsetMetres(origin.latitudeDeg, origin.longitudeDeg, 50.0, 0.0)
        val (coastLat, coastLon) = Wgs84.offsetMetres(origin.latitudeDeg, origin.longitudeDeg, 46.0, 0.0)
        val score = EmulatorRouteFixture.score(
            coastLatitudeDeg = coastLat,
            coastLongitudeDeg = coastLon,
            truthLatitudeDeg = truthLat,
            truthLongitudeDeg = truthLon,
            pathM = 50.0,
            gapStartS = 12,
            gapS = 9,
        )
        assertEquals(4.0, score.errorM, 0.05)
        assertEquals(0.08, score.ratio, 0.002)
        assertTrue(score.met10pct)
        assertEquals(EmulatorRouteFixture.LABEL, score.label)
    }

    @Test
    fun freezeAtLastFixFailsTheGate() {
        val trip = EmulatorRouteFixture.officialFiftyMetreNorth()
        val lastBefore = trip.emitted.last { it.timestampNs < trip.resumeNs }
        val score = EmulatorRouteFixture.score(
            coastLatitudeDeg = lastBefore.latitudeDeg,
            coastLongitudeDeg = lastBefore.longitudeDeg,
            truthLatitudeDeg = trip.truthLatitudeDeg,
            truthLongitudeDeg = trip.truthLongitudeDeg,
            pathM = trip.pathM,
            gapStartS = trip.gapStartS,
            gapS = trip.gapS,
        )
        assertTrue("freeze error ${score.errorM} m should be near path ${trip.pathM}", score.errorM > 45.0)
        assertTrue(score.ratio > 0.90)
        assertFalse(score.met10pct)
    }

    @Test
    fun yawSpeedHoldOnFiftyMetrePolylineMeetsTenPercent() {
        val trip = EmulatorRouteFixture.officialFiftyMetreNorth()
        val consumed = ArrayList<SensorFrame>()
        val (states, score) = EmulatorRouteFixture.run(trip)
        Replay.runFilter(trip.frames, DeadReckoningFilter(EmulatorRouteFixture.CONFIG), trip.mask) {
            consumed.add(it)
        }
        assertTrue(consumed.none { trip.mask.drops(it) })
        assertTrue(trip.frames.any { trip.mask.drops(it) })
        assertTrue(
            "emulator-route fixture ratio ${score.ratio} error ${score.errorM} m " +
                "path ${score.pathM} m (not IO-VNBD, not SIH screening)",
            score.met10pct,
        )
        assertTrue(score.errorM < 5.0)
        assertTrue(states.any { it.timestamp.value < trip.resumeNs })
        val coast = states.last { it.timestamp.value < trip.resumeNs }
        assertTrue(
            coast.mode == NavigationMode.DEAD_RECKONING ||
                coast.mode == NavigationMode.LOW_CONFIDENCE ||
                coast.mode == NavigationMode.GNSS_DEGRADED,
        )
    }

    @Test
    fun maskedDecoyDoesNotPullCoast() {
        val trip = EmulatorRouteFixture.officialFiftyMetreNorth()
        val decoy = SensorFrame(
            sourceId = EmulatorRouteFixture.SOURCE_ID,
            sequence = 99_000L,
            timestamp = Nanoseconds(trip.gapStartS * 1_000_000_000L + 500_000_000L),
            clockDomain = ClockDomain.DATASET_DECLARED,
            kind = SensorKind.GNSS_FIX,
            quality = Quality(available = true, accuracyCode = 3),
            payload = FixPayload(
                GnssFixPayload(
                    latitude = LatitudeDeg(trip.truthLatitudeDeg + 1.0),
                    longitude = LongitudeDeg(trip.truthLongitudeDeg),
                    horizontalAccuracyM = Metres(3.0),
                    providerTimeMs = 1L,
                    speedMps = MetresPerSecond(5.0),
                    bearingRad = HeadingRadians(0.0),
                    isMock = true,
                ),
            ),
        )
        val frames = trip.frames + decoy
        val leaked = ArrayList<SensorFrame>()
        val states = Replay.runFilter(
            frames,
            DeadReckoningFilter(EmulatorRouteFixture.CONFIG),
            trip.mask,
        ) { leaked.add(it) }
        assertTrue(leaked.none { it.sequence == 99_000L })
        val score = EmulatorRouteFixture.score(states, trip)
        val toDecoy = Wgs84.distanceMetres(
            score.coastLatitudeDeg,
            score.coastLongitudeDeg,
            trip.truthLatitudeDeg + 1.0,
            trip.truthLongitudeDeg,
        )
        assertTrue("filter followed decoy, distance $toDecoy m", toDecoy > 50_000.0)
        assertTrue(score.met10pct)
    }

    @Test
    fun emittedGpxJumpRebuildsTheSamePath() {
        val trip = EmulatorRouteFixture.officialFiftyMetreNorth()
        val rebuilt = EmulatorRouteFixture.fromEmittedFixes(trip.emitted)
        assertEquals(trip.gapStartS, rebuilt.gapStartS)
        assertEquals(trip.gapS, rebuilt.gapS)
        assertEquals(trip.pathM, rebuilt.pathM, 0.6)
        val (_, score) = EmulatorRouteFixture.run(rebuilt)
        assertTrue(score.met10pct)
    }

    @Test
    fun emittedPuneCantonmentGpxMeetsTenPercent() {
        val xml = javaClass.getResourceAsStream("/emulator_route_emitted_50m.gpx")
            ?.bufferedReader()
            ?.readText()
            ?: error("missing emulator_route_emitted_50m.gpx")
        val trip = EmulatorRouteFixture.fromEmittedFixes(EmulatorRouteFixture.parseGpx(xml))
        assertEquals(12, trip.gapStartS)
        assertEquals(9, trip.gapS)
        assertEquals(49.8, trip.pathM, 1.0)
        val consumed = ArrayList<SensorFrame>()
        val (states, score) = EmulatorRouteFixture.run(trip)
        Replay.runFilter(trip.frames, DeadReckoningFilter(EmulatorRouteFixture.CONFIG), trip.mask) {
            consumed.add(it)
        }
        assertTrue(consumed.none { trip.mask.drops(it) })
        assertTrue(states.isNotEmpty())
        val report = java.io.File("build/emulator_route_score.json")
        report.parentFile?.mkdirs()
        report.writeText(
            """
            {"label":"${score.label}","path_m":${score.pathM},"error_m":${score.errorM},"ratio":${score.ratio},"met_0_10":${score.met10pct},"gap_start_s":${score.gapStartS},"gap_s":${score.gapS},"coast_lat":${score.coastLatitudeDeg},"coast_lon":${score.coastLongitudeDeg},"truth_lat":${score.truthLatitudeDeg},"truth_lon":${score.truthLongitudeDeg}}
            """.trimIndent() + "\n",
        )
        assertTrue(
            "emulator-route fixture on emitted GPX path=${score.pathM} error=${score.errorM} ratio=${score.ratio}",
            score.met10pct,
        )
    }

    @Test
    fun parseGpxReadsDriveRouteRows() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="DriftZero drive_route" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>emulator-route fixture</name>
                <trkseg>
                  <trkpt lat="18.51090730" lon="73.88510180">
                    <time>2026-09-04T06:00:00Z</time>
                  </trkpt>
                  <trkpt lat="18.51095230" lon="73.88510180">
                    <time>2026-09-04T06:00:01Z</time>
                  </trkpt>
                  <trkpt lat="18.51135730" lon="73.88510180">
                    <time>2026-09-04T06:00:11Z</time>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val points = EmulatorRouteFixture.parseGpx(xml)
        assertEquals(3, points.size)
        assertEquals(18.51090730, points[0].latitudeDeg, 1e-8)
        assertEquals(73.88510180, points[0].longitudeDeg, 1e-8)
        val dtS = (points[2].timestampNs - points[1].timestampNs) / 1_000_000_000.0
        assertEquals(10.0, dtS, 1e-9)
        val rebuilt = EmulatorRouteFixture.fromEmittedFixes(points)
        assertTrue(rebuilt.pathM > 0.0)
        assertEquals(2, rebuilt.gapStartS)
    }
}
