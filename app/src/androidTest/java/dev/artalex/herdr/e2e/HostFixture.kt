package dev.artalex.herdr.e2e

import androidx.test.platform.app.InstrumentationRegistry
import dev.artalex.herdr.AppContainer
import dev.artalex.herdr.Server
import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.core.host.HostStatus
import dev.artalex.herdr.core.proto.readNdjsonLine
import dev.artalex.herdr.core.proto.writeNdjsonLine
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.ssh.HostKey
import dev.artalex.herdr.core.ssh.SshConnection
import dev.artalex.herdr.core.ssh.SshFailure
import dev.artalex.herdr.core.ssh.SshTarget
import dev.artalex.herdr.core.ssh.discoverHerdrHost
import java.io.Closeable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals

/**
 * The test's own SSH line to herdr session [session] on the host, next to the app's: sets up panes and fake agents
 * through the raw API (the app's whitelist doesn't allow that) and reads what the panes received.
 */
class HostFixture(private val e2e: E2eArgs, val session: String = e2e.session) : Closeable {
  private val target = SshTarget(e2e.host, e2e.port.toInt(), e2e.user)

  /** The server's key, checked against the fingerprint from ssh-keygen on the host. */
  val hostKey: HostKey =
    try {
      SshConnection(target, e2e.key, null).connect()
      error("host key was trusted without pinning")
    } catch (e: SshFailure.UnknownHostKey) {
      assertEquals(e2e.hostKeyFingerprint, e.presented.fingerprint)
      e.presented
    }

  private val connection = SshConnection(target, e2e.key, hostKey).also { it.connect() }
  private val host: HerdrHost = runBlocking { discoverHerdrHost(connection, HERDR_PATH, session) }

