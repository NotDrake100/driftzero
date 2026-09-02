package `in`.driftzero.app.pose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One satellite row copied from [android.location.GnssStatus].
 * [constellation] uses the sensor-frame contract names (GPS, GALILEO, IRNSS).
 */
data class GnssSatRow(
    val constellation: String,
    val usedInFix: Boolean,
)

/**
 * Counts of satellites Android reports as used in the current fix, plus
 * NavIC/IRNSS visibility. This is a chipset classification, not integrity.
 */
data class NavicSnapshot(
    val gpsUsed: Int,
    val galileoUsed: Int,
    val navicUsed: Int,
    val navicVisible: Int,
    val visible: Int,
    val used: Int,
    val constellations: Set<String>,
) {
    val irnssReported: Boolean get() = navicVisible > 0

    /** Shown only when IRNSS SVs are used in the fix. Idle chrome stays empty. */
    val chipLabel: String?
        get() = if (navicUsed > 0) "NavIC $navicUsed" else null

    companion object {
        val NONE: NavicSnapshot = NavicSnapshot(
            gpsUsed = 0,
            galileoUsed = 0,
            navicUsed = 0,
            navicVisible = 0,
            visible = 0,
            used = 0,
            constellations = emptySet(),
        )
    }
}

/**
 * Tallies GPS / Galileo / IRNSS used-in-fix counts from [GnssSatRow] copies.
 * The [android.location.GnssStatus] callback must only copy rows here.
 */
class NavicMonitor {
    private val _visibility = MutableStateFlow(NavicSnapshot.NONE)
    val visibility: StateFlow<NavicSnapshot> = _visibility.asStateFlow()

    /**
     * Replace the snapshot. Returns a log line when IRNSS membership changed
     * or is present, otherwise null so logcat is not a per-epoch flood.
     */
    fun ingest(sats: List<GnssSatRow>): String? {
        val next = tally(sats)
        val previous = _visibility.value
        if (next == previous) {
            return null
        }
        _visibility.value = next
        return logMessage(previous, next)
    }

    fun clear() {
        _visibility.value = NavicSnapshot.NONE
    }

    companion object {
        const val LOG_TAG: String = "DriftZeroNavIC"

        const val GPS: String = "GPS"
        const val GALILEO: String = "GALILEO"
        const val IRNSS: String = "IRNSS"

        // android.location.GnssStatus constellation ints (API 24+, IRNSS API 29).
        const val CONSTELLATION_UNKNOWN: Int = 0
        const val CONSTELLATION_GPS: Int = 1
        const val CONSTELLATION_SBAS: Int = 2
        const val CONSTELLATION_GLONASS: Int = 3
        const val CONSTELLATION_QZSS: Int = 4
        const val CONSTELLATION_BEIDOU: Int = 5
        const val CONSTELLATION_GALILEO: Int = 6
        const val CONSTELLATION_IRNSS: Int = 7

        fun constellationName(androidType: Int): String = when (androidType) {
            CONSTELLATION_GPS -> GPS
            CONSTELLATION_SBAS -> "SBAS"
            CONSTELLATION_GLONASS -> "GLONASS"
            CONSTELLATION_QZSS -> "QZSS"
            CONSTELLATION_BEIDOU -> "BEIDOU"
            CONSTELLATION_GALILEO -> GALILEO
            CONSTELLATION_IRNSS -> IRNSS
            else -> "UNKNOWN"
        }

        fun tally(sats: List<GnssSatRow>): NavicSnapshot {
            var gpsUsed = 0
            var galileoUsed = 0
            var navicUsed = 0
            var navicVisible = 0
            var used = 0
            val names = LinkedHashSet<String>()
            for (sat in sats) {
                val name = sat.constellation
                names.add(name)
                if (name == IRNSS) {
                    navicVisible += 1
                }
                if (!sat.usedInFix) {
                    continue
                }
                used += 1
                when (name) {
                    GPS -> gpsUsed += 1
                    GALILEO -> galileoUsed += 1
                    IRNSS -> navicUsed += 1
                }
            }
            return NavicSnapshot(
                gpsUsed = gpsUsed,
                galileoUsed = galileoUsed,
                navicUsed = navicUsed,
                navicVisible = navicVisible,
                visible = sats.size,
                used = used,
                constellations = names,
            )
        }

        fun logMessage(previous: NavicSnapshot, next: NavicSnapshot): String? {
            if (!next.irnssReported && !previous.irnssReported) {
                return null
            }
            return "IRNSS visible=${next.navicVisible} used=${next.navicUsed} " +
                "GPS used=${next.gpsUsed} Galileo used=${next.galileoUsed}"
        }
    }
}
