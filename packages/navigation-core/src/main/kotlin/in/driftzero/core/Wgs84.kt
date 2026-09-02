package `in`.driftzero.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 ellipsoid anywhere on Earth. Radii follow Groves 2nd ed. (2.105).
 * Gravity is Somigliana (2.134) plus the (2.139) height term. The n-frame
 * used by strapdown is local-tangent ENU, a permutation of Groves NED.
 */
object Wgs84 {
    const val MEAN_RADIUS_M: Double = 6_371_000.0
    const val A_M: Double = 6_378_137.0
    const val F: Double = 1.0 / 298.257223563
    const val E2: Double = F * (2.0 - F)
    const val STANDARD_G: Double = 9.80665
    /** WGS84 sidereal rate. Groves (2.123), NIMA TR8350.2. */
    const val OMEGA_IE_RADPS: Double = 7.292115e-5
    const val GM_M3PS2: Double = 3.986004418e14
    val B_M: Double = A_M * (1.0 - F)
    private const val GE: Double = 9.7803253359
    private const val K_SOMIGLIANA: Double = 0.00193185265241
    /** Groves (2.140) north gravity slope, 1/s². Companion Gravity_NED.m. */
    internal const val NORTH_GRAVITY_PER_M: Double = 8.08e-9

    /**
     * Downward normal gravity, m/s². [altitudeM] is ellipsoidal height.
     * Surface term is NIMA TR8350.2 (4-1) / Groves (2.134). Height is (2.139).
     */
    fun gravityMps2(latitudeDeg: Double, altitudeM: Double = 0.0): Double {
        require(latitudeDeg.isFinite())
        require(altitudeM.isFinite())
        val s = sin(latitudeDeg * PI / 180.0)
        val s2 = s * s
        val g0 = GE * (1.0 + K_SOMIGLIANA * s2) / sqrt(1.0 - E2 * s2)
        if (altitudeM == 0.0) {
            return g0
        }
        val m = OMEGA_IE_RADPS * OMEGA_IE_RADPS * A_M * A_M * B_M / GM_M3PS2
        val heightFactor = 1.0 -
            (2.0 / A_M) * (1.0 + F * (1.0 - 2.0 * s2) + m) * altitudeM +
            3.0 * altitudeM * altitudeM / (A_M * A_M)
        return g0 * heightFactor
    }

    fun meridianRadiusM(latitudeDeg: Double): Double {
        val s = sin(latitudeDeg * PI / 180.0)
        val den = 1.0 - E2 * s * s
        return A_M * (1.0 - E2) / (den * sqrt(den))
    }

    fun primeVerticalRadiusM(latitudeDeg: Double): Double {
        val s = sin(latitudeDeg * PI / 180.0)
        return A_M / sqrt(1.0 - E2 * s * s)
    }

    fun geodeticToEnu(
        originLatitudeDeg: Double,
        originLongitudeDeg: Double,
        originAltitudeM: Double,
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ): Triple<Double, Double, Double> {
        val rm = meridianRadiusM(originLatitudeDeg)
        val rn = primeVerticalRadiusM(originLatitudeDeg)
        val meanLat = ((originLatitudeDeg + latitudeDeg) / 2.0) * PI / 180.0
        val north = ((latitudeDeg - originLatitudeDeg) * PI / 180.0) * (rm + originAltitudeM)
        val east = ((longitudeDeg - originLongitudeDeg) * PI / 180.0) *
            (rn + originAltitudeM) * cos(meanLat)
        val up = altitudeM - originAltitudeM
        return Triple(east, north, up)
    }

    fun enuToGeodetic(
        originLatitudeDeg: Double,
        originLongitudeDeg: Double,
        originAltitudeM: Double,
        eastM: Double,
        northM: Double,
        upM: Double,
    ): Triple<Double, Double, Double> {
        val rm = meridianRadiusM(originLatitudeDeg)
        val rn = primeVerticalRadiusM(originLatitudeDeg)
        val lat0 = originLatitudeDeg * PI / 180.0
        val dLat = northM / (rm + originAltitudeM)
        val cosLat = cos(lat0)
        val denom = (rn + originAltitudeM) * if (abs(cosLat) < 1e-12) 1e-12 else cosLat
        val dLon = eastM / denom
        val lat = (originLatitudeDeg + dLat * 180.0 / PI).coerceIn(-90.0, 90.0)
        var lon = originLongitudeDeg + dLon * 180.0 / PI
        lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return Triple(lat, lon, originAltitudeM + upM)
    }

