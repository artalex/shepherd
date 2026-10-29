package dev.artalex.herdr.ui.common

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.artalex.herdr.R
import kotlin.math.ceil

/**
 * JetBrains Mono 2.304 (OFL, licence in assets/licenses) for terminal grids. termlib sizes its cells by the
 * width of "M", and some phones ship a proportional face as `monospace`: narrow letters then float in wide cells.
 */
@Composable
fun rememberTerminalTypeface(): Typeface {
  val resources = LocalResources.current
  return remember(resources) { resources.getFont(R.font.jetbrains_mono) }
}

/** The live terminal and its gestures use the same font and pixel grid. */
data class TerminalFont(val typeface: Typeface, val fontSize: TextUnit, val cellSize: Size)

@Composable
fun rememberTerminalFont(): TerminalFont {
  val typeface = rememberTerminalTypeface()
  val density = LocalDensity.current
  return remember(typeface, density) {
    val fontSize = 11.sp
    val paint = TextPaint().apply {
      this.typeface = typeface
      textSize = with(density) { fontSize.toPx() }
      isAntiAlias = true
    }
    // Match termlib's measured grid; spare pixels at the right and bottom do not stretch its cells.
    val height = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
    TerminalFont(typeface, fontSize, Size(paint.measureText("M"), if (height <= 0f) 20f else height))
  }
}
