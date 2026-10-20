package dev.artalex.herdr.notify

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.artalex.herdr.Servers
import dev.artalex.herdr.core.notify.AgentMark
import dev.artalex.herdr.core.notify.AgentMarks
import dev.artalex.herdr.core.notify.agentMarks
import dev.artalex.herdr.core.notify.notableChanges
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.ssh.SshConnection
import dev.artalex.herdr.core.ssh.SshKeyPair
import dev.artalex.herdr.core.ssh.discoverHerdrHost
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.data.BackgroundCheck
import dev.artalex.herdr.data.KeyVault
import dev.artalex.herdr.data.NotificationSettings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Notices agents that start waiting for an answer or finish while the app is off the screen, and shows a
 * notification for each through [Alerts].
 *
 * Every snapshot of a server comes to [seen], which keeps what its agents were doing when last seen, between app
 * runs too. On screen the user sees the agents, so a snapshot only updates that and takes down notifications that no
 * longer hold. Off screen, an agent that came to a notable status since the last look shows a notification.
 *
 * Off screen the app looks in two ways:
 * - the watch, as the app leaves the screen: the connections stay up and the sessions are polled as with the agent
 *   list open, until nothing has changed for [NotificationSettings.watchMinutes]. [WatchService] keeps Android from
 *   freezing the app meanwhile;
 * - the periodic check ([CheckJobService]) every 15 minutes, or as rarely as Android decides: a short connection of
 *   its own to each server for one snapshot. A server that doesn't answer is skipped quietly: the phone often can't
 *   reach one, with the VPN off for example. The next look that reaches it tells what happened meanwhile.
 */
