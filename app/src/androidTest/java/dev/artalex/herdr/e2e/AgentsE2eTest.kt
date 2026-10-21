package dev.artalex.herdr.e2e

import android.text.InputType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import org.junit.After
import org.junit.Assert.assertEquals
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

  /** Taps the key tagged [tag] in the row under the screen, scrolling the row to it first. */
  private fun key(tag: String) {
    compose.onNodeWithTag("quickKeys").performScrollToNode(hasTestTag(tag))
    compose.onNodeWithTag(tag).performClick()
  }

  private fun promptHolds(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

  @Test
  fun shiftGoesWithTheNextArrowOrTab() {
    fixture.type(shell, "cat -v")
    launchApp(compose, "shiftGoesWithTheNextArrowOrTab") {
      openPane(shell)
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
  fun ctrlGoesWithTheNextArrow() {
    fixture.type(shell, "cat -v")
    launchApp(compose, "ctrlGoesWithTheNextArrow") {
      openPane(shell)
      // Ctrl and an arrow move by words, with Shift as well they select them.
      key("mod-ctrl")
      key("key-left")
      key("key-enter")
      fixture.waitForScreen(shell) { it.contains("^[[1;5D") }
      key("mod-shift")
      key("mod-ctrl")
      Screens.take(compose, "08b-agent-shift-ctrl")
      key("key-right")
      key("key-enter")
      fixture.waitForScreen(shell) { it.contains("^[[1;6C") }
      // Ctrl lasts one key too: the arrow after the digit comes plain.
      key("mod-ctrl")
      key("key-2")
      key("key-right")
      key("key-enter")
      fixture.waitForScreen(shell) { screen -> screen.lines().any { it.trim() == "2^[[C" } }
    }
  }

  @Test
  fun ctrlTakesTheNextLetterTypedIntoThePrompt() {
    launchApp(compose, "ctrlTakesTheNextLetterTypedIntoThePrompt") { scenario ->
      openPane(idle)
      compose.onNodeWithTag("prompt").performClick()
      compose.waitUntil(5_000) { keyboardShown(scenario) }
      // Ctrl changes what the prompt asks of the keyboard; the keyboard stays up for the letter.
      key("mod-ctrl")
      compose.waitUntil(5_000) { keyboardShown(scenario) }
      Screens.take(compose, "08c-agent-ctrl-prompt")
      // With Ctrl on the prompt asks for no suggestions: a keyboard then sends the letter whole, as commitText, and has
      // no word in the making to put back into the prompt once the letter has gone to the agent.
      keyboard(scenario, check = { assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, it.inputType and InputType.TYPE_MASK_VARIATION) }) {
        commitText("o", 1)
      }
      fixture.waitForScreen(idle) { it.contains("^O") }
      compose.onNodeWithTag("prompt").assert(promptHolds(""))
      // Ctrl holds for one letter: suggestions are back, and the next letter is typed into the prompt.
      keyboard(scenario, check = { assertEquals(0, it.inputType and InputType.TYPE_MASK_VARIATION) }) { commitText("x", 1) }
      compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("prompt").assert(promptHolds("x")) }.isSuccess }
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
