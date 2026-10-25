package dev.artalex.herdr.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import dev.artalex.herdr.connection.ConnectionState
import org.junit.After
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Several servers against real herdr: the e2e session and a second session on the same host stand for two servers.
 * The list groups agents by server, a server switched off leaves the list and lets go of its connection, the agent
 * screen reaches panes of either server and names their server, and the settings rename and delete a server.
 */
@OptIn(ExperimentalTestApi::class)
class ServersE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var first: HostFixture
  private lateinit var second: HostFixture
  private lateinit var one: String
  private lateinit var two: String
  private lateinit var firstTitle: String
  private val container get() = ApplicationProvider.getApplicationContext<HerdrApp>().container
  private val run = System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    first = HostFixture(e2e)
    second = HostFixture(e2e, e2e.session2)
    one = first.workspace("e2e-one-$run")
    first.fakeClaude(one, "blocked")
    two = second.workspace("e2e-two-$run")
    second.fakeClaude(two, "idle")
    first.connectApp(container)
    second.addServer(container, SECOND, name = "второй")
    firstTitle = "${e2e.host} · ${first.session}"
  }

  @After
  fun closeFixtures() {
    if (::first.isInitialized) first.close()
    if (::second.isInitialized) second.close()
  }

  private fun waitForPane(paneId: String, serverId: String) {
    compose.scrollListTo(paneTag(paneId, serverId))
    compose.waitUntilExactlyOneExists(hasTestTag(paneTag(paneId, serverId)), 10_000)
  }

  private fun gone(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()

  /** The server's name under the pane's title; the title's row merges it, so it is looked for in the unmerged tree. */
  private fun serverTitle() = compose.onNodeWithTag("serverTitle", useUnmergedTree = true)

  /** Nowhere on the list, not just scrolled away. */
  private fun offTheList(tag: String) = runCatching { compose.onNodeWithTag("board").performScrollToNode(hasTestTag(tag)) }.isFailure

  @Test
  fun listGroupsAgentsByServerAndSwitchesOneOff() {
    launchApp(compose, "listGroupsAgentsByServerAndSwitchesOneOff") {
      waitForPane(one, HostFixture.SERVER_ID)
      waitForPane(two, SECOND)
      compose.scrollListTo("server-$SECOND")
      compose.onNodeWithTag("server-$SECOND").assert(hasText("второй")).assertIsOn()
      Screens.take(compose, "13a-servers")

      // Off: its agents leave the list and its connection closes; the other server stays as it was.
      compose.onNodeWithTag("server-$SECOND").performClick()
      compose.waitUntil(10_000) { gone(paneTag(two, SECOND)) }
      compose.onNodeWithTag("server-$SECOND").assertIsOff().assert(hasText(text(R.string.server_off)))
      assertTrue(offTheList(paneTag(two, SECOND)))
      assertEquals(ConnectionState.Disconnected, container.servers[SECOND]!!.connection.state.value)
      assertFalse(container.servers.config(SECOND)!!.active)
      compose.scrollListTo("server-$SECOND")
      Screens.take(compose, "13b-server-off")
      waitForPane(one, HostFixture.SERVER_ID)
      // With one server on, the agent screen doesn't name it.
      compose.onNodeWithTag(paneTag(one)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("agentStatus") and hasText(text(R.string.status_blocked)), 10_000)
      assertTrue(compose.onAllNodes(hasTestTag("serverTitle"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
      compose.onNodeWithContentDescription(text(R.string.agent_back)).performClick()

      // On again: it connects and its agents come back.
      compose.scrollListTo("server-$SECOND")
      compose.onNodeWithTag("server-$SECOND").performClick()
      waitForPane(two, SECOND)
      assertTrue(container.servers.config(SECOND)!!.active)
    }
  }

  @Test
  fun agentScreenReachesBothServers() {
    launchApp(compose, "agentScreenReachesBothServers") {
      waitForPane(two, SECOND)
      compose.onNodeWithTag(paneTag(two, SECOND)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("prompt"), 15_000)
      compose.onNodeWithTag("prompt").performTextInput("второму серверу")
      compose.waitUntilExactlyOneExists(hasTestTag("sendPrompt") and isEnabled(), 10_000)
      compose.onNodeWithTag("sendPrompt").performClick()
      second.waitForScreen(two, pump = { compose.waitForIdle() }) { it.contains("второму серверу") }
      serverTitle().assertTextEquals("второй")

      // The switcher lists the panes of both servers; one of the first server opens in place.
      compose.onNodeWithTag("paneSwitcher").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag(switchTag(one)), 10_000)
      // The end of the first server's panes, the second server's name and its panes, this one ticked.
      compose.onNodeWithTag(switchTag(two, SECOND)).performScrollTo()
      Screens.take(compose, "13c-switcher-servers")
      compose.onNodeWithTag(switchTag(one)).performScrollTo().performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("agentStatus") and hasText(text(R.string.status_blocked)), 10_000)
      serverTitle().assertTextEquals(firstTitle)
      compose.onNodeWithTag("key-2").performClick()
      compose.onNodeWithTag("key-enter").performClick()
      first.waitForScreen(one, pump = { compose.waitForIdle() }) { screen -> screen.lines().any { it.trim() == "2" } }
      Thread.sleep(1_500) // the stream brings the answer to the screen
      Screens.take(compose, "13f-agent-server-title")
      compose.onNodeWithContentDescription(text(R.string.agent_back)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("board"), 10_000)
    }
  }

  @Test
  fun settingsRenameAndDeleteServer() {
    launchApp(compose, "settingsRenameAndDeleteServer") {
      waitForPane(two, SECOND)
      val host = (container.servers[SECOND]!!.connection.state.value as ConnectionState.Connected).host
      compose.onNodeWithTag("openSettings").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("serverRow-$SECOND"), 10_000)
      Screens.take(compose, "13d-settings")
      compose.onNodeWithTag("serverRow-$SECOND").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("name"), 10_000)
      Screens.take(compose, "13e-server-form")
      compose.onNodeWithTag("name").performTextReplacement("переименован")
      compose.onNodeWithTag("save").performScrollTo().performClick()

      // Saved, the app is back on the list under the new name, and a new name needs no new connection.
      compose.scrollListTo("server-$SECOND")
      compose.waitUntilExactlyOneExists(hasTestTag("server-$SECOND") and hasText("переименован"), 10_000)
      assertSame(host, (container.servers[SECOND]!!.connection.state.value as ConnectionState.Connected).host)

      val scope = container.servers[SECOND]!!.scope
      compose.onNodeWithTag("openSettings").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("serverRow-$SECOND"), 10_000)
      compose.onNodeWithTag("serverRow-$SECOND").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("deleteServer"), 10_000)
      compose.onNodeWithTag("deleteServer").performClick()
      compose.waitUntilExactlyOneExists(hasText(text(R.string.server_delete_text, "переименован")), 5_000)
      compose.onNodeWithTag("confirmDelete").performClick()
      compose.waitUntil(10_000) { gone("serverRow-$SECOND") }
      assertNull(container.servers[SECOND])
      // Its coroutines, the session's sharing among them, went with it.
      assertFalse(scope.isActive)
      compose.onNodeWithContentDescription(text(R.string.agent_back)).performClick()
      waitForPane(one, HostFixture.SERVER_ID)
      assertTrue(offTheList("server-$SECOND"))
    }
  }

  private companion object {
    const val SECOND = "e2e2"
  }
}
