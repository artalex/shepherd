package dev.artalex.herdr.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import dev.artalex.herdr.Servers
import dev.artalex.herdr.connection.ConnectionState
import dev.artalex.herdr.core.host.resolveFolder
import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.rpc.ApiError
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.snippets.onStart
import dev.artalex.herdr.core.ssh.HostKey
import dev.artalex.herdr.core.store.Board
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.store.buildBoard
import dev.artalex.herdr.core.store.startSession
import dev.artalex.herdr.data.NotificationSettings
import dev.artalex.herdr.data.SnippetStore
import dev.artalex.herdr.notify.Alerts
import dev.artalex.herdr.ui.agent.UiMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface BoardState {
  data object Loading : BoardState

  /** [staleReason] is set when the last poll failed and [board] is the previous snapshot. */
  data class Ready(val board: Board, val staleReason: String?) : BoardState
}

/** A server on the agent list: its settings, its connection and its panes. */
data class ServerSection(val config: ServerConfig, val connection: ConnectionState, val board: BoardState)

/**
 * The dialog that starts an agent on server [serverId] from the agent list. [agents] are those herdr can start on its
 * machine, null while they are looked up; the folder field starts with [folder], and [folders] are the folders of the
 * server's panes, to pick instead of typing. [missingFolder] is a folder the host turned out not to have, as typed;
 * [problem] is why looking up the agents or starting one failed.
 */
