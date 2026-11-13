package dev.artalex.herdr.notify

import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.core.model.AgentInfo
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.model.WorkspaceInfo
import dev.artalex.herdr.core.notify.AgentMark
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.e2e.Device.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The agent notifications as Android lists them, with no herdr and no agent watch. */
class AlertsTest {
  @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant("android.permission.POST_NOTIFICATIONS")

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val alerts: Alerts get() = ApplicationProvider.getApplicationContext<HerdrApp>().container.alerts
  private val manager = instrumentation.targetContext.getSystemService(NotificationManager::class.java)

  // A server of the test's own: its notifications are tagged by its id, and no other test's are.
  private val server = ServerConfig(id = "alerts-test-${System.nanoTime()}", name = "alerts-test")

  @After
  fun tearDown() {
    alerts.dismissServer(server.id)
    waitFor("the test server's notification gone") { alert() == null }
  }

  @Test
  fun aPaneJustShownKeepsItsNotificationFromTheStaleOnes() {
    // The agent asked: its notification is listed.
    alerts.show(server, false, snapshot(AgentStatus.BLOCKED), PANE, AgentStatus.BLOCKED, System.currentTimeMillis())
    waitFor("the question listed") { statusOf(alert()) == AgentStatus.BLOCKED.wire }
    // The agent is done now, and the notification of that has just been shown: Android lists the question until it
    // gets to the new one, and a cancel by the tag would take the new one down with it. The pane is left alone.
    val done = mapOf(PANE to AgentMark(AgentStatus.IDLE, seq = 3, completion = 3))
    alerts.dropStale(server.id, done, justShown = setOf(PANE))
    Thread.sleep(1_000)
    assertEquals("no cancel for the pane just shown", AgentStatus.BLOCKED.wire, statusOf(alert()))
    // With no new notification of the pane, as when Android didn't take it, the question no longer holds and goes.
    alerts.dropStale(server.id, done)
    waitFor("the stale question taken down") { alert() == null }
  }

  private fun alert(): StatusBarNotification? =
    manager.activeNotifications.firstOrNull { it.id == Alerts.ALERT_ID && it.tag == Alerts.tag(server.id, PANE) }

  /** The status the notification keeps, as [Alerts.dropStale] reads it. */
  private fun statusOf(notification: StatusBarNotification?): String? = notification?.notification?.extras?.getString("dev.artalex.herdr.extra.STATUS")

  private fun snapshot(status: AgentStatus) =
    SessionSnapshot(
      version = "0.9.5",
      protocol = 22,
      workspaces = listOf(WorkspaceInfo("w1", 1, label = "alerts-test")),
      panes = listOf(PaneInfo(PANE, "t1", "w1", "w1:t1", agent = "claude", agentStatus = status)),
      agents = listOf(AgentInfo(PANE, "t1", "w1", "w1:t1", agent = "claude", agentStatus = status, stateChangeSeq = 2)),
    )

  private companion object {
    const val PANE = "w1:p1"
  }
}
