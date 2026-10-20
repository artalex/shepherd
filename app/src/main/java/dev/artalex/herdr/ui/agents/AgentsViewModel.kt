package dev.artalex.herdr.ui.agents

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.artalex.herdr.Server
import dev.artalex.herdr.Servers
import dev.artalex.herdr.connection.ConnectionState
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.ssh.HostKey
import dev.artalex.herdr.core.store.Board
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.store.buildBoard
import dev.artalex.herdr.data.NotificationSettings
import dev.artalex.herdr.notify.Alerts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface BoardState {
  data object Loading : BoardState

  /** [staleReason] is set when the last poll failed and [board] is the previous snapshot. */
  data class Ready(val board: Board, val staleReason: String?) : BoardState
}

/** A server on the agent list: its settings, its connection and its panes. */
data class ServerSection(val config: ServerConfig, val connection: ConnectionState, val board: BoardState)

@OptIn(ExperimentalCoroutinesApi::class)
class AgentsViewModel(private val servers: Servers, private val notifications: NotificationSettings, private val alerts: Alerts) : ViewModel() {
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
  fun shouldAskForNotifications(): Boolean = Build.VERSION.SDK_INT >= 33 && notifications.enabled.value && !notifications.asked && !alerts.allowed()

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

  private companion object {
    /** Keeps polling across a configuration change instead of restarting. */
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
