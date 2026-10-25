package dev.artalex.herdr.e2e

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.artalex.herdr.MainActivity
import dev.artalex.herdr.Server

/** Rows to columns of [paneId]'s view on the phone, as termlib measured it. */
fun Server.viewGrid(paneId: String): Pair<Int, Int> {
  lateinit var grid: Pair<Int, Int>
  InstrumentationRegistry.getInstrumentation().runOnMainSync {
    val stream = streams.of(paneId)
    grid = checkNotNull(stream.rows) to checkNotNull(stream.columns)
  }
  return grid
}

/** Waits until [paneId] has as many rows as its view on the phone: the other screen of the pane resizes it a moment later. */
fun ComposeTestRule.awaitPaneFitsView(fixture: HostFixture, server: Server, paneId: String) {
  val deadline = System.currentTimeMillis() + 10_000
  while (fixture.viewportRows(paneId) != server.viewGrid(paneId).first) {
    check(System.currentTimeMillis() < deadline) { "the pane never took the rows of its view" }
    waitForIdle()
    Thread.sleep(200)
  }
}

/**
 * Brings up the keyboard of the terminal screen and waits until [paneId] has the rows it leaves. termlib raises the
 * keyboard whenever it gets to it, and herdr then draws the pane shorter: rows measured and links found before that
 * no longer hold.
 */
fun ComposeTestRule.raiseTerminalKeyboard(scenario: ActivityScenario<MainActivity>, fixture: HostFixture, server: Server, paneId: String) {
  onNodeWithTag("terminal").performClick()
  waitUntil(15_000) { keyboardShown(scenario) }
  idle(1_500)
  awaitPaneFitsView(fixture, server, paneId)
}
