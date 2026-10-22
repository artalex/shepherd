package dev.artalex.herdr

import android.util.Log
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.store.promptWhenReady
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Snippets that didn't reach a new agent: [late] if it wasn't ready in time, otherwise herdr or the connection failed. */
data class StartPromptsFailure(val prompts: List<String>, val late: Boolean)

/**
 * Snippets for an agent the phone started, sent once the agent is ready for them (see promptWhenReady). They go from
 * the server's scope rather than a screen's: the screen of a new session replaces the one that started it, and the user
 * may leave before the agent is ready. Waiting keeps the session polled. What didn't go comes out of [failures], for
 * the agent screen to tell.
 */
class StartPrompts(private val server: Server) {
  private val _failures = Channel<StartPromptsFailure>(Channel.BUFFERED)
  val failures: Flow<StartPromptsFailure> = _failures.receiveAsFlow()

  fun send(paneId: String, prompts: List<String>) {
    if (prompts.isEmpty()) return
    server.scope.launch {
      val snapshots = server.sessions.state.mapNotNull { (it as? SessionState.Ready)?.snapshot }
      try {
        val left =
          promptWhenReady(paneId, prompts, snapshots, TIMEOUT_MS) { text ->
            val api = server.host?.api ?: throw IOException("not connected")
            api.promptAgent(paneId, text)
          }
        if (left.isNotEmpty()) {
          Log.w(TAG, "agent in $paneId wasn't ready within $TIMEOUT_MS ms; ${left.size} start snippets not sent")
          _failures.send(StartPromptsFailure(left, late = true))
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "start snippets for $paneId failed: ${e.message}")
        _failures.send(StartPromptsFailure(prompts, late = false))
      }
    }
  }

  private companion object {
    const val TAG = "HerdrStartPrompts"

    /** A new agent may first ask whether to trust its folder: time for the user to answer. */
    const val TIMEOUT_MS = 3 * 60_000L
  }
}
