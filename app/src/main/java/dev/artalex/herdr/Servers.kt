package dev.artalex.herdr

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.artalex.herdr.connection.ConnectionController
import dev.artalex.herdr.connection.ConnectionState
import dev.artalex.herdr.connection.SessionRepository
import dev.artalex.herdr.core.files.FileOrigin
import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.store.lastSnapshot
import dev.artalex.herdr.data.KeyVault
import dev.artalex.herdr.data.ServerStore
import dev.artalex.herdr.files.FileTransfers
import dev.artalex.herdr.ui.terminal.PaneStreams
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * A server at run time: its SSH connection, the herdr session as the screens see it, pane streams and files. Its
 * snapshots also go to [onSnapshot].
 */
class Server(
  val id: String,
  app: Application,
  store: ServerStore,
  keys: KeyVault,
  appScope: CoroutineScope,
  transfers: MutableStateFlow<Int>,
  onSnapshot: (serverId: String, SessionState.Ready) -> Unit,
) {
  /** The server's coroutines, under the app's: they end with the server ([dispose]). */
  val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext.job))

  val connection = ConnectionController(app, id, store, keys, scope)

  /** Shared text and files for the prompts of the panes; see [PromptInbox]. It learns of closed panes from the snapshots. */
  val inbox = PromptInbox()

  val sessions =
    SessionRepository(connection, scope) {
      inbox.seen(it.snapshot)
      onSnapshot(id, it)
    }
  val streams = PaneStreams(this)
  val files = FileTransfers(app, this, transfers)

  /** What follows agents the phone started: a launch the pane can't run, then their snippets; see [AgentStarts]. */
  val agentStarts = AgentStarts(this)

  /** The server's settings as the user changes them. */
  val config: Flow<ServerConfig> = store.servers.mapNotNull { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

  /** The connected host, if any: screens use it for requests that aren't polling. */
  val host: HerdrHost? get() = (connection.state.value as? ConnectionState.Connected)?.host

  /** Where files of pane [paneId] come from, by the last snapshot; null for a pane it doesn't have yet. */
  fun originOf(paneId: String): FileOrigin? =
    sessions.state.value.lastSnapshot?.panes?.firstOrNull { it.paneId == paneId }?.let { FileOrigin(it.terminalId, it.workspaceId) }

  fun dispose() {
    connection.dispose()
    files.dispose()
    scope.cancel()
  }
}

/**
 * The servers from the settings at run time, a [Server] each. A server that is on connects while the app is in the
 * foreground (see [ConnectionController]) or while background work keeps it connected ([keepConnected]); one that
 * is off stays disconnected. Changes to the servers go through here, so that their connections follow. Snapshots of
 * every server go to [onSnapshot].
 */
class Servers(
  private val app: Application,
  private val store: ServerStore,
  private val keys: KeyVault,
  private val scope: CoroutineScope,
  private val transfers: MutableStateFlow<Int>,
  private val onSnapshot: (serverId: String, SessionState.Ready) -> Unit,
) : DefaultLifecycleObserver {
  private val running = ConcurrentHashMap<String, Server>()
  private val _foreground = MutableStateFlow(false)
  private var keepers = 0
  private var network: Network? = null

  /** The app is on screen. */
  val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

  init {
    for (config in store.servers.value) running[config.id] = create(config.id)
  }

  /** The servers' settings, in the order they were added. */
  val configs: StateFlow<List<ServerConfig>> get() = store.servers

  fun config(id: String): ServerConfig? = store[id]

  /** No server is set up yet: the app starts on the form for one. */
  fun isEmpty() = store.servers.value.isEmpty()

  /** The server with [id]; null if there is none, as after it was deleted. */
  operator fun get(id: String): Server? = running[id]

  /** Adds a server or edits one. A new server connects if it is on; an edited one reconnects if it moved. */
  fun save(config: ServerConfig) {
    val old = store[config.id]
    // The server exists before its settings do: whoever sees them in the list finds the server too.
    val server = running.getOrPut(config.id) { create(config.id) }
    store.save(config)
    val saved = store[config.id] ?: return
    when {
      old == null -> if (_foreground.value) server.connection.foreground()
      !saved.sameConnection(old) && saved.active && _foreground.value -> server.connection.connect()
    }
  }

  fun delete(id: String) {
    store.delete(id)
    running.remove(id)?.dispose()
  }

  /** Switches a server on, and it connects, or off, and it disconnects and drops off the agent list. */
  fun setActive(id: String, active: Boolean) {
    store.setActive(id, active)
    val connection = running[id]?.connection ?: return
    if (!active) connection.disconnect() else if (_foreground.value) connection.connect()
  }

  /** Remembers [folder] for the next agent started from the agent list on server [id]; the connection stays. */
  fun setAgentFolder(id: String, folder: String) = store.setAgentFolder(id, folder)

  /** Deletes every server: the e2e tests start from scratch. */
  fun clear() {
    store.clear()
    running.values.forEach { it.dispose() }
    running.clear()
  }

  /**
   * Keeps the servers that are on connected while [block] runs, with the app in the background too: the agent watch
   * for notifications does that. They disconnect right after, unless the app is back on screen by then. Main thread.
   */
  suspend fun <T> keepConnected(block: suspend () -> T): T {
    if (keepers++ == 0 && !_foreground.value) running.values.forEach { it.connection.foreground() }
    try {
      return block()
    } finally {
      if (--keepers == 0 && !_foreground.value) running.values.forEach { it.connection.background(graceMs = 0) }
    }
  }

  override fun onStart(owner: LifecycleOwner) {
    _foreground.value = true
    running.values.forEach { it.connection.foreground() }
  }

  override fun onStop(owner: LifecycleOwner) {
    _foreground.value = false
    if (keepers == 0) running.values.forEach { it.connection.background() }
  }

  fun watchNetwork() {
    val cm = app.getSystemService(ConnectivityManager::class.java)
    cm.registerDefaultNetworkCallback(
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) {
          scope.launch {
            val previous = network
            network = available
            running.values.forEach { it.connection.networkChanged(changed = previous != null && previous != available) }
          }
        }
      }
    )
  }

  private fun create(id: String) = Server(id, app, store, keys, scope, transfers, onSnapshot)
}
