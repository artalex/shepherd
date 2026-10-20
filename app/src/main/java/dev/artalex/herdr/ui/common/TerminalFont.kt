package dev.artalex.herdr.ui.common

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import dev.artalex.herdr.R

/**
 * JetBrains Mono 2.304 (OFL, licence in assets/licenses) for terminal grids. termlib sizes its cells by the
 * width of "M", and some phones ship a proportional face as `monospace`: narrow letters then float in wide cells.
 */
@Composable
fun rememberTerminalTypeface(): Typeface {
  val resources = LocalResources.current
  return remember(resources) { resources.getFont(R.font.jetbrains_mono) }
}
