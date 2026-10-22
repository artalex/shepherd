package dev.artalex.herdr.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import dev.artalex.herdr.connection.ConnectionProblem
import dev.artalex.herdr.connection.ConnectionState
import dev.artalex.herdr.core.DEFAULT_SESSION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The real path, as on a new phone: the server form on the first start, then the agent list, where the app connects
 * over SSH to the host machine (10.0.2.2), asks to accept the host key and shows the server's workspaces. The form
 * doesn't save a session name herdr would refuse; one saved before it checked names is reported on the list with
 * herdr's reason.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectE2eTest {
  // Notifications are on by default: without the permission the agent list would ask for it over the screenshot.
  @get:Rule
  val permissions: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK", "android.permission.POST_NOTIFICATIONS")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var e2e: E2eArgs
  private lateinit var fixture: HostFixture
  private val container get() = ApplicationProvider.getApplicationContext<HerdrApp>().container
  private val run = System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun freshApp() {
    e2e = E2eArgs.require()
    fixture = HostFixture(e2e)
    fixture.workspace("e2e-connect-$run")
    InstrumentationRegistry.getInstrumentation().runOnMainSync { container.servers.clear() }
    container.keys.store(e2e.key)
  }

  @After
  fun closeFixture() {
    if (::fixture.isInitialized) fixture.close()
  }

  /** The form of the first start, filled in for the host machine and [session]; the name stays empty. */
  private fun fillServerForm(session: String) {
    compose.waitUntilExactlyOneExists(hasTestTag("host"), 10_000)
    compose.onNodeWithTag("host").performTextReplacement(e2e.host)
    compose.onNodeWithTag("port").performTextReplacement(e2e.port)
    compose.onNodeWithTag("user").performTextReplacement(e2e.user)
    compose.onNodeWithTag("session").performTextReplacement(session)
  }

  @Test
  fun firstStartAddsServerAndAcceptsItsKeyOnTheList() {
    launchApp(compose, "firstStartAddsServerAndAcceptsItsKeyOnTheList") {
      fillServerForm(e2e.session)
      // Without a name of its own the server is listed by host and session.
      val title = "${e2e.host} · ${e2e.session}"
      compose.onNodeWithText(text(R.string.server_name_default, title)).assertExists()
      Espresso.closeSoftKeyboard()
      Screens.take(compose, "00-server-form")
      // The soft keyboard is up now and may cover the button.
      compose.onNodeWithTag("save").performScrollTo().assertIsEnabled().performClick()

      try {
        compose.waitUntilExactlyOneExists(hasTestTag("trustHostKey"), 20_000)
      } catch (e: Throwable) {
        compose.onAllNodes(isRoot()).printToLog("E2E", maxDepth = Int.MAX_VALUE)
        throw e
      }
      compose.onNodeWithText(text(R.string.host_key_title, title)).assertExists()
      compose.onNodeWithTag("hostKeyFingerprint").assert(hasText(e2e.hostKeyFingerprint, substring = true))
      Screens.take(compose, "01-host-key")
      compose.onNodeWithTag("trustHostKey").performClick()

      compose.waitUntil(30_000) { container.servers.configs.value.singleOrNull()?.let { container.servers[it.id]?.connection?.state?.value } is ConnectionState.Connected }
      // The server's name stays on top of its workspaces.
      compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("board").performScrollToNode(hasText("e2e-connect-$run", substring = true)) }.isSuccess }
      compose.onNodeWithText(title).assertExists()
      Screens.take(compose, "02-connected")
    }
  }

  @Test
  fun sessionNameHerdrWouldRefuseIsCaughtInTheForm() {
    launchApp(compose, "sessionNameHerdrWouldRefuseIsCaughtInTheForm") {
      // The line under the field says what herdr would refuse as the name is typed, and the server can't be saved.
      fillServerForm("my session")
      compose.onNodeWithText(text(R.string.server_session_characters)).assertExists()
      Espresso.closeSoftKeyboard()
      compose.onNodeWithTag("save").performScrollTo().assertIsNotEnabled()
      Screens.take(compose, "00b-session-name")
      compose.onNodeWithTag("session").performTextReplacement("")
      compose.onNodeWithText(text(R.string.server_session_empty, DEFAULT_SESSION)).assertExists()
      compose.onNodeWithTag("save").assertIsNotEnabled()

      // A name herdr takes: the line goes, and the server can be saved.
      compose.onNodeWithTag("session").performTextReplacement(e2e.session)
      compose.onNodeWithText(text(R.string.server_session_empty, DEFAULT_SESSION)).assertDoesNotExist()
      compose.onNodeWithTag("save").assertIsEnabled()
    }
  }

  @Test
  fun sessionNameHerdrRefusesIsReportedOnTheList() {
    // A server saved before the form checked names keeps the name.
    fixture.addServer(container, HostFixture.SERVER_ID, session = "my session")
    launchApp(compose, "sessionNameHerdrRefusesIsReportedOnTheList") {
      // herdr's reason instead of "herdr not found", and no retries: only the settings can fix the name.
      val reason = "session name may only contain ASCII letters, numbers, '.', '_' and '-'"
      compose.waitUntilExactlyOneExists(hasText(text(R.string.problem_bad_session, "my session", reason)), 30_000)
      val server = container.servers[HostFixture.SERVER_ID]
      assertEquals(ConnectionState.Failed(ConnectionProblem.BadSessionName("my session", reason), retryInMs = null), server?.connection?.state?.value)
      Screens.take(compose, "02b-bad-session")
    }
  }
}
