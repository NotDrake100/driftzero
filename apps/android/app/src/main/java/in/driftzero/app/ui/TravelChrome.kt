package `in`.driftzero.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import `in`.driftzero.app.R

private val SheetCorner = RoundedCornerShape(8.dp)
private val SearchPill = RoundedCornerShape(24.dp)
private val LampCorner = RoundedCornerShape(4.dp)

@Composable
internal fun TravelSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = InstrumentTheme.colors
    val type = InstrumentTheme.type
    val fieldLabel = stringResource(R.string.destination_field)
    val clearLabel = stringResource(R.string.action_clear)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .shadow(2.dp, SearchPill)
            .clip(SearchPill)
            .background(colors.panel)
            .semantics { contentDescription = fieldLabel }
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DestinationMark(size = 24.dp)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
            textStyle = type.body,
            singleLine = true,
            cursorBrush = SolidColor(colors.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        BasicText(
                            text = stringResource(R.string.destination_hint),
                            style = type.body.copy(color = colors.inkDim),
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .travelClickable(
                        onClick = onClear,
                        idle = colors.panel,
                        pressed = colors.panelPressed,
                        shape = CircleShape,
                    )
                    .semantics { contentDescription = clearLabel },
                contentAlignment = Alignment.Center,
            ) {
                ClearMark()
            }
        }
    }
}

@Composable
internal fun TravelSuggestionList(
    places: List<TravelPlace>,
    onPick: (TravelPlace) -> Unit,
    visible: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = InstrumentTheme.colors
    val type = InstrumentTheme.type
    val motion = tween<Float>(durationMillis = TRAVEL_MOTION_MS, easing = TravelEaseOut)
    AnimatedVisibility(
        visible = visible && places.isNotEmpty(),
        modifier = modifier.fillMaxWidth(),
        enter = if (reduceMotion) {
            fadeIn(tween(0))
        } else {
            fadeIn(motion) + scaleIn(
                animationSpec = tween(TRAVEL_MOTION_MS, easing = TravelEaseOut),
                initialScale = 0.95f,
                transformOrigin = TransformOrigin(0.5f, 0f),
            )
        },
        exit = if (reduceMotion) {
            fadeOut(tween(0))
        } else {
            fadeOut(motion) + scaleOut(
                animationSpec = tween(TRAVEL_MOTION_MS, easing = TravelEaseOut),
                targetScale = 0.95f,
                transformOrigin = TransformOrigin(0.5f, 0f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(2.dp, SheetCorner)
                .background(colors.panel, SheetCorner),
        ) {
            places.forEachIndexed { index, place ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.hairline),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .travelClickable(
                            onClick = { onPick(place) },
                            idle = colors.panel,
                            pressed = colors.panelPressed,
                            shape = RoundedCornerShape(0.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    BasicText(text = place.name, style = type.body)
                    if (place.detail.isNotEmpty()) {
                        BasicText(text = place.detail, style = type.caption)
                    }
                }
            }
        }
    }
}

/**
 * Status lamp on the map. Dot colour is the tone, the word is the TalkBack
 * label, the mono readout is fix age. Tap opens the sheet; long-press holds
 * GNSS when [onLongPress] is set (the caller decides when that is allowed).
 */
@Composable
internal fun ModeLamp(
    lamp: LampDisplay,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = InstrumentTheme.colors
    val type = InstrumentTheme.type
    val word = lampWordText(lamp.word)
    val readout = lampReadoutText(lamp)
    val dot = lampColor(lamp.tone)
    val tone by animateColorAsState(
        targetValue = dot,
        animationSpec = tween(TRAVEL_MOTION_MS, easing = TravelEaseOut),
        label = "lamp",
    )
    val description = if (readout == null) word else "$word, $readout"
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .shadow(2.dp, LampCorner)
            .travelClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                idle = colors.panel,
                pressed = colors.panelPressed,
                shape = LampCorner,
            )
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (InstrumentTheme.reduceMotion) dot else tone, CircleShape),
        )
        BasicText(text = word, style = type.label)
        if (readout != null) {
            BasicText(text = readout, style = type.readout.copy(color = colors.inkDim))
        }
    }
}

@Composable
internal fun MapControls(
    showCompass: Boolean,
    compassBearingDeg: Float,
    onCompassClick: () -> Unit,
    onLocateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = InstrumentTheme.colors
    val compassLabel = stringResource(R.string.action_compass)
    val locateLabel = stringResource(R.string.action_locate)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showCompass) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(2.dp, CircleShape)
                    .travelClickable(
                        onClick = onCompassClick,
                        idle = colors.panel,
                        pressed = colors.panelPressed,
                        shape = CircleShape,
                    )
                    .semantics { contentDescription = compassLabel },
                contentAlignment = Alignment.Center,
            ) {
                CompassMark(bearingDeg = compassBearingDeg)
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(2.dp, CircleShape)
                .travelClickable(
                    onClick = onLocateClick,
                    idle = colors.panel,
                    pressed = colors.panelPressed,
                    shape = CircleShape,
                )
                .semantics { contentDescription = locateLabel },
            contentAlignment = Alignment.Center,
        ) {
            LocateMark()
        }
    }
}

@Composable
internal fun TravelTopChrome(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    places: List<TravelPlace>,
    onPick: (TravelPlace) -> Unit,
    searchNote: String?,
    reduceMotion: Boolean,
    lamp: LampDisplay,
    onLampClick: () -> Unit,
    onLampLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TravelSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onSubmit = onSubmit,
            onClear = onClear,
        )
        if (places.isEmpty()) {
            ModeLamp(lamp = lamp, onClick = onLampClick, onLongPress = onLampLongPress)
        }
        TravelSuggestionList(
            places = places,
            onPick = onPick,
            visible = places.isNotEmpty(),
            reduceMotion = reduceMotion,
        )
        if (searchNote != null) {
            BasicText(
                text = searchNote,
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(2.dp, SheetCorner)
                    .background(InstrumentTheme.colors.panel, SheetCorner)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = InstrumentTheme.type.caption,
            )
        }
    }
}
