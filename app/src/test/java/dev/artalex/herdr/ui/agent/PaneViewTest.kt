package dev.artalex.herdr.ui.agent

import dev.artalex.herdr.core.model.AgentInfo
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.store.SessionState
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneViewTest {
  private val paneId = "w1:p1"

  private fun snapshot(status: AgentStatus, launchPending: Boolean = false) =
    SessionSnapshot(
      version = "0.9.5",
      panes = listOf(PaneInfo(paneId, "term_1", "w1", "w1:t1", agent = "claude", agentStatus = status)),
      agents = listOf(AgentInfo(paneId, "term_1", "w1", "w1:t1", agent = "claude", agentStatus = status, launchPending = launchPending)),
    )

  private fun ready(status: AgentStatus, launchPending: Boolean = false) = SessionState.Ready(snapshot(status, launchPending), fetchedAtMs = 0)

  private val failed = IOException("session.snapshot: no answer within 5000 ms")

  @Test
  fun aSnapshotGivesAFreshView() {
    val view = paneViewOf(paneId, ready(AgentStatus.WORKING, launchPending = true), known = null, connected = true)
    assertEquals(AgentStatus.WORKING, view?.pane?.agentStatus)
    assertEquals(true, view?.starting)
    assertEquals(false, view?.stale)
  }

  @Test
  fun aFailedPollLeavesThePaneAsTheSnapshotBeforeItHadIt() {
    val known = paneViewOf(paneId, ready(AgentStatus.IDLE), known = null, connected = true)
    val view = paneViewOf(paneId, SessionState.Failed(failed, last = ready(AgentStatus.BLOCKED)), known, connected = true)
    assertEquals(AgentStatus.BLOCKED, view?.pane?.agentStatus)
    assertEquals(true, view?.stale)
    // A poller that failed from the start has no snapshot of its own: the screen keeps what it knew.
    assertEquals(known?.copy(stale = true), paneViewOf(paneId, SessionState.Failed(failed, last = null), known, connected = true))
    assertNull(paneViewOf(paneId, SessionState.Failed(failed, last = null), known = null, connected = true))
  }

  @Test
  fun withoutAConnectionTheLastViewIsOutOfDate() {
    val known = paneViewOf(paneId, ready(AgentStatus.WORKING), known = null, connected = true)
    assertEquals(known?.copy(stale = true), paneViewOf(paneId, SessionState.Loading, known, connected = false))
    assertNull(paneViewOf(paneId, SessionState.Loading, known = null, connected = false))
  }

  @Test
  fun aNewPollerOnALiveConnectionLeavesTheViewAsItWas() {
    val known = paneViewOf(paneId, ready(AgentStatus.WORKING), known = null, connected = true)
    assertEquals(known, paneViewOf(paneId, SessionState.Loading, known, connected = true))
    // Back from no connection, the view stays out of date until the new connection's first snapshot.
    val lost = known?.copy(stale = true)
    assertEquals(lost, paneViewOf(paneId, SessionState.Loading, lost, connected = true))
  }

  @Test
  fun aScreenBackOnALiveConnectionNeverShowsTheStatusOutOfDate() {
    // A screen that comes back after its poller stopped sees Ready, then the new poller's Loading, then Ready again.
    val states = listOf(ready(AgentStatus.WORKING), SessionState.Loading, ready(AgentStatus.BLOCKED))
    val views = states.runningFold(null as PaneView?) { known, state -> paneViewOf(paneId, state, known, connected = true) }.drop(1)
    assertEquals(listOf(false, false, false), views.map { it?.stale })
    assertEquals(AgentStatus.BLOCKED, views.last()?.pane?.agentStatus)
  }
}
