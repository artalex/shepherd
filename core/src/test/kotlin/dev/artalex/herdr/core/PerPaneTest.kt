package dev.artalex.herdr.core

import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.store.PerPane
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class PerPaneTest {
  private val kept = PerPane { Any() }

  private fun snapshot(vararg panes: String) =
    SessionSnapshot(version = "0.9.5", protocol = 22, panes = panes.map { PaneInfo(it, "t-$it", "w1", "w1:t1") })

  @Test
  fun aPaneKeepsWhatWasMadeForIt() {
    val first = kept.of("w1:p1")
    kept.seen(snapshot("w1:p1"))
    assertSame(first, kept.of("w1:p1"))
    assertNotSame(first, kept.of("w1:p2"))
  }

  @Test
  fun aClosedPaneTakesItsThingAlong() {
    val closed = kept.of("w1:p1")
    val open = kept.of("w1:p2")
    kept.seen(snapshot("w1:p1", "w1:p2"))
    kept.seen(snapshot("w1:p2"))
    // The id may come back as a new pane, which starts afresh.
    assertNotSame(closed, kept.of("w1:p1"))
    assertSame(open, kept.of("w1:p2"))
  }

  @Test
  fun aPaneOfTheLastSnapshotCountsAsSeen() {
    // Snapshots come as the session changes: after this one, the next may be the one without the pane.
    kept.seen(snapshot("w1:p1", "w1:p2"))
    val made = kept.of("w1:p2")
    kept.seen(snapshot("w1:p1"))
    assertNotSame(made, kept.of("w1:p2"))
  }

  @Test
  fun aPaneNotInASnapshotYetIsNotClosed() {
    // Just started: herdr's next snapshot may not have it yet.
    val started = kept.of("w1:p3")
    kept.seen(snapshot("w1:p1"))
    assertSame(started, kept.of("w1:p3"))
    kept.seen(snapshot("w1:p1", "w1:p3"))
    kept.seen(snapshot("w1:p1"))
    assertNotSame(started, kept.of("w1:p3"))
  }
}
