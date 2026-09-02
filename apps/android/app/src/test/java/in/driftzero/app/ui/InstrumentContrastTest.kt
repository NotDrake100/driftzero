package `in`.driftzero.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentContrastTest {
    @Test
    fun bodyInkOnChassisMeetsAaa() {
        val ratio = InstrumentPalette.contrastRatio(InstrumentPalette.INK, InstrumentPalette.CHASSIS)
        assertTrue("ink on chassis contrast was $ratio", ratio >= 7.0)
    }

    @Test
    fun dimInkOnChassisMeetsAa() {
        val ratio = InstrumentPalette.contrastRatio(InstrumentPalette.INK_DIM, InstrumentPalette.CHASSIS)
        assertTrue("dim ink on chassis contrast was $ratio", ratio >= 4.5)
    }

    @Test
    fun bodyInkOnPanelMeetsAaa() {
        val ratio = InstrumentPalette.contrastRatio(InstrumentPalette.INK, InstrumentPalette.PANEL)
        assertTrue("ink on panel contrast was $ratio", ratio >= 7.0)
    }

    @Test
    fun bodyInkOnWellMeetsAaa() {
        val ratio = InstrumentPalette.contrastRatio(InstrumentPalette.INK, InstrumentPalette.WELL)
        assertTrue("ink on well contrast was $ratio", ratio >= 7.0)
    }

    @Test
    fun bodyInkOnPressedPanelMeetsAaa() {
        val ratio = InstrumentPalette.contrastRatio(InstrumentPalette.INK, InstrumentPalette.PANEL_PRESSED)
        assertTrue("ink on pressed panel contrast was $ratio", ratio >= 7.0)
    }

    @Test
    fun dimInkOnPanelMeetsAa() {
        val ratio = InstrumentPalette.contrastRatio(InstrumentPalette.INK_DIM, InstrumentPalette.PANEL)
        assertTrue("dim ink on panel contrast was $ratio", ratio >= 4.5)
    }
}
