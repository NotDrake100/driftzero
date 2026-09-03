package `in`.driftzero.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverflowMenuTest {
    @Test
    fun suggestionsDismissTheOverflowPanel() {
        assertFalse(OverflowMenu.shouldDismiss(0))
        assertTrue(OverflowMenu.shouldDismiss(1))
        assertTrue(OverflowMenu.shouldDismiss(5))
    }

    @Test
    fun moreControlIsAMarkNotClippedText() {
        val chrome = readUi("TravelChrome.kt")
        assertTrue(chrome.contains("OverflowMark"))
        assertTrue(chrome.contains("ClearMark"))
        assertTrue(chrome.contains("OverflowMenu.shouldDismiss"))
        assertFalse(chrome.contains("BasicText(text = label, style = InstrumentTheme.type.label)"))
        val marks = readUi("TravelMarks.kt")
        assertTrue(marks.contains("fun OverflowMark"))
    }

    private fun readUi(name: String): String {
        val candidates = listOf(
            File("src/main/java/in/driftzero/app/ui/$name"),
            File("app/src/main/java/in/driftzero/app/ui/$name"),
            File("apps/android/app/src/main/java/in/driftzero/app/ui/$name"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
