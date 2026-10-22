package dev.artalex.herdr.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import dev.artalex.herdr.Servers
import dev.artalex.herdr.StartPromptsFailure
import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.rpc.ApiError
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.snippets.Snippet
import dev.artalex.herdr.core.snippets.onStart
import dev.artalex.herdr.core.store.Board
import dev.artalex.herdr.core.store.PaneGrid
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.store.buildBoard
import dev.artalex.herdr.core.store.paneGrid
import dev.artalex.herdr.core.store.startNamedAgent
import dev.artalex.herdr.core.store.startNamedAgentInNewPane
import dev.artalex.herdr.data.SnippetStore
import dev.artalex.herdr.ui.terminal.TerminalPhase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the agent screen shows for its pane, refreshed with every session poll. [starting]: an agent started
 * from here isn't ready for input yet.
 */
data class PaneView(val pane: PaneInfo?, val grid: PaneGrid?, val starting: Boolean = false)

/** A message for the snackbar: a string resource and its argument. */
data class UiMessage(val text: Int, val arg: String? = null)

/** A server's panes in the list's order, for switching to them. */
data class ServerBoard(val serverId: String, val title: String, val board: Board)

/** The screen of pane [paneId] on [server]. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModel(servers: Servers, val server: Server, val paneId: String, private val snippetStore: SnippetStore) : ViewModel() {
  /** The pane at the phone's size, shared with its terminal screen. */
  val stream = server.streams.of(paneId)

  val pane: StateFlow<PaneView?> =
    server.sessions.state
      .mapNotNull { state ->
        (state as? SessionState.Ready)?.snapshot?.let {
          PaneView(it.panes.firstOrNull { p -> p.paneId == paneId }, it.paneGrid(paneId), it.agents.any { a -> a.paneId == paneId && a.launchPending })
        }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

  /** Every pane of the session in the list's order: closing the last one of a workspace takes it along. */
  val board: StateFlow<Board?> =
    server.sessions.state
      .mapNotNull { state -> (state as? SessionState.Ready)?.snapshot?.let(::buildBoard) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

  /** The panes of every server that is on, for switching panes without going back to the list. */
  val switcher: StateFlow<List<ServerBoard>> =
    servers.configs
      .map { configs -> configs.filter { it.active }.mapNotNull { config -> servers[config.id]?.let { config.title to it } } }
      .distinctUntilChanged()
      .flatMapLatest { active ->
        if (active.isEmpty()) flowOf(emptyList())
        else
          combine(
            active.map { (title, s) ->
              s.sessions.state.map { state -> (state as? SessionState.Ready)?.let { ServerBoard(s.id, title, buildBoard(it.snapshot)) } }
            }
          ) { boards -> boards.filterNotNull() }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

  /** The server's name while more than one server is on: their panes look alike. With one, the screen stays as it was. */
  val serverTitle: StateFlow<String?> =
    servers.configs
      .map(::serverTitleIn)
      .distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), serverTitleIn(servers.configs.value))

  private fun serverTitleIn(configs: List<ServerConfig>): String? =
    if (configs.count { it.active } > 1) configs.firstOrNull { it.id == server.id }?.title else null

  // Outlives resubscriptions. A pane that hasn't shown up yet (just created) is waited for; one that was
  // there and is missing now was closed.
  private var seen = false

  /** The pane is gone: closed here or on the desktop, or its shell exited. */
  val paneClosed: StateFlow<Boolean> =
    pane
      .map { view ->
        if (view?.pane != null) seen = true
        seen && view != null && view.pane == null
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

  private val _agents = MutableStateFlow<List<AgentKind>?>(null)

  /** Agents herdr can start that the host has installed; null until [loadAgents] finds out. */
  val agents: StateFlow<List<AgentKind>?> = _agents

  private val _opened = Channel<String>(Channel.BUFFERED)

  /** Panes of the server to show instead of this one: a new session started from here. */
  val opened: Flow<String> = _opened.receiveAsFlow()

  private val kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /**
   * The pane's rendered screen with colors, read once a second while another client controls the pane and the
   * screen can't stream it, and right after the user sends something. A second is enough to follow an agent and
   * costs one ~16 KB read.
   */
  val screen: StateFlow<String?> =
    channelFlow {
        while (true) {
          server.host?.let { host ->
            try {
              send(host.api.readPane(paneId).text)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              // The connection banner and the session poll report problems; keep the last screen.
            }
          }
          withTimeoutOrNull(SCREEN_PERIOD_MS) { kicks.first() }
        }
      }
      .distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

  private val _messages = Channel<UiMessage>(Channel.BUFFERED)
  val messages: Flow<UiMessage> = _messages.receiveAsFlow()

  /** Logical keys (`1`, `enter`, `esc`, `up`, `ctrl+c`, …); herdr encodes them for the pane. */
  fun sendKeys(keys: List<String>) =
    act {
      val host = server.host ?: return@act
      stream.scrollToBottom()
      if (pane.value?.pane?.agent != null) host.api.sendAgentKeys(paneId, keys) else host.api.sendPaneKeys(paneId, keys)
    }

  fun sendPrompt(text: String) =
    act {
      stream.scrollToBottom()
      server.host?.api?.promptAgent(paneId, text)
    }

  /** The prompt snippets, the same on every agent screen. */
  val snippets: StateFlow<List<Snippet>> = snippetStore.snippets

  /** Saves [text] at the end of the snippets and says so: the menu it came from is gone by then. */
  fun saveSnippet(text: String) {
    snippetStore.add(text)
    _messages.trySend(UiMessage(R.string.snippets_saved, text.trim()))
  }

  fun setSnippets(snippets: List<Snippet>) = snippetStore.set(snippets)

  /** Snippets that didn't reach an agent started from the phone, whichever screen started it. */
  val startPromptsFailures: Flow<StartPromptsFailure> = server.startPrompts.failures

  /** Looks up the installed agents once; the host caches the answer for the connection. */
  fun loadAgents() {
    if (_agents.value != null) return
    viewModelScope.launch {
      val host = server.host ?: return@launch
      _agents.value =
        try {
          host.installedAgents()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          _messages.send(UiMessage(R.string.error_connection, e.message ?: e.javaClass.simpleName))
          null
        }
    }
  }

  /**
   * Starts a [kind] agent in this pane, which must be at a shell prompt, as after the previous agent exited. The
   * snippets for its start go to it once it is ready.
   */
  fun startAgent(kind: AgentKind) =
    act {
      val api = server.host?.api ?: return@act
      api.startNamedAgent(paneId, kind, cwd)
      server.startPrompts.send(paneId, snippetStore.snippets.value.onStart(kind))
    }

  /** Opens a tab in this pane's workspace and directory, starts a [kind] agent there and shows it; see [startAgent]. */
  fun newSession(kind: AgentKind) =
    act {
      val api = server.host?.api ?: return@act
      val pane = pane.value?.pane ?: return@act
      val dir = cwd
      val created = api.createTab(pane.workspaceId, dir)
      api.startNamedAgentInNewPane(created.paneId, kind, dir)
      server.startPrompts.send(created.paneId, snippetStore.snippets.value.onStart(kind))
      _opened.send(created.paneId)
    }

  /** Closes this pane and everything in it; the screen leaves once a snapshot no longer has the pane. */
  fun closePane() =
    act {
      server.host?.api?.closePane(paneId)
    }

  /** Files between the phone and the host, for the files window. */
  val files = server.files

  /** The agent's folder, where relative paths on its screen start. */
  val cwd: String?
    get() = pane.value?.pane?.let { it.foregroundCwd ?: it.cwd }

  /** The files window opens: the paths on the pane's screen as it is now are looked up on the host. */
  fun scanFiles() {
    // The live stream gives the pane the phone's width; a snapshot shows it at the width of whoever controls it.
    val width = if (stream.phase.value == TerminalPhase.Live) stream.columns else pane.value?.grid?.cols
    files.scan(paneId, width, cwd)
  }

  private fun act(block: suspend () -> Unit) {
    viewModelScope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: ApiError) {
        _messages.send(
          when (e.code) {
            "agent_blocked" -> UiMessage(R.string.error_agent_blocked)
            "agent_not_ready" -> UiMessage(if (pane.value?.starting == true) R.string.error_agent_starting else R.string.error_agent_not_ready)
            "agent_not_found" -> UiMessage(R.string.error_agent_not_ready)
            "agent_pane_busy" -> UiMessage(R.string.error_pane_busy)
            "confirmation_required" -> UiMessage(R.string.error_close_confirm)
            else -> UiMessage(R.string.error_herdr, e.code)
          }
        )
      } catch (e: Exception) {
        _messages.send(UiMessage(R.string.error_connection, e.message ?: e.javaClass.simpleName))
      }
      kicks.tryEmit(Unit)
      server.sessions.refreshNow()
    }
  }

  private companion object {
    const val SCREEN_PERIOD_MS = 1_000L
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
