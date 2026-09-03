package `in`.driftzero.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * Compact [graph.bin] for the live matcher and [LocalRouter]. The bytes are
 * OSM PBF so [OsmGraphLoader.load] accepts a `.bin` name via the blob header.
 * Road names live as OSM `name` tags on those ways.
 */
object RoadGraphBin {
    fun write(graph: RoadGraph, namesByOsmWay: Map<Long, String> = emptyMap()): ByteArray =
        OsmPbfWriter.fromGraph(graph, namesByOsmWay)

    fun load(path: Path, packageId: String = path.fileName.toString()): Pair<RoadGraph, Map<Long, String>> {
        val bytes = Files.readAllBytes(path)
        return load(bytes, packageId)
    }

    fun load(bytes: ByteArray, packageId: String): Pair<RoadGraph, Map<Long, String>> {
        val graph = OsmGraphLoader.load(bytes, packageId, OsmFormat.PBF)
        val names = OsmPbfWriter.readWayNames(bytes)
        return graph to names
    }
}
