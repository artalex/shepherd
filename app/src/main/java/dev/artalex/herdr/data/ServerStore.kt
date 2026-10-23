package dev.artalex.herdr.data

import android.content.Context
import androidx.core.content.edit
import dev.artalex.herdr.core.DEFAULT_SESSION
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.servers.ServerList
import dev.artalex.herdr.core.servers.withActive
import dev.artalex.herdr.core.servers.withAgentFolder
import dev.artalex.herdr.core.servers.withHostKey
import dev.artalex.herdr.core.servers.withServer
import dev.artalex.herdr.core.servers.without
import dev.artalex.herdr.core.ssh.HostKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The servers the user set up, kept as one value: a handful of servers, written only when the user changes one. */
class ServerStore(private val context: Context) {
  private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
  private val _servers = MutableStateFlow(load())

  /** The servers in the order they were added. */
  val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

  operator fun get(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id }

  /** Adds [server] or puts the form's fields into the server with its id; see [withServer]. */
  fun save(server: ServerConfig) = change { it.withServer(server) }

  fun delete(id: String) = change { it.without(id) }

  fun setActive(id: String, active: Boolean) = change { it.withActive(id, active) }

  fun pinHostKey(id: String, key: HostKey?) = change { it.withHostKey(id, key) }

  fun setAgentFolder(id: String, folder: String) = change { it.withAgentFolder(id, folder) }

  fun clear() = change { emptyList() }

  @Synchronized
  private fun change(edit: (List<ServerConfig>) -> List<ServerConfig>) {
    val servers = edit(_servers.value)
    prefs.edit { putString(LIST, ServerList.encode(servers)) }
    _servers.value = servers
  }

  private fun load(): List<ServerConfig> = prefs.getString(LIST, null)?.let(ServerList::decode) ?: migrate()

  /** Up to version 1.7 the app knew one server, kept as separate settings: it becomes the first server, switched on. */
  private fun migrate(): List<ServerConfig> {
    val old = context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE)
    val server =
      ServerConfig.new().copy(
        host = old.getString("host", "")!!,
        port = old.getInt("port", 22),
        user = old.getString("user", "")!!,
        herdrPath = old.getString("herdr_path", ServerConfig.DEFAULT_HERDR_PATH)!!,
        session = old.getString("session", DEFAULT_SESSION)!!,
        hostKey = old.getString("host_key", null),
      )
    val servers = if (server.isComplete) listOf(server.trimmed()) else emptyList()
    prefs.edit { putString(LIST, ServerList.encode(servers)) }
    context.deleteSharedPreferences(LEGACY)
    return servers
  }

  private companion object {
    const val LIST = "list"
    const val LEGACY = "connection"
  }
}
