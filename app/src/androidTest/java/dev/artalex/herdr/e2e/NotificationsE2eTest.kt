package dev.artalex.herdr.e2e

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.AppContainer
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.MainActivity
import dev.artalex.herdr.R
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.e2e.Device.goHome
import dev.artalex.herdr.e2e.Device.shell
import dev.artalex.herdr.e2e.Device.waitFor
import dev.artalex.herdr.notify.Alerts
import dev.artalex.herdr.notify.CheckJobService
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Notifications against real herdr: the app leaves the screen, a fake agent of the e2e session changes its status,
 * and the notification comes from the watch or from the periodic check, the screen locked or not.
 */
@OptIn(ExperimentalTestApi::class)
class NotificationsE2eTest {
  @get:Rule
  val permissions: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK", "android.permission.POST_NOTIFICATIONS")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var agent: String
  private val container: AppContainer get() = ApplicationProvider.getApplicationContext<HerdrApp>().container
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val run = System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    fixture = HostFixture(E2eArgs.require())
    agent = fixture.workspace("e2e-notify-$run")
    fixture.fakeClaude(agent, "working")
    fixture.connectApp(container)
    InstrumentationRegistry.getInstrumentation().runOnMainSync { container.notifications.setEnabled(true) }
  }

  @After
  fun cleanUp() {
    shell("cmd statusbar collapse")
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      container.watch.stopWatch()
      container.notifications.setEnabled(false)
    }
    manager.cancelAll()
    if (::fixture.isInitialized) fixture.close()
  }

  private fun alert(): StatusBarNotification? =
    manager.activeNotifications.firstOrNull { it.id == Alerts.ALERT_ID && it.tag == Alerts.tag(HostFixture.SERVER_ID, agent) }

  /** Waits for the agent's notification in [channel]. */
  private fun waitForAlert(channel: String): StatusBarNotification {
    waitFor("notification in $channel") { alert()?.notification?.channelId == channel }
    return checkNotNull(alert())
  }

  /** The agent list has seen the agent: what it showed then isn't news for the notifications. */
  private fun waitForAgentOnList() {
    compose.scrollListTo(paneTag(agent))
    compose.waitUntilExactlyOneExists(hasTestTag(paneTag(agent)), 10_000)
  }

  /** The agent's status as the app last saw it, and as herdr has it now. */
  private fun appStatus(): AgentStatus? =
    (container.servers[HostFixture.SERVER_ID]?.sessions?.state?.value as? SessionState.Ready)?.snapshot?.agents?.firstOrNull { it.paneId == agent }?.agentStatus

  private fun hostStatus(): String? =
    fixture.raw("session.snapshot").getValue("snapshot").jsonObject.getValue("agents").jsonArray
      .map { it.jsonObject }
      .firstOrNull { it.getValue("pane_id").jsonPrimitive.content == agent }
      ?.getValue("agent_status")?.jsonPrimitive?.content

  /** herdr has no `done` to report: an agent that goes idle with nobody looking at its pane is done. */
  private fun finish() = fixture.reportAgent(agent, "idle")

  private fun runCheck() {
    shell("cmd jobscheduler run -f ${context.packageName} ${CheckJobService.JOB_ID}")
  }

  /** Brings the app back as its launcher icon would, without opening an agent. */
  private fun backToApp() {
    context.startActivity(MainActivity.intent(context))
    waitFor("app on screen") { container.servers.foreground.value }
  }

  private fun watchServiceRunning() = shell("dumpsys activity services ${context.packageName}/dev.artalex.herdr.notify.WatchService").contains("ServiceRecord")

  /** The notification shade, pulled down for a screenshot. */
  private fun screenshotShade(name: String) {
    shell("cmd statusbar expand-notifications")
    Thread.sleep(1_500)
    Screens.take(name)
    shell("cmd statusbar collapse")
  }

  @Test
  fun watchNotifiesWhenAgentAsksAndFinishes() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { container.notifications.setWatchMinutes(5) }
    launchApp(compose, "watchNotifiesWhenAgentAsksAndFinishes") {
      waitForAgentOnList()
      goHome(container)
      waitFor("watch on") { container.watch.until.value != null }
      // Started in the background, the service is in the foreground: Android let it start.
      waitFor("watch service in the foreground") {
        shell("dumpsys activity services ${context.packageName}/dev.artalex.herdr.notify.WatchService").contains("isForeground=true")
      }

      fixture.reportAgent(agent, "blocked")
      val asks = waitForAlert(Alerts.BLOCKED_CHANNEL)
      assertEquals("${text(R.string.alerts_blocked)} · e2e-notify-$run", asks.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
      screenshotShade("14a-notification-blocked")

      // The same notification moves on to the next status.
      finish()
      val done = waitForAlert(Alerts.DONE_CHANNEL)
      assertEquals("${text(R.string.alerts_done)} · e2e-notify-$run", done.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
      screenshotShade("14b-notification-done")

      // A tap opens the agent, which takes the notification down, and the app on screen ends the watch.
      done.notification.contentIntent.send()
      compose.waitUntilExactlyOneExists(hasTestTag("quickKeys"), 15_000)
      waitFor("notification taken down") { alert() == null }
      waitFor("watch over") { container.watch.until.value == null }
      waitFor("watch service gone") { !watchServiceRunning() }
      Screens.take(compose, "14c-agent-from-notification")
    }
  }

  @Test
  fun stopButtonEndsTheWatch() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { container.notifications.setWatchMinutes(5) }
    launchApp(compose, "stopButtonEndsTheWatch") {
      waitForAgentOnList()
      goHome(container)
      waitFor("watch on") { container.watch.until.value != null }
      waitFor("watch service notification") { manager.activeNotifications.any { it.notification.channelId == "watch" } }
      val watching = manager.activeNotifications.first { it.notification.channelId == "watch" }
      assertEquals(text(R.string.watch_stop), watching.notification.actions.single().title.toString())
      watching.notification.actions.single().actionIntent.send()
      waitFor("watch over") { container.watch.until.value == null }
      waitFor("watch service gone") { !watchServiceRunning() }
      // The connection goes at once rather than after the usual grace for a quick look at another app.
      waitFor("disconnected", timeoutMs = 5_000) { container.servers[HostFixture.SERVER_ID]?.host == null }
    }
  }

  @Test
  fun checkNotifiesOffScreenOnlyAndStaleOnesGo() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { container.notifications.setWatchMinutes(0) }
    launchApp(compose, "checkNotifiesOffScreenOnlyAndStaleOnesGo") {
      waitForAgentOnList()
      // On screen the list shows the question, and there is no notification.
      fixture.reportAgent(agent, "blocked")
      try {
        waitFor("the app sees the question") { appStatus() == AgentStatus.BLOCKED }
      } catch (e: IllegalStateException) {
        throw AssertionError("the app sees ${appStatus()}, herdr says ${hostStatus()}", e)
      }
      // A workspace with a question moves to the top, off the screen of a long list: scroll to it on every try.
      compose.waitUntil(10_000) {
        runCatching {
          compose.onNodeWithTag("board").performScrollToNode(hasTestTag(paneTag(agent)))
          compose.onNodeWithTag(paneTag(agent)).assert(hasAnyDescendant(hasText(text(R.string.status_blocked))))
        }.isSuccess
      }
      Thread.sleep(2_500) // a poll more
      assertNull(alert())

      // Off screen without a watch, the periodic check brings it.
      goHome(container)
      assertNull(container.watch.until.value)
      finish()
      runCheck()
      waitForAlert(Alerts.DONE_CHANNEL)
      waitFor("check recorded") { container.notifications.lastCheck.value?.let { it.reached == 1 && it.total == 1 } == true }

      // Back on screen, an agent that moved on takes its notification down.
      backToApp()
      fixture.reportAgent(agent, "working")
      waitFor("stale notification taken down") { alert() == null }
    }
  }

  @Test
  fun finishIsDoneWhateverHerdrCallsIt() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { container.notifications.setWatchMinutes(5) }
    launchApp(compose, "finishIsDoneWhateverHerdrCallsIt") {
      waitForAgentOnList()
      goHome(container)
      waitFor("watch on") { container.watch.until.value != null }

      // In a workspace of its own the agent finishes done; seen on the desktop it is idle, and the notification goes.
      finish()
      waitForAlert(Alerts.DONE_CHANNEL)
      fixture.focusWorkspaceOf(agent)
      waitFor("herdr counts the agent as seen") { hostStatus() == "idle" }
      waitFor("notification taken down") { alert() == null }

      // In the active tab herdr counts the pane as seen even with no desktop attached: the agent finishes idle, a
      // finish all the same. The notification stays while the agent is idle and goes when it works again.
      fixture.reportAgent(agent, "working")
      finish()
      val done = waitForAlert(Alerts.DONE_CHANNEL)
      assertEquals("idle", hostStatus())
      assertEquals("${text(R.string.alerts_done)} · e2e-notify-$run", done.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
      Thread.sleep(2_500) // a poll more
      assertNotNull(alert())
      fixture.reportAgent(agent, "working")
      waitFor("notification taken down") { alert() == null }
    }
  }

  @Test
  fun checkConnectsWithTheScreenLocked() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { container.notifications.setWatchMinutes(0) }
    // The previous test's activity may still count as on screen for a moment, and then the check doesn't look.
    waitFor("app in background") { !container.servers.foreground.value }
    // A first look, unlocked: what the agent does now is no news.
    runCheck()
    waitFor("first check") { container.notifications.lastCheck.value?.reached == 1 }
    val first = container.notifications.lastCheck.value!!.atMs
    Device.locked {
      fixture.reportAgent(agent, "blocked")
      runCheck()
      // The phone's key opens after the first unlock, so the check gets through with the screen locked.
      waitFor("check while locked") { container.notifications.lastCheck.value!!.let { it.atMs > first && it.reached == 1 } }
      assertEquals(Alerts.BLOCKED_CHANNEL, waitForAlert(Alerts.BLOCKED_CHANNEL).notification.channelId)
    }
  }

  @Test
  fun settingsShowNotifications() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { container.notifications.setWatchMinutes(5) }
    launchApp(compose, "settingsShowNotifications") {
      compose.waitUntilExactlyOneExists(hasTestTag("openSettings"), 10_000)
      compose.onNodeWithTag("openSettings").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("watch-15"), 10_000)
      compose.onNodeWithTag("watch-15").performScrollTo().performClick()
      compose.waitUntil(5_000) { container.notifications.watchMinutes.value == 15 }
      compose.onNodeWithTag("lastCheck").performScrollTo()
      Screens.take(compose, "14d-settings-notifications")
      // Off, the rest of the section goes.
      compose.onNodeWithTag("notifications").performScrollTo().performClick()
      compose.waitUntil(5_000) { !container.notifications.enabled.value }
      compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("watch-15")).fetchSemanticsNodes().isEmpty() }
    }
  }
}