    fun offsetMetres(
        latitudeDeg: Double,
        longitudeDeg: Double,
        northM: Double,
        eastM: Double,
    ): Pair<Double, Double> {
        require(latitudeDeg.isFinite() && longitudeDeg.isFinite())
        require(northM.isFinite() && eastM.isFinite())
        val latRad = latitudeDeg * PI / 180.0
        val dLatDeg = (northM / MEAN_RADIUS_M) * 180.0 / PI
        val cosLat = cos(latRad)
        val denom = MEAN_RADIUS_M * if (abs(cosLat) < 1e-12) 1e-12 else cosLat
        val dLonDeg = (eastM / denom) * 180.0 / PI
        val lat = (latitudeDeg + dLatDeg).coerceIn(-90.0, 90.0)
        var lon = longitudeDeg + dLonDeg
        lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return lat to lon
    }

    fun northEastMetres(
        fromLatitudeDeg: Double,
        fromLongitudeDeg: Double,
        toLatitudeDeg: Double,
        toLongitudeDeg: Double,
    ): Pair<Double, Double> {
        val meanLatRad = ((fromLatitudeDeg + toLatitudeDeg) / 2.0) * PI / 180.0
        val north = ((toLatitudeDeg - fromLatitudeDeg) * PI / 180.0) * MEAN_RADIUS_M
        val east = ((toLongitudeDeg - fromLongitudeDeg) * PI / 180.0) * MEAN_RADIUS_M * cos(meanLatRad)
        return north to east
    }

    fun distanceMetres(
        fromLatitudeDeg: Double,
        fromLongitudeDeg: Double,
        toLatitudeDeg: Double,
        toLongitudeDeg: Double,
    ): Double {
        val (north, east) = northEastMetres(
            fromLatitudeDeg,
            fromLongitudeDeg,
            toLatitudeDeg,
            toLongitudeDeg,
        )
        return kotlin.math.hypot(north, east)
    }

    /**
     * Groves (2.123) ω_ie^n after NED→ENU: (0, ω cos L, ω sin L) rad/s.
     * Strapdown does not apply this. See [coriolisAccelEnu] and docs/refs/INS_ESKF.md.
     */
    internal fun earthRateEnuRadps(latitudeDeg: Double): Vec3 {
        require(latitudeDeg.isFinite())
        val lat = latitudeDeg * PI / 180.0
        return Vec3(0.0, OMEGA_IE_RADPS * cos(lat), OMEGA_IE_RADPS * sin(lat))
    }

    /**
     * Groves (5.53) Coriolis piece (2 ω_ie^n) × v^n, ENU. Transport rate ω_en
     * is smaller (v/R). Phone IMU noise is ~0.2 m/s². This term is ~0.003 m/s²
     * at 20 m/s, so n-frame mechanization omits Earth rate on purpose.
     */
    internal fun coriolisAccelEnu(latitudeDeg: Double, velocityEnu: Vec3): Vec3 {
        val w = earthRateEnuRadps(latitudeDeg)
        return Vec3(2.0 * w.x, 2.0 * w.y, 2.0 * w.z).cross(velocityEnu)
    }

    /** Course clockwise from true north, in [0, 2π). */
    fun courseRad(
        fromLatitudeDeg: Double,
        fromLongitudeDeg: Double,
        toLatitudeDeg: Double,
        toLongitudeDeg: Double,
    ): Double {
        val fromLat = fromLatitudeDeg * PI / 180.0
        val toLat = toLatitudeDeg * PI / 180.0
        val dLon = (toLongitudeDeg - fromLongitudeDeg) * PI / 180.0
        val y = sin(dLon) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(dLon)
        return wrapHeadingRad(atan2(y, x))
    }
}

fun wrapHeadingRad(value: Double): Double {
    require(value.isFinite()) { "heading_rad must be finite" }
    var heading = value % TWO_PI
    if (heading < 0.0) {
        heading += TWO_PI
    }
    if (heading >= TWO_PI) {
        heading = 0.0
    }
    return heading
}
