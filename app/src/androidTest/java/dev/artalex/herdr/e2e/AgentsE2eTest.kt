package dev.artalex.herdr.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The agent list and the agent screen against real herdr: fake Claude agents in fresh workspaces
 * of the e2e session, the app connected over SSH as on the phone.
 */
@OptIn(ExperimentalTestApi::class)
class AgentsE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var blocked: String
  private lateinit var idle: String
  private lateinit var shell: String
  private val run = System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e)
    blocked = fixture.workspace("e2e-blocked-$run")
    fixture.fakeClaude(blocked, "blocked")
    idle = fixture.workspace("e2e-idle-$run")
    fixture.fakeClaude(idle, "idle")
    shell = fixture.split(idle)

    val container = ApplicationProvider.getApplicationContext<HerdrApp>().container
    fixture.connectApp(container)
  }

  @After
  fun closeFixture() {
    if (::fixture.isInitialized) fixture.close()
  }

  private fun waitForPane(paneId: String) {
    compose.scrollListTo(paneTag(paneId))
    compose.waitUntilExactlyOneExists(hasTestTag(paneTag(paneId)), 10_000)
  }

  private fun openPane(paneId: String) {
    waitForPane(paneId)
    compose.onNodeWithTag(paneTag(paneId)).performClick()
    compose.waitUntilExactlyOneExists(hasTestTag("quickKeys"), 10_000)
  }

  @Test
  fun listShowsAgentsWithStatuses() {
    launchApp(compose, "listShowsAgentsWithStatuses") {
      waitForPane(blocked)
      compose.onNodeWithTag(paneTag(blocked)).assert(hasAnyDescendant(hasText(text(R.string.status_blocked))))
      waitForPane(idle)
      compose.onNodeWithTag(paneTag(idle)).assert(hasAnyDescendant(hasText(text(R.string.status_idle))))
      waitForPane(shell)
      compose.onNodeWithTag("board").performScrollToNode(hasText("e2e-blocked-$run", substring = true))
      Screens.take(compose, "03-agents")
    }
  }

  @Test
  fun quickKeyAnswersWaitingAgent() {
    launchApp(compose, "quickKeyAnswersWaitingAgent") {
      openPane(blocked)
      compose.waitUntilExactlyOneExists(hasTestTag("agentStatus") and hasText(text(R.string.status_blocked)), 10_000)
      compose.onNodeWithTag("key-2").performClick()
      compose.onNodeWithTag("key-enter").performClick()
      fixture.waitForScreen(blocked) { screen -> screen.lines().count { it.trim() == "2" } >= 1 }
      Thread.sleep(1_500) // the next screen poll brings the answer
      Screens.take(compose, "04-agent-answered")
    }
  }

  @Test
  fun promptReachesIdleAgent() {
    launchApp(compose, "promptReachesIdleAgent") {
      openPane(idle)
      compose.onNodeWithTag("prompt").performTextInput("привет из e2e")
      compose.onNodeWithTag("sendPrompt").performClick()
      fixture.waitForScreen(idle) { it.contains("привет из e2e") }
    }
  }

  @Test
  fun promptToWaitingAgentExplainsWhatToDo() {
    launchApp(compose, "promptToWaitingAgentExplainsWhatToDo") {
      openPane(blocked)
      compose.onNodeWithTag("prompt").performTextInput("не дойдёт")
      compose.onNodeWithTag("sendPrompt").performClick()
      compose.waitUntilExactlyOneExists(hasText(text(R.string.error_agent_blocked)), 10_000)
      Screens.take(compose, "05-agent-blocked-hint")
    }
  }

  @Test
  fun shiftGoesWithTheNextArrowOrTab() {
    fixture.type(shell, "cat -v")
    launchApp(compose, "shiftGoesWithTheNextArrowOrTab") {
      openPane(shell)
      fun key(tag: String) {
        compose.onNodeWithTag("quickKeys").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
      }
      // Shift and an arrow, as Codex asks for to leave a dialog.
      key("mod-shift")
      Screens.take(compose, "08-agent-shift")
      key("key-left")
      key("key-enter")
      fixture.waitForScreen(shell) { it.contains("^[[1;2D") }
      // Shift lasts one key and leaves digits alone: the arrow after the digit comes plain.
      key("mod-shift")
      key("key-1")
      key("key-left")
      key("key-enter")
      fixture.waitForScreen(shell) { screen -> screen.lines().any { it.trim() == "1^[[D" } }
    }
  }

  @Test
  fun plainPaneGetsKeysWithoutAgent() {
    launchApp(compose, "plainPaneGetsKeysWithoutAgent") {
      openPane(shell)
      compose.onNodeWithTag("key-3").performClick()
      fixture.waitForScreen(shell) { screen -> screen.lines().any { it.trimEnd().endsWith("$ 3") } }
    }
  }
}
