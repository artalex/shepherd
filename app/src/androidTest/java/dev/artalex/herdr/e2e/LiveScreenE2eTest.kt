package dev.artalex.herdr.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The agent screen streams its pane at the phone's size against real herdr: the PTY follows the phone, prompts
 * still reach the agent, the terminal of the pane takes over without asking, and a pane someone else controls
 * shows as a snapshot until they let go. Swipes scroll the pane on both screens.
 */
@OptIn(ExperimentalTestApi::class)
class LiveScreenE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var shell: String
  private lateinit var agent: String
  private lateinit var held: String
  private lateinit var heldTerminal: String
  private val run = System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e)
    shell = fixture.workspace("e2e-live-$run")
    agent = fixture.workspace("e2e-live-agent-$run")
    fixture.type(agent, "seq -f L%g 1 300")
    fixture.fakeClaude(agent, "idle")
    held = fixture.workspace("e2e-held-$run")
    heldTerminal = fixture.terminalId(held)

    val container = ApplicationProvider.getApplicationContext<HerdrApp>().container
    fixture.connectApp(container)
  }

  @After
  fun closeFixture() {
    if (::fixture.isInitialized) {
      if (::heldTerminal.isInitialized) fixture.releaseHeld(heldTerminal)
      fixture.close()
    }
  }

  private fun openPane(paneId: String) {
    compose.scrollListTo(paneTag(paneId))
    compose.onNodeWithTag(paneTag(paneId)).performClick()
    compose.waitUntilExactlyOneExists(hasTestTag("quickKeys"), 10_000)
  }

  private fun back() {
    compose.waitUntilExactlyOneExists(hasContentDescription(text(R.string.agent_back)), 10_000)
    compose.onNodeWithContentDescription(text(R.string.agent_back)).performClick()
  }

  /**
   * A slow vertical swipe over [tag] by [fraction] of its height, down when positive; the finger stops before it
   * lifts, so nothing flings on.
   */
  private fun swipe(tag: String, fraction: Float) {
    compose.onNodeWithTag(tag).performTouchInput {
      down(Offset(centerX, if (fraction > 0) height * 0.2f else height * 0.8f))
      repeat(30) { moveBy(Offset(0f, height * fraction / 30)) }
      advanceEventTime(500)
      up()
    }
    compose.waitForIdle()
  }

  /** Pixels of [tag] drawn exactly in [rgb]. A run of full blocks in one colour gives thousands. */
  private fun pixelsOf(tag: String, rgb: Int): Int {
    val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
    var count = 0
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].toArgb() and 0xFFFFFF == rgb) count++
    return count
  }

  /** Prints a run of full blocks as faint text, and a prompt line as Claude writes it: typed text, then its suggestion. */
  private fun printFaint(paneId: String) =
    fixture.type(paneId, "clear; printf '\\033[2m%s\\033[0m\\n\\n❯ напиши \\033[2mтесты для парсера\\033[0m\\n' $FAINT_BLOCKS")

  /** The pane's PTY size as `stty size` reports it, rows to columns. */
  private fun paneSize(paneId: String): Pair<Int, Int> {
    fixture.type(paneId, "clear; stty size")
    fixture.waitForScreen(paneId, pump = { compose.waitForIdle() }) { fixture.sttySize(paneId) != null }
    return checkNotNull(fixture.sttySize(paneId))
  }

  /**
   * Waits until the app lets go of the pane. With a desktop attached herdr then gives the pane the desktop's size
   * back; the e2e server has none, so the pane keeps the last size.
   */
  private fun waitReleased(terminalId: String) {
    val deadline = System.currentTimeMillis() + 10_000
    while (fixture.isControlled(terminalId) && System.currentTimeMillis() < deadline) Thread.sleep(300)
    assertFalse("the app should release the pane", fixture.isControlled(terminalId))
  }

  @Test
  fun paneTakesPhoneSizeWhileShown() {
    val terminal = fixture.terminalId(shell)
    launchApp(compose, "paneTakesPhoneSizeWhileShown") {
      openPane(shell)
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      val phone = paneSize(shell)
      assertTrue("the pane should take the phone's width: $phone", phone.second < HostFixture.DESKTOP_SIZE.second)
      fixture.type(shell, "printf 'на компьютере в одну строку, на телефоне с переносами. %.0s' 1 2 3; echo; echo конец")
      fixture.waitForScreen(shell, pump = { compose.waitForIdle() }) { s -> s.lines().any { it.trim() == "конец" } }
      Thread.sleep(500) // frames arrive as they are drawn
      compose.waitForIdle()
      Screens.take(compose, "04b-agent-live")

      // The terminal takes the pane over from this screen without asking, and gives it back.
      compose.onNodeWithTag("openTerminal").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("extraKeys"), 10_000)
      compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("terminalOverlay")).fetchSemanticsNodes().isEmpty() }
      assertTrue("no takeover question for our own screen", compose.onAllNodes(hasTestTag("takeOver")).fetchSemanticsNodes().isEmpty())
      back()
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      assertTrue("back on the agent screen the pane keeps the phone's width", paneSize(shell).second < HostFixture.DESKTOP_SIZE.second)

      back()
      compose.waitUntilExactlyOneExists(hasTestTag("board"), 10_000)
      waitReleased(terminal)
    }
  }

  @Test
  fun promptReachesAgentStreamedLive() {
    launchApp(compose, "promptReachesAgentStreamedLive") {
      openPane(agent)
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      compose.waitUntilExactlyOneExists(hasTestTag("prompt"), 15_000)
      compose.onNodeWithTag("prompt").performTextInput("ответ с телефона")
      compose.waitUntilExactlyOneExists(hasTestTag("sendPrompt") and isEnabled(), 10_000)
      Screens.take(compose, "04c-agent-live-prompt")
      compose.onNodeWithTag("sendPrompt").performClick()
      fixture.waitForScreen(agent, pump = { compose.waitForIdle() }) { s -> s.lines().count { it.contains("ответ с телефона") } >= 2 }
      Thread.sleep(500) // frames arrive as they are drawn
      compose.waitForIdle()
      Screens.take(compose, "04d-agent-live-answered")
    }
  }

  @Test
  fun swipeScrollsAgentAndPromptBringsItBack() {
    launchApp(compose, "swipeScrollsAgentAndPromptBringsItBack") {
      openPane(agent)
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      fixture.waitForScreen(agent, pump = { compose.waitForIdle() }) { it.contains("L300") }
      // The visible screen is herdr's viewport, the one the stream draws.
      swipe("liveScreen", 0.5f)
      fixture.waitForScreen(agent, pump = { compose.waitForIdle() }) { s -> !s.contains("L300") && s.contains("L2") }
      Thread.sleep(500) // frames arrive as they are drawn
      compose.waitForIdle()
      Screens.take(compose, "04e-agent-scrolled")

      // The prompt goes through the API, which leaves the view where it is; the screen scrolls down first.
      compose.onNodeWithTag("prompt").performTextInput("снова внизу")
      compose.waitUntilExactlyOneExists(hasTestTag("sendPrompt") and isEnabled(), 10_000)
      compose.onNodeWithTag("sendPrompt").performClick()
      fixture.waitForScreen(agent, pump = { compose.waitForIdle() }) { it.contains("снова внизу") }
    }
  }

  @Test
  fun swipeScrollsTerminalBothWays() {
    launchApp(compose, "swipeScrollsTerminalBothWays") {
      openPane(shell)
      compose.onNodeWithTag("openTerminal").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("extraKeys"), 10_000)
      compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("terminalOverlay")).fetchSemanticsNodes().isEmpty() }
      fixture.type(shell, "seq -f L%g 1 300")
      fixture.waitForScreen(shell, pump = { compose.waitForIdle() }) { it.contains("L300") }

      swipe("terminal", 0.5f)
      fixture.waitForScreen(shell, pump = { compose.waitForIdle() }) { s -> !s.contains("L300") && s.contains("L2") }
      swipe("terminal", -0.6f)
      fixture.waitForScreen(shell, pump = { compose.waitForIdle() }) { it.contains("L300") }
    }
  }

  @Test
  fun faintTextIsDrawnDim() {
    launchApp(compose, "faintTextIsDrawnDim") {
      openPane(shell)
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      // termlib has no faint: without the app's help the blocks come out white.
      printFaint(shell)
      compose.waitUntil(10_000) { pixelsOf("liveScreen", DIM_WHITE) > 2_000 }
      Screens.take(compose, "04f-agent-faint")
    }
  }

  @Test
  fun paneControlledElsewhereShowsSnapshotUntilFree() {
    fixture.holdControl(heldTerminal)
    launchApp(compose, "paneControlledElsewhereShowsSnapshotUntilFree") {
      openPane(held)
      compose.waitUntilExactlyOneExists(hasTestTag("snapshot"), 15_000)
      // Home, End and the page keys type into a stream of the app's own, and there is none.
      compose.onNodeWithTag("quickKeys").performScrollToNode(hasTestTag("key-home"))
      compose.onNodeWithTag("key-home").assertIsNotEnabled()
      // The snapshot comes from pane.read, with the pane's own sequences rather than the stream's.
      printFaint(held)
      compose.waitUntil(10_000) { pixelsOf("snapshot", DIM_WHITE) > 2_000 }
      fixture.releaseHeld(heldTerminal)
      // The screen tries again every few seconds and takes the pane once it is free.
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 20_000)
      compose.waitUntilExactlyOneExists(hasTestTag("key-home") and isEnabled(), 10_000)
    }
  }
}

private val FAINT_BLOCKS = "█".repeat(40)

/** Faint text in the default colour: white at half brightness. */
private const val DIM_WHITE = 0x7F7F7F
