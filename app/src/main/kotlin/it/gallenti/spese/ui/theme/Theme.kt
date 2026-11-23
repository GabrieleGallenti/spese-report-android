package it.gallenti.spese.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import it.gallenti.spese.domain.model.Group

private val Cobalt = Color(0xFF1240E0)
private val CobaltNight = Color(0xFF4D74FF)
private val Magenta = Color(0xFFE0115F)
private val Slate = Color(0xFF8592B8)

private val Light = lightColorScheme(
    primary = Cobalt,
    onPrimary = Color.White,
    secondary = Magenta,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF2FF),
    onSurfaceVariant = Color(0xFF5A6382),
    outlineVariant = Color(0xFFD6DDF5),
)

private val Dark = darkColorScheme(
    primary = CobaltNight,
    onPrimary = Color.White,
    secondary = Magenta,
    background = Color(0xFF0A0D18),
    surface = Color(0xFF0A0D18),
    surfaceVariant = Color(0xFF131A30),
    onSurfaceVariant = Color(0xFF97A1C4),
    outlineVariant = Color(0xFF26304F),
)

@Composable
fun SpeseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Colore fisso per gruppo, uguale nel grafico, nelle schede e nella lista. */
@Composable
@ReadOnlyComposable
fun groupColor(group: Group): Color = when (group) {
    Group.RICORRENTI -> if (isSystemInDarkTheme()) CobaltNight else Cobalt
    Group.RESTO -> Slate
    Group.EXTRA -> Magenta
    Group.ENTRATE -> Color(0xFF0B7A43)
    Group.INTERNI -> Color(0xFF9AA0B4)
}
