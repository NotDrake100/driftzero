package `in`.driftzero.app.pose

import java.io.File

/**
 * Compact [graph.bin] is the only live matcher and map-coast input.
 * City OSM PBF/XML expands past a phone heap (a 2.5 MB Pune extract
 * OOM'd at 192 MB on the UI thread). Those files stay on disk for
 * desktop tooling. Drop a Ready pack on the phone. Do not parse the
 * extract here.
 */
internal fun liveGraphFile(file: File): File? {
    return if (file.name.equals("graph.bin", ignoreCase = true)) file else null
}
