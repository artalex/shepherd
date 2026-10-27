package dev.artalex.herdr.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's Telegram bot: its [token], its @[bot] name, and the chat it writes to, [chatId] titled [chat]. */
data class TelegramBotSettings(val token: String, val bot: String, val chatId: Long, val chat: String) {
  // The token stays out of anything that prints the settings.
  override fun toString() = "TelegramBotSettings(bot=$bot, chatId=$chatId, chat=$chat)"
}

/**
 * The Telegram bot that agents send their answers through, when a task asks for it; set up in the settings, where the
 * app finds the user's chat with it. It is kept on the phone, without backups, as every setting; a task that asks for
 * its answer in Telegram puts the token on its server too, for the agent. Also whether the last task asked so.
 */
class TelegramSettings(context: Context) {
  private val prefs = context.getSharedPreferences("telegram", Context.MODE_PRIVATE)

  private val _bot = MutableStateFlow(read())

  /** The bot set up, null for none: then a task can't ask for its answer in Telegram. */
  val bot: StateFlow<TelegramBotSettings?> = _bot.asStateFlow()

  private val _answerThere = MutableStateFlow(prefs.getBoolean(ANSWER_THERE, false))

  /** The last task asked for its answer in Telegram: the next one starts out so too. */
  val answerThere: StateFlow<Boolean> = _answerThere.asStateFlow()

  /** Tests send the bot's calls to a Telegram of their own, through a curl of their own on the host. */
  @Volatile var curl = "curl"

  /** Tests keep the token for agents in a folder of their own on the host, instead of the user's config folder. */
  @Volatile var tokenDirOverride: String? = null

  fun set(bot: TelegramBotSettings?) {
    prefs.edit {
      if (bot == null) {
        remove(TOKEN)
        remove(BOT)
        remove(CHAT_ID)
        remove(CHAT)
      } else {
        putString(TOKEN, bot.token)
        putString(BOT, bot.bot)
        putLong(CHAT_ID, bot.chatId)
        putString(CHAT, bot.chat)
      }
    }
    _bot.value = bot
  }

  fun setAnswerThere(on: Boolean) {
    prefs.edit { putBoolean(ANSWER_THERE, on) }
    _answerThere.value = on
  }

  private fun read(): TelegramBotSettings? {
    val token = prefs.getString(TOKEN, null) ?: return null
    if (!prefs.contains(CHAT_ID)) return null
    return TelegramBotSettings(token, prefs.getString(BOT, null).orEmpty(), prefs.getLong(CHAT_ID, 0), prefs.getString(CHAT, null).orEmpty())
  }

  private companion object {
    const val TOKEN = "token"
    const val BOT = "bot"
    const val CHAT_ID = "chat_id"
    const val CHAT = "chat"
    const val ANSWER_THERE = "answer_there"
  }
}
