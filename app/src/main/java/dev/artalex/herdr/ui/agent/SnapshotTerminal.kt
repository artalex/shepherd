package dev.artalex.herdr.ui.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import dev.artalex.herdr.core.terminal.FaintToColor
import dev.artalex.herdr.ui.common.rememberTerminalTypeface
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * A read-only terminal showing one rendered screen of a pane (`pane.read` in ANSI format).
 * Each new [text] repaints the whole grid; nothing is ever sent back. Faint text comes out in a dimmer colour.
 *
 * Links in it don't open. The grid takes its width from the pane's rectangle on the desktop, not from the client the
 * screen was drawn for, so the rows that client fills end short of the grid's edge: termlib couldn't join a link
 * wrapped over rows, and a tap would open only its first row.
 */
@Composable
fun SnapshotTerminal(text: String, rows: Int, cols: Int, modifier: Modifier = Modifier) {
  val emulator = remember { TerminalEmulatorFactory.create(initialRows = rows, initialCols = cols) }
  LaunchedEffect(rows, cols) { emulator.resize(rows, cols) }
  LaunchedEffect(text, rows, cols) {
    // Reset attributes, clear, home, hide the cursor: the snapshot's cursor position means nothing here.
    emulator.writeInput(FaintToColor().filter((RESET + text).toByteArray(Charsets.UTF_8)))
  }
  // termlib draws its canvas without clipping; with the keyboard up it would cover the top bar.
  Box(modifier.clipToBounds()) {
    Terminal(emulator, modifier = Modifier.fillMaxSize(), typeface = rememberTerminalTypeface(), keyboardEnabled = false, forcedSize = rows to cols)
  }
}

private const val RESET = "\u001b[0m\u001b[2J\u001b[H\u001b[?25l"
