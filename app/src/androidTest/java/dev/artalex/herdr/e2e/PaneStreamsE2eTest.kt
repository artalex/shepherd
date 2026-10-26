package dev.artalex.herdr.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import dev.artalex.herdr.core.store.lastSnapshot
import dev.artalex.herdr.ui.terminal.PaneStream
import dev.artalex.herdr.ui.terminal.TerminalPhase
import java.lang.ref.WeakReference
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The panes' streams against real herdr. A stream's emulator holds the pane's screen while a screen shows the pane, the
 * agent screen and the terminal screen sharing it, and goes a moment after the last one. The stream of a pane closed
 * in herdr leaves the app once no screen shows it, while a pane on screen keeps its own: then nothing holds it, neither
 * its screens' models nor termlib's native terminal. The prompt being written outlives the pane's screens and their
 * models, until the pane closes.
 */
@OptIn(ExperimentalTestApi::class)
class PaneStreamsE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var server: Server
  private lateinit var kept: String
  private lateinit var closed: String
  private lateinit var closedTerminal: String
  private val run = System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e)
    kept = fixture.workspace("e2e-streams-$run")
    closed = fixture.split(kept)
    closedTerminal = fixture.terminalId(closed)
    server = fixture.connectApp(ApplicationProvider.getApplicationContext<HerdrApp>().container)
  }

  @After
  fun closeFixture() {
    if (::fixture.isInitialized) {
      if (::closedTerminal.isInitialized) fixture.releaseHeld(closedTerminal)
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

  private fun waitForList() = compose.waitUntilExactlyOneExists(hasTestTag("board"), 15_000)

  /** The panes that have a stream in the app now. */
  private fun streamed(): Set<String> {
    var panes = emptySet<String>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync { panes = server.streams.paneIds }
    return panes
  }

  /** The stream of [paneId], which has one already: asking for the stream of a pane without one would make it. */
  private fun streamOf(paneId: String): PaneStream {
    lateinit var stream: PaneStream
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      check(paneId in server.streams.paneIds) { "pane $paneId has no stream" }
      stream = server.streams.of(paneId)
    }
    return stream
  }

  private fun waitLive(paneId: String) = compose.waitUntil(15_000) { streamOf(paneId).phase.value == TerminalPhase.Live }

  /** A reference to the stream of [paneId] that doesn't keep it: whether anything else does is what a test asks. */
  private fun weakStreamOf(paneId: String) = WeakReference(streamOf(paneId))

  /** What nothing holds goes. The runtime collects on request, though not always at the first one. */
  private fun collectGarbage() {
    Runtime.getRuntime().gc()
    System.runFinalization()
  }

  /** The text of the draft of [paneId]; a pane without one gets an empty one. */
  private fun draftOf(paneId: String): String {
    var text = ""
    InstrumentationRegistry.getInstrumentation().runOnMainSync { text = server.drafts.of(paneId).value.text }
    return text
  }

  @Test
  fun aPaneLeftLetsGoOfItsEmulator() {
    launchApp(compose, "aPaneLeftLetsGoOfItsEmulator") {
      openPane(kept)
      waitLive(kept)
      val emulator = WeakReference(server.emulatorOf(kept))
      // The terminal screen of the pane and back: both screens draw into the one emulator.
      compose.waitUntilExactlyOneExists(hasTestTag("openTerminal") and isEnabled(), 10_000)
      compose.onNodeWithTag("openTerminal").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("terminal"), 10_000)
      assertSame(emulator.get(), server.emulatorOf(kept))
      back()
      compose.waitUntilExactlyOneExists(hasTestTag("quickKeys"), 10_000)
      assertSame(emulator.get(), server.emulatorOf(kept))
      back()
      waitForList()
      // A moment after the pane's last screen, the emulator goes, and the old picture with it.
      compose.waitUntil(10_000) { server.emulatorOf(kept) == null }
      compose.waitUntil(10_000) {
        collectGarbage()
        emulator.get() == null
      }
      // The pane opened again gets a new one, filled by the new stream.
      openPane(kept)
      waitLive(kept)
      compose.waitUntil(10_000) { server.emulatorOf(kept) != null && streamOf(kept).drawn.value }
    }
  }

  @Test
  fun leftPaneLetsGoOfItsStreamOnceClosed() {
    launchApp(compose, "leftPaneLetsGoOfItsStreamOnceClosed") {
      openPane(closed)
      waitLive(closed)
      val stream = weakStreamOf(closed)
      back()
      waitForList()
      fixture.closePane(closed)
      compose.waitUntil(15_000) { closed !in streamed() }
      // The agent screen's model went with the screen, and the stream closed its emulator: nothing holds it any more.
      compose.waitUntil(10_000) {
        collectGarbage()
        stream.get() == null
      }
    }
  }

  @Test
  fun draftOutlivesItsScreenUntilThePaneCloses() {
    fixture.fakeClaude(closed, "idle")
    launchApp(compose, "draftOutlivesItsScreenUntilThePaneCloses") {
      openPane(closed)
      compose.waitUntilExactlyOneExists(hasTestTag("prompt"), 10_000)
      compose.onNodeWithTag("prompt").performTextInput("не отправлено")
      back()
      waitForList()
      // The screen took its model along; the pane opened again has its text back.
      openPane(closed)
      compose.waitUntilExactlyOneExists(hasTestTag("prompt"), 10_000)
      compose.onNodeWithTag("prompt").assert(promptHolds("не отправлено"))
      back()
      waitForList()
      // The list polls the session: the first snapshot without the pane takes its draft along.
      fixture.closePane(closed)
      compose.waitUntil(15_000) { draftOf(closed).isEmpty() }
    }
  }

  @Test
  fun closedPaneTakesItsStreamWithTheNextSnapshot() {
    launchApp(compose, "closedPaneTakesItsStreamWithTheNextSnapshot") {
      openPane(closed)
      waitLive(closed)
      back()
      waitForList()
      openPane(kept)
      waitLive(kept)
      // Off screen, the stream of a pane that is still there stays: the pane's screens come back to it.
      assertEquals(setOf(closed, kept), streamed())
      val keptStream = streamOf(kept)

      fixture.closePane(closed)
      // The agent screen polls the session: the first snapshot without the pane takes its stream along.
      compose.waitUntil(15_000) { closed !in streamed() }
      // The pane on screen keeps its stream, live all along.
      assertSame(keptStream, streamOf(kept))
      assertEquals(TerminalPhase.Live, keptStream.phase.value)
    }
  }

  @Test
  fun closedPaneStillOnScreenKeepsItsStreamUntilTheScreenLetsGo() {
    // Another client controls the pane: the terminal screen asks about taking it over, and stays as the pane closes.
    fixture.holdControl(closedTerminal)
    launchApp(compose, "closedPaneStillOnScreenKeepsItsStreamUntilTheScreenLetsGo") {
      openPane(closed)
      compose.waitUntilExactlyOneExists(hasTestTag("openTerminal") and isEnabled(), 10_000)
      compose.onNodeWithTag("openTerminal").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("takeOver"), 15_000)
      // The terminal screen polls no snapshots. This stands in for what polls meanwhile, as the agent screen under it
      // does for a few seconds.
      val polling = MainScope()
      polling.launch { server.sessions.state.collect {} }
      try {
        fixture.closePane(closed)
        compose.waitUntil(15_000) { server.sessions.state.value.lastSnapshot?.panes?.none { it.paneId == closed } == true }
        compose.onNodeWithTag("takeOver").assertExists()
        assertTrue("a screen still shows the pane, so its stream stays", closed in streamed())
      } finally {
        polling.cancel()
      }
      // Back from the terminal screen, the agent screen finds the pane gone and leaves too; then the stream goes.
      compose.onNodeWithText(text(R.string.cancel)).performClick()
      waitForList()
      compose.waitUntil(10_000) { closed !in streamed() }
    }
  }
}
