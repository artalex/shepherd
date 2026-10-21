package dev.artalex.herdr.connection

import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.core.store.SessionPoller
import dev.artalex.herdr.core.store.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * The herdr session as the screens see it: snapshots of the connected host, polled only while some
 * screen (or the agent watch) collects [state]. A reconnect (new [HerdrHost]) starts a fresh poller.
 * Every snapshot also goes to [onSnapshot]: the notifications keep track of the agents with it. A failed poll goes to
 * the connection, which checks whether the herdr server has stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepository(connection: ConnectionController, scope: CoroutineScope, onSnapshot: (SessionState.Ready) -> Unit) {
  @Volatile private var poller: SessionPoller? = null

  val state: StateFlow<SessionState> =
    connection.state
      .map { (it as? ConnectionState.Connected)?.host }
      .distinctUntilChanged()
      .flatMapLatest { host ->
        if (host == null) flowOf(SessionState.Loading)
        else SessionPoller(host.api, scope).also { poller = it }.state
      }
      .onEach {
        if (it is SessionState.Ready) onSnapshot(it)
        if (it is SessionState.Failed) connection.apiFailed()
      }
      .stateIn(scope, SharingStarted.WhileSubscribed(), SessionState.Loading)

  /** Polls right away, e.g. after the user answered an agent. */
  fun refreshNow() {
    poller?.refreshNow()
  }
}
