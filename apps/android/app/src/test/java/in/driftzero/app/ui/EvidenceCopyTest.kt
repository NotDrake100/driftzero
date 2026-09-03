package `in`.driftzero.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvidenceCopyTest {
    @Test
    fun gateIsNotMetAndTimesFmIsOffPhone() {
        assertFalse(EvidenceCopy.gateMetOnIoVnbd())
        assertFalse(EvidenceCopy.timesFmOnPhone())
        assertTrue(EvidenceCopy.gateLine().contains("not met"))
        assertTrue(EvidenceCopy.gateLine().contains(EvidenceCopy.GATE))
        assertEquals("TimesFM is not on the phone", EvidenceCopy.timesFmLine())
    }

    @Test
    fun persistIsTheScreeningHeadline() {
        assertEquals("0.5168", EvidenceCopy.PERSIST_DRIFT_P50)
        assertTrue(EvidenceCopy.persistHeadline().contains("0.5168"))
        assertTrue(EvidenceCopy.screeningHeadline().contains("0.5168"))
        assertTrue(EvidenceCopy.screeningHeadline().contains("not met"))
        assertTrue(EvidenceCopy.screeningBody().contains("0.5168"))
        assertTrue(EvidenceCopy.screeningBody().contains("0.10"))
        assertTrue(EvidenceCopy.screeningBody().contains("not met"))
    }

    @Test
    fun kotlinV5CitesLeakFreeNotLeakyHeadline() {
        val line = EvidenceCopy.kotlinV5LeakFreeLine()
        assertTrue(line.contains("0.611"))
        assertTrue(line.contains("187.5"))
        assertTrue(line.contains("0.257"))
        assertFalse(line.contains(EvidenceCopy.LEAKY_KOTLIN_DRIFT))
        val body = EvidenceCopy.screeningBody()
        assertTrue(body.contains("0.611"))
        assertTrue(body.contains("187.5"))
        assertTrue(body.contains("0.257"))
        assertTrue(body.contains("Do not cite leaky-frame 0.541"))
        assertFalse(EvidenceCopy.citesLeakyKotlinAsHeadline(body))
        assertTrue(
            EvidenceCopy.citesLeakyKotlinAsHeadline("kotlin drift p50 0.541 on leaky frames"),
        )
    }

    @Test
    fun labStringsCarryTheSameFacts() {
        val xml = readStringsXml()
        assertEquals(5, EvidenceCopy.LAB_UNLOCK_TAPS)
        assertEquals(EvidenceCopy.screeningBody(), xmlString(xml, "about_screening_body"))
        assertEquals(EvidenceCopy.judgeScoreOnlyLine(), xmlString(xml, "judge_score_only"))
        assertTrue(xmlString(xml, "about_ai_body").contains(EvidenceCopy.timesFmLine()))
        assertTrue(xmlString(xml, "about_not_body").contains(EvidenceCopy.timesFmLine()))
        assertFalse(EvidenceCopy.citesLeakyKotlinAsHeadline(xmlString(xml, "about_screening_body")))
    }

    @Test
    fun defaultAboutOmitsScreeningStory() {
        val xml = readStringsXml()
        val defaultNames = listOf(
            "about_what_body",
            "about_outage_body",
            "about_demo_1",
            "about_demo_2",
            "about_demo_3",
            "about_demo_4",
            "about_demo_5",
            "about_emulator",
            "about_no_obd",
            "about_notices_body",
        )
        val blob = defaultNames.joinToString(separator = "\n") { xmlString(xml, it) }
        assertFalse(blob.contains("0.5168"))
        assertFalse(blob.contains("0.611"))
        assertFalse(blob.contains("0.541"))
        assertFalse(blob.contains("IO-VNBD"))
        assertFalse(blob.contains("TimesFM"))
        assertFalse(blob.contains("persist"))
        assertFalse(blob.contains("not met"))
        assertFalse(blob.contains("gate 0.10"))
    }

    @Test
    fun aboutScreenGatesScreeningBehindLab() {
        val src = readAboutScreen()
        val labIdx = src.indexOf("if (labOpen)")
        val screeningIdx = src.indexOf("about_screening_body")
        val aiIdx = src.indexOf("about_ai_body")
        val notIdx = src.indexOf("about_not_body")
        assertTrue(labIdx >= 0)
        assertTrue(screeningIdx > labIdx)
        assertTrue(aiIdx > labIdx)
        assertTrue(notIdx > labIdx)
        assertTrue(src.contains("EvidenceCopy.LAB_UNLOCK_TAPS"))
        assertFalse(src.contains("about_india"))
    }

    private fun readStringsXml(): String {
        val candidates = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
            File("apps/android/app/src/main/res/values/strings.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("strings.xml not found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun readAboutScreen(): String {
        val candidates = listOf(
            File("src/main/java/in/driftzero/app/ui/AboutScreen.kt"),
            File("app/src/main/java/in/driftzero/app/ui/AboutScreen.kt"),
            File("apps/android/app/src/main/java/in/driftzero/app/ui/AboutScreen.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("AboutScreen.kt not found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun xmlString(xml: String, name: String): String {
        val open = "<string name=\"$name\">"
        val start = xml.indexOf(open)
        require(start >= 0) { "missing string $name" }
        val from = start + open.length
        val end = xml.indexOf("</string>", from)
        return xml.substring(from, end)
    }
}
