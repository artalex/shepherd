package dev.artalex.herdr.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Managing panes from the agent screen against real herdr: switching panes, closing one, starting Claude
 * in a shell or in a new tab next to an agent, and leaving the screens of a pane that is gone. The menu lists
 * Claude because the host has it installed; agent.start then runs the fake `claude` that scripts/e2e.sh puts
 * first on the e2e server's PATH.
 */
@OptIn(ExperimentalTestApi::class)
class PanesE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var first: String
  private lateinit var second: String
  private lateinit var shell: String
  private val run = System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e)
    first = fixture.workspace("e2e-first-$run")
    fixture.fakeClaude(first, "idle")
    second = fixture.workspace("e2e-second-$run")
    fixture.fakeClaude(second, "idle")
    shell = fixture.split(second)

    val container = ApplicationProvider.getApplicationContext<HerdrApp>().container
    fixture.connectApp(container)
  }

  @After
  fun closeFixture() {
    if (::fixture.isInitialized) fixture.close()
  }

  private fun openPane(paneId: String) {
    compose.scrollListTo(paneTag(paneId))
    compose.onNodeWithTag(paneTag(paneId)).performClick()
    compose.waitUntilExactlyOneExists(hasTestTag("quickKeys"), 10_000)
  }

  private fun openMenu(item: String) {
    compose.onNodeWithTag("paneMenu").performClick()
    compose.waitUntilExactlyOneExists(hasTestTag(item), 5_000)
  }

  /** Types and sends a prompt; a just-started agent takes a few seconds before the app lets it through. */
  private fun sendPrompt(text: String) {
    compose.waitUntilExactlyOneExists(hasTestTag("prompt"), 15_000)
    compose.onNodeWithTag("prompt").performTextInput(text)
    compose.waitUntilExactlyOneExists(hasTestTag("sendPrompt") and isEnabled(), 20_000)
    compose.onNodeWithTag("sendPrompt").performClick()
  }

  private fun waitForList() = compose.waitUntilExactlyOneExists(hasTestTag("board"), 15_000)

  private fun JsonObject.field(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

  @Test
  fun switcherOpensAnotherPaneInPlace() {
    launchApp(compose, "switcherOpensAnotherPaneInPlace") {
      openPane(first)
      compose.onNodeWithTag("paneSwitcher").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag(switchTag(second)), 10_000)
      Screens.take(compose, "09-pane-switcher")
      // Earlier tests leave their workspaces in the e2e session, so the menu scrolls.
      compose.onNodeWithTag(switchTag(second)).performScrollTo().performClick()
      // The screen drives the other pane now.
      sendPrompt("через переключатель")
      fixture.waitForScreen(second, pump = { compose.waitForIdle() }) { it.contains("через переключатель") }
      // Switching replaced the screen: Back leads to the list, not to the first pane.
      compose.onNodeWithContentDescription(text(R.string.agent_back)).performClick()
      waitForList()
    }
  }

  @Test
  fun closingAfterConfirmationReturnsToList() {
    launchApp(compose, "closingAfterConfirmationReturnsToList") {
      openPane(shell)
      openMenu("closePane")
      Screens.take(compose, "10-pane-menu")
      compose.onNodeWithTag("closePane").performClick()
      compose.waitUntilExactlyOneExists(hasText(textAfterArg(R.string.pane_close_text), substring = true), 5_000)
      Screens.take(compose, "11-close-pane")
      compose.onNodeWithTag("confirmClose").performClick()
      waitForList()
      fixture.waitForPanes { panes -> panes.none { it.field("pane_id") == shell } }
    }
  }

  @Test
  fun claudeStartsInShellPane() {
    launchApp(compose, "claudeStartsInShellPane") {
      openPane(shell)
      openMenu("startAgent-claude")
      compose.onNodeWithTag("startAgent-claude").performClick()
      fixture.waitForPanes(pump = { compose.waitForIdle() }) { panes -> panes.any { it.field("pane_id") == shell && it.field("agent") == "claude" } }
      sendPrompt("claude в шелле")
      fixture.waitForScreen(shell, pump = { compose.waitForIdle() }) { it.contains("claude в шелле") }
    }
  }

  @Test
  fun newSessionOpensInItsOwnTab() {
    launchApp(compose, "newSessionOpensInItsOwnTab") {
      val before = fixture.panes()
      val source = before.single { it.field("pane_id") == first }
      openPane(first)
      openMenu("newSession-claude")
      compose.onNodeWithTag("newSession-claude").performClick()
      val created =
        fixture
          .waitForPanes(pump = { compose.waitForIdle() }) { panes ->
            panes.any { p -> before.none { it.field("pane_id") == p.field("pane_id") } && p.field("agent") == "claude" }
          }
          .single { p -> before.none { it.field("pane_id") == p.field("pane_id") } }
      assertEquals(source.field("workspace_id"), created.field("workspace_id"))
      assertNotEquals("the new session should get its own tab", source.field("tab_id"), created.field("tab_id"))
      assertEquals(source.field("foreground_cwd") ?: source.field("cwd"), created.field("cwd"))
      // The app shows the new pane: a prompt from it lands there.
      sendPrompt("новая сессия")
      fixture.waitForScreen(checkNotNull(created.field("pane_id")), pump = { compose.waitForIdle() }) { it.contains("новая сессия") }
    }
  }

  @Test
  fun agentScreenLeavesWhenPaneClosesElsewhere() {
    launchApp(compose, "agentScreenLeavesWhenPaneClosesElsewhere") {
      openPane(shell)
      fixture.closePane(shell)
      waitForList()
    }
  }

  @Test
  fun exitInTerminalReturnsToList() {
    launchApp(compose, "exitInTerminalReturnsToList") {
      openPane(shell)
      compose.onNodeWithTag("openTerminal").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("extraKeys"), 10_000)
      compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("terminalOverlay")).fetchSemanticsNodes().isEmpty() }
      fixture.type(shell, "exit")
      waitForList()
    }
  }
}
