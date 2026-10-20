package dev.artalex.herdr.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.artalex.herdr.AppContainer
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.ssh.SshKeyPair
import dev.artalex.herdr.data.BackgroundCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings and the server form: the servers, the notifications and the phone's key, the same for every server. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
  val servers: StateFlow<List<ServerConfig>> = container.servers.configs

  val notificationsOn: StateFlow<Boolean> = container.notifications.enabled
  val watchMinutes: StateFlow<Int> = container.notifications.watchMinutes
  val lastCheck: StateFlow<BackgroundCheck?> = container.notifications.lastCheck

  private val _key = MutableStateFlow(container.keys.load())
  val key: StateFlow<SshKeyPair?> = _key.asStateFlow()

  fun server(id: String): ServerConfig? = container.servers.config(id)

  fun generateKey() {
    viewModelScope.launch { _key.value = withContext(Dispatchers.Default) { container.keys.generate() } }
  }

  /** A new server comes switched on and starts connecting; an edited one reconnects if it moved. */
  fun save(server: ServerConfig) = container.servers.save(server)

  fun delete(id: String) = container.servers.delete(id)

  fun setNotifications(on: Boolean) = container.notifications.setEnabled(on)

  fun setWatchMinutes(minutes: Int) = container.notifications.setWatchMinutes(minutes)
}
