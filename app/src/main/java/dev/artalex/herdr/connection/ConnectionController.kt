package dev.artalex.herdr.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.artalex.herdr.core.host.BadSessionName
import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.core.host.HerdrNotFound
import dev.artalex.herdr.core.host.HostCheck
import dev.artalex.herdr.core.host.check
import dev.artalex.herdr.core.host.readHostStatus
import dev.artalex.herdr.core.ssh.HostKey
import dev.artalex.herdr.core.ssh.SshConnection
import dev.artalex.herdr.core.ssh.SshFailure
import dev.artalex.herdr.core.ssh.discoverHerdrHost
import dev.artalex.herdr.data.KeyVault
import dev.artalex.herdr.data.ServerStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ConnectionState {
  /** The server's settings are incomplete, or the phone has no key yet. */
  data object NotConfigured : ConnectionState

  data object Disconnected : ConnectionState

  data object Connecting : ConnectionState

  /** [restartNeeded]: the API works, but terminal streams need the herdr server restarted on the updated binary. */
  data class Connected(val host: HerdrHost, val restartNeeded: Boolean) : ConnectionState

  /** [retryInMs] is set when the controller will try again by itself. */
  data class Failed(val problem: ConnectionProblem, val retryInMs: Long?) : ConnectionState
}

sealed interface ConnectionProblem {
  data class UnknownHostKey(val key: HostKey) : ConnectionProblem

  data class HostKeyChanged(val pinned: HostKey, val presented: HostKey) : ConnectionProblem

  data object KeyRejected : ConnectionProblem

  data object LocalNetworkDenied : ConnectionProblem

  data class Unreachable(val detail: String) : ConnectionProblem

  data class HerdrMissing(val path: String) : ConnectionProblem

  /** herdr refused the session name in the server's settings; [reason] is herdr's. */
  data class BadSessionName(val session: String, val reason: String) : ConnectionProblem

  data object HerdrNotRunning : ConnectionProblem

  data class HerdrTooOld(val version: String) : ConnectionProblem

  data class WrongProtocol(val protocol: Int?) : ConnectionProblem

  data class Other(val detail: String) : ConnectionProblem
}

/** Problems that can go away without the user changing anything. */
val ConnectionProblem.isTransient: Boolean
  get() = this is ConnectionProblem.Unreachable || this is ConnectionProblem.HerdrNotRunning || this is ConnectionProblem.Other

/**
 * Owns the SSH connection to the herdr host of server [serverId].
 *
 * A server that is on connects while the app is in the foreground and drops the connection [BACKGROUND_GRACE_MS]
 * after it leaves. In the background only file transfers keep it up while they run ([holding]), and the agent watch
 * for notifications for a few minutes, which counts as the foreground here. [dev.artalex.herdr.Servers] tells it
 * when the app comes and goes, when the watch starts and ends, and when the network changes.
 * Transient failures are retried with backoff; host key and authentication problems wait for the user. A changed
 * default network (Wi-Fi ↔ mobile) reconnects at once: the old TCP connection is dead even if the session doesn't
 * know it yet.
 */
