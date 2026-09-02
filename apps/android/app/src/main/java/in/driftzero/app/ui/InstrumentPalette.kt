package `in`.driftzero.app.ui

/**
 * One set of packed ARGB colour roles. Day is the paper sheet on the liberty
 * landmass. Night is olive chassis with paper ink on the OpenFreeMap dark sheet.
 * Contrast of every ink-on-surface pair is asserted in [InstrumentContrastTest].
 * No grain, wash, gradient, or translucent overlay. `haloFillAlpha` is the only
 * transparency and it sits on the map, never on chrome.
 */
data class InstrumentPaletteSet(
    val chassis: Long,
    val panel: Long,
    val well: Long,
    val panelPressed: Long,
    val hairline: Long,
    val ink: Long,
    val inkDim: Long,
    val lampOk: Long,
    val lampCaution: Long,
    val lampAlert: Long,
    val marker: Long,
    val routeFill: Long,
    val routeCasing: Long,
    val paper: Long,
    val haloFillAlpha: Float,
)

object InstrumentPalette {
    const val MARKER_BLUE = 0xFF1E6BFFL
    const val ROUTE_CASING = 0xFF0E3E9CL
    const val NIGHT_CHASSIS = 0xFF0B0D0AL
    const val NIGHT_INK = 0xFFF4F1E8L

    val DAY = InstrumentPaletteSet(
        chassis = 0xFFF2EFE9L,
        panel = 0xFFFFFFFFL,
        well = 0xFFEBE7DFL,
        panelPressed = 0xFFE8E6DCL,
        hairline = 0xFFD4D0C8L,
        ink = 0xFF1A1C19L,
        inkDim = 0xFF3D413AL,
        lampOk = 0xFF2E7D32L,
        lampCaution = 0xFFB86A00L,
        lampAlert = 0xFFC62828L,
        marker = MARKER_BLUE,
        routeFill = MARKER_BLUE,
        routeCasing = ROUTE_CASING,
        paper = 0xFFFFFFFFL,
        haloFillAlpha = 0.14f,
    )

    val NIGHT = InstrumentPaletteSet(
        chassis = NIGHT_CHASSIS,
        panel = 0xFF161A14L,
        well = 0xFF1F241DL,
        panelPressed = 0xFF0F120EL,
        hairline = 0xFF2E342BL,
        ink = NIGHT_INK,
        inkDim = 0xFFB8B4A8L,
        lampOk = 0xFF4CCB5AL,
        lampCaution = 0xFFE6A317L,
        lampAlert = 0xFFFF5A4EL,
        marker = MARKER_BLUE,
        routeFill = MARKER_BLUE,
        routeCasing = ROUTE_CASING,
        paper = NIGHT_INK,
        haloFillAlpha = 0.18f,
    )

    fun red(color: Long): Int = ((color shr 16) and 0xFFL).toInt()

    fun green(color: Long): Int = ((color shr 8) and 0xFFL).toInt()

    fun blue(color: Long): Int = (color and 0xFFL).toInt()

    fun relativeLuminance(color: Long): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel(red(color))
        val g = channel(green(color))
        val b = channel(blue(color))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun contrastRatio(foreground: Long, background: Long): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
