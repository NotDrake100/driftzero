package `in`.driftzero.app.maps

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

internal fun installAreaPackFromUri(
    context: Context,
    store: AreaPackStore,
    uri: Uri,
    tree: Boolean,
): AreaPack? {
    val work = File(context.cacheDir, "area-import/${System.currentTimeMillis()}")
    work.mkdirs()
    try {
        if (tree) {
            copyDocumentTree(context, uri, work)
            return store.installSideload(AreaPackImport.findPackRoot(work))
        }
        val name = queryDisplayName(context, uri) ?: "pack.bin"
        val dest = File(work, safeFileName(name))
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return if (dest.name.endsWith(".zip", ignoreCase = true) || isZipFile(dest)) {
            AreaPackImport.installFromZip(store, dest)
        } else {
            store.installSideload(AreaPackImport.findPackRoot(work))
        }
    } finally {
        work.deleteRecursively()
    }
}

private fun safeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    if (base.isBlank() || base == "." || base == ".." || base.contains("..")) {
        return "pack.bin"
    }
    return base
}

private fun isZipFile(file: File): Boolean {
    if (file.length() < 4) {
        return false
    }
    file.inputStream().use { input ->
        val header = ByteArray(4)
        if (input.read(header) < 4) {
            return false
        }
        return header[0] == 0x50.toByte() && header[1] == 0x4b.toByte()
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                return cursor.getString(index)
            }
        }
    }
    return uri.lastPathSegment
}

private fun copyDocumentTree(context: Context, treeUri: Uri, dest: File) {
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
    copyDocumentChildren(context, treeUri, children, dest)
}

private fun copyDocumentChildren(context: Context, treeUri: Uri, childrenUri: Uri, dest: File) {
    dest.mkdirs()
    val resolver = context.contentResolver
    resolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        if (idCol < 0 || nameCol < 0) {
            return
        }
        while (cursor.moveToNext()) {
            val id = cursor.getString(idCol) ?: continue
            if (nameCol < 0) {
                continue
            }
            val rawName = cursor.getString(nameCol) ?: continue
            val name = safeFileName(rawName)
            val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
            val child = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            val out = File(dest, name)
            if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                val nested = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
                copyDocumentChildren(context, treeUri, nested, out)
            } else {
                resolver.openInputStream(child)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
