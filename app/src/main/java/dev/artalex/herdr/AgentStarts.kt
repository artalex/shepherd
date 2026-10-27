package dev.artalex.herdr

import android.util.Log
import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.store.launchLacksProgram
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** What went wrong after the phone started an agent, for the agent screen to tell. */
sealed interface AgentStartProblem {
  /** Snippets that didn't reach the new agent: [late] if it wasn't ready in time, otherwise herdr or the connection failed. */
  data class SnippetsNotSent(val prompts: List<String>, val late: Boolean) : AgentStartProblem

  /** The pane's shell has no [executable], the program herdr typed: the agent didn't start. */
  data class ProgramMissing(val executable: String) : AgentStartProblem
}

/**
 * What follows an agent the phone started: whether its pane's shell could run it at all (see launchLacksProgram), then
 * its start snippets, sent once it is ready (see promptWhenReady). This goes on in the server's scope rather than a
 * screen's: the screen of a new session replaces the one that started it, and the user may leave before the agent is
 * ready. Waiting keeps the session polled. What went wrong comes out of [problems], for the agent screen to tell.
 */
class AgentStarts(private val server: Server) {
  private val _problems = Channel<AgentStartProblem>(Channel.BUFFERED)
  val problems: Flow<AgentStartProblem> = _problems.receiveAsFlow()

  /**
   * An agent of [kind] started in [paneId]; [prompts] go to it once it is ready. Completes once they went, with what
   * went wrong, null if nothing did. An agent started for a [task] has its task wait for that ([AgentTasks]), which
   * tells of snippets that didn't go itself; a missing program still comes out of [problems], for the agent screen to
   * say what fixes it.
   */
  fun started(paneId: String, kind: AgentKind, prompts: List<String>, task: Boolean = false): Deferred<AgentStartProblem?> =
    server.scope.async {
      if (lacksProgram(paneId, kind)) {
        Log.w(TAG, "the shell of $paneId has no ${kind.executable}: the agent didn't start")
        return@async AgentStartProblem.ProgramMissing(kind.executable).also { _problems.send(it) }
      }
      if (prompts.isEmpty()) return@async null
      sendWhenReady(paneId, prompts)?.also { if (!task) _problems.send(it) }
    }

  private suspend fun lacksProgram(paneId: String, kind: AgentKind): Boolean =
    try {
      launchLacksProgram(paneId, kind.executable, server.snapshots) {
        val api = server.host?.api ?: throw IOException("not connected")
        api.readPane(paneId, ansi = false).text
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Unknown, then: the snippets still wait for the agent.
      Log.w(TAG, "watching the start in $paneId failed: ${e.message}")
      false
    }

  private suspend fun sendWhenReady(paneId: String, prompts: List<String>): AgentStartProblem? =
    try {
      val left = server.promptAgentWhenReady(paneId, prompts, TIMEOUT_MS)
      if (left.isEmpty()) {
        null
      } else {
        Log.w(TAG, "agent in $paneId wasn't ready within $TIMEOUT_MS ms; ${left.size} start snippets not sent")
        AgentStartProblem.SnippetsNotSent(left, late = true)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "start snippets for $paneId failed: ${e.message}")
      AgentStartProblem.SnippetsNotSent(prompts, late = false)
    }

  companion object {
    private const val TAG = "HerdrAgentStarts"

    /** A new agent may first ask whether to trust its folder: time for the user to answer. */
    const val TIMEOUT_MS = 3 * 60_000L
  }
}
