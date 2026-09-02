package `in`.driftzero.app.ui

/**
 * Travel-map colors. Packed ARGB. Paper sheet on a liberty landmass.
 * Contrast of ink on chassis, panel, and well is checked in
 * [InstrumentContrastTest]. No grain, wash, or translucent overlay.
 */
object InstrumentPalette {
    const val CHASSIS = 0xFFF2EFE9L
    const val PANEL = 0xFFFFFFFFL
    const val WELL = 0xFFEBE7DFL
    const val PANEL_PRESSED = 0xFFE8E6DCL
    const val HAIRLINE = 0xFFD4D0C8L
    const val INK = 0xFF1A1C19L
    const val INK_DIM = 0xFF3D413AL
    const val LAMP_OK = 0xFF2E7D32L
    const val LAMP_CAUTION = 0xFFB86A00L
    const val LAMP_ALERT = 0xFFC62828L
    const val MARKER_BLUE = 0xFF1E6BFFL

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
