package dev.artalex.herdr.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import dev.artalex.herdr.core.model.AgentKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Starting an agent from the agent list against real herdr, in a session without panes: the + by the server's name,
 * a folder the host doesn't have, the agent in a workspace of its own, and the next one in a tab next to it, in the
 * folder the app remembered. agent.start runs the fake `claude` that scripts/e2e.sh puts first on the e2e servers'
 * PATH. The session is the stopped server test's, emptied first: that test needs only the workspace it makes itself.
 */
@OptIn(ExperimentalTestApi::class)
class NewAgentE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var folder: String
  private val container get() = ApplicationProvider.getApplicationContext<HerdrApp>().container

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e, e2e.sessionToStop)
    fixture.closeWorkspaces()
    // A folder of the test's own, as the host resolves it.
    folder = fixture.exec("mkdir -p ~/.cache/herdr-android-e2e && cd \"$(mktemp -d ~/.cache/herdr-android-e2e/agent-XXXXX)\" && pwd -P").trim()
    fixture.connectApp(container)
  }

  @After
  fun cleanUp() {
    if (!::fixture.isInitialized) return
    fixture.closeWorkspaces()
    if (::folder.isInitialized && folder.startsWith("/")) fixture.exec("rm -rf -- '$folder'")
    fixture.close()
  }

  private fun JsonObject.field(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

  /** Opens the dialog that starts an agent on the e2e server, once it has looked up the machine's agents. */
  private fun openNewAgent() {
    compose.waitUntilExactlyOneExists(hasTestTag("newAgent-${HostFixture.SERVER_ID}"), 30_000)
    compose.onNodeWithTag("newAgent-${HostFixture.SERVER_ID}").performClick()
    compose.waitUntilExactlyOneExists(hasTestTag("newAgentKind-claude"), 10_000)
  }

  /** Types and sends a prompt; a just-started agent takes a few seconds before the app lets it through. */
  private fun sendPrompt(text: String) {
    compose.waitUntilExactlyOneExists(hasTestTag("prompt"), 15_000)
    compose.onNodeWithTag("prompt").performTextInput(text)
    compose.waitUntilExactlyOneExists(hasTestTag("sendPrompt") and isEnabled(), 20_000)
    compose.onNodeWithTag("sendPrompt").performClick()
  }

  @Test
  fun agentStartsFromTheListInASessionWithoutPanes() {
    launchApp(compose, "agentStartsFromTheListInASessionWithoutPanes") {
      // No pane to start an agent from; the + by the server's name starts one all the same.
      compose.waitUntilExactlyOneExists(hasText(text(R.string.agents_empty)), 30_000)
      Screens.take(compose, "17-session-empty")
      openNewAgent()
      // herdr would open a folder the host doesn't have as the home folder: the app looks the folder up first.
      compose.onNodeWithTag("newAgentFolder").performTextReplacement("$folder/нет")
      compose.onNodeWithTag("startNewAgent").performClick()
      compose.waitUntilExactlyOneExists(hasText(text(R.string.new_agent_folder_missing)), 10_000)
      Screens.take(compose, "17a-new-agent-no-folder")
      assertTrue(fixture.panes().isEmpty())
      compose.onNodeWithTag("newAgentFolder").performTextReplacement(folder)
      Screens.take(compose, "17b-new-agent")
      compose.onNodeWithTag("startNewAgent").performClick()
      val first = fixture.waitForPanes(pump = { compose.waitForIdle() }) { panes -> panes.any { it.field("agent") == "claude" } }.single()
      assertEquals(folder, first.field("cwd"))
      // The app shows the new agent: a prompt from it lands there.
      sendPrompt("агент из списка")
      fixture.waitForScreen(checkNotNull(first.field("pane_id")), pump = { compose.waitForIdle() }) { it.contains("агент из списка") }

      // The next agent from the list is offered the same folder, and gets a tab next to the first one.
      compose.onNodeWithContentDescription(text(R.string.agent_back)).performClick()
      openNewAgent()
      compose.onNodeWithTag("newAgentFolder").assert(hasText(folder))
      compose.onNodeWithTag("startNewAgent").performClick()
      val panes = fixture.waitForPanes(pump = { compose.waitForIdle() }) { panes -> panes.size == 2 && panes.all { it.field("agent") == "claude" } }
      val second = panes.single { it.field("pane_id") != first.field("pane_id") }
      assertEquals(first.field("workspace_id"), second.field("workspace_id"))
      assertNotEquals("the second agent should get its own tab", first.field("tab_id"), second.field("tab_id"))
      sendPrompt("второй агент")
      fixture.waitForScreen(checkNotNull(second.field("pane_id")), pump = { compose.waitForIdle() }) { it.contains("второй агент") }
    }
  }

  @Test
  fun agentThePaneCantRunIsExplained() {
    // Kimi, which the host hasn't: the pane's shell says so, and no real agent starts.
    val pane = fixture.workspace("e2e-missing")
    val server = checkNotNull(container.servers[HostFixture.SERVER_ID])
    launchApp(compose, "agentThePaneCantRunIsExplained") {
      compose.scrollListTo(paneTag(pane))
      compose.onNodeWithTag(paneTag(pane)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("quickKeys"), 10_000)
      // As when the app starts it: herdr types the program into the pane's shell, and the app watches the start.
      fixture.raw("agent.start", buildJsonObject {
        put("name", "kimi-e2e")
        put("kind", AgentKind.KIMI.kind)
        put("pane_id", pane)
      })
      server.agentStarts.started(pane, AgentKind.KIMI, emptyList())
      compose.waitUntilExactlyOneExists(hasText(text(R.string.agent_missing_title)), 20_000)
      Screens.take(compose, "17-agent-missing")
      compose.onNodeWithTag("copyLoginShells").performClick()
      compose.waitUntilExactlyOneExists(hasText(text(R.string.agent_missing_copied)), 5_000)
    }
  }
}