class ConnectionController(
  private val context: Context,
  private val serverId: String,
  private val store: ServerStore,
  private val keys: KeyVault,
  private val scope: CoroutineScope,
) {
  private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
  val state: StateFlow<ConnectionState> = _state.asStateFlow()

  private var loop: Job? = null
  private var backgroundTimer: Job? = null
  private var connection: SshConnection? = null
  private var foreground = false
  private val holds = MutableStateFlow(0)
  private val apiFailures = Channel<Unit>(Channel.CONFLATED)

  /** Runs [block] with the connection kept up in the background, as for a file transfer the user started. */
  suspend fun <T> holding(block: suspend () -> T): T {
    holds.update { it + 1 }
    try {
      return block()
    } finally {
      holds.update { it - 1 }
    }
  }

  /**
   * A request to the herdr API failed. The SSH session outlives a stopped herdr server, so the connection asks herdr
   * whether its server still runs; if not, it reports that and retries until the server is back.
   */
  fun apiFailed() {
    apiFailures.trySend(Unit)
  }

  /** (Re)connects with the server's current settings; cancels any attempt or retry in progress. */
  fun connect() {
    loop?.cancel()
    loop = scope.launch { connectLoop() }
  }

  fun disconnect() {
    loop?.cancel()
    loop = null
    closeConnection()
    _state.value = ConnectionState.Disconnected
  }

  /** The user compared the fingerprint and accepted [key] for this server. */
  fun trustHostKey(key: HostKey) {
    store.pinHostKey(serverId, key)
    connect()
  }

  /**
   * The app came to the foreground, or the watch needs the connection: a server that is on connects, unless it is
   * connected or on its way.
   */
  fun foreground() {
    foreground = true
    backgroundTimer?.cancel()
    val state = _state.value
    if (state !is ConnectionState.Connected && state !is ConnectionState.Connecting && store[serverId]?.active == true) connect()
  }

  /**
   * The app left the foreground: the connection goes [graceMs] later, once no file transfer needs it. A quick look at
   * another app keeps it; the end of the watch doesn't need to wait.
   */
  fun background(graceMs: Long = BACKGROUND_GRACE_MS) {
    foreground = false
    backgroundTimer?.cancel()
    backgroundTimer =
      scope.launch {
        delay(graceMs)
        if (holds.value > 0) {
          Log.i(TAG, "app in background, keeping SSH connection to ${label()} for file transfers")
          holds.first { it == 0 }
        }
        Log.i(TAG, "app in background, closing SSH connection to ${label()}")
        disconnect()
      }
  }

  /** The default network is up; [changed] if it replaced another one, whose connections are dead now. */
  fun networkChanged(changed: Boolean) {
    val stale = _state.value is ConnectionState.Connected && changed
    val waiting = (_state.value as? ConnectionState.Failed)?.retryInMs != null
    if (foreground && (stale || waiting)) {
      Log.i(TAG, "default network changed, reconnecting to ${label()}")
      connect()
    }
  }

  /** The server is deleted: nothing may connect for it again. */
  fun dispose() {
    backgroundTimer?.cancel()
    disconnect()
  }

  private fun label() = store[serverId]?.let { "${it.target.user}@${it.target.host}:${it.target.port}, session ${it.session}" } ?: serverId

  private suspend fun connectLoop() {
    var attempt = 0
    while (true) {
      val problem = attemptOnce() ?: return
      val retry = if (problem.isTransient && foreground) BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)] else null
      _state.value = ConnectionState.Failed(problem, retry)
      if (retry == null) return
      attempt++
      delay(retry)
    }
  }

  /** Returns null when connected (and keeps watching the session) or when there is nothing to connect to. */
  private suspend fun attemptOnce(): ConnectionProblem? {
    val cfg = store[serverId]
    if (cfg == null || !cfg.active) {
      _state.value = ConnectionState.Disconnected
      return null
    }
    val key =
      try {
        keys.load()
      } catch (e: Exception) {
        Log.w(TAG, "phone key unavailable: ${e.message}")
        return ConnectionProblem.Other("phone key unavailable: ${e.message}")
      }
    if (!cfg.isComplete || key == null) {
      Log.i(TAG, "not configured: settings complete=${cfg.isComplete}, key present=${key != null}")
      _state.value = ConnectionState.NotConfigured
      return null
    }
    if (!localNetworkAllowed()) {
      Log.w(TAG, "local network permission is not granted")
      return ConnectionProblem.LocalNetworkDenied
    }
    Log.i(TAG, "connecting to ${cfg.target.user}@${cfg.target.host}:${cfg.target.port}, session ${cfg.session}")
    closeConnection()
    _state.value = ConnectionState.Connecting
    val conn = SshConnection(cfg.target, key, cfg.pinnedHostKey)
    val host =
      try {
        withContext(Dispatchers.IO) { conn.connect() }
        discoverHerdrHost(conn, cfg.herdrPath, cfg.session)
      } catch (e: CancellationException) {
        conn.close()
        throw e
      } catch (e: Exception) {
        conn.close()
        Log.w(TAG, "connection attempt failed: ${e.message}")
        return problemOf(e)
      }
    val check = host.status.check()
    val problem =
      when (check) {
        HostCheck.Ok, HostCheck.RestartNeeded -> null
        HostCheck.ServerNotRunning -> ConnectionProblem.HerdrNotRunning
        is HostCheck.TooOld -> ConnectionProblem.HerdrTooOld(check.version)
        is HostCheck.WrongProtocol -> ConnectionProblem.WrongProtocol(check.protocol)
      }
    if (problem != null) {
      conn.close()
      return problem
    }
    connection = conn
    _state.value = ConnectionState.Connected(host, restartNeeded = check == HostCheck.RestartNeeded)
    Log.i(TAG, "connected to herdr ${host.status.server.version}, session ${host.session}")
    // We start over when JSch notices a dead session through keepalives, or when a failed API request turns out to
    // be a herdr server that stopped.
    while (conn.isConnected) {
      val apiFailed = withTimeoutOrNull(SESSION_CHECK_MS) { apiFailures.receive() } != null
      if (apiFailed && !serverRunning(host, cfg.herdrPath)) {
        Log.i(TAG, "herdr server of ${label()} stopped")
        return ConnectionProblem.HerdrNotRunning
      }
    }
    Log.i(TAG, "SSH session to ${label()} dropped")
    return ConnectionProblem.Unreachable("connection lost")
  }

  /**
   * Whether `herdr status` says the server runs. If it doesn't answer, the server counts as running: the session poll
   * goes on and shows its own errors.
   */
  private suspend fun serverRunning(host: HerdrHost, herdrPath: String): Boolean =
    try {
      host.transport.readHostStatus(herdrPath, host.session).server.running
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "herdr status of ${label()} failed: ${e.message}")
      true
    }

  private fun closeConnection() {
    connection?.close()
    connection = null
  }

  private fun localNetworkAllowed(): Boolean =
    Build.VERSION.SDK_INT < 37 ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

  private fun problemOf(e: Exception): ConnectionProblem =
    when (e) {
      is SshFailure.UnknownHostKey -> ConnectionProblem.UnknownHostKey(e.presented)
      is SshFailure.HostKeyChanged -> ConnectionProblem.HostKeyChanged(e.pinned, e.presented)
      is SshFailure.AuthFailed -> ConnectionProblem.KeyRejected
      is SshFailure.Unreachable -> ConnectionProblem.Unreachable(e.cause?.message ?: e.message ?: "")
      is HerdrNotFound -> ConnectionProblem.HerdrMissing(e.path)
      is BadSessionName -> ConnectionProblem.BadSessionName(e.session, e.reason)
      else -> ConnectionProblem.Other(e.message ?: e.javaClass.simpleName)
    }

  companion object {
    private const val TAG = "HerdrConnection"
    const val BACKGROUND_GRACE_MS = 15_000L
    const val SESSION_CHECK_MS = 2_000L
    val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
  }
}
