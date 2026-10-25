package dev.artalex.herdr

import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.model.SessionSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What waits for a pane's prompt: until its screen takes it, and no longer than the pane lives. */
class PromptInboxTest {
  private val inbox = PromptInbox()

  @Test
  fun partsWaitForTheScreenEachTakenOnce() {
    inbox.put(PANE, PromptPart.Text("посмотри"))
    inbox.put(PANE, PromptPart.Path("/home/user/shot.png"))
    assertEquals(PromptPart.Text("посмотри"), next())
    assertEquals(PromptPart.Path("/home/user/shot.png"), next())
    assertNull(next())
  }

  @Test
  fun aPaneNotInASnapshotYetKeepsItsParts() {
    // An agent just started from the list gets its share before a snapshot has its pane.
    inbox.put(PANE, PromptPart.Text("посмотри"))
    inbox.seen(snapshot())
    assertEquals(PromptPart.Text("посмотри"), next())
  }

  @Test
  fun aClosedPaneTakesItsPartsAlong() {
    inbox.put(PANE, PromptPart.Text("для закрытой"))
    inbox.seen(snapshot(PANE))
    inbox.seen(snapshot())
    // Its id comes back as a new pane: only what came for that one goes to it.
    inbox.put(PANE, PromptPart.Text("для новой"))
    inbox.seen(snapshot(PANE))
    assertEquals(PromptPart.Text("для новой"), next())
    assertNull(next())
  }

  private fun next(): PromptPart? = runBlocking { withTimeoutOrNull(200) { inbox.of(PANE).first() } }

  private fun snapshot(vararg panes: String) = SessionSnapshot("0.9.5", 22, panes = panes.map { PaneInfo(it, "t-$it", "w1", "w1:t1") })

  private companion object {
    const val PANE = "w1:p1"
  }
}