  /**
   * Makes this session the app's only server, [SERVER_ID], with the test key; see [addServer]. Notifications go off:
   * the watch after each test's activity would keep connections and poll into the next test. NotificationsE2eTest
   * switches them on itself.
   */
  fun connectApp(container: AppContainer): Server {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      container.servers.clear()
      container.notifications.setEnabled(false)
    }
    container.keys.store(e2e.key)
    return addServer(container, SERVER_ID)
  }

  /**
   * Adds [session], this one unless a test names another, to the app's servers as [id], switched on with its host key
   * accepted, and connects it afresh. Coming to the foreground doesn't do that reliably: ProcessLifecycleOwner reports
   * the previous test's activity gone only 700 ms later, and a test that starts its own sooner never takes the app
   * through the background, so nothing would connect it.
   */
  fun addServer(container: AppContainer, id: String, name: String = "", session: String = this.session): Server {
    val config = ServerConfig(id = id, name = name, host = e2e.host, port = e2e.port.toInt(), user = e2e.user, session = session, hostKey = hostKey.encode())
    var server: Server? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      container.servers.save(config)
      server = container.servers[id]?.also { it.connection.connect() }
    }
    return checkNotNull(server) { "server $id was not added" }
  }

  fun raw(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
    connection.openStreamLocal(host.status.server.socket).use { stream ->
      stream.output.writeNdjsonLine(buildJsonObject { put("id", "e2e"); put("method", method); put("params", params) }.toString())
      val response = Json.parseToJsonElement(checkNotNull(stream.input.buffered().readNdjsonLine())).jsonObject
      check("error" !in response) { "$method failed: ${response["error"]}" }
      response.getValue("result").jsonObject
    }

  /** Runs [command] on the host with [stdin] and returns its output. */
  fun exec(command: String, stdin: String = ""): String =
    connection.exec(command).use { stream ->
      stream.output.write(stdin.toByteArray())
      stream.closeOutput()
      stream.input.readBytes().toString(Charsets.UTF_8)
    }

  /** Stops the session's herdr server, as `herdr server stop` on the host does; the session keeps its workspaces. */
  fun stopServer() {
    exec("$HERDR_PATH --session $session server stop")
    waitForServer(running = false)
  }

  /** Starts the session's herdr server again; it brings back the workspaces the session had. */
  fun startServer() {
    exec("nohup $HERDR_PATH --session $session server >/dev/null 2>&1 &")
    waitForServer(running = true)
  }

  private fun waitForServer(running: Boolean, timeoutMs: Long = 15_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (HostStatus.parse(exec("$HERDR_PATH --session $session status --json")).server.running != running) {
      check(System.currentTimeMillis() < deadline) { "herdr server of $session never got ${if (running) "up" else "down"}" }
      Thread.sleep(200)
    }
  }

  /** A new workspace; returns its root pane id. */
  fun workspace(label: String): String =
    raw("workspace.create", buildJsonObject { put("label", label) }).getValue("root_pane").jsonObject.getValue("pane_id").jsonPrimitive.content

  /** Closes every workspace of the session, with what runs in them: the session is left without panes. */
  fun closeWorkspaces() {
    // Listed afresh each time: closing a group takes its worktree workspaces along.
    while (true) {
      val workspace = raw("workspace.list").getValue("workspaces").jsonArray.firstOrNull() ?: return
      raw("workspace.close", buildJsonObject {
        put("workspace_id", workspace.jsonObject.getValue("workspace_id").jsonPrimitive.content)
        put("close_group", true)
      })
    }
  }

  fun split(paneId: String): String =
    raw("pane.split", buildJsonObject { put("target_pane_id", paneId); put("direction", "down") })
      .getValue("pane").jsonObject.getValue("pane_id").jsonPrimitive.content

  fun type(paneId: String, text: String) {
    raw("pane.send_input", buildJsonObject {
      put("pane_id", paneId)
      put("text", text)
      putJsonArray("keys") { add("enter") }
    })
  }

  /**
   * Turns [paneId] into a Claude agent herdr recognizes: a line echo started as argv0 `claude`
   * (herdr checks the foreground process before accepting agent.send_keys), in [state].
   */
  fun fakeClaude(paneId: String, state: String) {
    val script = exec("mkdir -p ~/.cache/herdr-android-e2e && cat > ~/.cache/herdr-android-e2e/echo.py && echo ~/.cache/herdr-android-e2e/echo.py", ECHO_PY).trim()
    type(paneId, "(exec -a claude python3 -u $script)")
    Thread.sleep(800)
    reportAgent(paneId, state)
  }

  fun reportAgent(paneId: String, state: String) {
    raw("pane.report_agent", buildJsonObject {
      put("pane_id", paneId)
      put("source", "custom:e2e")
      put("agent", "claude")
      put("state", state)
    })
  }

  fun screen(paneId: String): String =
    raw("pane.read", buildJsonObject { put("pane_id", paneId); put("source", "visible"); put("format", "text") })
      .getValue("read").jsonObject.getValue("text").jsonPrimitive.content

  /**
   * Waits until the pane's visible text satisfies [predicate]; returns the text. [pump] runs between polls:
   * Compose tests only advance frames when asked, and the app must keep rendering and resizing meanwhile.
   */
  fun waitForScreen(paneId: String, timeoutMs: Long = 10_000, pump: () -> Unit = {}, predicate: (String) -> Boolean): String {
    val deadline = System.currentTimeMillis() + timeoutMs
    var text = screen(paneId)
    while (!predicate(text)) {
      check(System.currentTimeMillis() < deadline) { "pane $paneId never matched; screen:\n$text" }
      pump()
      Thread.sleep(200)
      text = screen(paneId)
    }
    return text
  }

  /** The session's panes as herdr reports them in a snapshot. */
  fun panes(): List<JsonObject> = raw("session.snapshot").getValue("snapshot").jsonObject.getValue("panes").jsonArray.map { it.jsonObject }

  /** Waits until the session's panes satisfy [predicate]; returns them. */
  fun waitForPanes(timeoutMs: Long = 15_000, pump: () -> Unit = {}, predicate: (List<JsonObject>) -> Boolean): List<JsonObject> {
    val deadline = System.currentTimeMillis() + timeoutMs
    var panes = panes()
    while (!predicate(panes)) {
      check(System.currentTimeMillis() < deadline) { "panes never matched: $panes" }
      pump()
      Thread.sleep(200)
      panes = panes()
    }
    return panes
  }

  /** Closes [paneId] as the desktop would. */
  fun closePane(paneId: String) {
    raw("pane.close", buildJsonObject { put("pane_id", paneId) })
  }

  /** Makes the workspace of [paneId] the active one, as switching to it on the desktop would. */
  fun focusWorkspaceOf(paneId: String) {
    val workspace = raw("pane.get", buildJsonObject { put("pane_id", paneId) }).getValue("pane").jsonObject.getValue("workspace_id").jsonPrimitive.content
    raw("workspace.focus", buildJsonObject { put("workspace_id", workspace) })
  }

  fun terminalId(paneId: String): String =
    raw("pane.get", buildJsonObject { put("pane_id", paneId) }).getValue("pane").jsonObject.getValue("terminal_id").jsonPrimitive.content

  /** Text rows of [paneId]'s screen; while a client controls the pane, that client's rows. */
  fun viewportRows(paneId: String): Int =
    raw("pane.get", buildJsonObject { put("pane_id", paneId) }).getValue("pane").jsonObject
      .getValue("scroll").jsonObject.getValue("viewport_rows").jsonPrimitive.int

  /**
   * Does anyone control [terminalId] directly? A probe controller tells: it gets a frame, or "retry with --takeover".
   * The probe attaches for a moment itself, so don't run it while another controller is connecting.
   */
  fun isControlled(terminalId: String): Boolean {
    val line = exec("$HERDR_PATH --session $session terminal session control $terminalId --cols 40 --rows 10 </dev/null | head -1")
    return line.contains("retry with --takeover")
  }

  /**
   * Holds [terminalId] with a controller that lives up to a minute, like a desktop that attached directly.
   * The check waits first: a probe that runs while the holder attaches would itself be the controller.
   */
  fun holdControl(terminalId: String) {
    exec("nohup sh -c 'sleep 60 | $HERDR_PATH --session $session terminal session control $terminalId --cols 80 --rows 24 >/dev/null 2>&1' >/dev/null 2>&1 &")
    Thread.sleep(1_500)
    check(isControlled(terminalId)) { "holding controller did not attach" }
  }

  fun releaseHeld(terminalId: String) {
    exec("pkill -f 'terminal session control $terminalId --cols 80 --rows 24' || true")
  }

  /** The last `rows cols` line `stty size` printed in the pane. */
  fun sttySize(paneId: String): Pair<Int, Int>? =
    screen(paneId).lines().lastOrNull { it.trim().matches(Regex("\\d+ \\d+")) }?.trim()?.split(' ')?.let { it[0].toInt() to it[1].toInt() }

  override fun close() = connection.close()

  companion object {
    const val HERDR_PATH = "~/.local/bin/herdr"

    /** The id of the server [connectApp] sets up. */
    const val SERVER_ID = "e2e"

    /** Rows to columns of a pane with no desktop attached: herdr's default headless size. */
    val DESKTOP_SIZE = 40 to 120
    private const val ECHO_PY = "import sys\nfor line in sys.stdin:\n    sys.stdout.write(line)\n    sys.stdout.flush()\n"
  }
}
