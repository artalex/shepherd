package dev.artalex.herdr

import android.app.Application
import android.net.Uri
import android.util.Log
import dev.artalex.herdr.core.host.saveTelegramToken
import dev.artalex.herdr.core.host.shellQuote
import dev.artalex.herdr.core.rpc.ApiError
import dev.artalex.herdr.core.store.taskPrompt
import dev.artalex.herdr.data.TelegramSettings
import dev.artalex.herdr.files.TransferService
import dev.artalex.herdr.notify.Alerts
import dev.artalex.herdr.ui.agents.paneTitle
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the user wrote for a new agent while sharing: the [text] of the task, and what came from the other app, the
 * [shared] text and the [files]; [telegram] if the answer goes there.
 */
data class AgentTask(val text: String, val shared: String?, val files: List<Uri>, val telegram: Boolean)

/** A task that didn't reach its agent: [reason] in words for the user, the message in English for the log. */
private class NotGiven(val reason: String, message: String) : Exception(message)

/**
 * Gives tasks to the agents the phone starts for them, in the background: the user shared something with the app,
 * wrote what to do with it, and went back to where they came from. The files go to the host while the agent starts;
 * once it is ready and has its start snippets ([AgentStarts]), the prompt goes: the task, what was shared and, for an
 * answer in Telegram, how to send it. Meanwhile the servers stay connected and [TransferService] keeps the app going;
 * [busy] counts the tasks for it. A task that doesn't get through waits in the agent's prompt, and a notification tells
 * why.
 */
class AgentTasks(private val app: Application, private val servers: Servers, private val telegram: TelegramSettings, private val alerts: Alerts, private val busy: MutableStateFlow<Int>) {
  /** Gives [task] to the agent just started in [paneId] of [server], once [started] tells how its start went. */
  fun give(server: Server, paneId: String, started: Deferred<AgentStartProblem?>, task: AgentTask) {
    // On the main thread, the count goes up at once, before the service looks at it; a server already gone counts nothing.
    server.scope.launch {
      busy.update { it + 1 }
      try {
        servers.keepConnected { send(server, paneId, started, task) }
      } finally {
        busy.update { it - 1 }
      }
    }
    // While the screen still shows: Android starts a foreground service from the background only at times.
    TransferService.start(app)
  }

  private suspend fun send(server: Server, paneId: String, started: Deferred<AgentStartProblem?>, task: AgentTask) {
    var prompt = taskPrompt(task.text, task.shared, emptyList())
    var inPrompt = true
    try {
      // The files go while the agent starts, which it has gone on doing since the dialog.
      val sent = server.files.uploadAll(task.files, server.originOf(paneId), forPane = paneId)
      val paths = sent.mapNotNull { it.path.getOrNull() }
      // A task that waits in the prompt asks for the answer in Telegram too, once the files that got there are in it.
      val note =
        try {
          Result.success(if (task.telegram) telegramNote(server) else null)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      prompt = taskPrompt(task.text, task.shared, paths, note.getOrNull())
      sent.firstOrNull { it.path.isFailure }?.let { end ->
        val error = end.path.exceptionOrNull()?.message.orEmpty()
        throw NotGiven(app.getString(R.string.task_file_not_sent, end.name, error), "${end.name} didn't get to the host: $error")
      }
      note.exceptionOrNull()?.let { throw NotGiven(app.getString(R.string.telegram_error, it.message ?: it.javaClass.simpleName), "saving the Telegram token failed: ${it.message}") }
      when (val problem = started.await()) {
        is AgentStartProblem.ProgramMissing -> {
          inPrompt = false
          throw NotGiven(app.getString(R.string.task_program_missing, problem.executable), "the pane's shell has no ${problem.executable}")
        }
        is AgentStartProblem.SnippetsNotSent -> if (problem.late) throw NotGiven(app.getString(R.string.task_not_ready), "the agent wasn't ready in time")
        null -> Unit
      }
      val left = server.promptAgentWhenReady(paneId, listOf(prompt), AgentStarts.TIMEOUT_MS)
      if (left.isNotEmpty()) throw NotGiven(app.getString(R.string.task_not_ready), "the agent wasn't ready in time")
      Log.i(TAG, "task given to $paneId")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "task for $paneId not given: ${e.message}")
      val reason =
        when (e) {
          is NotGiven -> e.reason
          is ApiError -> app.getString(R.string.error_herdr, e.code)
          else -> app.getString(R.string.error_connection, e.message ?: e.javaClass.simpleName)
        }
      // An agent that didn't start has no prompt field to show it: the task waits there for one started in the pane.
      server.drafts.of(paneId).putBack(prompt)
      val config = servers.config(server.id) ?: return
      val withServer = servers.configs.value.count { it.active } > 1
      alerts.taskNotGiven(config, withServer, paneId, server.paneOf(paneId)?.let(::paneTitle), reason, inPrompt)
    }
  }

  /**
   * How the agent sends its answer to Telegram: the bot's token goes into a file on the host, which only the user may
   * read, and the note names the file rather than the token, so that the token is in no prompt, screen or history.
   * Null if the bot was taken off meanwhile.
   */
  private suspend fun telegramNote(server: Server): String? {
    val bot = telegram.bot.value ?: return null
    val host = server.host ?: throw IOException("not connected")
    val file = host.transport.saveTelegramToken(bot.token, telegram.tokenDirOverride)
    return app.getString(R.string.task_telegram_note, shellQuote(listOf(file)), bot.chatId)
  }

  private companion object {
    const val TAG = "HerdrTasks"
  }
}
