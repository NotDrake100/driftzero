package `in`.driftzero.app.pose

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveGraphTest {
    @Test
    fun compactBinIsLive() {
        assertNotNull(liveGraphFile(File("/packs/graph.bin")))
        assertNotNull(liveGraphFile(File("/packs/GRAPH.BIN")))
    }

    @Test
    fun osmExtractsStayOffThePhonePath() {
        assertNull(liveGraphFile(File("/packs/graph.osm.pbf")))
        assertNull(liveGraphFile(File("/packs/graph.osm.xml")))
        assertNull(liveGraphFile(File("/packs/graph.osm")))
    }
}
