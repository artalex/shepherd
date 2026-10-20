package dev.artalex.herdr.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.ssh.HostKey
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The update from 1.7, which knew one server and kept it as separate settings. */
class ServerStoreTest {
  private val context = ApplicationProvider.getApplicationContext<HerdrApp>()
  private val key = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })

  @After
  fun resetApp() {
    // The stores made here wrote behind the app's back; clearing the app's servers puts the two in step again.
    InstrumentationRegistry.getInstrumentation().runOnMainSync { context.container.servers.clear() }
  }

  @Test
  fun serverOfVersionOneSevenBecomesTheFirstServerSwitchedOn() {
    context.getSharedPreferences("servers", Context.MODE_PRIVATE).edit().clear().commit()
    context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
      .putString("host", "10.0.2.2 ")
      .putInt("port", 2222)
      .putString("user", "art")
      .putString("herdr_path", "/opt/herdr")
      .putString("session", "lab")
      .putString("host_key", key.encode())
      .commit()

    val servers = ServerStore(context).servers.value
    val server = servers.single()
    assertEquals(ServerConfig(id = server.id, host = "10.0.2.2", port = 2222, user = "art", herdrPath = "/opt/herdr", session = "lab", hostKey = key.encode()), server)
    assertEquals(key, server.pinnedHostKey)
    assertFalse(File(context.dataDir, "shared_prefs/connection.xml").exists())
    // The next start loads the list as it is, without the old settings.
    assertEquals(servers, ServerStore(context).servers.value)
  }

  @Test
  fun versionOneSevenThatWasNeverSetUpHasNoServers() {
    context.getSharedPreferences("servers", Context.MODE_PRIVATE).edit().clear().commit()
    context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().putString("host", "").commit()
    assertEquals(emptyList<ServerConfig>(), ServerStore(context).servers.value)
  }
}
