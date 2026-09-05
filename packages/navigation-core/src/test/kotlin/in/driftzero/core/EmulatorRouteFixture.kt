package `in`.driftzero.core

import kotlin.math.atan2

/**
 * Labeled emulator-route fixture. Feeds a 1 Hz geo polyline (the same
 * points `adb emu geo fix` would emit) into [Replay] / [DeadReckoningFilter].
 *
 * IMU here is gravity plus zero gyro in [VectorFrame.VEHICLE_FLU]. That is
 * not SwiftShader noise and not a phone. Not IO-VNBD. Not SIH screening.
 * Score-only truth is the polyline. Hidden gap GNSS never reaches the filter.
 */
object EmulatorRouteFixture {
    const val SOURCE_ID: String = "emulator-route-fixture"
    const val LABEL: String = "emulator-route fixture"
    const val RATIO_GATE: Double = 0.10
    const val GNSS_ACCURACY_M: Double = 3.0
    const val IMU_HZ: Double = 50.0
    const val GNSS_HZ: Double = 1.0

    /** Pune Cantonment, same origin as `tools/emulator/README.md`. Fixture only. */
    const val FIXTURE_ORIGIN_LAT_DEG: Double = 18.51090730
    const val FIXTURE_ORIGIN_LON_DEG: Double = 73.88510180

    data class GeoFix(
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val timestampNs: Long,
    ) {
        init {
            require(latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0)
            require(longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0)
            require(timestampNs >= 0L)
        }
    }

    data class Trip(
        val frames: List<SensorFrame>,
        val mask: GnssMaskInterval,
        val emitted: List<GeoFix>,
        val pathM: Double,
        val truthLatitudeDeg: Double,
        val truthLongitudeDeg: Double,
        val resumeNs: Long,
        val gapStartS: Int,
        val gapS: Int,
        val speedMps: Double,
    )

    data class Score(
        val pathM: Double,
        val errorM: Double,
        val ratio: Double,
        val coastLatitudeDeg: Double,
        val coastLongitudeDeg: Double,
        val truthLatitudeDeg: Double,
        val truthLongitudeDeg: Double,
        val gapStartS: Int,
        val gapS: Int,
        val met10pct: Boolean,
        val label: String = LABEL,
    ) {
        init {
            require(pathM.isFinite() && pathM > 0.0) { "path_m must be positive" }
            require(errorM.isFinite() && errorM >= 0.0)
            require(ratio.isFinite() && ratio >= 0.0)
        }
    }

    /**
     * Official 5 m over 50 m example as a northbound 1 Hz line.
     * Warmup 12 s, gap t=12..20 s omitted, resume at t=21 s.
     * Path is last pre-gap fix to first resume fix: 10 s at 5 m/s = 50 m.
     */
    fun officialFiftyMetreNorth(
        originLatDeg: Double = FIXTURE_ORIGIN_LAT_DEG,
        originLonDeg: Double = FIXTURE_ORIGIN_LON_DEG,
        imuHz: Double = IMU_HZ,
    ): Trip = northbound(
        originLatDeg = originLatDeg,
        originLonDeg = originLonDeg,
        speedMps = 5.0,
        warmupS = 12,
        gapS = 9,
        afterS = 8,
        imuHz = imuHz,
    )

    fun northbound(
        originLatDeg: Double,
        originLonDeg: Double,
        speedMps: Double,
        warmupS: Int,
        gapS: Int,
        afterS: Int,
        imuHz: Double = IMU_HZ,
    ): Trip {
        require(speedMps > 0.0 && speedMps.isFinite())
        require(warmupS >= 1 && gapS >= 1 && afterS >= 1)
        require(imuHz > 0.0 && imuHz.isFinite())
        val endS = warmupS + gapS + afterS
        val points = ArrayList<GeoFix>(endS + 1)
        for (t in 0..endS) {
            val northM = speedMps * t.toDouble()
            val (lat, lon) = Wgs84.offsetMetres(originLatDeg, originLonDeg, northM, 0.0)
            points.add(GeoFix(lat, lon, t * NS_PER_S))
        }
        return fromOneHzPolyline(
            points = points,
            gapStartS = warmupS,
            gapS = gapS,
            speedMps = speedMps,
            imuHz = imuHz,
        )
    }

