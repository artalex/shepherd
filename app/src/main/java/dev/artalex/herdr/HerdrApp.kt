package dev.artalex.herdr

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import dev.artalex.herdr.core.ssh.SshLog
import dev.artalex.herdr.data.FilesSettings
import dev.artalex.herdr.data.KeyVault
import dev.artalex.herdr.data.NotificationSettings
import dev.artalex.herdr.data.ServerStore
import dev.artalex.herdr.data.SnippetStore
import dev.artalex.herdr.notify.AgentWatch
import dev.artalex.herdr.notify.Alerts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class HerdrApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    SshLog.install(verbose = debuggable) { level, message, error ->
      when (level) {
        "debug", "info" -> Log.d("JSch", message)
        "warn" -> Log.w("JSch", message, error)
        else -> Log.e("JSch", message, error)
      }
    }
    container = AppContainer(this)
    ProcessLifecycleOwner.get().lifecycle.addObserver(container.servers)
    container.servers.watchNetwork()
  }
}

/** Manual dependency wiring: the app is small enough not to need a DI framework. */
class AppContainer(app: Application) {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  val keys = KeyVault(app)

  /** How many file transfers run now, on all servers: the transfer service keeps the app going while there are any. */
  val transfers = MutableStateFlow(0)

  val notifications = NotificationSettings(app)
  val snippets = SnippetStore(app)
  val filesSettings = FilesSettings(app)
  val alerts = Alerts(app)

  // The watch gets every snapshot; it is made right after, and no snapshot comes before the app runs.
  val servers: Servers = Servers(app, ServerStore(app), keys, scope, transfers) { serverId, ready -> watch.seen(serverId, ready) }
  val watch: AgentWatch = AgentWatch(app, servers, keys, notifications, alerts, scope)
}
