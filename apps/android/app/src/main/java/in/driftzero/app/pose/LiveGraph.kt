package `in`.driftzero.app.pose

import java.io.File

/**
 * Compact [graph.bin] is the only live matcher input. City OSM PBF/XML
 * expands past a phone heap (a 2.5 MB Pune extract OOM'd at 192 MB on
 * the UI thread). Those files stay on disk for desktop tooling.
 */
internal fun liveGraphFile(file: File): File? {
    return if (file.name.equals("graph.bin", ignoreCase = true)) file else null
}
