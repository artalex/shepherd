package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.installedAgents
import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.rpc.ApiError
import dev.artalex.herdr.core.rpc.HerdrApi
import dev.artalex.herdr.core.store.SessionPoller
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.store.buildBoard
import dev.artalex.herdr.core.store.startNamedAgent
import dev.artalex.herdr.core.store.startNamedAgentInNewPane
import dev.artalex.herdr.core.terminal.CloseReason
import dev.artalex.herdr.core.terminal.TerminalEvent
import dev.artalex.herdr.core.terminal.TerminalSession
import dev.artalex.herdr.core.terminal.terminalControlArgv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HerdrIntegrationTest {
  private lateinit var server: HerdrTestServer
  private lateinit var api: HerdrApi

  @BeforeAll
  fun startServer() {
    assumeTrue(HerdrTestServer.available, "herdr >= $MIN_HERDR_VERSION is not installed")
    server = HerdrTestServer()
    api = HerdrApi(server.transport)
  }

  @AfterAll
  fun stopServer() {
    if (::server.isInitialized) server.close()
  }

  /** A new workspace whose root pane runs [command] (`cat -v` by default, so keys show up as text). */
  private fun catPane(label: String, command: String = "cat -v"): Pair<String, String> {
    val created = server.raw("workspace.create", buildJsonObject { put("label", label) })
    val pane = created.getValue("root_pane").jsonObject
    val paneId = pane.getValue("pane_id").jsonPrimitive.content
    server.raw("pane.send_input", buildJsonObject {
      put("pane_id", paneId)
      put("text", command)
      putJsonArray("keys") { add("enter") }
    })
    Thread.sleep(500)
    return paneId to pane.getValue("terminal_id").jsonPrimitive.content
  }

  private fun reportAgent(paneId: String, state: String) {
    server.raw("pane.report_agent", buildJsonObject {
      put("pane_id", paneId)
      put("source", "custom:test")
      put("agent", "claude")
      put("state", state)
    })
  }

  private suspend fun screen(paneId: String) = api.readPane(paneId, ansi = false).text

  private suspend fun waitForScreen(paneId: String, predicate: (String) -> Boolean): String {
    var text = ""
    withTimeout(5_000) {
      while (true) {
        text = screen(paneId)
        if (predicate(text)) break
        delay(100)
      }
    }
    return text
  }

  /** Asks the shell for its terminal size; the pane must run `cat -v`, which is interrupted first. */
  private suspend fun sttySize(paneId: String): String {
    api.sendPaneKeys(paneId, listOf("ctrl+c"))
    server.raw("pane.send_input", buildJsonObject {
      put("pane_id", paneId)
      put("text", "clear; stty size; cat -v")
      putJsonArray("keys") { add("enter") }
    })
    val text = waitForScreen(paneId) { s -> s.lines().any { it.trim().matches(Regex("\\d+ \\d+")) } }
    return text.lines().last { it.trim().matches(Regex("\\d+ \\d+")) }.trim()
  }

  private suspend fun waitForSnapshot(predicate: (SessionSnapshot) -> Boolean): SessionSnapshot {
    val deadline = System.currentTimeMillis() + 15_000
    while (true) {
      val snapshot = api.snapshot()
      if (predicate(snapshot)) return snapshot
      check(System.currentTimeMillis() < deadline) { "snapshot never matched: ${snapshot.panes}" }
      delay(200)
    }
  }

  @Test
  fun newTabGetsClaudeAndCloses(): Unit = runBlocking {
    val root = server.raw("workspace.create", buildJsonObject { put("label", "panes") }).getValue("root_pane").jsonObject.getValue("pane_id").jsonPrimitive.content
    val dir = Files.createDirectories(server.dir.resolve("proj")).toString()
    // The fake claude the test server puts first on PATH counts as installed.
    assertTrue(AgentKind.CLAUDE in server.transport.installedAgents())
    val workspace = api.snapshot().panes.first { it.paneId == root }.workspaceId
    val created = api.createTab(workspace, dir)
    api.startNamedAgentInNewPane(created.paneId, AgentKind.CLAUDE, dir)
    val started = waitForSnapshot { s -> s.panes.any { it.paneId == created.paneId && it.agent == "claude" } }
    val pane = started.panes.first { it.paneId == created.paneId }
    assertEquals(dir, pane.cwd)
    assertEquals(workspace, pane.workspaceId)
    assertTrue(pane.tabId != started.panes.first { it.paneId == root }.tabId, "the new session should get its own tab")
    // The name `proj` is taken now; the second Claude in the same directory gets the next one.
    api.startNamedAgent(root, AgentKind.CLAUDE, dir)
    waitForSnapshot { s -> s.panes.any { it.paneId == root && it.agent == "claude" } }
    api.closePane(created.paneId)
    waitForSnapshot { s -> s.panes.none { it.paneId == created.paneId } }
  }

  @Test
  fun pingReportsSupportedProtocol() = runBlocking {
    val pong = api.ping()
    assertEquals(SUPPORTED_PROTOCOL, pong.protocol)
  }

  @Test
  fun fakeAgentsAppearInSnapshotAndBoard() = runBlocking {
    val (working, _) = catPane("agents-working")
    val (blocked, _) = catPane("agents-blocked")
    reportAgent(working, "working")
    reportAgent(blocked, "blocked")
    val snapshot = api.snapshot()
    assertEquals(AgentStatus.BLOCKED, snapshot.panes.single { it.paneId == blocked }.agentStatus)
    val board = buildBoard(snapshot)
    assertEquals("agents-blocked", board.groups.first().workspace.label)
    assertTrue(board.blockedCount >= 1)
  }

  @Test
  fun paneKeysReachTheTerminal() = runBlocking {
    val (pane, _) = catPane("keys")
    api.sendPaneKeys(pane, listOf("x", "enter"))
    waitForScreen(pane) { it.lines().any { line -> line.trim() == "x" } }
    Unit
  }

  @Test
  fun agentKeysReachTheAgentPane() = runBlocking {
    // agent.send_keys refuses panes whose foreground process isn't the agent itself.
    val pane = fakeClaudePane("agent-keys")
    reportAgent(pane, "blocked")
    api.sendAgentKeys(pane, listOf("2", "enter"))
    waitForScreen(pane) { it.lines().any { line -> line.trim() == "2" } }
    Unit
  }

  /**
   * A pane whose foreground process herdr recognizes as Claude: a line echo started with argv0 `claude`, after
   * [history] has run.
   */
  private fun fakeClaudePane(label: String, history: String? = null): String {
    // herdr matches the agent by argv0; /bin/cat can't be renamed because coreutils dispatches on argv0.
    val echo = server.dir.resolve("echo.py")
    Files.writeString(echo, "import sys\nfor line in sys.stdin:\n    sys.stdout.write(line)\n    sys.stdout.flush()\n")
    return catPane(label, listOfNotNull(history, "(exec -a claude python3 -u $echo)").joinToString("; ")).first
  }

  @Test
  fun promptReachesIdleAgent() = runBlocking {
    val pane = fakeClaudePane("prompt-idle")
    reportAgent(pane, "idle")
    api.promptAgent(pane, "привет агенту")
    waitForScreen(pane) { it.contains("привет агенту") }
    Unit
  }

  @Test
  fun promptIsRefusedWhileAgentWaitsForAnswer() = runBlocking {
    val (pane, _) = catPane("prompt-blocked")
    reportAgent(pane, "blocked")
    val e = assertFailsWith<ApiError> { api.promptAgent(pane, "hello") }
    assertEquals("agent_blocked", e.code)
  }

  @Test
  fun pollerDeliversSnapshots() = runBlocking {
    val poller = SessionPoller(api, this, periodMs = 200)
    val ready = withTimeout(5_000) { poller.state.filterIsInstance<SessionState.Ready>().first() }
    assertEquals(SUPPORTED_PROTOCOL, ready.snapshot.protocol)
    coroutineContext.cancelChildren()
  }

  /** Collects a control stream's events in the background. */
  private class Control(scope: CoroutineScope, val session: TerminalSession) {
    val events = Channel<TerminalEvent>(Channel.UNLIMITED)
    val job = scope.launch { session.events.collect { events.send(it) } }

    suspend fun next(): TerminalEvent = withTimeout(5_000) { events.receive() }

    suspend fun closed(): CloseReason = withTimeout(10_000) {
      var reason: CloseReason? = null
      while (reason == null) reason = (events.receive() as? TerminalEvent.Closed)?.reason
      reason
    }
  }

  private fun CoroutineScope.control(terminalId: String, cols: Int, rows: Int, takeover: Boolean = false) =
    Control(this, TerminalSession(server.transport.exec(terminalControlArgv(HerdrTestServer.binary, server.session, terminalId, cols, rows, takeover))))

  @Test
  fun controlStreamResizesAndReleases() = runBlocking {
    val (pane, terminal) = catPane("control")
    val a = control(terminal, 40, 12)
    val first = assertIs<TerminalEvent.Frame>(a.next())
    assertTrue(first.full)
    assertEquals(40 to 12, first.width to first.height)
    assertEquals("12 40", sttySize(pane))

    a.session.resize(50, 15)
    withTimeout(5_000) { while (sttySize(pane) != "15 50") delay(100) }

    a.session.sendText("typed\r")
    waitForScreen(pane) { it.lines().any { line -> line.trim() == "typed" } }
    // Russian text from the phone's keyboard travels as UTF-8 in terminal.input: the echo shows the word,
    // `cat -v` its exact bytes.
    a.session.sendText("привет\r")
    waitForScreen(pane) { s -> s.lines().any { it.trim() == "привет" } && s.contains("M-PM-?M-QM-^@M-PM-8M-PM-2M-PM-5M-QM-^B") }

    a.session.release()
    assertEquals(CloseReason.Detached, a.closed())
  }

  @Test
  fun scrollMovesThroughHistory() = runBlocking {
    val (pane, terminal) = catPane("scroll", "seq -f L%g 1 300")
    val a = control(terminal, 40, 12)
    assertIs<TerminalEvent.Frame>(a.next())
    waitForScreen(pane) { it.contains("L300") }

    // The visible screen is herdr's viewport, the same one the stream draws.
    a.session.scroll(100)
    waitForScreen(pane) { s -> s.contains("L200") && !s.contains("L300") }
    a.session.scroll(-50)
    waitForScreen(pane) { s -> s.contains("L250") && !s.contains("L200") }
    a.session.sendText("x")
    waitForScreen(pane) { it.contains("L300") }

    a.session.release()
    assertEquals(CloseReason.Detached, a.closed())
  }

  @Test
  fun agentInputLeavesViewScrolledBack() = runBlocking {
    val pane = fakeClaudePane("scroll-agent", history = "seq -f L%g 1 300")
    reportAgent(pane, "idle")
    val a = control(api.snapshot().panes.single { it.paneId == pane }.terminalId, 40, 12)
    assertIs<TerminalEvent.Frame>(a.next())
    waitForScreen(pane) { it.contains("L300") }
    a.session.scroll(100)
    waitForScreen(pane) { !it.contains("L300") }

    // Unlike typing into the stream, a prompt through the API leaves the view where it is: the app scrolls back down.
    api.promptAgent(pane, "ответ")
    delay(1_000)
    assertFalse(screen(pane).contains("ответ"))
    a.session.scroll(-100)
    waitForScreen(pane) { it.contains("ответ") }

    a.session.release()
    assertEquals(CloseReason.Detached, a.closed())
  }

  @Test
  fun pasteIsBracketedOnlyForAppsThatAsk() = runBlocking {
    // cat -v shows the escape bytes it gets: ^[[200~ would mean the brackets reached it.
    val (plain, plainTerminal) = catPane("paste-plain")
    val a = control(plainTerminal, 40, 12)
    assertIs<TerminalEvent.Frame>(a.next())
    a.session.paste("first\nsecond\n")
    waitForScreen(plain) { s -> s.lines().count { it.trim() == "second" } >= 2 }
    assertFalse(screen(plain).contains("[200~"))
    a.session.release()
    assertEquals(CloseReason.Detached, a.closed())

    val (bracketed, bracketedTerminal) = catPane("paste-bracketed", "printf '\\e[?2004h'; cat -v")
    val b = control(bracketedTerminal, 40, 12)
    assertIs<TerminalEvent.Frame>(b.next())
    b.session.paste("line\n")
    waitForScreen(bracketed) { it.contains("^[[200~line") }
    b.session.release()
    assertEquals(CloseReason.Detached, b.closed())
  }

  @Test
  fun secondControllerNeedsTakeover() = runBlocking {
    val (_, terminal) = catPane("takeover")
    val a = control(terminal, 30, 10)
    assertIs<TerminalEvent.Frame>(a.next())

    val b = control(terminal, 30, 10)
    assertEquals(CloseReason.NeedsTakeover, b.closed())

    val c = control(terminal, 30, 10, takeover = true)
    assertIs<TerminalEvent.Frame>(c.next())
    assertEquals(CloseReason.TakenOver, a.closed())
    c.session.release()
    assertEquals(CloseReason.Detached, c.closed())
  }

  @Test
  fun closingPaneEndsControlStream() = runBlocking {
    val (pane, terminal) = catPane("close")
    val a = control(terminal, 30, 10)
    assertIs<TerminalEvent.Frame>(a.next())
    server.raw("pane.close", buildJsonObject { put("pane_id", pane) })
    val reason = a.closed()
    assertTrue(reason == CloseReason.NotFound || reason == CloseReason.Ended, "unexpected close reason $reason")
  }
}
