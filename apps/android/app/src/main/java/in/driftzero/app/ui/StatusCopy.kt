package `in`.driftzero.app.ui

import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.pose.NavicSnapshot
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.MapMatchStatus
import `in`.driftzero.core.NavigationState

/** Sheet value strings. Labels stay in strings.xml. */
object StatusCopy {
    fun gnssAgeS(lastSeenNs: Long?, nowNs: Long): Double? {
        if (lastSeenNs == null || nowNs < lastSeenNs) {
            return null
        }
        return (nowNs - lastSeenNs) / 1_000_000_000.0
    }

    fun lastTrustedAgo(ageS: Double): String = "${InstrumentFormat.formatSeconds(ageS)} ago"

    fun radius95(metres: Double): String = "${InstrumentFormat.formatRadius(metres)} 95%"

    fun heading(headingRad: Double, heading95Rad: Double): String =
        "${InstrumentFormat.formatHeadingDeg(headingRad)}, ${InstrumentFormat.formatAngleDeg(heading95Rad)} 95%"

    fun mapMatch(status: MapMatchStatus, confidence: Double): String = when (status) {
        MapMatchStatus.MATCHED -> "Matched ${InstrumentFormat.formatConfidence(confidence)}"
        MapMatchStatus.AMBIGUOUS -> "Ambiguous ${InstrumentFormat.formatConfidence(confidence)}"
        MapMatchStatus.UNMATCHED -> "Off road"
        MapMatchStatus.NO_MAP -> "No area pack"
    }

    fun sensors(flags: Set<String>): String = when {
        flags.contains(DeadReckoningFilter.FLAG_NO_IMU) -> "No IMU"
        flags.contains(DeadReckoningFilter.FLAG_ZUPT) && flags.contains(DeadReckoningFilter.FLAG_NHC) -> "ZUPT, NHC"
        flags.contains(DeadReckoningFilter.FLAG_ZUPT) -> "ZUPT"
        flags.contains(DeadReckoningFilter.FLAG_NHC) -> "NHC"
        else -> "Accel, gyro ok"
    }

    fun model(flags: Set<String>, studentLoaded: Boolean): String = when {
        studentLoaded && flags.contains(DeadReckoningFilter.FLAG_MOTION_PSEUDO) -> "Speed student"
        flags.contains(DeadReckoningFilter.FLAG_MOTION_PSEUDO) -> "Heuristic speed"
        else -> "Filter only"
    }

    fun navic(snapshot: NavicSnapshot): String {
        if (!snapshot.constellations.contains(NavicSnapshotIRNSS) && snapshot.navicVisible == 0) {
            return "Not reported by chipset"
        }
        return "${snapshot.navicVisible} visible, ${snapshot.navicUsed} used"
    }

    fun areaPack(pack: AreaPack?, bytes: Long?): String {
        if (pack == null) {
            return "None. Streets and routing from network"
        }
        val name = pack.manifest.label ?: pack.manifest.id.value
        return if (bytes != null && bytes > 0L) {
            "$name, ${InstrumentFormat.formatBytes(bytes)}"
        } else {
            name
        }
    }

    fun outputRate(p95Ms: Double?): String? {
        if (p95Ms == null || !p95Ms.isFinite() || p95Ms < 0.0) {
            return null
        }
        return "p95 gap ${p95Ms.toInt()} ms"
    }

    fun of(
        state: NavigationState,
        lastGnssSeenNs: Long?,
        nowNs: Long,
        studentLoaded: Boolean,
        navic: NavicSnapshot,
        pack: AreaPack?,
        packBytes: Long?,
        p95Ms: Double?,
    ): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>(11)
        modeReason(state)?.let { reason ->
            rows += "Reason" to reasonPlain(reason)
        }
        gnssAgeS(lastGnssSeenNs, nowNs)?.let { age ->
            rows += "GNSS age" to InstrumentFormat.formatSeconds(age)
        }
        rows += "Last trusted fix" to lastTrustedAgo(state.gnssHealth.lastTrustedFixAgeS)
        rows += "Confidence radius" to radius95(state.uncertainty.horizontal95.value)
        rows += "Heading" to heading(state.motion.heading.value, state.uncertainty.heading95Rad)
        rows += "Map match" to mapMatch(state.mapMatch.status, state.mapMatch.confidence)
        rows += "Sensors" to sensors(state.health.flags)
        rows += "Model" to model(state.health.flags, studentLoaded)
        rows += "NavIC" to navic(navic)
        rows += "Area pack" to areaPack(pack, packBytes)
        outputRate(p95Ms)?.let { rows += "Output rate" to it }
        return rows
    }

    private fun reasonPlain(reason: ModeReason): String = when (reason) {
        is ModeReason.NoFix -> "No fix for ${InstrumentFormat.formatSeconds(reason.ageS)}"
        ModeReason.Held -> "GNSS held"
        is ModeReason.RadiusLimit ->
            "Radius ${InstrumentFormat.formatRadius(reason.radiusM)} over ${InstrumentFormat.formatRadius(reason.limitM)} limit"
        is ModeReason.FixAccuracyOver ->
            "Fix accuracy over ${InstrumentFormat.formatRadius(reason.limitM)}"
        ModeReason.GatedFix -> "Fix disagreed with estimate"
        ModeReason.Reacquiring -> "Fix back, confirming"
        ModeReason.ImuGap -> "No IMU data"
    }
}

private const val NavicSnapshotIRNSS: String = "IRNSS"
