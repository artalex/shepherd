package dev.artalex.herdr.core.host

import dev.artalex.herdr.core.rpc.blockingIoWithDeadline
import dev.artalex.herdr.core.transport.HerdrTransport
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A Telegram bot, by its @[username]. */
data class TelegramBot(val username: String)

/** A chat a bot writes to: its [id], and a [name] to show, a person's name or a group's title. */
data class TelegramChat(val id: Long, val name: String)

/**
 * Telegram refused [method]: [code] is its error_code, such as 401 for a wrong token, or 409 for a bot whose updates
 * another program gets, or one with a webhook; [description] is Telegram's own.
 */
class TelegramError(val method: String, val code: Int?, val description: String) : IOException("Telegram $method failed: ${code ?: "no code"} $description")

/** The host has no curl, which reaches Telegram for the phone. */
class CurlMissing : IOException("the host has no curl")

/**
 * The Telegram Bot API of the bot with [token], reached from the host with [curl]: the phone itself talks only to its
 * servers. The address with the token and the parameters go to curl's config on its stdin, so they are on no command
 * line, which any user of the host may read.
 */
class TelegramBotApi(private val transport: HerdrTransport, private val token: String, private val curl: String = "curl") {
  /** The bot of the token; a wrong token is a [TelegramError] with code 401. */
  suspend fun me(): TelegramBot =
    TelegramBot(call("getMe").jsonObject["username"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Telegram getMe gave no username"))

  /**
   * The chat of the first message to the bot from now on, waiting [waitMs] at most; null if none came. Messages that
   * came before are left out: the chat of one of them could be anyone's. Telegram forgets what came up to the message.
   */
  suspend fun nextChat(waitMs: Long, pollSeconds: Int = POLL_SECONDS): TelegramChat? {
    // A negative offset gives the latest update, if any, and forgets those before it.
    var last = updateIds(call("getUpdates", mapOf("offset" to "-1", "timeout" to "0"))).lastOrNull()
    val deadline = System.currentTimeMillis() + waitMs
    while (true) {
      val left = deadline - System.currentTimeMillis()
      if (left <= 0) return null
      val seconds = minOf(pollSeconds.toLong(), (left + 999) / 1000).toInt()
      val updates = call("getUpdates", buildMap { last?.let { put("offset", "${it + 1}") }; put("timeout", "$seconds") }, timeoutMs = (seconds + POLL_SLACK_SECONDS) * 1000L)
      last = updateIds(updates).lastOrNull() ?: last
      val found = firstChat(updates) ?: continue
      call("getUpdates", mapOf("offset" to "${found.first + 1}", "timeout" to "0"))
      return found.second
    }
  }

  /** Sends [text] to chat [chatId]. */
  suspend fun send(chatId: Long, text: String) {
    call("sendMessage", mapOf("chat_id" to "$chatId", "text" to text))
  }

  private suspend fun call(method: String, params: Map<String, String> = emptyMap(), timeoutMs: Long = CALL_TIMEOUT_MS): JsonElement {
    val stream = withContext(Dispatchers.IO) { transport.exec(listOf(curl, "-sS", "--max-time", "${timeoutMs / 1000}", "-K", "-")) }
    val (out, exitStatus) =
      blockingIoWithDeadline(stream, timeoutMs + DEADLINE_SLACK_MS, "Telegram $method") {
        // The end of stdin ends curl's config.
        stream.output.use { it.write(curlConfig(token, method, params).toByteArray()) }
        stream.input.readAllBytes() to stream.exitStatus()
      }
    if (exitStatus == 127) throw CurlMissing()
    // Neither should hold the token, but an error message may go anywhere.
    return telegramResult(method, out.toString(Charsets.UTF_8).replace(token, "<token>"), stream.stderr.replace(token, "<token>"), exitStatus)
  }

  private companion object {
    const val CALL_TIMEOUT_MS = 20_000L
    const val DEADLINE_SLACK_MS = 5_000L

    /** How long one getUpdates waits for a message, and what curl gets on top of that. */
    const val POLL_SECONDS = 25
    const val POLL_SLACK_SECONDS = 10
  }
}

/**
 * Puts [token] where agents on the host find it, in `telegram-bot-token` of [dir] (by default `herdr-android` in the
 * user's config folder), readable by the user alone, and returns its path. The token goes on stdin, not on a command
 * line, and replaces an earlier one whole.
 */
suspend fun HerdrTransport.saveTelegramToken(token: String, dir: String? = null, timeoutMs: Long = 10_000): String {
  val script =
    "$TOKEN_FILE; umask 077; " +
      "mkdir -p -- \"${'$'}d\" && rm -f -- \"${'$'}f.part\" && cat > \"${'$'}f.part\" && mv -f -- \"${'$'}f.part\" \"${'$'}f\" && printf '%s' \"${'$'}f\""
  val stream = withContext(Dispatchers.IO) { exec(listOf("sh", "-c", script, "sh") + listOfNotNull(dir)) }
  val (out, exitStatus) =
    blockingIoWithDeadline(stream, timeoutMs, "saving the Telegram token") {
      stream.output.use { it.write(token.toByteArray()) }
      stream.input.readAllBytes() to stream.exitStatus()
    }
  return out.toString(Charsets.UTF_8).takeIf { exitStatus == 0 && it.startsWith("/") }
    ?: throw IOException("saving the Telegram token failed, exit status ${exitStatus ?: "unknown"}: ${stream.stderr.trim()}")
}

/** Takes the token [saveTelegramToken] put in [dir] off the host: the bot is no longer the user's way to get answers. */
suspend fun HerdrTransport.removeTelegramToken(dir: String? = null, timeoutMs: Long = 10_000) {
  val stream = withContext(Dispatchers.IO) { exec(listOf("sh", "-c", "$TOKEN_FILE; rm -f -- \"${'$'}f\" \"${'$'}f.part\"", "sh") + listOfNotNull(dir)) }
  val exitStatus =
    blockingIoWithDeadline(stream, timeoutMs, "removing the Telegram token") {
      stream.input.readAllBytes()
      stream.exitStatus()
    }
  if (exitStatus != 0) throw IOException("removing the Telegram token failed, exit status ${exitStatus ?: "unknown"}: ${stream.stderr.trim()}")
}

/** Shell that names the token's folder `d`, the one given as `$1` or else `herdr-android` in the config folder, and its file `f`. */
private val TOKEN_FILE = "d=${'$'}{1:-${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}/herdr-android}; f=${'$'}d/telegram-bot-token"

/** curl's config to call [method] of the bot with [token]: its address, then each of [params] as a form field. */
internal fun curlConfig(token: String, method: String, params: Map<String, String>): String =
  buildString {
    appendLine("url = ${curlQuoted("https://api.telegram.org/bot$token/$method")}")
    for ((name, value) in params) appendLine("data-urlencode = ${curlQuoted("$name=$value")}")
  }

/** [value] in double quotes as curl's config reads it: backslashes, quotes, line breaks and tabs escaped. */
internal fun curlQuoted(value: String): String =
  "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

/** The `result` of Telegram's answer to [method] that curl printed, or why there is none. */
internal fun telegramResult(method: String, stdout: String, stderr: String, exitStatus: Int?): JsonElement {
  val text = stdout.trim()
  if (text.isEmpty()) throw IOException("Telegram $method: ${stderr.trim().ifEmpty { "no answer, exit status ${exitStatus ?: "unknown"}" }}")
  val answer = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw IOException("Telegram $method: not a Bot API answer: ${text.take(200)}")
  if (answer["ok"]?.jsonPrimitive?.booleanOrNull == true) return answer["result"] ?: throw IOException("Telegram $method gave no result")
  throw TelegramError(method, answer["error_code"]?.jsonPrimitive?.intOrNull, answer["description"]?.jsonPrimitive?.contentOrNull ?: "no description")
}

/** The ids of the updates in an answer of getUpdates. */
internal fun updateIds(updates: JsonElement): List<Long> = updates.jsonArray.mapNotNull { it.jsonObject["update_id"]?.jsonPrimitive?.longOrNull }

/** The first update with a message in an answer of getUpdates: its id, and the chat of the message. */
internal fun firstChat(updates: JsonElement): Pair<Long, TelegramChat>? =
  updates.jsonArray.firstNotNullOfOrNull { update ->
    val id = update.jsonObject["update_id"]?.jsonPrimitive?.longOrNull
    val chat = update.jsonObject["message"]?.jsonObject?.get("chat")?.jsonObject
    val chatId = chat?.get("id")?.jsonPrimitive?.longOrNull
    if (id == null || chat == null || chatId == null) null else id to TelegramChat(chatId, chatName(chat))
  }

/** A group's title, or a person's name, or their @username. */
private fun chatName(chat: JsonObject): String {
  fun field(name: String) = chat[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
  return field("title") ?: listOfNotNull(field("first_name"), field("last_name")).joinToString(" ").ifEmpty { null } ?: field("username")?.let { "@$it" } ?: "${chat["id"]}"
}
