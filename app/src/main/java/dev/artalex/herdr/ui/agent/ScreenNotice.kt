package dev.artalex.herdr.ui.agent

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import dev.artalex.herdr.R
import dev.artalex.herdr.connection.ConnectionState
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.terminal.CloseReason
import dev.artalex.herdr.ui.common.ScreenOverlay
import dev.artalex.herdr.ui.common.connectionText
import dev.artalex.herdr.ui.terminal.TerminalPhase
import dev.artalex.herdr.ui.terminal.phaseText

/**
 * Why the agent screen may not show its pane as it is now. It is said over the pane's screen, which shows dimmed under
 * it; without a connection or an answer to the session's polls, the agent's status by the title is out of date too.
 */
sealed interface ScreenNotice {
  /** The server isn't connected, or herdr's server needs a restart before the stream can open: [state] says which. */
  data class Connection(val state: ConnectionState) : ScreenNotice

  /** The server is connected, but the session's last poll failed, for [reason]. */
  data class NoAnswer(val reason: String) : ScreenNotice

  /** The pane's stream failed or ended, as [phase] says, and the frame on the screen is the last one before that. */
  data class Stream(val phase: TerminalPhase) : ScreenNotice
}

/**
 * What the agent screen says over its pane's screen, if anything. The connection comes first: without it nothing on the
 * screen is live. Then the session's polls: one that failed leaves the status out of date, and the stream may have gone
 * with it. Then the pane's stream: its [problem] (see [keptProblem]), once it has [drawn] a frame, which would pass for
 * the pane as it is now. A stream that drew nothing leaves the screen waiting for the pane's screen, which says so.
 */
internal fun screenNotice(connection: ConnectionState, session: SessionState, problem: TerminalPhase?, drawn: Boolean): ScreenNotice? =
  when {
    connection !is ConnectionState.Connected -> ScreenNotice.Connection(connection)
    session is SessionState.Failed -> ScreenNotice.NoAnswer(session.error.message ?: session.error.javaClass.simpleName)
    problem == null -> null
    // No stream opens until herdr's server runs the binary of its CLI: that rather than how the stream failed.
    connection.restartNeeded -> ScreenNotice.Connection(connection)
    drawn -> ScreenNotice.Stream(problem)
    else -> null
  }

/**
 * The stream's problem as the agent screen tells it, once [phase] comes after [kept]: a failure or an end. It is kept
 * while the screen opens the stream again, as it does every few seconds, so that the notice stays rather than blinks,
 * and it goes once the stream is live or waits for its view to be measured. Another client's control is no problem:
 * the screen shows a snapshot of the pane then.
 */
internal fun keptProblem(kept: TerminalPhase?, phase: TerminalPhase): TerminalPhase? =
  when (phase) {
    TerminalPhase.Connecting -> kept
    is TerminalPhase.Failed -> phase
    is TerminalPhase.Closed -> phase.takeIf { it.reason != CloseReason.TakenOver }
    TerminalPhase.Measuring, TerminalPhase.Live, TerminalPhase.NeedsTakeover -> null
  }

/** [notice] over the pane's screen, which shows dimmed under it; read out as one, by its text. */
@Composable
fun ScreenNoticeOverlay(notice: ScreenNotice) {
  val text =
    when (notice) {
      is ScreenNotice.Connection -> connectionText(notice.state)
      is ScreenNotice.NoAnswer -> stringResource(R.string.agent_stale, notice.reason)
      is ScreenNotice.Stream -> phaseText(notice.phase)
    } ?: return
  ScreenOverlay(text, Modifier.testTag("screenNotice").semantics(mergeDescendants = true) {})
}