data class NewAgent(
  val serverId: String,
  val serverTitle: String,
  val agents: List<AgentKind>?,
  val folder: String,
  val folders: List<String>,
  val starting: Boolean = false,
  val missingFolder: String? = null,
  val problem: UiMessage? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AgentsViewModel(
  private val servers: Servers,
  private val notifications: NotificationSettings,
  private val alerts: Alerts,
  private val snippets: SnippetStore,
) : ViewModel() {
  /** The servers in the order they were added; null until the first look at them. */
  val sections: StateFlow<List<ServerSection>?> =
    servers.configs
      // A new name or a switched server comes through the server's own settings; only adding or deleting a server
      // starts over, which polls every server afresh.
      .map { configs -> configs.mapNotNull { servers[it.id] } }
      .distinctUntilChanged()
      .flatMapLatest { list -> if (list.isEmpty()) flowOf(emptyList()) else combine(list.map(::section)) { it.toList() } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

  private fun section(server: Server): Flow<ServerSection> =
    combine(server.config, server.connection.state, server.sessions.state) { config, connection, session ->
      ServerSection(config, connection, boardOf(session))
    }

  private fun boardOf(state: SessionState): BoardState =
    when (state) {
      SessionState.Loading -> BoardState.Loading
      is SessionState.Ready -> BoardState.Ready(buildBoard(state.snapshot), staleReason = null)
      is SessionState.Failed -> state.last?.let { BoardState.Ready(buildBoard(it.snapshot), staleReason = state.error.message ?: "") } ?: BoardState.Loading
    }

  fun setActive(id: String, active: Boolean) = servers.setActive(id, active)

  /** Notifications are on, but Android wants a permission for them that the list hasn't asked for yet. */
  fun shouldAskForNotifications(): Boolean = notifications.enabled.value && !notifications.asked && !alerts.allowed()

  /** The list asks once; after that the permission is the settings' business. */
  fun askedForNotifications() {
    notifications.asked = true
  }

  /** The user compared the fingerprint and accepted [key] for server [id]. */
  fun trustHostKey(id: String, key: HostKey) {
    servers[id]?.connection?.trustHostKey(key)
  }

  /** Polls every server that is on right away; one whose connection failed tries again. */
  fun refresh() {
    for (config in servers.configs.value) {
      val server = servers[config.id]?.takeIf { config.active } ?: continue
      if (server.connection.state.value is ConnectionState.Failed) server.connection.connect() else server.sessions.refreshNow()
    }
  }

  private val _newAgent = MutableStateFlow<NewAgent?>(null)

  /** The dialog that starts an agent from the list, while it is open; see [openNewAgent]. */
  val newAgent: StateFlow<NewAgent?> = _newAgent.asStateFlow()

  private val _opened = Channel<Pair<String, String>>(Channel.BUFFERED)

  /** A server and a pane to show: the agent just started from the list. */
  val opened: Flow<Pair<String, String>> = _opened.receiveAsFlow()

  /**
   * Opens the dialog that starts an agent on server [id]. Its folder is the one the last agent from the list started
   * in, else a pane's, else the home folder: a session without panes offers none of its own.
   */
  fun openNewAgent(id: String) {
    val server = servers[id] ?: return
    val config = servers.config(id) ?: return
    val folders = paneFolders(server)
    _newAgent.value = NewAgent(id, config.title, agents = null, folder = config.agentFolder ?: folders.firstOrNull() ?: HOME, folders = folders)
    viewModelScope.launch {
      try {
        val host = server.host
        if (host == null) {
          change(id) { it.copy(agents = emptyList(), problem = UiMessage(R.string.state_disconnected)) }
          return@launch
        }
        val agents = host.installedAgents()
        change(id) { it.copy(agents = agents) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        change(id) { it.copy(agents = emptyList(), problem = UiMessage(R.string.error_connection, e.message ?: e.javaClass.simpleName)) }
      }
    }
  }

  /** Closes the dialog, unless an agent is starting from it: it shows where the agent went once started. */
  fun closeNewAgent() {
    _newAgent.update { if (it?.starting == true) it else null }
  }

  /**
   * Starts a [kind] agent in [folder] on the dialog's server (see startSession), with its start snippets, and shows it.
   * The folder is looked up on the host first: herdr would open one the host doesn't have as the home folder. The
   * folder as the host resolved it is remembered for the next agent from the list.
   */
  fun startNewAgent(kind: AgentKind, folder: String) {
    val form = _newAgent.value?.takeUnless { it.starting } ?: return
    val id = form.serverId
    _newAgent.value = form.copy(starting = true, missingFolder = null, problem = null)
    viewModelScope.launch {
      try {
        val server = servers[id]
        val host = server?.host
        if (host == null) {
          change(id) { it.copy(starting = false, problem = UiMessage(R.string.state_disconnected)) }
          return@launch
        }
        val typed = folder.trim()
        val resolved = host.transport.resolveFolder(typed)
        if (resolved == null) {
          change(id) { it.copy(starting = false, missingFolder = typed) }
          return@launch
        }
        val paneId = host.api.startSession(resolved, kind)
        server.startPrompts.send(paneId, snippets.snippets.value.onStart(kind))
        servers.setAgentFolder(id, resolved)
        _newAgent.value = null
        _opened.send(id to paneId)
        server.sessions.refreshNow()
      } catch (e: CancellationException) {
        throw e
      } catch (e: ApiError) {
        change(id) { it.copy(starting = false, problem = UiMessage(R.string.error_herdr, e.code)) }
      } catch (e: Exception) {
        change(id) { it.copy(starting = false, problem = UiMessage(R.string.error_connection, e.message ?: e.javaClass.simpleName)) }
      }
    }
  }

  /** Changes the dialog if it is still the one of server [id]. */
  private fun change(id: String, edit: (NewAgent) -> NewAgent) = _newAgent.update { form -> if (form?.serverId == id) edit(form) else form }

  /** The folders of the server's panes in its last snapshot, each once. */
  private fun paneFolders(server: Server): List<String> {
    val snapshot =
      when (val state = server.sessions.state.value) {
        SessionState.Loading -> null
        is SessionState.Ready -> state.snapshot
        is SessionState.Failed -> state.last?.snapshot
      }
    return snapshot?.panes.orEmpty().mapNotNull { it.foregroundCwd ?: it.cwd }.distinct()
  }

  private companion object {
    /** The remote home folder, as the folder field takes it. */
    const val HOME = "~"

    /** Keeps polling across a configuration change instead of restarting. */
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