    /**
     * Emitted `geo fix` samples (gap seconds omitted, timestamps jump).
     * Path is the chord across the first jump. Interpolated gap GNSS is
     * built only so the mask has a defined interval; those fixes are dropped.
     */
    fun fromEmittedFixes(
        emitted: List<GeoFix>,
        imuHz: Double = IMU_HZ,
        minGapS: Double = 1.5,
    ): Trip {
        require(emitted.size >= 3) { "need warmup, a gap, and a resume fix" }
        val ordered = emitted.sortedBy { it.timestampNs }
        var jumpAt = -1
        for (i in 1 until ordered.size) {
            val dtS = (ordered[i].timestampNs - ordered[i - 1].timestampNs) / NS_PER_S_D
            if (dtS > minGapS) {
                jumpAt = i
                break
            }
        }
        require(jumpAt > 0) { "emitted fixes have no timestamp jump larger than $minGapS s" }
        val lastBefore = ordered[jumpAt - 1]
        val firstAfter = ordered[jumpAt]
        val originNs = ordered.first().timestampNs
        val rel = ordered.map { it.copy(timestampNs = it.timestampNs - originNs) }
        val lastRel = rel[jumpAt - 1]
        val resumeRel = rel[jumpAt]
        val gapStartS = ((lastRel.timestampNs + NS_PER_S) / NS_PER_S).toInt()
        val resumeS = (resumeRel.timestampNs / NS_PER_S).toInt()
        val gapS = resumeS - gapStartS
        require(gapS >= 1) { "gap must cover at least one omitted second" }
        val speedMps = pathMetres(lastBefore, firstAfter) /
            ((firstAfter.timestampNs - lastBefore.timestampNs) / NS_PER_S_D)
        val filled = ArrayList<GeoFix>()
        for (fix in rel.take(jumpAt)) {
            filled.add(fix)
        }
        for (t in gapStartS until resumeS) {
            val span = (resumeRel.timestampNs - lastRel.timestampNs).toDouble()
            val frac = (t * NS_PER_S - lastRel.timestampNs) / span
            filled.add(
                GeoFix(
                    latitudeDeg = lastRel.latitudeDeg + frac * (resumeRel.latitudeDeg - lastRel.latitudeDeg),
                    longitudeDeg = lastRel.longitudeDeg + frac * (resumeRel.longitudeDeg - lastRel.longitudeDeg),
                    timestampNs = t * NS_PER_S,
                ),
            )
        }
        for (fix in rel.drop(jumpAt)) {
            filled.add(fix)
        }
        return fromOneHzPolyline(
            points = filled,
            gapStartS = gapStartS,
            gapS = gapS,
            speedMps = speedMps,
            imuHz = imuHz,
        )
    }

    fun fromOneHzPolyline(
        points: List<GeoFix>,
        gapStartS: Int,
        gapS: Int,
        speedMps: Double,
        imuHz: Double = IMU_HZ,
    ): Trip {
        require(points.size >= 2)
        require(gapStartS >= 1)
        require(gapS >= 1)
        require(imuHz > 0.0)
        val resumeS = gapStartS + gapS
        val lastBefore = pointAtS(points, gapStartS - 1)
        val resume = pointAtS(points, resumeS)
        val fromIndex = points.indexOfFirst { it.timestampNs == lastBefore.timestampNs }
        val toIndex = points.indexOfFirst { it.timestampNs == resume.timestampNs }
        require(fromIndex >= 0 && toIndex > fromIndex)
        val pathM = polylineMetres(points, fromIndex = fromIndex, toIndex = toIndex)
        require(pathM > 0.0) { "gap path_m is 0" }
        val mask = GnssMaskInterval(gapStartS * NS_PER_S, resumeS * NS_PER_S - 1L)
        val frames = buildFrames(points, imuHz, speedMps)
        val emitted = points.filter { !mask.contains(it.timestampNs) }
        return Trip(
            frames = frames,
            mask = mask,
            emitted = emitted,
            pathM = pathM,
            truthLatitudeDeg = resume.latitudeDeg,
            truthLongitudeDeg = resume.longitudeDeg,
            resumeNs = resume.timestampNs,
            gapStartS = gapStartS,
            gapS = gapS,
            speedMps = speedMps,
        )
    }

    fun run(trip: Trip, filter: DeadReckoningFilter = DeadReckoningFilter(CONFIG)): Pair<List<NavigationState>, Score> {
        val consumed = ArrayList<SensorFrame>()
        val states = Replay.runFilter(trip.frames, filter, trip.mask) { consumed.add(it) }
        require(consumed.none { trip.mask.drops(it) }) { "masked GNSS reached the filter" }
        return states to score(states, trip)
    }

