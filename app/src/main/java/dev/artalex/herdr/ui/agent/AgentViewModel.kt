package dev.artalex.herdr.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.artalex.herdr.PromptPart
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import dev.artalex.herdr.Servers
import dev.artalex.herdr.AgentStartProblem
import dev.artalex.herdr.core.files.FileOrigin
import dev.artalex.herdr.core.host.HerdrHost
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
import dev.artalex.herdr.data.FilesSettings
import dev.artalex.herdr.data.SnippetStore
import dev.artalex.herdr.ui.common.PaneKey
import dev.artalex.herdr.ui.terminal.PaneStream
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
class AgentViewModel(
  servers: Servers,
  val server: Server,
  val paneId: String,
  private val snippetStore: SnippetStore,
  private val filesSettings: FilesSettings,
) : ViewModel() {
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

  /**
   * Logical keys (`1`, `enter`, `esc`, `up`, `ctrl+shift+c`, …); herdr encodes them for the pane. They go in order with
   * the other input to the pane ([PaneStream.inOrder]).
   */
  fun sendKeys(keys: List<String>) =
    act { host ->
      stream.inOrder {
        stream.scrollToBottom()
        if (pane.value?.pane?.agent != null) host.api.sendAgentKeys(paneId, keys) else host.api.sendPaneKeys(paneId, keys)
      }
    }

  /**
   * [key] by name, as [sendKeys], or typed into the live stream as on the terminal screen, when herdr's API has no name
   * for it (Home, PgUp). Without a live stream such a key goes nowhere, and the snackbar says so.
   */
  fun sendKey(key: PaneKey) =
    when (key) {
      is PaneKey.Named -> sendKeys(listOf(key.name))
      is PaneKey.Typed -> stream.type(key)
    }

  /** The prompt being written. The server keeps it: it outlives this model, which goes with the screen. */
  val draft = server.drafts.of(paneId)

  /**
   * Sends the prompt's text to the agent; the draft keeps it until herdr takes it.
   *
   * [now]: Ctrl+Enter rather than the send button. Claude Code then gets Ctrl+Enter too, its "send now": a prompt that
   * comes while it works waits for the end of the turn, and Ctrl+Enter hands it over at once. Should the key come before
   * Claude Code has queued the prompt, the prompt waits, as if sent with the button. Other agents get the prompt alone:
   * Ctrl+Enter may mean something else to them. Without a prompt Ctrl+Enter goes by itself, as from the row.
   */
  fun sendPrompt(now: Boolean = false) {
    if (now && draft.value.text.isBlank()) {
      sendKeys(listOf(SEND_NOW))
      return
    }
    val text = draft.take() ?: return
    val thenSendNow = now && pane.value?.pane?.agent == AgentKind.CLAUDE.kind
    act(onFailure = draft::failed) { host ->
      stream.inOrder {
        stream.scrollToBottom()
        host.api.promptAgent(paneId, text)
        draft.sent()
        if (thenSendNow) host.api.sendAgentKeys(paneId, listOf(SEND_NOW))
      }
    }
  }

  /** The prompt snippets, the same on every agent screen. */
  val snippets: StateFlow<List<Snippet>> = snippetStore.snippets

  /** Saves [text] at the end of the snippets and says so: the menu it came from is gone by then. */
  fun saveSnippet(text: String) {
    snippetStore.add(text)
    _messages.trySend(UiMessage(R.string.snippets_saved, text.trim()))
  }

  fun setSnippets(snippets: List<Snippet>) = snippetStore.set(snippets)

  /** What went wrong with an agent started from the phone, whichever screen started it. */
  val startProblems: Flow<AgentStartProblem> = server.agentStarts.problems

  /** Looks up the installed agents once; the host caches the answer for the connection. Without one the snackbar says so. */
  fun loadAgents() {
    if (_agents.value != null) return
    viewModelScope.launch {
      val host = server.host
      if (host == null) {
        _messages.send(UiMessage(R.string.state_disconnected))
        return@launch
      }
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
    act { host ->
      host.api.startNamedAgent(paneId, kind, cwd)
      server.agentStarts.started(paneId, kind, snippetStore.snippets.value.onStart(kind))
    }

  /** Opens a tab in this pane's workspace and directory, starts a [kind] agent there and shows it; see [startAgent]. */
  fun newSession(kind: AgentKind) =
    act { host ->
      val pane = pane.value?.pane ?: return@act
      val dir = cwd
      val created = host.api.createTab(pane.workspaceId, dir)
      host.api.startNamedAgentInNewPane(created.paneId, kind, dir)
      server.agentStarts.started(created.paneId, kind, snippetStore.snippets.value.onStart(kind))
      _opened.send(created.paneId)
    }

  /** Closes this pane and everything in it; the screen leaves once a snapshot no longer has the pane. */
  fun closePane() =
    act { host ->
      host.api.closePane(paneId)
    }

  /** Files between the phone and the host, for the files window. */
  val files = server.files

  /** Shared text and files for the prompt, as they come; see [dev.artalex.herdr.PromptInbox]. */
  val inbox: Flow<PromptPart> = server.inbox.of(paneId)

  /** The agent's folder, where relative paths on its screen start. */
  val cwd: String?
    get() = pane.value?.pane?.let { it.foregroundCwd ?: it.cwd }

  /** Where the files of this pane come from: its terminal and workspace, once the pane is known. */
  val origin: FileOrigin?
    get() = pane.value?.pane?.let { FileOrigin(it.terminalId, it.workspaceId) }

  /** The files window lists the files of the whole workspace rather than of this pane; see [FilesSettings]. */
  val filesWorkspace: StateFlow<Boolean> = filesSettings.workspace

  fun setFilesWorkspace(on: Boolean) = filesSettings.setWorkspace(on)

  /** The files window opens: the paths on the pane's screen as it is now are looked up on the host. */
  fun scanFiles() {
    // The live stream gives the pane the phone's width; a snapshot shows it at the width of whoever controls it.
    val width = if (stream.phase.value == TerminalPhase.Live) stream.columns else pane.value?.grid?.cols
    files.scan(paneId, width, cwd, origin)
  }

  /**
   * Runs [block] against the connected host. Without a connection nothing goes and the snackbar says so; herdr's refusal
   * or a broken connection goes to the snackbar too. Either way [onFailure] runs. It runs in the server's scope, so
   * leaving the screen doesn't cut it short: a sent prompt reaches herdr or comes back to its draft, and an agent started
   * gets its snippets.
   */
  private fun act(onFailure: () -> Unit = {}, block: suspend (HerdrHost) -> Unit) {
    server.scope.launch {
      val host = server.host
      if (host == null) {
        onFailure()
        _messages.send(UiMessage(R.string.state_disconnected))
        return@launch
      }
      try {
        block(host)
      } catch (e: CancellationException) {
        throw e
      } catch (e: ApiError) {
        onFailure()
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
        onFailure()
        _messages.send(UiMessage(R.string.error_connection, e.message ?: e.javaClass.simpleName))
      }
      kicks.tryEmit(Unit)
      server.sessions.refreshNow()
    }
  }

  private companion object {
    const val SCREEN_PERIOD_MS = 1_000L
    const val STOP_TIMEOUT_MS = 5_000L

    /** Claude Code's "send now": the prompts it has queued go to Claude at once, mid-turn. */
    const val SEND_NOW = "ctrl+enter"
  }
}
