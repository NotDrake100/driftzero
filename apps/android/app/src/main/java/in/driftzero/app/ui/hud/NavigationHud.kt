package `in`.driftzero.app.ui.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Edge chrome only. Intentionally leaves the map center empty so the location
 * puck at the camera target stays visible. Sibling UI may restyle these chips;
 * do not move them over the map center.
 */
@Composable
fun BoxScope.NavigationHud(
    modeLabel: String,
    speedLabel: String,
    confidenceLabel: String,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(start = 12.dp, top = 8.dp, end = 12.dp)
            .fillMaxWidth()
            .zIndex(2f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        HudChip(title = modeLabel, subtitle = "Mode")
        HudChip(title = confidenceLabel, subtitle = "Confidence")
    }

    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .navigationBarsPadding()
            .padding(start = 12.dp, bottom = 12.dp, end = 12.dp)
            .fillMaxWidth()
            .zIndex(2f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        HudChip(title = speedLabel, subtitle = "Speed")
        HudChip(title = "Follow", subtitle = "Camera")
    }
}

@Composable
private fun HudChip(
    title: String,
    subtitle: String,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall)
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
