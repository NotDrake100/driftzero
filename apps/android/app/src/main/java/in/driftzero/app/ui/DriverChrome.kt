package `in`.driftzero.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.driftzero.app.copy.DriverCopy
import `in`.driftzero.app.search.Place
import `in`.driftzero.app.ui.theme.Ink
import `in`.driftzero.app.ui.theme.InkMuted
import `in`.driftzero.app.ui.theme.Olive
import `in`.driftzero.app.ui.theme.Paper

@Composable
fun DestinationSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = DriverCopy.WORDMARK,
            color = Olive,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 0.3.sp,
        )
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 16.sp,
                color = Ink,
            ),
            cursorBrush = SolidColor(Olive),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(10.dp), clip = false)
                        .background(Paper, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = DriverCopy.SEARCH_PLACEHOLDER,
                            color = InkMuted,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 16.sp,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
fun SearchResultsSheet(
    state: NavigationUiState,
    onPlaceSelected: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.searchPanel == SearchPanel.Hidden) return
    val message = when (state.searchPanel) {
        SearchPanel.Typing -> if (state.destinationQuery.trim().length < 3) {
            "Keep typing a place name"
        } else {
            DriverCopy.searching()
        }
        SearchPanel.Loading -> DriverCopy.searching()
        SearchPanel.Empty -> DriverCopy.noResults(state.destinationQuery.trim())
        SearchPanel.Error -> state.searchFailure?.let { DriverCopy.searchError(it) }
            ?: "Could not look up places."
        SearchPanel.Results, SearchPanel.Hidden -> null
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp), clip = false)
            .background(Paper, RoundedCornerShape(12.dp))
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        if (message != null) {
            Text(
                text = message,
                color = Ink,
                fontFamily = FontFamily.SansSerif,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (state.searchPanel == SearchPanel.Results) {
            state.searchResults.forEach { place ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaceSelected(place) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = place.name,
                        color = Ink,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                    )
                    if (place.subtitle.isNotBlank()) {
                        Text(
                            text = place.subtitle,
                            color = InkMuted,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DriverStatusBar(
    gpsLabel: String,
    speedLabel: String?,
    canStart: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    state: NavigationUiState? = null,
    onDemoSuggestion: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state != null) {
            RouteSummaryCard(
                state = state,
                onDemoSuggestion = onDemoSuggestion,
                onClear = onClear,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(gpsLabel)
            if (speedLabel != null) {
                StatusChip(speedLabel)
            }
            Spacer(Modifier.weight(1f))
            if (canStart) {
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Olive,
                        contentColor = Paper,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                ) {
                    Text(
                        text = DriverCopy.START,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteSummaryCard(
    state: NavigationUiState,
    onDemoSuggestion: () -> Unit,
    onClear: () -> Unit,
) {
    val title: String
    val body: String?
    when {
        state.routePanel == RoutePanel.Loading -> {
            title = DriverCopy.routing()
            body = state.destination?.name?.let { "To $it" }
        }
        state.routePanel == RoutePanel.Error && state.routeFailure != null -> {
            title = DriverCopy.routeError(state.routeFailure)
            body = state.destination?.name
        }
        state.routePanel == RoutePanel.Ready && state.route != null && state.destination != null -> {
            title = DriverCopy.routeTitle(state.destination.name)
            body = DriverCopy.routeSummary(state.route)
        }
        else -> {
            title = DriverCopy.idleTitle()
            body = DriverCopy.idleBody()
        }
    }
    val location = DriverCopy.locationLine(
        source = state.originSource,
        hasPermission = state.locationPermissionGranted,
        waitingForFix = state.waitingForFix,
        gpsLost = state.gpsLost,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp), clip = false)
            .background(Paper, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            color = Ink,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
        if (body != null) {
            Text(
                text = body,
                color = Ink,
                fontFamily = FontFamily.SansSerif,
                fontSize = 15.sp,
            )
        }
        if (location != null) {
            Text(
                text = location,
                color = InkMuted,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
            )
        }
        if (state.routePanel == RoutePanel.Idle && state.searchPanel == SearchPanel.Hidden) {
            TextButton(onClick = onDemoSuggestion) {
                Text(DriverCopy.TRY_DEMO, color = Olive)
            }
        }
        if (state.destination != null) {
            TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) {
                Text("Clear destination", color = Olive)
            }
        }
        Text(
            text = DriverCopy.ATTRIBUTION,
            color = InkMuted,
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StatusChip(label: String) {
    Box(
        modifier = Modifier
            .shadow(3.dp, RoundedCornerShape(10.dp), clip = false)
            .background(Ink, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = Paper,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
        )
    }
}
