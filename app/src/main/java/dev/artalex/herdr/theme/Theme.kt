package dev.artalex.herdr.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Always dark, whatever the system theme: the app is a frame around terminals. No wallpaper colours either, so
// the accent and the status colours match the launcher icon on every device.
private val ColorScheme =
  darkColorScheme(
    // Green: the accent and the "done" status.
    primary = Color(0xFF7BD88F),
    onPrimary = Color(0xFF00391B),
    primaryContainer = Color(0xFF1E4D2B),
    onPrimaryContainer = Color(0xFFA6F2B6),
    inversePrimary = Color(0xFF1F6E3A),
    // Muted green: selected chips (Ctrl and Alt in the terminal).
    secondary = Color(0xFFB7CCB9),
    onSecondary = Color(0xFF223527),
    secondaryContainer = Color(0xFF2F4A37),
    onSecondaryContainer = Color(0xFFCFE9D3),
    // Blue: the "working" status.
    tertiary = Color(0xFF8CC4FF),
    onTertiary = Color(0xFF00315B),
    tertiaryContainer = Color(0xFF17395A),
    onTertiaryContainer = Color(0xFFCFE4FF),
    // Blue-grey surfaces, from the darkest up.
    background = Color(0xFF0F1215),
    onBackground = Color(0xFFE3E7EB),
    surface = Color(0xFF0F1215),
    onSurface = Color(0xFFE3E7EB),
    surfaceDim = Color(0xFF0F1215),
    surfaceContainerLowest = Color(0xFF0A0C0F),
    surfaceContainerLow = Color(0xFF14181C),
    surfaceContainer = Color(0xFF181C21),
    surfaceContainerHigh = Color(0xFF1F2429),
    surfaceContainerHighest = Color(0xFF292F35),
    surfaceBright = Color(0xFF33393F),
    surfaceVariant = Color(0xFF262C32),
    onSurfaceVariant = Color(0xFFA1AAB3),
    surfaceTint = Color(0xFF7BD88F),
    outline = Color(0xFF69727C),
    outlineVariant = Color(0xFF30363D),
    inverseSurface = Color(0xFFE3E7EB),
    inverseOnSurface = Color(0xFF2C3136),
  )

@Composable
fun HerdrTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = ColorScheme, typography = Typography, content = content)
}
