package dev.artalex.herdr.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import dev.artalex.herdr.ui.terminal.PaneStream
import dev.artalex.herdr.ui.terminal.TerminalPhase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A herdr server that stops and comes back, as after `herdr server stop` on the host. The list says the server isn't
 * running, whether it stopped before the app connected or under a live connection, and shows the session again once
 * the server is back; a prompt sent meanwhile stays in its field, and terminal input says it went nowhere. An open agent
 * screen says the connection is gone over its last frame, its agent's status out of date. A pane opened again while the
 * server is away shows no old picture of it. The tests stop a session of their own: the other tests' servers stay up.
 */
@OptIn(ExperimentalTestApi::class)
class StoppedServerE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var pane: String
  private val container get() = ApplicationProvider.getApplicationContext<HerdrApp>().container
  private val workspace = "e2e-stopped-" + System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e, e2e.sessionToStop)
    pane = fixture.workspace(workspace)
  }

  @After
  fun closeFixture() {
    if (::fixture.isInitialized) fixture.close()
  }

  private fun waitUntilServerNotRunning() =
    compose.waitUntilExactlyOneExists(hasTestTag("server-${HostFixture.SERVER_ID}") and hasText(text(R.string.problem_herdr_not_running), substring = true), 30_000)

  private fun waitForWorkspace() =
    compose.waitUntil(60_000) { runCatching { compose.onNodeWithTag("board").performScrollToNode(hasText(workspace, substring = true)) }.isSuccess }

  @Test
  fun stoppedServerIsReportedUntilItIsBack() {
    // Stopped before the app connects: the app reads the status of a stopped server.
    fixture.stopServer()
    fixture.connectApp(container)
    launchApp(compose, "stoppedServerIsReportedUntilItIsBack") {
      waitUntilServerNotRunning()
      Screens.take(compose, "16a-server-stopped")
      fixture.startServer()
      waitForWorkspace()
      // Stopped under a live connection: SSH stays up, the session polls fail.
      fixture.stopServer()
      waitUntilServerNotRunning()
      fixture.startServer()
      waitForWorkspace()
      Screens.take(compose, "16b-server-back")
    }
  }

  @Test
  fun filesWindowWithoutTheServerSaysNotConnected() {
    val server = fixture.connectApp(container)
    launchApp(compose, "filesWindowWithoutTheServerSaysNotConnected") {
      compose.scrollListTo(paneTag(pane))
      compose.onNodeWithTag(paneTag(pane)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("quickKeys"), 10_000)
      try {
        fixture.stopServer()
        compose.waitUntil(30_000) { server.host == null }
        // The window looks up the paths on the screen: without a connection it says so in words for people.
        compose.onNodeWithTag("files").performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.state_disconnected)), 10_000)
      } finally {
        fixture.startServer()
      }
    }
  }

  @Test
  fun promptStaysInItsFieldWhileTheServerIsGone() {
    fixture.fakeClaude(pane, "idle")
    val server = fixture.connectApp(container)
    launchApp(compose, "promptStaysInItsFieldWhileTheServerIsGone") {
      compose.scrollListTo(paneTag(pane))
      compose.onNodeWithTag(paneTag(pane)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("prompt"), 10_000)
      compose.onNodeWithTag("prompt").performTextInput("дождётся сервера")
      try {
        fixture.stopServer()
        // Once the connection sees the server gone, a prompt has nowhere to go.
        compose.waitUntil(30_000) { server.host == null }
        compose.onNodeWithTag("sendPrompt").performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.state_disconnected)), 10_000)
        compose.onNodeWithTag("prompt").assert(promptHolds("дождётся сервера"))
      } finally {
        // The next test's workspace needs the server.
        fixture.startServer()
      }
    }
  }

  @Test
  fun terminalInputWithoutALiveStreamIsReported() {
    val server = fixture.connectApp(container)
    launchApp(compose, "terminalInputWithoutALiveStreamIsReported") {
      compose.scrollListTo(paneTag(pane))
      compose.onNodeWithTag(paneTag(pane)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("openTerminal") and isEnabled(), 10_000)
      compose.onNodeWithTag("openTerminal").performClick()
      // Live: the extra keys work.
      compose.waitUntilExactlyOneExists(hasTestTag("xkey-Esc") and isEnabled(), 20_000)
      try {
        fixture.stopServer()
        compose.waitUntilExactlyOneExists(hasTestTag("terminalOverlay"), 30_000)
        compose.onNodeWithTag("xkey-Esc").assertIsNotEnabled()
        compose.onNodeWithTag("paste").assertIsNotEnabled()
        // A key from the soft keyboard, as termlib passes it on: it has nowhere to go, and the screen says so.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { checkNotNull(server.streams.of(pane).emulator.value).dispatchCharacter(0, 'a'.code) }
        compose.waitUntilExactlyOneExists(hasText(text(R.string.terminal_input_lost)), 10_000)
      } finally {
        fixture.startServer()
      }
    }
  }

  /**
   * The herdr server stops under an open agent screen: the screen says why over the pane's last frame, and the agent's
   * status shows as out of date, where both passed for live. Once the server is back, the screen is live again and the
   * status fresh.
   */
  @Test
  fun lostConnectionIsShownOnTheAgentScreen() {
    fixture.fakeClaude(pane, "working")
    val server = fixture.connectApp(container)
    launchApp(compose, "lostConnectionIsShownOnTheAgentScreen") {
      compose.scrollListTo(paneTag(pane))
      compose.onNodeWithTag(paneTag(pane)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      // A frame of the pane on the phone: the picture that would pass for the pane as it is.
      val frame = "frame-" + System.currentTimeMillis().toString().takeLast(5)
      fixture.raw("pane.send_input", buildJsonObject { put("pane_id", pane); put("text", frame) })
      fixture.waitForScreen(pane, pump = { compose.waitForIdle() }) { it.contains(frame) }
      compose.waitUntil(10_000) { streamOf(server).drawn.value }
      val working = hasTestTag("agentStatus") and hasText(text(R.string.status_working))
      compose.waitUntilExactlyOneExists(working and fresh, 10_000)
      compose.onNodeWithTag("screenNotice").assertDoesNotExist()
      try {
        fixture.stopServer()
        // Why, over the whole screen: the last frame stays under it, dimmed. The status is out of date.
        compose.waitUntilExactlyOneExists(hasTestTag("screenNotice") and hasText(text(R.string.problem_herdr_not_running), substring = true), 30_000)
        assertEquals(compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot, compose.onNodeWithTag("screenNotice").fetchSemanticsNode().boundsInRoot)
        assertTrue(streamOf(server).drawn.value)
        compose.onNode(working).assert(hasStateDescription(text(R.string.status_stale)))
        Screens.take(compose, "16c-agent-connection-lost")
      } finally {
        fixture.startServer()
      }
      // Back: the screen is live again. The agent went with its server; one started anew has a fresh status.
      compose.waitUntil(60_000) { compose.onAllNodes(hasTestTag("screenNotice")).fetchSemanticsNodes().isEmpty() }
      compose.waitUntil(15_000) { streamOf(server).phase.value == TerminalPhase.Live }
      fixture.fakeClaude(pane, "idle")
      compose.waitUntilExactlyOneExists(hasTestTag("agentStatus") and hasText(text(R.string.status_idle)) and fresh, 30_000)
      Screens.take(compose, "16d-agent-connection-back")
    }
  }

  /** A status chip that isn't marked out of date. */
  private val fresh = SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)

  /** The stream of the test's pane on the phone; the streams are the main thread's. */
  private fun streamOf(server: Server): PaneStream {
    lateinit var stream: PaneStream
    InstrumentationRegistry.getInstrumentation().runOnMainSync { stream = server.streams.of(pane) }
    return stream
  }

  @Test
  fun paneComesBackWithoutItsOldPicture() {
    val server = fixture.connectApp(container)
    launchApp(compose, "paneComesBackWithoutItsOldPicture") { scenario ->
      compose.scrollListTo(paneTag(pane))
      compose.onNodeWithTag(paneTag(pane)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("quickKeys"), 10_000)
      // termlib finds the links in what it shows: one on the screen makes the phone's picture of the pane readable.
      val old = "https://old-" + System.currentTimeMillis().toString().takeLast(5) + ".example.com"
      fixture.type(pane, "echo $old")
      compose.waitUntil(15_000) { server.emulatorOf(pane)?.getUrls()?.any { it.url.startsWith(old) } == true }
      // Away from the app for longer than a moment: the emulator goes with the stream, the old picture with it.
      scenario.moveToState(Lifecycle.State.CREATED)
      waitFor(10_000) { server.emulatorOf(pane) == null }
      try {
        // Back with the server stopped, no frame comes: the screen waits for one rather than show the old picture.
        fixture.stopServer()
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntilExactlyOneExists(hasText(text(R.string.agent_no_screen)), 10_000)
        assertTrue(server.emulatorOf(pane)?.getUrls().orEmpty().none { it.url.startsWith(old) })
      } finally {
        fixture.startServer()
      }
    }
  }

  /** Waits for [condition] off the compose rule, which has nothing to wait for while the activity is stopped. */
  private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "still not so after $timeoutMs ms" }
      Thread.sleep(100)
    }
  }
}
