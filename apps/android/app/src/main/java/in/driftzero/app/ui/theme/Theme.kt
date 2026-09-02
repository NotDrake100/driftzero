package `in`.driftzero.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ProductColors = lightColorScheme(
    primary = Olive,
    onPrimary = Paper,
    secondary = OliveDeep,
    onSecondary = Paper,
    background = Ink,
    onBackground = Paper,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Paper,
    onSurfaceVariant = InkMuted,
    outline = InkMuted,
)

@Composable
fun DriftZeroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ProductColors,
        typography = DriftZeroTypography,
        content = content,
    )
}
