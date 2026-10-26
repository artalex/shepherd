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

/** The shell found no herdr at [path]: `herdr status` exited with 127. */
class HerdrNotFound(val path: String) : IOException("herdr not found at $path")

/** herdr refused [session] as a session name; [reason] is herdr's own, like "session name cannot be empty". */
class BadSessionName(val session: String, val reason: String) : IOException("herdr refused session name '$session': $reason")

/** `herdr status` printed no status; [detail] is what herdr or the shell said instead. */
class HerdrStatusFailed(val detail: String) : IOException("herdr status failed: $detail")

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
 * The folder [path] names on the host, as herdr shows the folders of panes: absolute and with symlinks resolved. `~`,
 * and a path that starts with `~/` or doesn't start with `/`, start in the remote home. Null if there is no such
 * folder or it can't be entered.
 */
suspend fun HerdrTransport.resolveFolder(path: String, timeoutMs: Long = 10_000): String? {
  val script = "d=${'$'}1; case ${'$'}d in \"~\") d=${'$'}HOME ;; \"~/\"*) d=${'$'}HOME/${'$'}{d#\"~/\"} ;; esac; cd -- \"${'$'}d\" 2>/dev/null && pwd -P"
  val stream = withContext(Dispatchers.IO) { exec(listOf("sh", "-c", script, "sh", path)) }
  val (out, exitStatus) = blockingIoWithDeadline(stream, timeoutMs, "folder lookup") { stream.input.readAllBytes() to stream.exitStatus() }
  return out.toString(Charsets.UTF_8).removeSuffix("\n").takeIf { exitStatus == 0 && it.startsWith("/") }
}

/** The host's home folder as [resolveFolder] gives `~`, and the folders of a [checkFolders] the host still has. */
data class FolderCheck(val home: String, val existing: List<String>)

/**
 * Looks up the remote home folder and which of [folders], folders [resolveFolder] gave earlier, the host still has and
 * lets in. They keep their order.
 */
suspend fun HerdrTransport.checkFolders(folders: List<String>, timeoutMs: Long = 10_000): FolderCheck {
  // A NUL after each folder: a name may hold any other character. A folder one can enter is a directory with x; test
  // is built in, where a cd would take a subshell for each of what may be many folders.
  val script = "h=${'$'}(cd -- \"${'$'}HOME\" 2>/dev/null && pwd -P) || exit 1; printf '%s\\0' \"${'$'}h\"; for d; do [ -d \"${'$'}d\" ] && [ -x \"${'$'}d\" ] && printf '%s\\0' \"${'$'}d\"; done; exit 0"
  val stream = withContext(Dispatchers.IO) { exec(listOf("sh", "-c", script, "sh") + folders) }
  val (out, exitStatus) = blockingIoWithDeadline(stream, timeoutMs, "folder check") { stream.input.readAllBytes() to stream.exitStatus() }
  val names = out.toString(Charsets.UTF_8).split('\u0000').dropLast(1)
  val home = names.firstOrNull()?.takeIf { exitStatus == 0 && it.startsWith("/") } ?: throw IOException("folder check failed, exit status ${exitStatus ?: "unknown"}")
  return FolderCheck(home, names.drop(1))
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
  val process = withContext(Dispatchers.IO) { exec(listOf(herdrPath, "--session", session, "status", "--json")) }
  val (out, exitStatus) = blockingIoWithDeadline(process, timeoutMs, "herdr status") { process.input.readAllBytes() to process.exitStatus() }
  return hostStatusOf(herdrPath, session, out.toString(Charsets.UTF_8), process.stderr, exitStatus)
}

/**
 * The status `herdr status --json` printed, or why there is none: the shell found no herdr at [herdrPath], herdr
 * refused [session] as a session name, or another error herdr or the shell wrote to [stderr].
 */
internal fun hostStatusOf(herdrPath: String, session: String, stdout: String, stderr: String, exitStatus: Int?): HostStatus {
  val text = stdout.trim()
  if (text.isNotEmpty()) return HostStatus.parse(text)
  if (exitStatus == 127) throw HerdrNotFound(herdrPath)
  // herdr reports a command line it refuses as "error: <reason>", then a line about --help.
  val lines = stderr.lines().map { it.trim() }.filter { it.isNotEmpty() }
  val reason = lines.firstOrNull { it.startsWith("error: ") }?.removePrefix("error: ")
  if (reason != null && reason.startsWith("session name")) throw BadSessionName(session, reason)
  throw HerdrStatusFailed(reason ?: lines.joinToString(" ").ifEmpty { "nothing printed, exit status ${exitStatus ?: "unknown"}" })
}
