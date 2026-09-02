package `in`.driftzero.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG pairs for both palettes. Body ink 7:1 (AAA), secondary ink 4.5:1 (AA),
 * non-text marks (lamps, marker, route casing under fill) 3:1.
 */
class InstrumentContrastTest {
    private val sets = mapOf("day" to InstrumentPalette.DAY, "night" to InstrumentPalette.NIGHT)

    private fun assertAtLeast(name: String, ratio: Double, floor: Double) {
        assertTrue("$name contrast was $ratio, floor $floor", ratio >= floor)
    }

    @Test
    fun bodyInkOnEverySurfaceMeetsAaa() {
        sets.forEach { (label, s) ->
            listOf("chassis" to s.chassis, "panel" to s.panel, "well" to s.well, "panelPressed" to s.panelPressed)
                .forEach { (surface, bg) ->
                    assertAtLeast("$label ink on $surface", InstrumentPalette.contrastRatio(s.ink, bg), 7.0)
                }
        }
    }

    @Test
    fun dimInkOnChassisAndPanelMeetsAa() {
        sets.forEach { (label, s) ->
            assertAtLeast("$label dim ink on chassis", InstrumentPalette.contrastRatio(s.inkDim, s.chassis), 4.5)
            assertAtLeast("$label dim ink on panel", InstrumentPalette.contrastRatio(s.inkDim, s.panel), 4.5)
            assertAtLeast("$label dim ink on well", InstrumentPalette.contrastRatio(s.inkDim, s.well), 4.5)
        }
    }

    @Test
    fun lampsAndMarkerOnPanelMeetNonTextContrast() {
        sets.forEach { (label, s) ->
            assertAtLeast("$label lampOk on panel", InstrumentPalette.contrastRatio(s.lampOk, s.panel), 3.0)
            assertAtLeast("$label lampCaution on panel", InstrumentPalette.contrastRatio(s.lampCaution, s.panel), 3.0)
            assertAtLeast("$label lampAlert on panel", InstrumentPalette.contrastRatio(s.lampAlert, s.panel), 3.0)
            assertAtLeast("$label marker on panel", InstrumentPalette.contrastRatio(s.marker, s.panel), 3.0)
        }
    }

    @Test
    fun paperStrokeReadsOnMarkerAndCasingSitsUnderFill() {
        sets.forEach { (label, s) ->
            assertAtLeast("$label paper on marker", InstrumentPalette.contrastRatio(s.paper, s.marker), 3.0)
            assertTrue(
                "$label route casing must be darker than the fill",
                InstrumentPalette.relativeLuminance(s.routeCasing) < InstrumentPalette.relativeLuminance(s.routeFill),
            )
        }
    }

    @Test
    fun primaryButtonInkOnChassisTextMeetsAaa() {
        sets.forEach { (label, s) ->
            assertAtLeast("$label chassis text on ink button", InstrumentPalette.contrastRatio(s.chassis, s.ink), 7.0)
        }
    }

    @Test
    fun nightPaletteMatchesDesignRuleReferenceColours() {
        assertEquals(0xFF0B0D0AL, InstrumentPalette.NIGHT.chassis)
        assertEquals(0xFF161A14L, InstrumentPalette.NIGHT.panel)
        assertEquals(0xFFF4F1E8L, InstrumentPalette.NIGHT.ink)
        assertEquals(0xFF1E6BFFL, InstrumentPalette.NIGHT.marker)
        assertEquals(InstrumentPalette.DAY.marker, InstrumentPalette.NIGHT.marker)
    }

    @Test
    fun haloFillIsTheOnlyTransparencyAndStaysFaint() {
        sets.values.forEach { s ->
            assertTrue(s.haloFillAlpha in 0.1f..0.25f)
        }
    }
}
