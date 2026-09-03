package `in`.driftzero.app.maps

import `in`.driftzero.core.LocalRouter
import `in`.driftzero.core.OsmGraphLoader
import `in`.driftzero.core.RoadGraph
import `in`.driftzero.core.RoadGraphBin
import java.io.File

/**
 * Live matcher and [LocalRouter] from an installed pack graph. Compact
 * [graph.bin] / PBF keep OSM `name` tags. XML sideloads load topology only.
 * The pack file is the window. Do not shrink to a 3 km origin pad.
 */
object LocalGraphPolicy {
    fun loadGraphAndNames(file: File, packageId: String): Pair<RoadGraph, Map<Long, String>> {
        val name = file.name.lowercase()
        if (isCompactBin(name)) {
            return RoadGraphBin.load(file.toPath(), packageId)
        }
        return OsmGraphLoader.load(file.toPath(), packageId) to emptyMap()
    }

    fun routerOf(graph: RoadGraph, names: Map<Long, String>): LocalRouter? {
        if (graph.isEmpty()) {
            return null
        }
        return LocalRouter(graph, names)
    }

    fun isCompactBin(fileName: String): Boolean {
        val name = fileName.lowercase()
        return name.endsWith(".bin") || name.endsWith(".pbf")
    }
}
