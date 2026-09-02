package `in`.driftzero.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.driftzero.app.R
import `in`.driftzero.app.trips.TripSummary

@Composable
internal fun TripsScreen(
    trips: List<TripSummary>,
    onBack: () -> Unit,
    onReplay: (TripSummary) -> Unit,
    onDelete: (TripSummary) -> Unit,
    onExport: (TripSummary) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<TripSummary?>(null) }
    var pendingExport by remember { mutableStateOf<TripSummary?>(null) }
    ScreenScaffold(title = stringResource(R.string.trips_title), onBack = onBack) {
        if (trips.isEmpty()) {
            BasicText(text = stringResource(R.string.trips_empty), style = InstrumentTheme.type.body)
        } else {
            trips.forEach { trip ->
                TripRow(
                    trip = trip,
                    onReplay = { onReplay(trip) },
                    onDelete = { pendingDelete = trip },
                    onExport = { pendingExport = trip },
                )
            }
        }
        val deleteTrip = pendingDelete
        if (deleteTrip != null) {
            ConfirmPanel(
                title = stringResource(R.string.trips_delete_title),
                confirmLabel = stringResource(R.string.action_delete),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    onDelete(deleteTrip)
                    pendingDelete = null
                },
                onCancel = { pendingDelete = null },
                destructive = true,
            )
        }
        val exportTrip = pendingExport
        if (exportTrip != null) {
            ConfirmPanel(
                title = stringResource(R.string.trips_export_title),
                body = stringResource(R.string.trips_export_body),
                confirmLabel = stringResource(R.string.action_export),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    onExport(exportTrip)
                    pendingExport = null
                },
                onCancel = { pendingExport = null },
            )
        }
    }
}

@Composable
private fun TripRow(
    trip: TripSummary,
    onReplay: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
            text = stringResource(
                R.string.trips_row,
                InstrumentFormat.formatClock(trip.startWallMs),
                InstrumentFormat.formatTripKm(trip.distanceM),
                trip.drPercent,
            ),
            style = InstrumentTheme.type.body,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(label = stringResource(R.string.action_replay), onClick = onReplay, modifier = Modifier.weight(1f))
            SecondaryButton(label = stringResource(R.string.action_delete), onClick = onDelete, modifier = Modifier.weight(1f))
            SecondaryButton(label = stringResource(R.string.action_export), onClick = onExport, modifier = Modifier.weight(1f))
        }
    }
}
