package dev.artalex.herdr.e2e

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.MainActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The full terminal against real herdr: input (Russian too), extra keys, paste, resize on rotation, release and
 * takeover.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var pane: String
  private lateinit var terminal: String
  private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
  private val run = System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e)
    pane = fixture.workspace("e2e-term-$run")
    terminal = fixture.terminalId(pane)
    val container = ApplicationProvider.getApplicationContext<HerdrApp>().container
    fixture.connectApp(container)
  }

  @After
  fun cleanUp() {
    automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
    if (::fixture.isInitialized) {
      if (::terminal.isInitialized) fixture.releaseHeld(terminal)
      fixture.close()
    }
  }

  private fun shell(command: String) {
    automation.executeShellCommand(command).close()
    compose.idle(300)
  }

  private fun waitPane(predicate: (String) -> Boolean) = fixture.waitForScreen(pane, pump = { compose.waitForIdle() }, predicate = predicate)

  /** Types ASCII through the system input path, as the soft keyboard would. */
  private fun type(text: String) = shell("input text " + text.replace(" ", "%s"))

  private fun typeLine(text: String) {
    type(text)
    shell("input keyevent 66")
  }

  /** Types into the terminal's input view the way a soft keyboard does; see [keyboard]. */
  private fun ime(scenario: ActivityScenario<MainActivity>, action: InputConnection.() -> Unit) {
    keyboard(
      scenario,
      check = { info ->
        // Without suggestions keyboards commit text instead of composing words: termlib drops composing
        // text in this mode, so a change here needs its own test.
        assertTrue("the terminal should ask for no suggestions", (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0)
      },
      action = action,
    )
    compose.idle(300)
  }

  private fun openTerminal() {
    compose.scrollListTo(paneTag(pane))
    compose.onNodeWithTag(paneTag(pane)).performClick()
    compose.waitUntilExactlyOneExists(hasTestTag("openTerminal"), 10_000)
    compose.onNodeWithTag("openTerminal").performClick()
    compose.waitUntilExactlyOneExists(hasTestTag("extraKeys"), 10_000)
  }

  /** The first frame arrived: no overlay over the terminal any more. */
  private fun waitLive() {
    compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("terminalOverlay")).fetchSemanticsNodes().isEmpty() }
    compose.onNodeWithTag("terminal").performClick() // as a user would: tap to bring up the keyboard
  }

  @Test
  fun typingExtraKeysAndRotationReachThePane() {
    launchApp(compose, "typingExtraKeysAndRotationReachThePane") { scenario ->
      openTerminal()
      waitLive()

      typeLine("stty size")
      waitPane { fixture.sttySize(pane) != null }
      val portrait = fixture.sttySize(pane)!!
      assertNotEquals("the pane should take the phone's size", HostFixture.DESKTOP_SIZE, portrait)
      Screens.take(compose, "06-terminal")

      // Russian: a whole word, as voice input sends it, then letter by letter, as a keyboard types.
      type("echo ")
      ime(scenario) { commitText("привет ", 1) }
      ime(scenario) { "мир".forEach { commitText(it.toString(), 1) } }
      shell("input keyevent 66")
      waitPane { s -> s.lines().any { it.trim() == "привет мир" } }

      // Enter in the row, for when the keyboard is down.
      type("echo via-enter-key")
      compose.onNodeWithTag("extraKeys").performScrollToNode(hasTestTag("xkey-Enter"))
      compose.onNodeWithTag("xkey-Enter").performClick()
      waitPane { s -> s.lines().any { it.trim() == "via-enter-key" } }

      typeLine("cat -v")
      compose.onNodeWithTag("extraKeys").performScrollToNode(hasTestTag("xkey-↑"))
      compose.onNodeWithTag("xkey-↑").performClick()
      waitPane { it.contains("^[[A") || it.contains("^[OA") }
      compose.onNodeWithTag("mod-shift").performClick()
      compose.onNodeWithTag("extraKeys").performScrollToNode(hasTestTag("xkey-←"))
      compose.onNodeWithTag("xkey-←").performClick()
      waitPane { it.contains("^[[1;2D") }
      compose.onNodeWithTag("extraKeys").performScrollToNode(hasTestTag("mod-ctrl"))
      compose.onNodeWithTag("mod-ctrl").performClick()
      shell("input text c")
      waitPane { it.contains("^C") }

      // Back with the keyboard up only hides the keyboard: rotate with the whole height for the terminal.
      shell("input keyevent 4")
      automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
      // The recreated view measures itself a few seconds later; ask the shell until the PTY is wider.
      // No tap: after the activity is recreated the terminal must have the keyboard focus by itself.
      var landscape = portrait
      val deadline = System.currentTimeMillis() + 15_000
      while (landscape.second <= portrait.second && System.currentTimeMillis() < deadline) {
        compose.idle(1_000)
        typeLine("clear; stty size")
        waitPane { fixture.sttySize(pane) != null }
        landscape = fixture.sttySize(pane)!!
      }
      assertTrue("landscape should be wider than portrait: $portrait vs $landscape", landscape.second > portrait.second)
      Screens.take(compose, "07-terminal-landscape")
      compose.onNodeWithTag("terminal").performClick() // the keyboard in landscape: the terminal keeps some rows
      compose.idle(1_500)
      Screens.take(compose, "07b-terminal-landscape-keyboard")
    }
    // Leaving the screen (the activity closes) releases the pane.
    val deadline = System.currentTimeMillis() + 10_000
    while (fixture.isControlled(terminal) && System.currentTimeMillis() < deadline) Thread.sleep(300)
    assertFalse("the pane should be released after leaving the terminal", fixture.isControlled(terminal))
  }

  @Test
  fun pasteAndShiftTabReachThePane() {
    launchApp(compose, "pasteAndShiftTabReachThePane") {
      openTerminal()
      waitLive()
      typeLine("cat -v")
      compose.idle(1_000) // the keyboard is up
      Screens.take(compose, "06a-terminal-extra-keys")
      // Two lines, as copied from a console. cat didn't ask for bracketed paste, so herdr pastes them as plain text.
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        ApplicationProvider.getApplicationContext<HerdrApp>().getSystemService(ClipboardManager::class.java)
          .setPrimaryClip(ClipData.newPlainText("e2e", "first pasted\nsecond pasted\n"))
      }
      compose.onNodeWithTag("paste").performClick()
      // The terminal echoes each line and cat prints it again.
      waitPane { s -> s.lines().count { it.trim() == "second pasted" } >= 2 && !s.contains("[200~") }

      compose.onNodeWithTag("mod-shift").performClick()
      compose.onNodeWithTag("extraKeys").performScrollToNode(hasTestTag("xkey-Tab"))
      compose.onNodeWithTag("xkey-Tab").performClick()
      waitPane { it.contains("^[[Z") }
      Screens.take(compose, "06b-terminal-paste-shift-tab")
    }
  }

  /** Taps the key tagged [tag] of the extra row, scrolling the row to it first. */
  private fun xkey(tag: String) {
    compose.onNodeWithTag("extraKeys").performScrollToNode(hasTestTag(tag))
    compose.onNodeWithTag(tag).performClick()
  }

  @Test
  fun modifiersGoWithEveryKey() {
    // A program with the kitty keyboard protocol, as Claude Code: herdr tells Ctrl+Shift+C from Ctrl+C for it.
    fixture.kittyCat(pane)
    launchApp(compose, "modifiersGoWithEveryKey") { scenario ->
      openTerminal()
      waitLive()
      // Ctrl and Shift of the row with a letter of the soft keyboard: Claude Code's copy, not its interrupt, which is
      // what termlib's libvterm would make of it.
      xkey("mod-ctrl")
      xkey("mod-shift")
      type("c")
      // Ctrl with a letter a keyboard sends as text, of the Russian layout: the Latin key it shares, C.
      xkey("mod-ctrl")
      ime(scenario) { commitText("с", 1) }
      // Alt with an arrow and Ctrl with End, both of the row.
      xkey("mod-alt")
      xkey("xkey-↑")
      xkey("mod-ctrl")
      xkey("xkey-End")
      // A keyboard's own Ctrl+Shift+C, with typing on both sides of it: all in order.
      type("ab")
      shell("input keycombination KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_C")
      type("cd")
      waitPane { s -> s.lines().any { it.trim() == "^[[99:67;6u^[[99;5u^[[1;3A^[[1;5Fab^[[99:67;6ucd" } }
    }
  }

  @Test
  fun functionKeysComeWithFnAndTakeTheModifiers() {
    fixture.type(pane, "cat -v")
    launchApp(compose, "functionKeysComeWithFnAndTakeTheModifiers") {
      openTerminal()
      waitLive()
      // Fn swaps the extra keys for F1–F12, with Ctrl, Alt and Shift first.
      compose.onNodeWithTag("mod-fn").performClick()
      compose.onNodeWithTag("mod-ctrl").assertIsDisplayed()
      Screens.take(compose, "06c-terminal-function-keys")
      // Alone the terminal types the key itself, with a modifier herdr encodes it.
      xkey("xkey-F2")
      xkey("mod-alt")
      xkey("xkey-F1")
      xkey("xkey-F10")
      // Fn doesn't scroll away with the row, and brings the usual keys back.
      compose.onNodeWithTag("mod-fn").assertIsDisplayed().performClick()
      xkey("xkey-Enter")
      waitPane { s -> s.lines().any { it.trim() == "^[OQ^[[1;3P^[[21~" } }
    }
  }

  @Test
  fun busyPaneOffersTakeover() {
    fixture.holdControl(terminal)
    launchApp(compose, "busyPaneOffersTakeover") {
      openTerminal()
      compose.waitUntilExactlyOneExists(hasTestTag("takeOver"), 15_000)
      Screens.take(compose, "08-terminal-takeover")
      compose.onNodeWithTag("takeOver").performClick()
      waitLive()
      typeLine("echo taken")
      waitPane { s -> s.lines().any { it.trim() == "taken" } }
    }
  }
}
