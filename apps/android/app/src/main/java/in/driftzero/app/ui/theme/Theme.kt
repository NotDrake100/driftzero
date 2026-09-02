package `in`.driftzero.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1E6BFF),
    onPrimary = Color.White,
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF12203A),
    background = Color(0xFFF2F4F7),
    onBackground = Color(0xFF12203A),
)

@Composable
fun DriftZeroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        content = content,
    )
}