    fun score(states: List<NavigationState>, trip: Trip): Score {
        val coast = states.lastOrNull { it.timestamp.value < trip.resumeNs }
            ?: error("no fused state before resume GNSS")
        return score(
            coastLatitudeDeg = coast.position.latitude.value,
            coastLongitudeDeg = coast.position.longitude.value,
            truthLatitudeDeg = trip.truthLatitudeDeg,
            truthLongitudeDeg = trip.truthLongitudeDeg,
            pathM = trip.pathM,
            gapStartS = trip.gapStartS,
            gapS = trip.gapS,
        )
    }

    fun score(
        coastLatitudeDeg: Double,
        coastLongitudeDeg: Double,
        truthLatitudeDeg: Double,
        truthLongitudeDeg: Double,
        pathM: Double,
        gapStartS: Int,
        gapS: Int,
    ): Score {
        require(pathM > 0.0 && pathM.isFinite()) { "path_m must be positive" }
        val errorM = Wgs84.distanceMetres(
            coastLatitudeDeg,
            coastLongitudeDeg,
            truthLatitudeDeg,
            truthLongitudeDeg,
        )
        val ratio = errorM / pathM
        return Score(
            pathM = pathM,
            errorM = errorM,
            ratio = ratio,
            coastLatitudeDeg = coastLatitudeDeg,
            coastLongitudeDeg = coastLongitudeDeg,
            truthLatitudeDeg = truthLatitudeDeg,
            truthLongitudeDeg = truthLongitudeDeg,
            gapStartS = gapStartS,
            gapS = gapS,
            met10pct = ratio < RATIO_GATE,
        )
    }

    fun parseGpx(xml: String): List<GeoFix> {
        val points = ArrayList<GeoFix>()
        val row = Regex(
            """<trkpt\s+lat="([^"]+)"\s+lon="([^"]+)"\s*>\s*<time>([^<]+)</time>""",
            RegexOption.IGNORE_CASE,
        )
        val alt = Regex(
            """<trkpt\s+lon="([^"]+)"\s+lat="([^"]+)"\s*>\s*<time>([^<]+)</time>""",
            RegexOption.IGNORE_CASE,
        )
        for (match in row.findAll(xml)) {
            points.add(gpxFix(match.groupValues[1], match.groupValues[2], match.groupValues[3]))
        }
        if (points.isEmpty()) {
            for (match in alt.findAll(xml)) {
                points.add(gpxFix(match.groupValues[2], match.groupValues[1], match.groupValues[3]))
            }
        }
        require(points.isNotEmpty()) { "GPX has no trkpt rows" }
        return points
    }

    private fun pointAtS(points: List<GeoFix>, tS: Int): GeoFix {
        val want = tS * NS_PER_S
        return points.firstOrNull { it.timestampNs == want }
            ?: error("polyline has no 1 Hz sample at t=$tS s")
    }

    fun polylineMetres(points: List<GeoFix>, fromIndex: Int, toIndex: Int): Double {
        require(fromIndex >= 0 && toIndex < points.size && toIndex > fromIndex)
        var sum = 0.0
        for (i in fromIndex until toIndex) {
            sum += pathMetres(points[i], points[i + 1])
        }
        return sum
    }

    fun pathMetres(a: GeoFix, b: GeoFix): Double =
        Wgs84.distanceMetres(a.latitudeDeg, a.longitudeDeg, b.latitudeDeg, b.longitudeDeg)

    private fun gpxFix(latText: String, lonText: String, timeText: String): GeoFix {
        val lat = latText.toDouble()
        val lon = lonText.toDouble()
        val epochS = parseIsoEpochS(timeText)
        return GeoFix(lat, lon, (epochS * NS_PER_S_D).toLong())
    }

