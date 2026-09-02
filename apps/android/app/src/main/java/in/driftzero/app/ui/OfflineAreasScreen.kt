package `in`.driftzero.app.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import `in`.driftzero.app.R
import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.maps.AreaPackState

@Composable
internal fun OfflineAreasScreen(
    installed: List<AreaPack>,
    queued: List<AreaPack>,
    sideloadPath: String,
    bytesOf: (AreaPack) -> Long?,
    onBack: () -> Unit,
) {
    val ready = installed.any { it.state == AreaPackState.Ready }
    ScreenScaffold(title = stringResource(R.string.offline_title), onBack = onBack) {
        if (!ready) {
            BasicText(text = stringResource(R.string.offline_notice), style = InstrumentTheme.type.body)
        }
        BasicText(text = stringResource(R.string.offline_installed), style = InstrumentTheme.type.label)
        if (installed.isEmpty()) {
            BasicText(text = stringResource(R.string.offline_none), style = InstrumentTheme.type.caption)
        } else {
            installed.forEach { pack ->
                ListRow(
                    label = pack.manifest.label ?: pack.manifest.id.value,
                    value = packState(pack) + packBytes(pack, bytesOf(pack)),
                )
                BasicText(text = stringResource(R.string.offline_no_expiry), style = InstrumentTheme.type.caption)
            }
        }
        BasicText(text = stringResource(R.string.offline_queued), style = InstrumentTheme.type.label)
        if (queued.isEmpty()) {
            BasicText(text = stringResource(R.string.offline_none), style = InstrumentTheme.type.caption)
        } else {
            queued.forEach { pack ->
                ListRow(
                    label = pack.manifest.id.value,
                    value = stringResource(R.string.offline_state_queued),
                )
            }
        }
        BasicText(text = stringResource(R.string.offline_sideload), style = InstrumentTheme.type.label)
        BasicText(text = sideloadPath, style = InstrumentTheme.type.readout)
        BasicText(text = stringResource(R.string.offline_sideload_files), style = InstrumentTheme.type.caption)
    }
}

@Composable
private fun packState(pack: AreaPack): String = when (pack.state) {
    AreaPackState.Ready -> stringResource(R.string.offline_state_ready)
    AreaPackState.Corrupt -> stringResource(R.string.offline_state_corrupt)
    AreaPackState.Queued -> stringResource(R.string.offline_state_queued)
    else -> pack.state.name
}

private fun packBytes(pack: AreaPack, bytes: Long?): String {
    if (bytes == null) {
        return ""
    }
    return ", ${InstrumentFormat.formatBytes(bytes)}"
}

internal fun areaPackSideloadHint(storeRoot: String): String =
    "$storeRoot/installed/<id>/"
