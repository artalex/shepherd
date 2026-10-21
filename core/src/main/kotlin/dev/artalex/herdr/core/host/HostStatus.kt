package dev.artalex.herdr.core.host

import dev.artalex.herdr.core.MIN_HERDR_VERSION
import dev.artalex.herdr.core.SUPPORTED_PROTOCOL
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

  data class WrongProtocol(val protocol: Int?) : HostCheck

  /** API works, but terminal streams need the server restarted on the updated binary. */
  data object RestartNeeded : HostCheck
}

fun HostStatus.check(): HostCheck {
  val version = server.version
  return when {
    !server.running -> HostCheck.ServerNotRunning
    version == null || compareVersions(version, MIN_HERDR_VERSION) < 0 -> HostCheck.TooOld(version ?: "?")
    server.protocol != SUPPORTED_PROTOCOL -> HostCheck.WrongProtocol(server.protocol)
    server.compatible != true -> HostCheck.RestartNeeded
    else -> HostCheck.Ok
  }
}

/** Compares dotted release numbers; pre-release suffixes (`0.9.3-preview.…`) sort before the release. */
fun compareVersions(a: String, b: String): Int {
  fun split(v: String): Pair<List<Int>, Boolean> {
    val core = v.substringBefore('-')
    return core.split('.').map { it.toIntOrNull() ?: 0 } to (core != v)
  }
  val (na, preA) = split(a)
  val (nb, preB) = split(b)
  for (i in 0 until maxOf(na.size, nb.size)) {
    val c = (na.getOrElse(i) { 0 }).compareTo(nb.getOrElse(i) { 0 })
    if (c != 0) return c
  }
  return preB.compareTo(preA)
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
