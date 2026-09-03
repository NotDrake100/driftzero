package `in`.driftzero.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import `in`.driftzero.app.R
import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.maps.AreaPackState
import `in`.driftzero.app.maps.GeoBbox
import java.util.Locale

@Composable
internal fun OfflineAreasScreen(
    installed: List<AreaPack>,
    queued: List<AreaPack>,
    sideloadPath: String,
    bytesOf: (AreaPack) -> Long?,
    onBack: () -> Unit,
    onImportZip: (Uri) -> Unit = {},
    onImportFolder: (Uri) -> Unit = {},
    importNote: String? = null,
) {
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onImportZip(uri)
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            onImportFolder(uri)
        }
    }
    val ready = installed.any { it.state == AreaPackState.Ready }
    ScreenScaffold(title = stringResource(R.string.offline_title), onBack = onBack) {
        if (!ready) {
            BasicText(text = stringResource(R.string.offline_notice), style = InstrumentTheme.type.body)
        }
        BasicText(text = stringResource(R.string.offline_installed), style = InstrumentTheme.type.label)
        if (installed.isEmpty()) {
            BasicText(text = stringResource(R.string.offline_state_absent), style = InstrumentTheme.type.caption)
        } else {
            installed.forEach { pack ->
                PackBlock(pack = pack, bytes = bytesOf(pack), queued = false)
            }
        }
        BasicText(text = stringResource(R.string.offline_queued), style = InstrumentTheme.type.label)
        if (queued.isEmpty()) {
            BasicText(text = stringResource(R.string.offline_none), style = InstrumentTheme.type.caption)
        } else {
            queued.forEach { pack ->
                PackBlock(pack = pack, bytes = bytesOf(pack), queued = true)
            }
        }
        SecondaryButton(
            label = stringResource(R.string.offline_import_zip),
            onClick = {
                zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton(
            label = stringResource(R.string.offline_import_folder),
            onClick = { folderPicker.launch(null) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (importNote != null) {
            BasicText(text = importNote, style = InstrumentTheme.type.readout)
        }
        BasicText(text = stringResource(R.string.offline_sideload), style = InstrumentTheme.type.label)
        BasicText(text = sideloadPath, style = InstrumentTheme.type.readout)
        BasicText(text = stringResource(R.string.offline_sideload_files), style = InstrumentTheme.type.caption)
        BasicText(text = stringResource(R.string.offline_sideload_adb), style = InstrumentTheme.type.caption)
    }
}

@Composable
private fun PackBlock(pack: AreaPack, bytes: Long?, queued: Boolean) {
    ListRow(
        label = pack.manifest.id.value,
        value = packStateText(pack, queued),
    )
    BasicText(text = packBboxText(pack.manifest.bbox), style = InstrumentTheme.type.caption)
    val osm = packOsmDateText(pack.manifest.osmSnapshot)
    val size = bytes?.takeIf { it > 0L }?.let { InstrumentFormat.formatBytes(it) }
    val detail = if (size != null) "$osm, $size" else osm
    BasicText(text = detail, style = InstrumentTheme.type.readout)
}

@Composable
private fun packStateText(pack: AreaPack, queued: Boolean): String = when {
    queued || pack.state == AreaPackState.Queued || pack.state == AreaPackState.Absent ->
        stringResource(R.string.offline_state_absent)
    pack.state == AreaPackState.Ready -> stringResource(R.string.offline_state_ready)
    pack.state == AreaPackState.Corrupt -> stringResource(R.string.offline_state_corrupt)
    else -> stringResource(R.string.offline_state_absent)
}

internal fun packBboxText(bbox: GeoBbox): String =
    String.format(
        Locale.US,
        "%.4f, %.4f to %.4f, %.4f",
        bbox.southLatDeg,
        bbox.westLonDeg,
        bbox.northLatDeg,
        bbox.eastLonDeg,
    )

internal fun packOsmDateText(snapshot: String?): String {
    if (snapshot.isNullOrBlank()) {
        return "OSM date unknown"
    }
    val day = snapshot.take(10)
    return "OSM $day"
}

internal fun areaPackSideloadHint(storeRoot: String): String =
    "$storeRoot/installed/<id>/"
