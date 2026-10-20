package dev.artalex.herdr.core.terminal

import dev.artalex.herdr.core.proto.readNdjsonLine
import dev.artalex.herdr.core.proto.writeNdjsonLine
import dev.artalex.herdr.core.rpc.herdrJson
import dev.artalex.herdr.core.transport.ByteStream
import java.io.IOException
import java.util.Base64
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

sealed interface TerminalEvent {
  /** ANSI bytes to feed into a terminal emulator; [full] frames repaint the whole screen. */
  class Frame(val seq: Long, val width: Int, val height: Int, val full: Boolean, val bytes: ByteArray) : TerminalEvent

  data class Closed(val reason: CloseReason) : TerminalEvent
}

/** Why herdr ended a control stream, classified from `terminal.closed.reason`. */
sealed interface CloseReason {
  /** We released the terminal or closed stdin. */
  data object Detached : CloseReason

  /** Another client took the terminal over with `--takeover`. */
  data object TakenOver : CloseReason

  /** Someone else controls the terminal; retry with takeover. */
  data object NeedsTakeover : CloseReason

  /** The pane or terminal no longer exists. */
  data object NotFound : CloseReason

  /** The server is restarting for an update. */
  data object ServerUpdating : CloseReason

  /** The stream ended without a `terminal.closed` record (server exit or stalled connection). */
  data object Ended : CloseReason

  data class Other(val text: String) : CloseReason

  companion object {
    fun classify(reason: String?): CloseReason =
      when {
        reason == null -> Other("")
        reason == "detached" -> Detached
        reason.contains("taken over") -> TakenOver
        reason.contains("retry with --takeover") -> NeedsTakeover
        reason.contains("not found") -> NotFound
        reason.contains("live update in progress") -> ServerUpdating
        else -> Other(reason)
      }
  }
}

/**
 * Builds the argv for `herdr terminal session control`; pass it to [dev.artalex.herdr.core.transport.HerdrTransport.exec].
 *
 * While a control stream is open the pane's PTY takes [cols]×[rows]; herdr restores the desktop
 * geometry after release.
 */
fun terminalControlArgv(herdrBinary: String, session: String, terminalId: String, cols: Int, rows: Int, takeover: Boolean): List<String> =
  buildList {
    addAll(listOf(herdrBinary, "--session", session, "terminal", "session", "control", terminalId))
    if (takeover) add("--takeover")
    addAll(listOf("--cols", cols.toString(), "--rows", rows.toString()))
  }

/** A writable live terminal stream over the stdio of `herdr terminal session control`. */
class TerminalSession(private val stream: ByteStream) {
  private val writeLock = Mutex()

  /**
   * Frames in order, ending with exactly one [TerminalEvent.Closed]. Collect once.
   *
   * Cancelling the collector closes the stream: a blocking read on a pipe or an SSH channel ignores
   * coroutine cancellation, and only closing the stream lets the reader thread finish.
   */
  val events: Flow<TerminalEvent> = channelFlow {
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        awaitCancellation()
      } finally {
        stream.close()
      }
    }
    withContext(Dispatchers.IO) {
      val input = stream.input.buffered()
      while (true) {
        val line =
          try {
            input.readNdjsonLine()
          } catch (e: IOException) {
            null
          }
        if (line == null) {
          send(TerminalEvent.Closed(CloseReason.Ended))
          break
        }
        val event = parseTerminalLine(line) ?: continue
        send(event)
        if (event is TerminalEvent.Closed) break
      }
    }
    closer.cancel()
  }

  suspend fun sendText(text: String) = send(buildJsonObject { put("type", "terminal.input"); put("text", text) })

  suspend fun sendBytes(bytes: ByteArray) =
    send(buildJsonObject { put("type", "terminal.input"); put("bytes", Base64.getEncoder().encodeToString(bytes)) })

  /**
   * Pastes [text] as one bracketed paste. herdr takes the brackets off and puts them back only for an app that asked
   * for them, so a shell or an agent gets pasted lines as text instead of running them one by one. Escape characters
   * are dropped: one in the text could end the paste early and run the rest as typed keys.
   */
  suspend fun paste(text: String) = sendText(BRACKETED_PASTE_START + text.replace("\u001b", "") + BRACKETED_PASTE_END)

  suspend fun resize(cols: Int, rows: Int) =
    send(buildJsonObject { put("type", "terminal.resize"); put("cols", cols); put("rows", rows) })

  /**
   * Scrolls like the desktop's mouse wheel, [lines] up into history or, when negative, back down: herdr moves through
   * its own scrollback, or hands the wheel to an app that asked for the mouse. Typing returns to the bottom.
   */
  suspend fun scroll(lines: Int) {
    require(lines != 0) { "nothing to scroll" }
    send(buildJsonObject { put("type", "terminal.scroll"); put("direction", if (lines > 0) "up" else "down"); put("lines", abs(lines)) })
  }

  /** Gives the terminal back; herdr then closes the stream with `detached`. */
  suspend fun release() = send(buildJsonObject { put("type", "terminal.release") })

  fun close() = stream.close()

  private suspend fun send(command: JsonObject) =
    writeLock.withLock { withContext(Dispatchers.IO) { stream.output.writeNdjsonLine(command.toString()) } }
}

private const val BRACKETED_PASTE_START = "\u001b[200~"
private const val BRACKETED_PASTE_END = "\u001b[201~"

internal fun parseTerminalLine(line: String): TerminalEvent? {
  val obj = herdrJson.parseToJsonElement(line).jsonObject
  return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
    "terminal.frame" ->
      TerminalEvent.Frame(
        seq = obj.getValue("seq").jsonPrimitive.long,
        width = obj.getValue("width").jsonPrimitive.int,
        height = obj.getValue("height").jsonPrimitive.int,
        full = obj.getValue("full").jsonPrimitive.boolean,
        bytes = Base64.getDecoder().decode(obj.getValue("bytes").jsonPrimitive.content),
      )
    "terminal.closed" -> TerminalEvent.Closed(CloseReason.classify(obj["reason"]?.jsonPrimitive?.contentOrNull))
    else -> null
  }
}
