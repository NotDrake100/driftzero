package `in`.driftzero.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalGraphLoadTest {
    @Test
    fun liveLoadUsesPackGraphAndWayNames() {
        val src = readMain()
        assertTrue(src.contains("LocalGraphPolicy.loadGraphAndNames"))
        assertTrue(src.contains("LocalGraphPolicy.routerOf"))
        assertTrue(src.contains("packs.graphFile(active)"))
        assertFalse(src.contains("graph.bin skipped: no origin window"))
        assertFalse(src.contains("fun routeWindow("))
        assertFalse(src.contains("OsmGraphLoader.load("))
    }

    private fun readMain(): String {
        val candidates = listOf(
            File("src/main/java/in/driftzero/app/MainActivity.kt"),
            File("app/src/main/java/in/driftzero/app/MainActivity.kt"),
            File("apps/android/app/src/main/java/in/driftzero/app/MainActivity.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("MainActivity.kt not found from ${File(".").absolutePath}")
    }
}
