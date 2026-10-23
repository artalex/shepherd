package dev.artalex.herdr.core.servers

import dev.artalex.herdr.core.DEFAULT_SESSION
import dev.artalex.herdr.core.SessionNameProblem
import dev.artalex.herdr.core.sessionNameProblem
import dev.artalex.herdr.core.ssh.HostKey
import dev.artalex.herdr.core.ssh.SshTarget
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A herdr session the app watches and how to reach it. [hostKey] is the server's SSH key the user accepted, as
 * [HostKey.encode] writes it; an [active] server is connected and its agents are on the list.
 */
@Serializable
data class ServerConfig(
  val id: String,
  /** The user's name for the server; empty for [defaultTitle]. */
  val name: String = "",
  val host: String = "",
  val port: Int = 22,
  val user: String = "",
  /** May start with `~/`, which expands to the remote home. */
  val herdrPath: String = DEFAULT_HERDR_PATH,
  val session: String = DEFAULT_SESSION,
  val hostKey: String? = null,
  val active: Boolean = true,
  /** The folder of the last agent started from the agent list, as the host resolved it; offered for the next one. */
  val agentFolder: String? = null,
) {
  val isComplete: Boolean get() = host.isNotBlank() && user.isNotBlank() && port in 1..65535

  /** What herdr would refuse in [session] as the form saves it, trimmed; null if herdr takes it. */
  val sessionProblem: SessionNameProblem? get() = sessionNameProblem(session.trim())

  val target: SshTarget get() = SshTarget(host.trim(), port, user.trim())

  /** The name to show: the user's, or [defaultTitle]. */
  val title: String get() = name.trim().ifEmpty { defaultTitle }

  /** The host, and the session unless it is herdr's default one. */
  val defaultTitle: String
    get() = if (session.isBlank() || session.trim() == DEFAULT_SESSION) host.trim() else "${host.trim()} · ${session.trim()}"

  /** The accepted host key; null if there is none yet, or it can't be read and the user has to accept it again. */
  val pinnedHostKey: HostKey? get() = hostKey?.let { runCatching { HostKey.decode(it) }.getOrNull() }

  /** Reaches the same herdr session as [other]: renaming a server doesn't need a new connection. */
  fun sameConnection(other: ServerConfig) = target == other.target && herdrPath.trim() == other.herdrPath.trim() && session.trim() == other.session.trim()

  /** Without the spaces a form leaves around its values. */
  fun trimmed() = copy(name = name.trim(), host = host.trim(), user = user.trim(), herdrPath = herdrPath.trim(), session = session.trim())

  companion object {
    const val DEFAULT_HERDR_PATH = "~/.local/bin/herdr"

    /** A blank server with an id of its own, for the form that adds one. */
    fun new() = ServerConfig(id = UUID.randomUUID().toString())
  }
}

/** The servers the user set up, in the order they were added, as the phone keeps them. */
object ServerList {
  // Defaults written out: a default changed later must not change what stored servers mean.
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val serializer = ListSerializer(ServerConfig.serializer())

  fun encode(servers: List<ServerConfig>): String = json.encodeToString(serializer, servers)

  /** The servers saved by [encode]; none for missing or unreadable text. */
  fun decode(text: String?): List<ServerConfig> =
    try {
      if (text.isNullOrBlank()) emptyList() else json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
      emptyList()
    } catch (e: IllegalArgumentException) {
      emptyList()
    }
}

/**
 * Adds [server], or puts the form's fields into the server with its id. A server keeps its accepted host key, whether
 * it is on and its agent folder: those change through [withHostKey], [withActive] and [withAgentFolder]. Moving it to
 * another host or port forgets the key and the folder, which belonged to the old machine.
 */
fun List<ServerConfig>.withServer(server: ServerConfig): List<ServerConfig> {
  val form = server.trimmed()
  val old = firstOrNull { it.id == form.id } ?: return this + form
  val moved = old.host != form.host || old.port != form.port
  val edited = form.copy(hostKey = if (moved) null else old.hostKey, active = old.active, agentFolder = if (moved) null else old.agentFolder)
  return map { if (it.id == form.id) edited else it }
}

fun List<ServerConfig>.without(id: String): List<ServerConfig> = filter { it.id != id }

fun List<ServerConfig>.withActive(id: String, active: Boolean): List<ServerConfig> = map { if (it.id == id) it.copy(active = active) else it }

fun List<ServerConfig>.withHostKey(id: String, key: HostKey?): List<ServerConfig> = map { if (it.id == id) it.copy(hostKey = key?.encode()) else it }

fun List<ServerConfig>.withAgentFolder(id: String, folder: String): List<ServerConfig> = map { if (it.id == id) it.copy(agentFolder = folder) else it }
