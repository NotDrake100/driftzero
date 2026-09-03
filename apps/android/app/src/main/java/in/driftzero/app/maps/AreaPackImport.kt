package `in`.driftzero.app.maps

import java.io.File
import java.util.zip.ZipInputStream

/** Unpack a sideloaded zip into a directory [AreaPackStore.installSideload] can read. */
object AreaPackImport {
    fun installFromZip(store: AreaPackStore, zip: File): AreaPack? {
        val unpack = File(zip.parentFile, zip.nameWithoutExtension + "-unpacked")
        unpack.deleteRecursively()
        if (!unpack.mkdirs()) {
            return null
        }
        unzip(zip, unpack)
        return store.installSideload(findPackRoot(unpack))
    }

    fun findPackRoot(dir: File): File {
        if (File(dir, AreaPackStore.MANIFEST).isFile) {
            return dir
        }
        val found = dir.walkTopDown()
            .maxDepth(3)
            .filter { it.isDirectory && File(it, AreaPackStore.MANIFEST).isFile }
            .toList()
        return found.singleOrNull() ?: dir
    }

    fun unzip(zip: File, dest: File) {
        val root = dest.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val target = File(root, entry.name).canonicalFile
                if (target != root && !target.path.startsWith(root.path + File.separator)) {
                    throw IllegalArgumentException("zip path")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> stream.copyTo(output) }
            }
        }
    }
}
