package dev.artalex.herdr.notify

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.artalex.herdr.Servers
import dev.artalex.herdr.connection.ConnectionState
import dev.artalex.herdr.core.notify.AgentMark
import dev.artalex.herdr.core.notify.AgentMarks
import dev.artalex.herdr.core.notify.agentMarks
import dev.artalex.herdr.core.notify.notableChanges
import dev.artalex.herdr.core.notify.seenMarks
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.ssh.SshConnection
import dev.artalex.herdr.core.ssh.SshKeyPair
import dev.artalex.herdr.core.ssh.discoverHerdrHost
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.store.lastSnapshot
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
 * Notices agents that start waiting for an answer or finish while the user doesn't see them, and shows a notification
 * for each through [Alerts] once the app is off the screen.
 *
 * Every snapshot of a server comes to [seen], which keeps what its agents were doing when the user last saw them,
 * between app runs too. On screen the user sees the agents the screen on top shows ([showing]): a snapshot marks
 * those as seen and takes down notifications that no longer hold. What the agents off it come to stays news. Off
 * screen, an agent that came to a notable status since the user last saw it shows a notification.
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

  /** The agents of each server's latest snapshot, for a screen that comes to show them. */
  private val latest = mutableMapOf<String, Map<String, AgentMark>>()
  private var inSight: Showing = Showing.NoAgents
  private val lastChange = MutableStateFlow(0L)
  private var watch: Job? = null

  private val _until = MutableStateFlow<Long?>(null)

  /** When the watch ends unless something changes; null while there is no watch. */
  val until: StateFlow<Long?> = _until.asStateFlow()

  init {
    // Marks of a server deleted while they weren't loaded would stay forever otherwise.
    val ids = servers.configs.value.map { it.id }.toSet()
    prefs.edit { for (id in prefs.all.keys) if (id !in ids) remove(id) }
    scope.launch {
      servers.foreground.drop(1).collect { onScreen ->
        if (onScreen) {
          stopWatch()
          lookAgain()
        } else {
          startWatch()
        }
      }
    }
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
    val previous = latest.put(serverId, after)
    if (servers.foreground.value) {
      // Snapshots come for more than the screen shows: the agent screen's pane switcher polls every server, and so do
      // agents started from the phone that wait to get their snippets or task.
      if (after != previous) alerts.dropStale(serverId, after)
      keep(serverId, before, seenMarks(before, after) { inSight.shows(serverId, it) })
      return
    }
    if (after == before) return
    lastChange.value = clock()
    val notified = mutableSetOf<String>()
    if (settings.enabled.value && alerts.allowed()) {
      val withServer = servers.configs.value.count { it.active } > 1
      for ((paneId, status) in notableChanges(before, after)) {
        Log.i(TAG, "agent in pane $paneId of ${config.title} is ${status.wire} now")
        // A notification Android doesn't take leaves the agent's old one, which goes if it no longer holds.
        if (alerts.show(config, withServer, ready.snapshot, paneId, status, ready.fetchedAtMs)) notified += paneId
      }
    }
    alerts.dropStale(serverId, after, justShown = notified)
    keep(serverId, before, after)
  }

  /**
   * The screen on top shows [what] of the agents: what the user sees of them from now on. It shows the latest snapshots
   * already, and they count as seen right away.
   */
  fun showing(what: Showing) {
    inSight = what
    if (servers.foreground.value) lookAgain()
  }

  /**
   * The agents the screen shows in the latest snapshots are seen: the screen is a new one, or the app is back on screen.
   * Not those of a server the screens can't show now ([onDisplay]): its news stays news.
   */
  private fun lookAgain() {
    for ((serverId, after) in latest) {
      if (servers.config(serverId)?.active != true || !onDisplay(serverId)) continue
      val before = marksOf(serverId)
      keep(serverId, before, seenMarks(before, after) { inSight.shows(serverId, it) })
    }
  }

  /**
   * The screens show the agents of server [serverId] only while it is connected and polled: without a connection the
   * agent list has the server's name and state alone, and the pane switcher leaves the server out.
   */
  private fun onDisplay(serverId: String): Boolean {
    val server = servers[serverId] ?: return false
    return server.connection.state.value is ConnectionState.Connected && server.sessions.state.value.lastSnapshot != null
  }

  /** Keeps [next] as the marks of server [serverId], between app runs too, unless they are still [before]. */
  private fun keep(serverId: String, before: Map<String, AgentMark>?, next: Map<String, AgentMark>) {
    if (next == before) return
    marks[serverId] = next
    prefs.edit { putString(serverId, AgentMarks.encode(next)) }
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
    // On screen nothing is notified: the news waits for the app to leave. The watch looks at every server itself.
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
    servers[config.id]?.seeFolders(snapshot)
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
    latest.remove(serverId)
    prefs.edit { remove(serverId) }
    alerts.dismissServer(serverId)
  }

  private fun marksOf(serverId: String): Map<String, AgentMark>? =
    marks[serverId] ?: AgentMarks.decode(prefs.getString(serverId, null))?.also { marks[serverId] = it }

  /**
   * The periodic check is scheduled while notifications are on and Android lets them show, and some server is on to
   * look at: without the permission a check would wake the phone only to find it may not notify. The user changes the
   * permission in Android's settings, outside the app, so it is read again as the app comes on the screen and as it
   * leaves: the app's own question for it doesn't take the app off the screen.
   */
  private suspend fun scheduleChecks() {
    combine(settings.enabled, servers.configs, servers.foreground) { on, list, _ -> on && alerts.allowed() && list.any { it.active && it.isComplete } }
      .distinctUntilChanged()
      .collect { wanted -> if (wanted) CheckJobService.schedule(app) else CheckJobService.cancel(app) }
  }

  private companion object {
    const val TAG = "HerdrWatch"
    const val MINUTE_MS = 60_000L
  }
}
