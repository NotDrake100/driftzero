package `in`.driftzero.app.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

internal val TravelEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
internal const val TRAVEL_MOTION_MS = 120

@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.travelClickable(
    onClick: () -> Unit,
    idle: Color,
    pressed: Color,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reduceMotion) 0.97f else 1f,
        animationSpec = tween(durationMillis = TRAVEL_MOTION_MS, easing = TravelEaseOut),
        label = "travel-press",
    )
    val clipped = this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .background(if (isPressed) pressed else idle)
    return if (onLongClick != null) {
        clipped.combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        clipped.clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    }
}