class AgentWatch(
  private val app: Application,
  private val servers: Servers,
  private val keys: KeyVault,
  private val settings: NotificationSettings,
  private val alerts: Alerts,
  private val scope: CoroutineScope,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val prefs = app.getSharedPreferences("agent-marks", Context.MODE_PRIVATE)

  // Main thread only, like everything here.
  private val marks = mutableMapOf<String, Map<String, AgentMark>>()
  private val seenAt = mutableMapOf<String, Long>()
  private val lastChange = MutableStateFlow(0L)
  private var watch: Job? = null

  private val _until = MutableStateFlow<Long?>(null)

  /** When the watch ends unless something changes; null while there is no watch. */
  val until: StateFlow<Long?> = _until.asStateFlow()

  init {
    // Marks of a server deleted while they weren't loaded would stay forever otherwise.
    val ids = servers.configs.value.map { it.id }.toSet()
    prefs.edit { for (id in prefs.all.keys) if (id !in ids) remove(id) }
    scope.launch { servers.foreground.drop(1).collect { onScreen -> if (onScreen) stopWatch() else startWatch() } }
    scope.launch { forgetChangedServers() }
    scope.launch { scheduleChecks() }
  }

  /** A snapshot of server [serverId]: from the screens' polling, the watch or a check. */
  fun seen(serverId: String, ready: SessionState.Ready) {
    // A poller collected again hands out its last snapshot first, and a check may have seen newer ones since.
    if (ready.fetchedAtMs <= (seenAt[serverId] ?: 0L)) return
    seenAt[serverId] = ready.fetchedAtMs
    val config = servers.config(serverId)?.takeIf { it.active } ?: return
    val before = marksOf(serverId)
    val after = ready.snapshot.agentMarks()
    if (after == before) return
    if (!servers.foreground.value) {
      lastChange.value = clock()
      if (settings.enabled.value && alerts.allowed()) {
        val withServer = servers.configs.value.count { it.active } > 1
        for ((paneId, status) in notableChanges(before, after)) {
          Log.i(TAG, "agent in pane $paneId of ${config.title} is ${status.wire} now")
          alerts.show(config, withServer, ready.snapshot, paneId, status, ready.fetchedAtMs)
        }
      }
    }
    alerts.dropStale(serverId, after)
    marks[serverId] = after
    prefs.edit { putString(serverId, AgentMarks.encode(after)) }
  }

  /** Ends the watch: the app is back on screen, or the user had enough. The periodic checks go on. */
  fun stopWatch() {
    watch?.let {
      Log.i(TAG, "watch stopped")
      it.cancel()
    }
  }

  /** The periodic check: one snapshot of every server that is on, over a connection of its own. */
  suspend fun check() {
    // The agent list sees everything, and so does the watch.
    if (servers.foreground.value || watch != null) return
    if (!settings.enabled.value || !alerts.allowed()) return
    val configs = servers.configs.value.filter { it.active && it.isComplete }
    if (configs.isEmpty()) return
    val key =
      try {
        keys.load()
      } catch (e: Exception) {
        Log.w(TAG, "check skipped, phone key unavailable: ${e.message}")
        null
      } ?: return
    val answered = coroutineScope { configs.map { async { checkOne(it, key) } }.awaitAll() }.count { it }
    Log.i(TAG, "check done, $answered of ${configs.size} servers answered")
    settings.checked(BackgroundCheck(clock(), answered, configs.size))
  }

  private suspend fun checkOne(config: ServerConfig, key: SshKeyPair): Boolean {
    val snapshot =
      try {
        withContext(Dispatchers.IO) {
          val connection = SshConnection(config.target, key, config.pinnedHostKey)
          try {
            connection.connect()
            discoverHerdrHost(connection, config.herdrPath, config.session).api.snapshot()
          } finally {
            connection.close()
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.i(TAG, "check: ${config.title} didn't answer: ${e.message}")
        return false
      }
    seen(config.id, SessionState.Ready(snapshot, clock()))
    return true
  }

  /** The app left the screen: watch for as long as the settings say, if notifications are on. */
  private fun startWatch() {
    val minutes = settings.watchMinutes.value
    val watched = servers.configs.value.filter { it.active && it.isComplete }.mapNotNull { servers[it.id] }
    if (minutes == 0 || watched.isEmpty() || !settings.enabled.value || !alerts.allowed()) return
    try {
      // In the background Android freezes the app within seconds; the service keeps it going. It may start only
      // in the first seconds after the app left the screen.
      ContextCompat.startForegroundService(app, Intent(app, WatchService::class.java))
    } catch (e: IllegalStateException) {
      Log.w(TAG, "watch service may not start: ${e.message}")
      return
    }
    watch?.cancel()
    val job =
      scope.launch {
        Log.i(TAG, "app in background, watching agents until nothing changes for $minutes min")
        servers.keepConnected {
          coroutineScope {
            // Polled as with the agent list open; the snapshots come to seen().
            val polls = watched.map { server -> launch { server.sessions.state.collect {} } }
            waitForQuiet(minutes * MINUTE_MS)
            polls.forEach { it.cancel() }
          }
        }
        Log.i(TAG, "no agent changed for $minutes min, watch over")
      }
    watch = job
    // A watch cancelled for a new one may finish after the new one started: only the current one clears.
    job.invokeOnCompletion {
      if (watch === job) {
        watch = null
        _until.value = null
      }
    }
  }

  /** Returns once [window] has passed without a change; every change starts it over. */
  private suspend fun waitForQuiet(window: Long) {
    lastChange.value = clock()
    while (true) {
      val end = lastChange.value + window
      _until.value = end
      val left = end - clock()
      if (left <= 0) return
      withTimeoutOrNull(left) { lastChange.first { it + window > end } }
    }
  }

  /** A server that is gone, switched off or moved starts over: its marks and notifications no longer hold. */
  private suspend fun forgetChangedServers() {
    var known = servers.configs.value.associateBy { it.id }
    servers.configs.collect { list ->
      for ((id, old) in known) {
        val new = list.firstOrNull { it.id == id }
        if (new == null || (old.active && !new.active) || !new.sameConnection(old)) forget(id)
      }
      known = list.associateBy { it.id }
    }
  }

  private fun forget(serverId: String) {
    marks.remove(serverId)
    seenAt.remove(serverId)
    prefs.edit { remove(serverId) }
    alerts.dismissServer(serverId)
  }

  private fun marksOf(serverId: String): Map<String, AgentMark>? =
    marks[serverId] ?: AgentMarks.decode(prefs.getString(serverId, null))?.also { marks[serverId] = it }

  /** The periodic check is scheduled while notifications are on and some server is on to look at. */
  private suspend fun scheduleChecks() {
    combine(settings.enabled, servers.configs) { on, list -> on && list.any { it.active && it.isComplete } }
      .distinctUntilChanged()
      .collect { wanted -> if (wanted) CheckJobService.schedule(app) else CheckJobService.cancel(app) }
  }

  private companion object {
    const val TAG = "HerdrWatch"
    const val MINUTE_MS = 60_000L
  }
}
