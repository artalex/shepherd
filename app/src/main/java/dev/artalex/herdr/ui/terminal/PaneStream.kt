package dev.artalex.herdr.ui.terminal

import android.util.Log
import dev.artalex.herdr.Server
import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.terminal.CloseReason
import dev.artalex.herdr.core.terminal.FaintToColor
import dev.artalex.herdr.core.terminal.TerminalEvent
import dev.artalex.herdr.core.terminal.TerminalSession
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulatorFactory

/** One [PaneStream] per pane of the server, for the screens that show it. */
class PaneStreams(private val server: Server) {
  private val streams = mutableMapOf<String, PaneStream>()

  /** A closed pane's stream is done for good: its id may come back as a new pane. */
  fun of(paneId: String): PaneStream {
    val existing = streams[paneId]
    if (existing != null && !existing.paneClosed.value) return existing
    return PaneStream(server, paneId).also { streams[paneId] = it }
  }
}

/**
 * A pane's live terminal: `herdr terminal session control` over SSH, drawn by termlib into [emulator]. The agent
 * screen and the terminal screen of the pane share it, so going from one to the other keeps the stream open:
 * two streams would hand the pane over to each other, and herdr would ask about a takeover in between.
 *
 * While the stream is open the pane's PTY takes the size of the view on the phone; herdr gives the desktop its
 * geometry back on release. That happens a moment after the last screen showing the pane goes away, or when the
 * SSH channel drops.
 */
class PaneStream(private val server: Server, private val paneId: String) {
  private val _phase = MutableStateFlow<TerminalPhase>(TerminalPhase.Measuring)
  val phase: StateFlow<TerminalPhase> = _phase.asStateFlow()

  private val _paneClosed = MutableStateFlow(false)

  /** The pane is gone: closed, or its shell exited. The screens have nothing left to show. */
  val paneClosed: StateFlow<Boolean> = _paneClosed.asStateFlow()

  private var session: TerminalSession? = null
  private var stream: Job? = null
  private var size: TerminalDimensions? = null
  private var resizeJob: Job? = null
  private var users = 0
  private var linger: Job? = null

  /** How far up the view may be scrolled, in lines; more than it is once herdr stops at the top of its history. */
  private var scrolledBack = 0

  val emulator =
    TerminalEmulatorFactory.create(
      onKeyboardInput = { bytes ->
        // Typing into the stream brings herdr's view back to the bottom.
        scrolledBack = 0
        session?.let { s -> server.scope.launch { runCatching { s.sendBytes(bytes) } } }
      },
      onResize = ::onResize,
    )

  /** Text rows of the view, once termlib has measured it. */
  val rows: Int?
    get() = size?.rows

  /** Columns of the view, and of the pane while the stream is live. */
  val columns: Int?
    get() = size?.columns

  /** A screen shows the pane. The stream opens once termlib has measured a view, or right away if it has. */
  fun acquire() {
    users++
    linger?.cancel()
    linger = null
    if (size != null && stream?.isActive != true) open(takeover = false)
  }

  /** A screen no longer shows the pane. A moment after the last one the stream closes; the next screen of the pane keeps it. */
  fun release() {
    users--
    if (users > 0) return
    linger =
      server.scope.launch {
        delay(LINGER_MS)
        stream?.cancel()
        stream = null
        closeSession()
      }
  }

  fun takeOver() = open(takeover = true)

  /** Pastes [text] into the pane; herdr brackets it for an app that asked for bracketed paste. */
  fun paste(text: String) {
    val s = session ?: return
    scrolledBack = 0
    server.scope.launch { runCatching { s.paste(text) } }
  }

  /** Scrolls like the desktop's mouse wheel: [lines] up into history, or down when negative. */
  fun scroll(lines: Int) {
    val s = session ?: return
    scrolledBack = (scrolledBack + lines).coerceAtLeast(0)
    server.scope.launch { runCatching { s.scroll(lines) } }
  }

  /** Back to the bottom of the pane: input through the API, unlike typing into the stream, leaves the view where it is. */
  fun scrollToBottom() {
    if (scrolledBack > 0) scroll(-scrolledBack)
  }

  fun reconnect() = open(takeover = false)

  private fun onResize(dimensions: TerminalDimensions) {
    val first = size == null
    size = dimensions
    if (first) {
      open(takeover = false)
      return
    }
    // Rotation, the keyboard and going to the other screen of the pane resize the view in steps; send only the final size.
    resizeJob?.cancel()
    resizeJob =
      server.scope.launch {
        delay(RESIZE_DEBOUNCE_MS)
        session?.let { runCatching { it.resize(dimensions.columns, dimensions.rows) } }
      }
  }

  private fun open(takeover: Boolean) {
    // No screen shows the pane: nothing to stream it for.
    if (users == 0) return
    val dims = size ?: return
    stream?.cancel()
    closeSession()
    _phase.value = TerminalPhase.Connecting
    stream =
      server.scope.launch {
        try {
          val host = server.host ?: error("not connected")
          val terminalId = terminalId()
          // Opening blocks on SSH; a stream opened for a cancelled attempt would stay attached with nobody to release it.
          val opened = withContext(Dispatchers.IO + NonCancellable) { host.openTerminal(terminalId, dims.columns, dims.rows, takeover) }
          if (!isActive) {
            server.scope.launch { giveBack(opened) }
            return@launch
          }
          session = opened
          // termlib draws no faint text; each stream starts with a full frame, so a fresh pen is right.
          val faint = FaintToColor()
          opened.events.collect { event ->
            when (event) {
              is TerminalEvent.Frame -> {
                if (_phase.value != TerminalPhase.Live) _phase.value = TerminalPhase.Live
                emulator.writeInput(faint.filter(event.bytes))
              }
              is TerminalEvent.Closed -> {
                _phase.value = if (event.reason == CloseReason.NeedsTakeover) TerminalPhase.NeedsTakeover else TerminalPhase.Closed(event.reason)
                if (event.reason != CloseReason.NeedsTakeover && paneGone(host)) _paneClosed.value = true
              }
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "terminal stream failed: ${e.message}")
          _phase.value = TerminalPhase.Failed(e.message ?: e.javaClass.simpleName)
          server.host?.let { if (paneGone(it)) _paneClosed.value = true }
        }
      }
  }

  // A stream also ends when its pane goes away. The terminal screen polls no snapshots, so ask once.
  private suspend fun paneGone(host: HerdrHost): Boolean =
    try {
      host.api.snapshot().panes.none { it.paneId == paneId }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      false
    }

  private suspend fun terminalId(): String =
    server.sessions.state
      .filterIsInstance<SessionState.Ready>()
      .first()
      .snapshot.panes.firstOrNull { it.paneId == paneId }
      ?.terminalId ?: error("pane $paneId is gone")

  /** Gives the pane back to the desktop, in the app scope: the stream's own coroutine may be cancelled already. */
  private fun closeSession() {
    val s = session ?: return
    session = null
    server.scope.launch { giveBack(s) }
  }

  private suspend fun giveBack(s: TerminalSession) {
    runCatching { s.release() }
    s.close()
  }

  private companion object {
    const val TAG = "HerdrTerminal"
    const val RESIZE_DEBOUNCE_MS = 200L
    const val LINGER_MS = 1_000L
  }
}
