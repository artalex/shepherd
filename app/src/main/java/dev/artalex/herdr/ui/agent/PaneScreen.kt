package dev.artalex.herdr.ui.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.artalex.herdr.R
import dev.artalex.herdr.core.store.PaneGrid
import dev.artalex.herdr.core.terminal.CloseReason
import dev.artalex.herdr.ui.common.rememberTerminalTypeface
import dev.artalex.herdr.ui.terminal.PaneStream
import dev.artalex.herdr.ui.terminal.TerminalPhase
import dev.artalex.herdr.ui.terminal.TerminalSelection
import dev.artalex.herdr.ui.terminal.wheelScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.connectbot.terminal.Terminal

/**
 * The pane's screen, streamed live at the phone's size while shown: the agent redraws itself for it, where a
 * desktop-sized snapshot would be unreadably small or cut off. While another client controls the pane, a snapshot
 * at that client's size stands in. A tap on a link in the live screen goes to [onLink]; see [SnapshotTerminal] on why
 * the snapshot has none.
 */
@Composable
fun PaneScreen(viewModel: AgentViewModel, grid: PaneGrid, onLink: (String) -> Unit) {
  val stream = viewModel.stream
  // Leaving the screen or the app lets go of the pane, and the desktop gets its size back.
  LifecycleStartEffect(stream) {
    stream.acquire()
    onStopOrDispose { stream.release() }
  }
  // Nothing here asks the user, unlike the terminal screen: someone else's control is waited out, and a stream
  // that broke off comes back once it can.
  LaunchedEffect(stream) {
    while (true) {
      val phase = stream.phase.first { retryDelay(it) != null }
      delay(checkNotNull(retryDelay(phase)))
      if (retryDelay(stream.phase.value) != null) stream.reconnect()
    }
  }
  val phase by stream.phase.collectAsStateWithLifecycle()
  if (controlledElsewhere(phase)) {
    val text by viewModel.screen.collectAsStateWithLifecycle()
    text?.let { SnapshotTerminal(it, grid.rows, grid.cols, Modifier.fillMaxSize().testTag("snapshot")) } ?: NoScreen()
  } else {
    LiveTerminal(stream, onLink)
  }
}

@Composable
fun NoScreen() = Text(stringResource(R.string.agent_no_screen), Modifier.padding(16.dp))

/** A read-only view of the stream; termlib picks rows and columns for the view at its default font. */
@Composable
private fun LiveTerminal(stream: PaneStream, onLink: (String) -> Unit) {
  val selection = remember { TerminalSelection() }
  // termlib draws its canvas without clipping; with the keyboard up it would cover the top bar.
  Box(Modifier.fillMaxSize().clipToBounds().wheelScroll(stream, selection).testTag("liveScreen")) {
    Terminal(
      stream.emulator,
      modifier = Modifier.fillMaxSize(),
      typeface = rememberTerminalTypeface(),
      keyboardEnabled = false,
      onSelectionControllerAvailable = { selection.controller = it },
      onHyperlinkClick = onLink,
    )
  }
}

private fun controlledElsewhere(phase: TerminalPhase) = phase == TerminalPhase.NeedsTakeover || phase == TerminalPhase.Closed(CloseReason.TakenOver)

/** How long to wait before opening the stream again, or null while it is open, opening, or its pane is gone. */
private fun retryDelay(phase: TerminalPhase): Long? =
  when {
    controlledElsewhere(phase) -> BUSY_RETRY_MS
    phase is TerminalPhase.Failed -> RETRY_MS
    phase is TerminalPhase.Closed && phase.reason != CloseReason.NotFound -> RETRY_MS
    else -> null
  }

private const val RETRY_MS = 2_000L
private const val BUSY_RETRY_MS = 5_000L
