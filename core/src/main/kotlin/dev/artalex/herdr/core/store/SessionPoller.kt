package dev.artalex.herdr.core.store

import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.rpc.HerdrApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SessionState {
  data object Loading : SessionState

  data class Ready(val snapshot: SessionSnapshot, val fetchedAtMs: Long) : SessionState

  /** The last poll failed; [last] keeps the previous snapshot so the UI can show it dimmed. */
  data class Failed(val error: Throwable, val last: Ready?) : SessionState
}

/** The latest snapshot there is, the one before a failed poll too; null before the first one. */
val SessionState.lastSnapshot: SessionSnapshot?
  get() =
    when (this) {
      SessionState.Loading -> null
      is SessionState.Ready -> snapshot
      is SessionState.Failed -> last?.snapshot
    }

/**
 * Polls `session.snapshot` while someone collects [state].
 *
 * herdr reports status changes only through per-pane subscriptions that the server itself polls every
 * 100 ms, so a 2 s snapshot poll (one ~7 KB request, ~0.5 ms of server time) is both simpler and cheaper.
 * Polling stops as soon as the UI stops collecting.
 */
class SessionPoller(
  private val api: HerdrApi,
  scope: CoroutineScope,
  private val periodMs: Long = DEFAULT_PERIOD_MS,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  val state: StateFlow<SessionState> =
    channelFlow {
        var last: SessionState.Ready? = null
        while (true) {
          val next =
            try {
              SessionState.Ready(api.snapshot(), clock()).also { last = it }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              SessionState.Failed(e, last)
            }
          send(next)
          withTimeoutOrNull(periodMs) { kicks.first() }
        }
      }
      .stateIn(scope, SharingStarted.WhileSubscribed(), SessionState.Loading)

  /** Polls right away, e.g. after the user answered an agent. */
  fun refreshNow() {
    kicks.tryEmit(Unit)
  }

  companion object {
    const val DEFAULT_PERIOD_MS = 2_000L
  }
}
