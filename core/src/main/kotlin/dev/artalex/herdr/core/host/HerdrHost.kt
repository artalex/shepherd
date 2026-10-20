package dev.artalex.herdr.core.host

import dev.artalex.herdr.core.files.RemoteFiles
import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.rpc.HerdrApi
import dev.artalex.herdr.core.rpc.blockingIoWithDeadline
import dev.artalex.herdr.core.terminal.TerminalSession
import dev.artalex.herdr.core.terminal.terminalControlArgv
import dev.artalex.herdr.core.transport.HerdrTransport
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** `herdr status --json` printed nothing: no herdr at [path] (the shell exits with 127). */
class HerdrNotFound(val path: String) : IOException("herdr not found at $path")

/** A herdr session the app talks to: [api] for requests, [openTerminal] for live terminal streams. */
class HerdrHost(val status: HostStatus, val session: String, val transport: HerdrTransport) {
  val api = HerdrApi(transport)

  private val agentsLock = Mutex()
  private var agents: List<AgentKind>? = null

  /** Agents herdr can start that are installed on the host; looked up once per connection. */
  suspend fun installedAgents(): List<AgentKind> = agentsLock.withLock { agents ?: transport.installedAgents().also { agents = it } }

  private val uploadsLock = Mutex()
  private var uploads: String? = null

  /** Where files sent from the phone go; looked up once per connection. */
  suspend fun uploadDir(): String = uploadsLock.withLock { uploads ?: transport.uploadDir().also { uploads = it } }

  fun openTerminal(terminalId: String, cols: Int, rows: Int, takeover: Boolean): TerminalSession =
    TerminalSession(transport.exec(terminalControlArgv(status.client.binary, session, terminalId, cols, rows, takeover)))

  /** Files on the host, over a channel of their own. Blocking: not on the main thread. */
  fun openFiles(): RemoteFiles = transport.openFiles()
}

/**
 * The `herdr-android` folder in the host user's downloads folder: the XDG one (named in the desktop's language),
 * or `~/Downloads` where there is none.
 */
suspend fun HerdrTransport.uploadDir(timeoutMs: Long = 10_000): String {
  val script = "d=$(xdg-user-dir DOWNLOAD 2>/dev/null); if [ -z \"${'$'}d\" ] || [ \"${'$'}d\" = \"${'$'}HOME\" ]; then d=\"${'$'}HOME/Downloads\"; fi; printf '%s/herdr-android' \"${'$'}d\""
  val stream = withContext(Dispatchers.IO) { exec(listOf("sh", "-c", script)) }
  val out = blockingIoWithDeadline(stream, timeoutMs, "downloads folder lookup") { stream.input.readAllBytes() }
  return out.toString(Charsets.UTF_8).trim().also { if (!it.startsWith("/")) throw IOException("downloads folder lookup printed '$it'") }
}

/**
 * Which [AgentKind] executables the host has. A login shell reads the profile, so PATH comes out as in the
 * desktop session herdr runs in, and as in its panes' shells.
 */
suspend fun HerdrTransport.installedAgents(timeoutMs: Long = 10_000): List<AgentKind> {
  val names = AgentKind.entries.joinToString(" ") { it.executable }
  val script = "for x in $names; do command -v \"${'$'}x\" >/dev/null 2>&1 && echo \"${'$'}x\"; done"
  val stream = withContext(Dispatchers.IO) { exec(listOf("sh", "-lc", script)) }
  val out = blockingIoWithDeadline(stream, timeoutMs, "agent lookup") { stream.input.readAllBytes() }
  return parseInstalledAgents(out.toString(Charsets.UTF_8))
}

/** Agents whose executables the lookup printed, one per line, in [AgentKind] order. */
fun parseInstalledAgents(output: String): List<AgentKind> {
  val found = output.lines().map { it.trim() }.toSet()
  return AgentKind.entries.filter { it.executable in found }
}

/** Runs `herdr --session <session> status --json` on the host through [HerdrTransport.exec]. */
suspend fun HerdrTransport.readHostStatus(herdrPath: String, session: String, timeoutMs: Long = 10_000): HostStatus {
  val stream = withContext(Dispatchers.IO) { exec(listOf(herdrPath, "--session", session, "status", "--json")) }
  val out = blockingIoWithDeadline(stream, timeoutMs, "herdr status") { stream.input.readAllBytes() }
  val text = out.toString(Charsets.UTF_8).trim()
  if (text.isEmpty()) throw HerdrNotFound(herdrPath)
  return HostStatus.parse(text)
}
