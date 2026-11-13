package dev.artalex.herdr.core.host

import dev.artalex.herdr.core.MIN_HERDR_VERSION
import dev.artalex.herdr.core.rpc.herdrJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `herdr --session <s> status --json`: where the API socket is and whether CLI and server match. */
@Serializable
data class HostStatus(val client: Client, val server: Server) {
  @Serializable data class Client(val version: String, val binary: String)

  @Serializable
  data class Server(
    val running: Boolean,
    val version: String? = null,
    /** herdr's private protocol between CLI and server, changed in 0.7.0, 0.7.2, 0.7.5, 0.8.0, 0.8.2, 0.9.0: only logged. */
    val protocol: Int? = null,
    /** CLI and server speak the same private protocol: required for terminal streams. Null while the server is down. */
    val compatible: Boolean? = null,
    val socket: String,
  )

  companion object {
    fun parse(json: String): HostStatus = herdrJson.decodeFromString(serializer(), json)
  }
}

sealed interface HostCheck {
  data object Ok : HostCheck

  data object ServerNotRunning : HostCheck

  data class TooOld(val version: String) : HostCheck

  /** API works, but terminal streams need the server restarted on the updated binary. */
  data object RestartNeeded : HostCheck
}

/**
 * Whether the app can work with this herdr. The API needs a running server of [MIN_HERDR_VERSION] or newer, and
 * nothing else: herdr's `protocol` number is that of its private protocol between CLI and server ("for same-install
 * and internal operations, including direct terminal attach and live handoff", its socket API docs say), and the app
 * never speaks it. The terminal stream does, through the CLI of the same install, so it works only while CLI and
 * server match ([HostStatus.Server.compatible]), whatever the number.
 */
fun HostStatus.check(): HostCheck {
  val version = server.version
  return when {
    !server.running -> HostCheck.ServerNotRunning
    version == null || compareVersions(version, MIN_HERDR_VERSION) < 0 -> HostCheck.TooOld(version ?: "?")
    server.compatible != true -> HostCheck.RestartNeeded
    else -> HostCheck.Ok
  }
}

/**
 * Compares herdr versions the way herdr does: by their dotted release numbers, and a build with a suffix, such as the
 * preview `0.9.3-preview.2026-10-12-6d697e1e75ff`, is development after the release it names, so it sorts after that
 * release and before the next one. Two such builds of one release compare equal: herdr doesn't order them either.
 */
fun compareVersions(a: String, b: String): Int {
  fun split(v: String): Pair<List<Int>, Boolean> {
    val release = v.substringBefore('-')
    return release.split('.').map { it.toIntOrNull() ?: 0 } to (release != v)
  }
  val (na, laterA) = split(a)
  val (nb, laterB) = split(b)
  for (i in 0 until maxOf(na.size, nb.size)) {
    val c = (na.getOrElse(i) { 0 }).compareTo(nb.getOrElse(i) { 0 })
    if (c != 0) return c
  }
  return laterA.compareTo(laterB)
}

/** Quotes argv for the remote shell that sshd uses to run exec commands. */
fun shellQuote(argv: List<String>): String = argv.joinToString(" ", transform = ::quoteArg)

/**
 * Like [shellQuote], but a leading `~/` in the program path expands to the remote `$HOME`, so the
 * default herdr location `~/.local/bin/herdr` works without knowing the remote user's home.
 */
fun remoteCommand(argv: List<String>): String {
  val program = argv.first()
  val head = if (program.startsWith("~/")) "\"\$HOME\"/" + quoteArg(program.removePrefix("~/")) else quoteArg(program)
  return (listOf(head) + argv.drop(1).map(::quoteArg)).joinToString(" ")
}

private fun quoteArg(arg: String): String =
  if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "@%+=:,./_-" }) arg else "'" + arg.replace("'", "'\\''") + "'"
