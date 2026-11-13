package dev.artalex.herdr.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * [text] over a pane's screen, which shows dimmed under it: what it shows isn't the pane as it is now. [action], such as
 * a button, goes under the text.
 */
@Composable
fun ScreenOverlay(text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
  Column(
    modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(text, color = Color.White)
    action()
  }
}
