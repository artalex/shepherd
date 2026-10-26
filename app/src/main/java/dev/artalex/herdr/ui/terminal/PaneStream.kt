package dev.artalex.herdr.ui.terminal

import android.util.Log
import dev.artalex.herdr.Server
import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.terminal.CloseReason
import dev.artalex.herdr.core.terminal.TermlibInput
import dev.artalex.herdr.core.terminal.TerminalEvent
import dev.artalex.herdr.core.terminal.TerminalSession
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * One [PaneStream] per pane of the server, for the screens that show it. A stream keeps the pane's screen and
 * scrollback in its emulator, so it goes once its pane is gone from the session and no screen shows it: with the next
 * snapshot ([seen]), or once the last screen has let go. Only an [PaneStream.idle] stream goes, so nothing of it is
 * left open, and it closes its emulator on the way out. Main thread only, like the screens and the snapshots.
 */
class PaneStreams(private val server: Server) {
  private val streams = mutableMapOf<String, Entry>()

  /** The panes of the last snapshot; null before the first one. */
  private var panes: Set<String>? = null

  /** The panes that have a stream now. For the e2e tests: a closed pane's stream goes once no screen shows it. */
  val paneIds: Set<String>
    get() = streams.keys.toSet()

  /** A closed pane's stream is done for good: its id may come back as a new pane. */
  fun of(paneId: String): PaneStream {
    streams[paneId]?.let {
      if (!it.stream.paneClosed.value) return it.stream
      // The new pane takes the id over; the old stream closes once no screen shows it (see [dropIfGone]).
      if (it.stream.idle) it.stream.close()
    }
    // A pane of the last snapshot is there: once a later snapshot lacks it, it is closed.
    val entry = Entry(PaneStream(server, paneId, onIdle = ::dropIfGone), seen = panes?.contains(paneId) == true)
    streams[paneId] = entry
    return entry.stream
  }

  /**
   * A snapshot of the session: a pane that was in one before and is missing now is closed, and its stream goes. One
   * that a screen still shows goes once the screen lets go: screens leave a pane that is gone.
   */
  fun seen(snapshot: SessionSnapshot) {
    val now = snapshot.panes.mapTo(HashSet()) { it.paneId }
    panes = now
    for ((paneId, entry) in streams) if (paneId in now) entry.seen = true
    streams.values.filter { it.stream.idle && gone(it) }.forEach { drop(it.stream) }
  }

  /** The server is deleted: every stream goes. No screen shows them by then: the server's screens left with it. */
  fun close() {
    streams.values.forEach { it.stream.close() }
    streams.clear()
  }

  /** The last screen of [stream] let go a moment ago: if its pane is gone by now, the stream goes. */
  private fun dropIfGone(stream: PaneStream) {
    val entry = streams[stream.paneId]
    // A new pane of the same id replaced it (see [of]): with no screen left, it has nothing to show.
    if (entry == null || entry.stream !== stream) stream.close()
    else if (stream.idle && gone(entry)) drop(stream)
  }

  private fun drop(stream: PaneStream) {
    streams.remove(stream.paneId)
    stream.close()
  }

  /** The pane is closed: its stream found it gone, or it was in a snapshot and the last one lacks it. */
  private fun gone(entry: Entry) = entry.stream.paneClosed.value || (entry.seen && panes?.contains(entry.stream.paneId) == false)

  /** [seen]: the pane has been in a snapshot. One just started may not be in one yet, and isn't closed for that. */
  private class Entry(val stream: PaneStream, var seen: Boolean)
}

/**
 * A pane's live terminal: `herdr terminal session control` over SSH, drawn by termlib into [emulator]. The agent
 * screen and the terminal screen of the pane share it, so going from one to the other keeps the stream open:
 * two streams would hand the pane over to each other, and herdr would ask about a takeover in between.
 *
 * While the stream is open the pane's PTY takes the size of the view on the phone; herdr gives the desktop its
 * geometry back on release. That happens a moment after the last screen showing the pane goes away, or when the
 * SSH channel drops. Past that moment without a screen, the stream is [idle], its emulator gone, and it tells
 * [onIdle]. Once its pane or server is gone, it is [close]d for good.
 */
class PaneStream(private val server: Server, val paneId: String, private val onIdle: (PaneStream) -> Unit) {
  private val _phase = MutableStateFlow<TerminalPhase>(TerminalPhase.Measuring)
  val phase: StateFlow<TerminalPhase> = _phase.asStateFlow()

  private val _paneClosed = MutableStateFlow(false)

  /** The pane is gone: closed, or its shell exited. The screens have nothing left to show. */
  val paneClosed: StateFlow<Boolean> = _paneClosed.asStateFlow()

  private val _undelivered = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /**
   * Typing or a paste that didn't reach the pane: no stream was live, or it broke on the way. Nothing is kept to send
   * later: a command typed now would run whenever the stream came back.
   */
  val undelivered: SharedFlow<Unit> = _undelivered.asSharedFlow()

