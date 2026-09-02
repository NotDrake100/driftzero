package `in`.driftzero.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import `in`.driftzero.app.R

data class InstrumentColors(
    val chassis: Color,
    val panel: Color,
    val well: Color,
    val panelPressed: Color,
    val hairline: Color,
    val ink: Color,
    val inkDim: Color,
    val lampOk: Color,
    val lampCaution: Color,
    val lampAlert: Color,
    val marker: Color,
)

data class InstrumentType(
    val search: TextStyle,
    val chip: TextStyle,
    val place: TextStyle,
    val detail: TextStyle,
    val readout: TextStyle,
    val note: TextStyle,
)

private val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

private val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

private val Colors = InstrumentColors(
    chassis = Color(InstrumentPalette.CHASSIS),
    panel = Color(InstrumentPalette.PANEL),
    well = Color(InstrumentPalette.WELL),
    panelPressed = Color(InstrumentPalette.PANEL_PRESSED),
    hairline = Color(InstrumentPalette.HAIRLINE),
    ink = Color(InstrumentPalette.INK),
    inkDim = Color(InstrumentPalette.INK_DIM),
    lampOk = Color(InstrumentPalette.LAMP_OK),
    lampCaution = Color(InstrumentPalette.LAMP_CAUTION),
    lampAlert = Color(InstrumentPalette.LAMP_ALERT),
    marker = Color(InstrumentPalette.MARKER_BLUE),
)

private val Type = InstrumentType(
    search = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        color = Colors.ink,
    ),
    chip = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = Colors.ink,
    ),
    place = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        color = Colors.ink,
    ),
    detail = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = Colors.inkDim,
    ),
    readout = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        fontFeatureSettings = "tnum, lnum",
        color = Colors.ink,
    ),
    note = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        color = Colors.ink,
    ),
)

internal val LocalInstrumentColors = staticCompositionLocalOf { Colors }
internal val LocalInstrumentType = staticCompositionLocalOf { Type }

object InstrumentTheme {
    val colors: InstrumentColors
        @Composable get() = LocalInstrumentColors.current
    val type: InstrumentType
        @Composable get() = LocalInstrumentType.current
}

@Composable
fun DriftZeroTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalInstrumentColors provides Colors,
        LocalInstrumentType provides Type,
        content = content,
    )
}
