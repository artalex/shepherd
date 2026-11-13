package dev.artalex.herdr.notify

import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.AppContainer
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.core.model.AgentInfo
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.model.WorkspaceInfo
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.e2e.Device.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The agent watch with snapshots made up here rather than polled from herdr: what it takes as seen, and the
 * notifications it shows. The test's server has no host and never connects.
 */
class AgentWatchTest {
  @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant("android.permission.POST_NOTIFICATIONS")

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val container: AppContainer get() = ApplicationProvider.getApplicationContext<HerdrApp>().container
  private val manager = instrumentation.targetContext.getSystemService(NotificationManager::class.java)

  // A server of the test's own, new to the watch: no marks, no snapshot seen yet.
  private val server = "watch-test-${System.nanoTime()}"
  private var fetchedAt = System.currentTimeMillis()
  private var watchMinutes = 0

  @Before
  fun setUp() {
    // Off the screen the watch notifies; another test's activity may count as on screen for a moment.
    waitFor("app in background") { !container.servers.foreground.value }
    instrumentation.runOnMainSync {
      // No host: it never connects, and neither the watch nor the check looks at it.
      container.servers.save(ServerConfig(id = server, name = "watch-test"))
      container.notifications.setEnabled(true)
      // A test that takes the app off the screen would start the watch, for the servers of other tests.
      watchMinutes = container.notifications.watchMinutes.value
      container.notifications.setWatchMinutes(0)
    }
  }

  @After
  fun tearDown() {
    instrumentation.runOnMainSync {
      container.notifications.setWatchMinutes(watchMinutes)
      container.notifications.setEnabled(false)
      // Its marks and notifications go with it.
      container.servers.delete(server)
    }
    waitFor("the test server's notifications gone") { alert(PANE) == null && alert(OTHER) == null }
  }

  /**
   * A smoke test: it passes on the code before 0ce4794 as well. On the emulator Android lists a new notification
   * almost as soon as it's shown, before the watch looks for the stale ones; AlertsTest checks the skip itself.
   */
  @Test
  fun aStatusRightAfterTheLastOneKeepsItsNotification() {
    // A first look: what the agent does now is no news.
    look(agent(PANE, AgentStatus.WORKING, seq = 1))
    // A question and the finish after it in two snapshots in a row, as when the question was answered on the desktop
    // and the agent finished before the next look; then the next question. Each notification takes the place of the
    // one before. Android's notification service takes them in its own time: a few rounds.
    for (round in 0 until ROUNDS) {
      val seq = 2L + 2 * round
      look(agent(PANE, AgentStatus.BLOCKED, seq))
      waitForAlert("question $round", PANE, Alerts.BLOCKED_CHANNEL)
      look(agent(PANE, AgentStatus.IDLE, seq + 1, finished = seq + 1))
      waitForAlert("finish $round", PANE, Alerts.DONE_CHANNEL)
    }
  }

  @Test
  fun anAgentOfAServerOffTheListStaysNews() {
    // The app on screen, on the agent screen of PANE.
    instrumentation.runOnMainSync {
      container.servers.onStart(ProcessLifecycleOwner.get())
      container.watch.showing(Showing.OneAgent(server, PANE))
    }
    try {
      // A first look, then both agents ask: the screen shows the question of PANE alone.
      look(agent(PANE, AgentStatus.WORKING, seq = 1), agent(OTHER, AgentStatus.WORKING, seq = 1))
      look(agent(PANE, AgentStatus.BLOCKED, seq = 2), agent(OTHER, AgentStatus.BLOCKED, seq = 3))
      // Back on the list. The server isn't connected, and the list shows its name alone, not its agents.
      instrumentation.runOnMainSync { container.watch.showing(Showing.AllAgents) }
    } finally {
      instrumentation.runOnMainSync { container.servers.onStop(ProcessLifecycleOwner.get()) }
    }
    // Off the screen the next look brings the question no screen showed, and not the one the agent screen did.
    look(agent(PANE, AgentStatus.BLOCKED, seq = 2), agent(OTHER, AgentStatus.BLOCKED, seq = 3))
    waitForAlert("the other agent's question", OTHER, Alerts.BLOCKED_CHANNEL)
    assertNull("the question the agent screen showed", alert(PANE))
  }

  private fun alert(pane: String): StatusBarNotification? =
    manager.activeNotifications.firstOrNull { it.id == Alerts.ALERT_ID && it.tag == Alerts.tag(server, pane) }

  /** Waits for the notification of the agent in [pane] in [channel], and it stays: a cancel after it would take it down at once. */
  private fun waitForAlert(what: String, pane: String, channel: String) {
    waitFor(what, timeoutMs = 5_000) { alert(pane)?.notification?.channelId == channel }
    Thread.sleep(1_000)
    assertEquals(what, channel, alert(pane)?.notification?.channelId)
  }

  /** The agent in [pane], [status] after its change [seq]; [finished]: its work ended on change [finished]. */
  private fun agent(pane: String, status: AgentStatus, seq: Long, finished: Long? = null) =
    AgentInfo(pane, "t-$pane", "w1", "w1:t1", agent = "claude", agentStatus = status, stateChangeSeq = seq, completionSeq = finished)

  /** A snapshot of the server with [agents], each in a pane of its own. */
  private fun look(vararg agents: AgentInfo) {
    fetchedAt += 1_000
    val snapshot =
      SessionSnapshot(
        version = "0.9.5",
        protocol = 22,
        workspaces = listOf(WorkspaceInfo("w1", 1, label = "watch-test")),
        panes = agents.map { PaneInfo(it.paneId, it.terminalId, "w1", "w1:t1", agent = "claude", agentStatus = it.agentStatus) },
        agents = agents.toList(),
      )
    instrumentation.runOnMainSync { container.watch.seen(server, SessionState.Ready(snapshot, fetchedAt)) }
  }

  private companion object {
    const val PANE = "w1:p1"
    const val OTHER = "w1:p2"
    const val ROUNDS = 5
  }
}
