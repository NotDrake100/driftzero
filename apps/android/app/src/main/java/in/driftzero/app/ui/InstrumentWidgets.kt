package `in`.driftzero.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val SheetShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val ButtonShape = RoundedCornerShape(4.dp)
private val RowShape = RoundedCornerShape(4.dp)

@Composable
internal fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = InstrumentTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .travelClickable(
                onClick = onClick,
                idle = colors.ink,
                pressed = colors.inkDim,
                shape = ButtonShape,
                enabled = enabled,
            )
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = label, style = InstrumentTheme.type.label.copy(color = colors.chassis))
    }
}

@Composable
internal fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = InstrumentTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .travelClickable(
                onClick = onClick,
                idle = colors.well,
                pressed = colors.panelPressed,
                shape = ButtonShape,
                enabled = enabled,
            )
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = label, style = InstrumentTheme.type.label)
    }
}

@Composable
internal fun ListRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = StatusCopy.sheetLabel(label),
            style = InstrumentTheme.type.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = StatusCopy.SHEET_LABEL_MIN_DP.dp),
        )
        BasicText(
            text = value,
            style = InstrumentTheme.type.readout,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun InstrumentSheet(
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongPress: (() -> Unit)?,
    handleDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = InstrumentTheme.colors
    val reduce = InstrumentTheme.reduceMotion
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, SheetShape)
            .clip(SheetShape)
            .background(colors.panel)
            .then(
                if (reduce) {
                    Modifier
                } else {
                    Modifier.animateContentSize(tween(if (expanded) 200 else 160, easing = TravelEaseOut))
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .travelClickable(
                    onClick = onToggle,
                    onLongClick = onLongPress,
                    idle = colors.panel,
                    pressed = colors.panelPressed,
                )
                .semantics { contentDescription = handleDescription },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(4.dp)
                    .background(colors.hairline, RoundedCornerShape(2.dp)),
            )
        }
        content()
    }
}

@Composable
internal fun ChoiceRow(
    label: String,
    options: List<Pair<String, Boolean>>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(text = label, style = InstrumentTheme.type.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (name, selected) ->
                if (selected) {
                    PrimaryButton(label = name, onClick = { onPick(index) }, modifier = Modifier.weight(1f))
                } else {
                    SecondaryButton(label = name, onClick = { onPick(index) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun ToggleRow(
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .travelClickable(
                onClick = onToggle,
                idle = InstrumentTheme.colors.panel,
                pressed = InstrumentTheme.colors.panelPressed,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(text = label, style = InstrumentTheme.type.body, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (on) InstrumentTheme.colors.ink else InstrumentTheme.colors.well),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .width(16.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(InstrumentTheme.colors.chassis),
            )
        }
    }
}

@Composable
internal fun ConfirmPanel(
    title: String,
    body: String? = null,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val colors = InstrumentTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, RowShape)
            .clip(RowShape)
            .background(colors.panel)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(text = title, style = InstrumentTheme.type.body)
        if (body != null) {
            BasicText(text = body, style = InstrumentTheme.type.caption)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(label = cancelLabel, onClick = onCancel, modifier = Modifier.weight(1f))
            if (destructive) {
                SecondaryButton(label = confirmLabel, onClick = onConfirm, modifier = Modifier.weight(1f))
            } else {
                PrimaryButton(label = confirmLabel, onClick = onConfirm, modifier = Modifier.weight(1f))
            }
        }
    }
}