  private var session: TerminalSession? = null
  private var stream: Job? = null
  private var size: TerminalDimensions? = null
  private var resizeJob: Job? = null
  private var users = 0
  private var linger: Job? = null
  private var closed = false

  /** How far up the view may be scrolled, in lines; more than it is once herdr stops at the top of its history. */
  private var scrolledBack = 0

  private val _emulator = MutableStateFlow<TerminalEmulator?>(null)

  /**
   * The pane's screen as termlib draws it, while a screen shows the pane and for a moment after. A screen that comes
   * later gets a new, empty one, which the first frame of its stream fills: the old picture would pass for the pane as
   * it is now, after any time away.
   */
  val emulator: StateFlow<TerminalEmulator?> = _emulator.asStateFlow()

  private val _drawn = MutableStateFlow(false)

  /** The emulator has a frame of the pane. Until it does, the screens say that they wait for one. */
  val drawn: StateFlow<Boolean> = _drawn.asStateFlow()

  /** Text rows of the view, once termlib has measured it. */
  val rows: Int?
    get() = size?.rows

  /** Columns of the view, and of the pane while the stream is live. */
  val columns: Int?
    get() = size?.columns

  /**
   * No screen shows the pane, and the moment after the last one is over: the stream is stopped and the session closed,
   * or still giving the pane back to the desktop.
   */
  val idle: Boolean
    get() = users == 0 && linger == null

  /**
   * A screen shows the pane. Without an emulator it gets a new one; the stream opens once termlib has measured a view,
   * or right away if it has.
   */
  fun acquire() {
    users++
    linger?.cancel()
    linger = null
    if (closed) return
    if (_emulator.value == null) _emulator.value = newEmulator()
    if (size != null && stream?.isActive != true) open(takeover = false)
  }

  /**
   * A screen no longer shows the pane. A moment after the last one the stream closes and the emulator goes, and the
   * stream is [idle]; a screen of the pane within that moment keeps both.
   */
  fun release() {
    users--
    if (users > 0) return
    linger =
      server.scope.launch {
        delay(LINGER_MS)
        stream?.cancel()
        stream = null
        closeSession()
        dropEmulator()
        linger = null
        onIdle(this@PaneStream)
      }
  }

  private fun newEmulator() =
    TerminalEmulatorFactory.create(
      onKeyboardInput = { bytes ->
        // Typing into the stream brings herdr's view back to the bottom.
        scrolledBack = 0
        deliver("typing") { it.sendBytes(bytes) }
      },
      onResize = ::onResize,
      // Links in the text get underlined, and a tap on one goes to the screen's onHyperlinkClick.
      autoDetectUrls = true,
    )

  /**
   * Closes the emulator, which lets go of termlib's native terminal: that would keep it, and the stream with it, for as
   * long as the app runs. The next one is measured afresh, and the stream opens at its size.
   */
  private fun dropEmulator() {
    _emulator.value?.close()
    _emulator.value = null
    _drawn.value = false
    resizeJob?.cancel()
    size = null
    scrolledBack = 0
    _phase.value = TerminalPhase.Measuring
  }

  /** The stream is done for good: its pane or its server is gone, and no screen shows it. */
  fun close() {
    if (closed) return
    closed = true
    stream?.cancel()
    stream = null
    linger?.cancel()
    linger = null
    closeSession()
    dropEmulator()
  }

  fun takeOver() = open(takeover = true)

  /** Pastes [text] into the pane; herdr brackets it for an app that asked for bracketed paste. */
  fun paste(text: String) {
    scrolledBack = 0
    deliver("a paste") { it.paste(text) }
  }

  /** Sends [what] over the live stream; without one, or when sending fails, the screens hear of it ([undelivered]). */
  private fun deliver(what: String, send: suspend (TerminalSession) -> Unit) {
    val s = session
    if (s == null) {
      _undelivered.tryEmit(Unit)
      return
    }
    server.scope.launch {
      try {
        send(s)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "$what didn't reach the pane: ${e.message}")
        _undelivered.tryEmit(Unit)
      }
    }
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
    // No screen shows the pane: nothing to stream it for. A closed stream has no emulator to draw into.
    if (users == 0 || closed) return
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
          // As termlib can take it; each stream starts with a full frame, so a fresh pen is right.
          val input = TermlibInput()
          opened.events.collect { event ->
            when (event) {
              is TerminalEvent.Frame -> {
                if (_phase.value != TerminalPhase.Live) _phase.value = TerminalPhase.Live
                _emulator.value?.writeInput(input.filter(event.bytes))
                _drawn.value = true
              }
              is TerminalEvent.Closed -> {
                _phase.value = if (event.reason == CloseReason.NeedsTakeover) TerminalPhase.NeedsTakeover else TerminalPhase.Closed(event.reason)
                if (event.reason != CloseReason.NeedsTakeover && paneGone(host)) _paneClosed.value = true
              }
            }
          }
          // The stream ended: input has nowhere to go until the next one opens.
          closeSession()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "terminal stream failed: ${e.message}")
          closeSession()
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
