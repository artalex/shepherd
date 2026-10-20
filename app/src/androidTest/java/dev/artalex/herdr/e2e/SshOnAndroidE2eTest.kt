package dev.artalex.herdr.e2e

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.core.ssh.SshConnection
import dev.artalex.herdr.core.ssh.SshTarget
import dev.artalex.herdr.core.ssh.discoverHerdrHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The SSH layer under Android's rules, without the UI. */
class SshOnAndroidE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")

  private lateinit var fixture: HostFixture
  private lateinit var connection: SshConnection

  @Before
  fun connect() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e)
    connection = SshConnection(SshTarget(e2e.host, e2e.port.toInt(), e2e.user), e2e.key, fixture.hostKey).also { it.connect() }
  }

  @After
  fun close() {
    if (::connection.isInitialized) connection.close()
    if (::fixture.isInitialized) fixture.close()
  }

  /**
   * Regression: closing a channel sends a packet, and on the main thread Android refused it after JSch had
   * already encrypted it; the server then dropped the session ("message authentication code incorrect").
   */
  @Test
  fun closingChannelsOnMainThreadKeepsSessionHealthy() = runBlocking {
    val host = discoverHerdrHost(connection, HostFixture.HERDR_PATH, E2eArgs.require().session)
    repeat(5) {
      val stream = withContext(Dispatchers.IO) { connection.openStreamLocal(host.status.server.socket) }
      InstrumentationRegistry.getInstrumentation().runOnMainSync { stream.close() }
      host.api.snapshot()
    }
    assertTrue("session dropped", connection.isConnected)
  }

  @Test
  fun parallelAndCancelledRequestsShareOneSession() = runBlocking {
    val host = discoverHerdrHost(connection, HostFixture.HERDR_PATH, E2eArgs.require().session)
    val pane = fixture.workspace("ssh-parallel")
    coroutineScope {
      repeat(4) { worker ->
        launch(Dispatchers.IO) {
          repeat(20) { i ->
            if ((worker + i) % 4 == 0) withTimeoutOrNull((1L..30L).random()) { host.api.readPane(pane) }
            else {
              host.api.snapshot()
              host.api.readPane(pane)
            }
          }
        }
      }
    }
    assertTrue("session dropped", connection.isConnected)
  }
}
