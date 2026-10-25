package dev.artalex.herdr.e2e

import android.content.ClipboardManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.connectbot.terminal.R as TerminalR

/**
 * The agent screen streams its pane at the phone's size against real herdr: the PTY follows the phone, prompts
 * still reach the agent, the terminal of the pane takes over without asking, and a pane someone else controls
 * shows as a snapshot until they let go. Swipes scroll the pane on both screens, but not while text is selected.
 */
@OptIn(ExperimentalTestApi::class)
class LiveScreenE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var server: Server
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
    server = fixture.connectApp(container)
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

  /** A text cell of [paneId]'s grid in the view [tag]: termlib fills the view with the grid from its top left. */
  private fun cellSize(tag: String, paneId: String): Size {
    val (rows, cols) = server.viewGrid(paneId)
    val view = compose.onNodeWithTag(tag).fetchSemanticsNode().size
    return Size(view.width.toFloat() / cols, view.height.toFloat() / rows)
  }

  /**
   * Selects text in [tag] from [row] down by about [rows] rows with termlib's handle: a long press takes a
   * character, then the end handle under it is dragged down at a finger's pace.
   */
  private fun selectWithHandle(tag: String, paneId: String, row: Int, rows: Int) {
    val cell = cellSize(tag, paneId)
    compose.onNodeWithTag(tag).performTouchInput { longClick(Offset(1.5f * cell.width, (row + 0.5f) * cell.height)) }
    compose.waitUntilExactlyOneExists(hasText(text(TerminalR.string.terminal_selection_copy)), 5_000)
    compose.onNodeWithTag(tag).performTouchInput {
      down(Offset(1.5f * cell.width, (row + 1.4f) * cell.height))
      repeat(10) { moveBy(Offset(0f, rows * cell.height / 10)) }
      advanceEventTime(300)
      up()
    }
    compose.waitForIdle()
  }

  /** Selects words in [tag] from [row] down by [rows] rows: a double tap takes a word, and the finger moves on down. */
  private fun selectWords(tag: String, paneId: String, row: Int, rows: Int) {
    val cell = cellSize(tag, paneId)
    val word = Offset(1.5f * cell.width, (row + 0.5f) * cell.height)
    compose.onNodeWithTag(tag).performTouchInput {
      click(word)
      advanceEventTime(100)
      down(word)
      repeat(10) { moveBy(Offset(0f, rows * cell.height / 10)) }
      advanceEventTime(300)
      up()
    }
    compose.waitForIdle()
  }

  /** Copies the selection with termlib's button; returns the lines it put on the clipboard. */
  private fun copySelection(): List<String> {
    val copy = text(TerminalR.string.terminal_selection_copy)
    compose.waitUntilExactlyOneExists(hasText(copy), 5_000)
    compose.onNodeWithText(copy).performClick()
    compose.waitForIdle()
    var copied = ""
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val clipboard = ApplicationProvider.getApplicationContext<HerdrApp>().getSystemService(ClipboardManager::class.java)
      copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    }
    return copied.lines()
  }

  /** [copied] is the text of [screen] from [row] down: the rows in between whole, the first and last in part. */
  private fun assertCopiedRows(screen: String, row: Int, copied: List<String>) {
    val rows = screen.lines().map { it.trim() }
    val last = row + copied.size - 1
    assertTrue("several rows should be copied: $copied", copied.size >= 3)
    assertTrue("the first row from the selected character: $copied, on the screen ${rows[row]}", rows[row].endsWith(copied.first()))
    assertEquals("the rows in between", rows.subList(row + 1, last), copied.subList(1, copied.size - 1))
    assertTrue("the last row up to the selection's end: $copied, on the screen ${rows[last]}", rows[last].startsWith(copied.last()))
  }

  /** The pane shows [screen] still: a swipe that scrolled it would have reached herdr by now. */
  private fun assertNotScrolled(paneId: String, screen: String) {
    Thread.sleep(1_000)
    assertEquals("the pane should stay where it was", screen, fixture.screen(paneId))
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

  /**
   * Prints a faint word and then blocks in bold and two RGB colours, as Codex's dialogs end a hint with its key, and a
   * row of blocks with every attribute in those colours: herdr writes that style as 17 parameters itself.
   */
  private fun printLongStyles(paneId: String) =
    fixture.type(
      paneId,
      "clear; printf '\\033[2;48;2;72;39;62m continue \\033[0;1;38;2;255;160;255;48;2;72;39;62m%s\\033[0m\\n" +
        "\\033[1;2;3;4;5;9;38;2;255;160;255;48;2;72;39;62m%s\\033[0m\\n' $FAINT_BLOCKS $FAINT_BLOCKS",
    )

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
  fun selectionGoesOverRowsWithoutScrolling() {
    launchApp(compose, "selectionGoesOverRowsWithoutScrolling") { scenario ->
      openPane(shell)
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      compose.awaitPaneFitsView(fixture, server, shell)
      fixture.type(shell, "seq -f L%g 1 300")
      val agentScreen = fixture.waitForScreen(shell, pump = { compose.waitForIdle() }) { it.contains("L300") }
      Thread.sleep(500) // frames arrive as they are drawn
      compose.waitForIdle()

      // Vertical drags while selecting are termlib's, though they look like the swipe that scrolls: a finger moving
      // on after a double tap, and a handle dragged down.
      selectWords("liveScreen", shell, row = 12, rows = 4)
      assertNotScrolled(shell, agentScreen)
      Screens.take(compose, "04h-agent-selected")
      assertCopiedRows(agentScreen, 12, copySelection())
      selectWithHandle("liveScreen", shell, row = 3, rows = 5)
      assertNotScrolled(shell, agentScreen)
      assertCopiedRows(agentScreen, 3, copySelection())

      compose.onNodeWithTag("openTerminal").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("extraKeys"), 10_000)
      compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("terminalOverlay")).fetchSemanticsNodes().isEmpty() }
      compose.raiseTerminalKeyboard(scenario, fixture, server, shell)
      fixture.type(shell, "clear; seq -f T%g 1 300")
      val terminalScreen = fixture.waitForScreen(shell, pump = { compose.waitForIdle() }) { it.contains("T300") }
      Thread.sleep(500)
      compose.waitForIdle()
      selectWithHandle("terminal", shell, row = 3, rows = 5)
      assertNotScrolled(shell, terminalScreen)
      assertCopiedRows(terminalScreen, 3, copySelection())
      // Copying ends the selection, and swipes scroll again.
      swipe("terminal", 0.5f)
      fixture.waitForScreen(shell, pump = { compose.waitForIdle() }) { !it.contains("T300") }
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
  fun stylesTooLongForLibvtermAreDrawn() {
    launchApp(compose, "stylesTooLongForLibvtermAreDrawn") {
      openPane(shell)
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      // An SGR of over 16 parameters overwrote libvterm's memory, and the app died in native code.
      printLongStyles(shell)
      compose.waitUntil(10_000) { pixelsOf("liveScreen", PINK) > 2_000 && pixelsOf("liveScreen", DIM_PINK) > 2_000 }
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

/** The colour of [LiveScreenE2eTest]'s long styles, and its faint version at half brightness. */
private const val PINK = 0xFFA0FF
private const val DIM_PINK = 0x7F507F
