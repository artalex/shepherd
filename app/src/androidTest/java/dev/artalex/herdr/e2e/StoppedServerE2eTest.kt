package dev.artalex.herdr.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A herdr server that stops and comes back, as after `herdr server stop` on the host. The list says the server isn't
 * running, whether it stopped before the app connected or under a live connection, and shows the session again once
 * the server is back; a prompt sent meanwhile stays in its field, and terminal input says it went nowhere. The tests stop
 * a session of their own: the other tests' servers stay up.
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
        InstrumentationRegistry.getInstrumentation().runOnMainSync { server.streams.of(pane).emulator.dispatchCharacter(0, 'a'.code) }
        compose.waitUntilExactlyOneExists(hasText(text(R.string.terminal_input_lost)), 10_000)
      } finally {
        fixture.startServer()
      }
    }
  }
}
