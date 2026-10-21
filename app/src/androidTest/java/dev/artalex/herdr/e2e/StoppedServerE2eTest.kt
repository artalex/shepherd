package dev.artalex.herdr.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A herdr server that stops and comes back, as after `herdr server stop` on the host. The list says the server isn't
 * running, whether it stopped before the app connected or under a live connection, and shows the session again once
 * the server is back. The test stops a session of its own: the other tests' servers stay up.
 */
@OptIn(ExperimentalTestApi::class)
class StoppedServerE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private val container get() = ApplicationProvider.getApplicationContext<HerdrApp>().container
  private val workspace = "e2e-stopped-" + System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e, e2e.sessionToStop)
    fixture.workspace(workspace)
  }

  @After
  fun closeFixture() {
    if (::fixture.isInitialized) fixture.close()
  }

  private fun waitUntilServerNotRunning() =
    compose.waitUntilExactlyOneExists(hasTestTag("server-${HostFixture.SERVER_ID}") and hasText(text(R.string.problem_herdr_not_running), substring = true), 30_000)

  private fun waitForWorkspace() =
    compose.waitUntil(60_000) { runCatching { compose.onNodeWithTag("board").performScrollToNode(hasText(workspace, substring = true)) }.isSuccess }

  @Test
  fun stoppedServerIsReportedUntilItIsBack() {
    // Stopped before the app connects: the app reads the status of a stopped server.
    fixture.stopServer()
    fixture.connectApp(container)
    launchApp(compose, "stoppedServerIsReportedUntilItIsBack") {
      waitUntilServerNotRunning()
      Screens.take(compose, "16a-server-stopped")
      fixture.startServer()
      waitForWorkspace()
      // Stopped under a live connection: SSH stays up, the session polls fail.
      fixture.stopServer()
      waitUntilServerNotRunning()
      fixture.startServer()
      waitForWorkspace()
      Screens.take(compose, "16b-server-back")
    }
  }
}
