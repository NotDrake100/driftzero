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
    val routeFill: Color,
    val routeCasing: Color,
    val paper: Color,
    val haloFillAlpha: Float,
) {
    companion object {
        fun from(set: InstrumentPaletteSet): InstrumentColors = InstrumentColors(
            chassis = Color(set.chassis),
            panel = Color(set.panel),
            well = Color(set.well),
            panelPressed = Color(set.panelPressed),
            hairline = Color(set.hairline),
            ink = Color(set.ink),
            inkDim = Color(set.inkDim),
            lampOk = Color(set.lampOk),
            lampCaution = Color(set.lampCaution),
            lampAlert = Color(set.lampAlert),
            marker = Color(set.marker),
            routeFill = Color(set.routeFill),
            routeCasing = Color(set.routeCasing),
            paper = Color(set.paper),
            haloFillAlpha = set.haloFillAlpha,
        )
    }
}

/**
 * Six type roles. Mono carries every number the driver reads (speed, age,
 * radius, heading, timestamps). Sans carries words.
 */
data class InstrumentType(
    val readoutLarge: TextStyle,
    val readout: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
)

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

private const val TABULAR = "tnum, lnum"

private fun typeFor(colors: InstrumentColors): InstrumentType = InstrumentType(
    readoutLarge = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        fontFeatureSettings = TABULAR,
        color = colors.ink,
    ),
    readout = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        fontFeatureSettings = TABULAR,
        color = colors.ink,
    ),
    title = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        color = colors.ink,
    ),
    body = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = colors.ink,
    ),
    label = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        color = colors.ink,
    ),
    caption = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = colors.inkDim,
    ),
)

private val DayColors = InstrumentColors.from(InstrumentPalette.DAY)
private val NightColors = InstrumentColors.from(InstrumentPalette.NIGHT)
private val DayType = typeFor(DayColors)
private val NightType = typeFor(NightColors)

internal val LocalInstrumentColors = staticCompositionLocalOf { DayColors }
internal val LocalInstrumentType = staticCompositionLocalOf { DayType }
internal val LocalInstrumentNight = staticCompositionLocalOf { false }
internal val LocalReduceMotion = staticCompositionLocalOf { false }

object InstrumentTheme {
    val colors: InstrumentColors
        @Composable get() = LocalInstrumentColors.current
    val type: InstrumentType
        @Composable get() = LocalInstrumentType.current
    val night: Boolean
        @Composable get() = LocalInstrumentNight.current
    val reduceMotion: Boolean
        @Composable get() = LocalReduceMotion.current
}

@Composable
fun DriftZeroTheme(
    night: Boolean,
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalInstrumentColors provides if (night) NightColors else DayColors,
        LocalInstrumentType provides if (night) NightType else DayType,
        LocalInstrumentNight provides night,
        LocalReduceMotion provides reduceMotion,
        content = content,
    )
}