    private fun parseIsoEpochS(text: String): Double {
        val iso = Regex(
            """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?Z""",
        )
        val match = iso.matchEntire(text.trim())
            ?: error("GPX time is not UTC ISO-8601: $text")
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        val frac = match.groupValues[7].let { raw ->
            if (raw.isEmpty()) 0.0 else raw.padEnd(9, '0').take(9).toLong() / 1_000_000_000.0
        }
        var days = 0
        for (y in 1970 until year) {
            days += if (isLeap(y)) 366 else 365
        }
        val monthDays = intArrayOf(31, if (isLeap(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        for (m in 1 until month) {
            days += monthDays[m - 1]
        }
        days += day - 1
        return days * 86400.0 + hour * 3600.0 + minute * 60.0 + second + frac
    }

    private fun isLeap(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private fun buildFrames(points: List<GeoFix>, imuHz: Double, defaultSpeedMps: Double): List<SensorFrame> {
        val byNs = points.associateBy { it.timestampNs }
        val endNs = points.last().timestampNs
        val imuPeriodNs = (NS_PER_S_D / imuHz).toLong()
        require(imuPeriodNs > 0L)
        val gravity = Wgs84.gravityMps2(points.first().latitudeDeg)
        val frames = ArrayList<SensorFrame>()
        var sequence = 0L
        var t = 0L
        while (t <= endNs) {
            frames.add(gyroFrame(sequence++, t))
            frames.add(accelFrame(sequence++, t, gravity))
            val fix = byNs[t]
            if (fix != null) {
                val (speed, heading) = kinematics(points, t, defaultSpeedMps)
                frames.add(gnssFrame(sequence++, t, fix.latitudeDeg, fix.longitudeDeg, speed, heading))
            }
            t += imuPeriodNs
        }
        return frames
    }

    private fun kinematics(points: List<GeoFix>, timestampNs: Long, fallbackSpeed: Double): Pair<Double, Double> {
        val index = points.indexOfFirst { it.timestampNs == timestampNs }
        if (index < 0) {
            return fallbackSpeed to 0.0
        }
        val next = if (index + 1 < points.size) points[index + 1] else points[index]
        val prev = if (index > 0) points[index - 1] else points[index]
        val pair = if (index + 1 < points.size) points[index] to next else prev to points[index]
        val dtS = (pair.second.timestampNs - pair.first.timestampNs) / NS_PER_S_D
        val dist = pathMetres(pair.first, pair.second)
        val speed = if (dtS > 1e-9) dist / dtS else fallbackSpeed
        val heading = bearingRad(pair.first, pair.second)
        return speed to heading
    }

    private fun bearingRad(from: GeoFix, to: GeoFix): Double {
        val (north, east) = Wgs84.northEastMetres(
            from.latitudeDeg,
            from.longitudeDeg,
            to.latitudeDeg,
            to.longitudeDeg,
        )
        if (north == 0.0 && east == 0.0) {
            return 0.0
        }
        val raw = atan2(east, north)
        return ((raw % TWO_PI) + TWO_PI) % TWO_PI
    }

    private fun accelFrame(sequence: Long, timestampNs: Long, gravity: Double): SensorFrame =
        SensorFrame(
            sourceId = SOURCE_ID,
            sequence = sequence,
            timestamp = Nanoseconds(timestampNs),
            clockDomain = ClockDomain.DATASET_DECLARED,
            kind = SensorKind.ACCELEROMETER,
            quality = Quality(available = true, accuracyCode = 3),
            payload = VectorPayload(
                Vector3Payload(0.0, 0.0, gravity, "m/s^2", VectorFrame.VEHICLE_FLU),
            ),
        )

    private fun gyroFrame(sequence: Long, timestampNs: Long): SensorFrame = SensorFrame(
        sourceId = SOURCE_ID,
        sequence = sequence,
        timestamp = Nanoseconds(timestampNs),
        clockDomain = ClockDomain.DATASET_DECLARED,
        kind = SensorKind.GYROSCOPE,
        quality = Quality(available = true, accuracyCode = 3),
        payload = VectorPayload(
            Vector3Payload(0.0, 0.0, 0.0, "rad/s", VectorFrame.VEHICLE_FLU),
        ),
    )

    private fun gnssFrame(
        sequence: Long,
        timestampNs: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
        speedMps: Double,
        headingRad: Double,
    ): SensorFrame = SensorFrame(
        sourceId = SOURCE_ID,
        sequence = sequence,
        timestamp = Nanoseconds(timestampNs),
        clockDomain = ClockDomain.DATASET_DECLARED,
        kind = SensorKind.GNSS_FIX,
        quality = Quality(available = true, accuracyCode = 3),
        payload = FixPayload(
            GnssFixPayload(
                latitude = LatitudeDeg(latitudeDeg),
                longitude = LongitudeDeg(longitudeDeg),
                horizontalAccuracyM = Metres(GNSS_ACCURACY_M),
                providerTimeMs = timestampNs / 1_000_000L,
                speedMps = MetresPerSecond(speedMps.coerceAtLeast(0.0)),
                bearingRad = HeadingRadians(headingRad),
                isMock = true,
            ),
        ),
    )

    val CONFIG: InsConfig = InsConfig(
        coastMode = CoastMode.YAW_SPEED_HOLD,
        coastHonestP = true,
        coastLatchGnssSpeed = true,
        coastStopDetect = false,
        studentForwardSpeed = false,
        staleAfterS = 8.0,
        nhcMinSpeedMps = 100.0,
        lowConfidenceRadiusM = 10_000.0,
    )

    private const val NS_PER_S: Long = 1_000_000_000L
    private const val NS_PER_S_D: Double = 1_000_000_000.0
}
