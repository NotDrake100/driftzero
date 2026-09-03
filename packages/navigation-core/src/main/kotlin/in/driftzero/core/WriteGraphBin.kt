package `in`.driftzero.core

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Desktop converter: OSM highway PBF or XML to compact [graph.bin].
 *
 * ```
 * ./gradlew :navigation-core:writeGraphBin --args="--input graph.osm.pbf --output graph.bin --id pune-core"
 * ```
 */
object WriteGraphBin {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = run(args)
        if (code != 0) {
            exitProcess(code)
        }
    }

    fun run(
        args: Array<String>,
        stdout: PrintStream = System.out,
        stderr: PrintStream = System.err,
    ): Int {
        val parsed = try {
            parseArgs(args)
        } catch (error: Exception) {
            stderr.println(error.message)
            return 2
        }
        return try {
            val input = parsed.input
            val bytes = Files.readAllBytes(input)
            val names = try {
                if (looksLikePbf(bytes)) {
                    OsmPbfWriter.readWayNames(bytes)
                } else {
                    emptyMap()
                }
            } catch (_: Exception) {
                emptyMap()
            }
            val graph = OsmGraphLoader.load(
                input,
                parsed.id ?: input.fileName.toString(),
                parsed.bbox,
            )
            if (graph.isEmpty()) {
                stderr.println("no vehicle edges in ${input.fileName}")
                return 1
            }
            val out = RoadGraphBin.write(graph, names)
            parsed.output.parent?.let { Files.createDirectories(it) }
            Files.write(parsed.output, out)
            stdout.println(
                "wrote ${parsed.output} bytes=${out.size} edges=${graph.edges.size} " +
                    "nodes=${graph.nodes.size} names=${names.size}",
            )
            0
        } catch (error: Exception) {
            stderr.println(error.toString())
            1
        }
    }

    private fun looksLikePbf(bytes: ByteArray): Boolean {
        if (bytes.size < 8) {
            return false
        }
        val headerLen = ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
        return headerLen in 1..64 && bytes.size > 4 + headerLen
    }

    private fun parseArgs(args: Array<String>): Parsed {
        var input: Path? = null
        var output: Path? = null
        var id: String? = null
        var south: Double? = null
        var west: Double? = null
        var north: Double? = null
        var east: Double? = null
        var i = 0
        while (i < args.size) {
            when (val key = args[i]) {
                "--input" -> {
                    input = Path.of(args.getOrNull(i + 1) ?: error("--input needs a path"))
                    i += 2
                }
                "--output" -> {
                    output = Path.of(args.getOrNull(i + 1) ?: error("--output needs a path"))
                    i += 2
                }
                "--id" -> {
                    id = args.getOrNull(i + 1) ?: error("--id needs a slug")
                    i += 2
                }
                "--south" -> {
                    south = args.getOrNull(i + 1)?.toDoubleOrNull() ?: error("--south needs a number")
                    i += 2
                }
                "--west" -> {
                    west = args.getOrNull(i + 1)?.toDoubleOrNull() ?: error("--west needs a number")
                    i += 2
                }
                "--north" -> {
                    north = args.getOrNull(i + 1)?.toDoubleOrNull() ?: error("--north needs a number")
                    i += 2
                }
                "--east" -> {
                    east = args.getOrNull(i + 1)?.toDoubleOrNull() ?: error("--east needs a number")
                    i += 2
                }
                else -> error("unknown argument: $key")
            }
        }
        val inPath = input ?: error("--input is required")
        val outPath = output ?: error("--output is required")
        val bbox = if (south != null && west != null && north != null && east != null) {
            Wgs84Bbox(south, west, north, east)
        } else {
            null
        }
        return Parsed(inPath, outPath, id, bbox)
    }

    private data class Parsed(
        val input: Path,
        val output: Path,
        val id: String?,
        val bbox: Wgs84Bbox?,
    )
}
