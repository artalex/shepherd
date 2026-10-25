package dev.artalex.herdr.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import java.util.concurrent.CopyOnWriteArrayList
import org.connectbot.terminal.TerminalEmulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Web links on a pane's screen open with a tap, on the agent screen and in the terminal: termlib finds them in the
 * text, and herdr passes on links set with OSC 8.
 * The browser never starts: [OpenedLinks] catches the address.
 */
@OptIn(ExperimentalTestApi::class)
class LinksE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var server: Server
  private lateinit var shell: String
  private val opened = OpenedLinks()
  private val run = System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e)
    shell = fixture.workspace("e2e-links-$run")
    server = fixture.connectApp(ApplicationProvider.getApplicationContext<HerdrApp>().container)
  }

  @After
  fun cleanUp() {
    opened.close()
    if (::fixture.isInitialized) fixture.close()
  }

  private fun openPane(paneId: String) {
    compose.scrollListTo(paneTag(paneId))
    compose.onNodeWithTag(paneTag(paneId)).performClick()
    compose.waitUntilExactlyOneExists(hasTestTag("quickKeys"), 10_000)
  }

  /** The pane's width in columns, as `stty size` reports it; the screen is cleared first. */
  private fun paneCols(paneId: String): Int {
    fixture.type(paneId, "clear; stty size")
    fixture.waitForScreen(paneId, pump = { compose.waitForIdle() }) { fixture.sttySize(paneId) != null }
    return checkNotNull(fixture.sttySize(paneId)).second
  }

  /**
   * Runs [command] after clearing the screen, and waits until the app has drawn what it printed; returns the pane's
   * visible text. The marker printed last is computed, so the command line itself never matches.
   */
  private fun runOnScreen(paneId: String, command: String): List<String> {
    fixture.type(paneId, "clear; $command; echo end-\$((6*7))")
    val screen = fixture.waitForScreen(paneId, pump = { compose.waitForIdle() }) { s -> s.lines().any { it.trim() == "end-42" } }
    Thread.sleep(500) // frames arrive as they are drawn
    compose.waitForIdle()
    return screen.lines()
  }

  /**
   * Prints, from the top of the screen: a link over four rows, a host without a scheme, a link set with OSC 8 and one
   * set to open an app rather than a page, both with text after them on their rows, and plain words. Returns the
   * rows each of them starts on.
   */
  private fun printLinks(paneId: String, long: String): Rows {
    val osc8 = """\033]8;;%s\033\\%s\033]8;;\033\\ after\n\n"""
    val lines = runOnScreen(paneId, """printf '%s\n\n%s\n\n$osc8$osc8%s\n' '$long' '$BARE_LINK' '$WEB_LINK' osc8-web '$APP_LINK' osc8-app 'plain words'""")
    fun row(predicate: (String) -> Boolean) = lines.indexOfFirst(predicate).also { check(it >= 0) { "missing from the screen:\n${lines.joinToString("\n")}" } }
    return Rows(
      long = row { it.startsWith(long.take(20)) },
      bare = row { it.trimEnd() == BARE_LINK },
      web = row { it.startsWith("osc8-web") },
      app = row { it.startsWith("osc8-app") },
      plain = row { it.trimEnd() == "plain words" },
    )
  }

  private data class Rows(val long: Int, val bare: Int, val web: Int, val app: Int, val plain: Int)

  /**
   * Waits until the app's terminal of [paneId] has found a link starting with [start]. Frames reach the screen a while
   * after herdr draws them, and termlib finds the links of each new one on its own thread.
   */
  private fun awaitLink(paneId: String, start: String) {
    lateinit var emulator: TerminalEmulator
    InstrumentationRegistry.getInstrumentation().runOnMainSync { emulator = server.streams.of(paneId).emulator }
    compose.waitUntil(10_000) { emulator.getUrls().any { it.url.startsWith(start) } }
    compose.waitForIdle()
  }

  /**
   * Taps column [col] of [row] in the view [tag] of [paneId], a grid [cols] wide that fills the view with the rows the
   * pane has now.
   */
  private fun tap(tag: String, paneId: String, cols: Int, row: Int, col: Int = 2) {
    val rows = fixture.viewportRows(paneId)
    compose.onNodeWithTag(tag).performTouchInput { click(Offset((col + 0.5f) * width / cols, (row + 0.5f) * height / rows)) }
    compose.waitForIdle()
  }

  private fun assertOpens(expected: String, tap: () -> Unit) {
    val before = opened.all.size
    tap()
    compose.waitUntil(5_000) { opened.all.size > before }
    assertEquals(expected, opened.all.last())
  }

  @Test
  fun linksOpenFromTheAgentScreenAndTheTerminal() {
    launchApp(compose, "linksOpenFromTheAgentScreenAndTheTerminal") { scenario ->
      openPane(shell)
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      var cols = paneCols(shell)
      var long = longLink(cols, "agent")
      val rows = printLinks(shell, long)
      awaitLink(shell, WEB_LINK)
      Screens.take(compose, "04g-agent-links")

      // A link wrapped over several rows opens whole from any of them.
      assertOpens(long) { tap("liveScreen", shell, cols, rows.long + 2) }
      assertOpens("http://$BARE_LINK") { tap("liveScreen", shell, cols, rows.bare) }
      assertOpens(WEB_LINK) { tap("liveScreen", shell, cols, rows.web) }
      // A link to anything but a web page doesn't open, and the screen says why.
      tap("liveScreen", shell, cols, rows.app)
      compose.waitUntilExactlyOneExists(hasText(text(R.string.link_not_web, APP_LINK)), 5_000)
      tap("liveScreen", shell, cols, rows.plain)
      assertEquals("only the web links open", 3, opened.all.size)

      compose.onNodeWithTag("openTerminal").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("extraKeys"), 10_000)
      compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("terminalOverlay")).fetchSemanticsNodes().isEmpty() }
      // Up later by itself, the keyboard would redraw the pane under the tap.
      compose.raiseTerminalKeyboard(scenario, fixture, server, shell)
      cols = paneCols(shell)
      long = longLink(cols, "terminal")
      runOnScreen(shell, "printf '%s\\n' '$long'")
      awaitLink(shell, long.take(30))
      assertOpens(long) { tap("terminal", shell, cols, row = 1) }
      Screens.take(compose, "07b-terminal-links")
    }
  }
}

/** Catches the web pages the app opens: the browser never starts, and the test gets the address. Close it when done. */
class OpenedLinks : AutoCloseable {
  private val links = CopyOnWriteArrayList<String>()
  private val monitor =
    object : Instrumentation.ActivityMonitor() {
      override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
        if (intent.action != Intent.ACTION_VIEW || intent.scheme !in setOf("http", "https")) return null
        links += intent.dataString.orEmpty()
        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
      }
    }

  init {
    InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
  }

  /** The addresses opened so far, oldest first. */
  val all: List<String>
    get() = links.toList()

  override fun close() = InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
}

/** A link over four rows [cols] wide, its path starting with [name]: termlib joins a link over six rows at most. */
private fun longLink(cols: Int, name: String) = "https://example.com/$name/" + "x".repeat(3 * cols - 13 - name.length) + "?q=1&end=1"

private const val BARE_LINK = "example.org/shepherd"
private const val WEB_LINK = "https://example.com/osc8"
private const val APP_LINK = "intent://shepherd-test"
