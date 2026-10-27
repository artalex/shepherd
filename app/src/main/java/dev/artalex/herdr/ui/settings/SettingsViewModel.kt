package dev.artalex.herdr.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.artalex.herdr.AppContainer
import dev.artalex.herdr.R
import dev.artalex.herdr.core.host.CurlMissing
import dev.artalex.herdr.core.host.TelegramBotApi
import dev.artalex.herdr.core.host.TelegramError
import dev.artalex.herdr.core.host.removeTelegramToken
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.ssh.SshKeyPair
import dev.artalex.herdr.data.BackgroundCheck
import dev.artalex.herdr.data.TelegramBotSettings
import dev.artalex.herdr.files.TransferService
import dev.artalex.herdr.ui.agent.UiMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where connecting a Telegram bot is, while its dialog is open. */
sealed interface TelegramSetup {
  /** The user types the bot's token; [problem] is why the last try failed. */
  data class Token(val problem: UiMessage? = null) : TelegramSetup

  /** The token is being checked. */
  data object Checking : TelegramSetup

  /** The token is good: the app waits for the user's message to @[bot], to know their chat. */
  data class Waiting(val bot: String) : TelegramSetup
}

/**
 * The settings and the server form: the servers, the notifications, the Telegram bot and the phone's key, the same for
 * every server.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
  val servers: StateFlow<List<ServerConfig>> = container.servers.configs

  val notificationsOn: StateFlow<Boolean> = container.notifications.enabled
  val watchMinutes: StateFlow<Int> = container.notifications.watchMinutes
  val lastCheck: StateFlow<BackgroundCheck?> = container.notifications.lastCheck

  /** The Telegram bot agents answer through, null for none. */
  val telegramBot: StateFlow<TelegramBotSettings?> = container.telegram.bot

  private val _telegramSetup = MutableStateFlow<TelegramSetup?>(null)

  /** The dialog that connects a bot, while it is open. */
  val telegramSetup: StateFlow<TelegramSetup?> = _telegramSetup.asStateFlow()

  private var connecting: Job? = null

  private val _key = MutableStateFlow(container.keys.load())
  val key: StateFlow<SshKeyPair?> = _key.asStateFlow()

  fun server(id: String): ServerConfig? = container.servers.config(id)

  fun generateKey() {
    viewModelScope.launch { _key.value = withContext(Dispatchers.Default) { container.keys.generate() } }
  }

  /** A new server comes switched on and starts connecting; an edited one reconnects if it moved. */
  fun save(server: ServerConfig) = container.servers.save(server)

  fun delete(id: String) = container.servers.delete(id)

  fun setNotifications(on: Boolean) = container.notifications.setEnabled(on)

  fun setWatchMinutes(minutes: Int) = container.notifications.setWatchMinutes(minutes)

  fun openTelegramSetup() {
    _telegramSetup.value = TelegramSetup.Token()
  }

  /** Closes the dialog; a check or a wait for the user's message under way stops. */
  fun closeTelegramSetup() {
    connecting?.cancel()
    _telegramSetup.value = null
  }

  /**
   * Connects the bot with [token]: checks it, then takes the chat of the first message the user sends the bot, and
   * says [hello] there, which shows the way to the chat works. Telegram is reached from the first server connected:
   * the phone itself talks only to its servers. The user writes to the bot in Telegram, likely on this phone, with the
   * app in the background: meanwhile it keeps going, and its servers connected.
   */
  fun connectTelegram(token: String, hello: String) {
    connecting?.cancel()
    connecting =
      viewModelScope.launch {
        container.telegramWaits.update { it + 1 }
        TransferService.start(container.app)
        try {
          container.servers.keepConnected { connect(token.trim(), hello) }
        } finally {
          container.telegramWaits.update { it - 1 }
        }
      }
  }

  private suspend fun connect(token: String, hello: String) {
    val host = container.servers.configs.value.filter { it.active }.firstNotNullOfOrNull { container.servers[it.id]?.host }
    if (host == null) {
      _telegramSetup.value = TelegramSetup.Token(UiMessage(R.string.telegram_no_server))
      return
    }
    val bot = TelegramBotApi(host.transport, token, container.telegram.curl)
    try {
      _telegramSetup.value = TelegramSetup.Checking
      val name = bot.me().username
      _telegramSetup.value = TelegramSetup.Waiting(name)
      val chat = bot.nextChat(CHAT_WAIT_MS)
      if (chat == null) {
        _telegramSetup.value = TelegramSetup.Token(UiMessage(R.string.telegram_no_message))
        return
      }
      bot.send(chat.id, hello)
      container.telegram.set(TelegramBotSettings(token, name, chat.id, chat.name))
      _telegramSetup.value = null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "connecting the Telegram bot failed: ${e.message}")
      _telegramSetup.value = TelegramSetup.Token(problemOf(e))
    }
  }

  /**
   * Takes the bot off the phone, and its token off the servers connected now, where tasks left it for their agents. It
   * goes on in the app's scope, past this screen.
   */
  fun disconnectTelegram() {
    container.telegram.set(null)
    val hosts = container.servers.configs.value.filter { it.active }.mapNotNull { container.servers[it.id]?.host }
    container.scope.launch {
      for (host in hosts) {
        try {
          host.transport.removeTelegramToken(container.telegram.tokenDirOverride)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "taking the Telegram token off ${host.session} failed: ${e.message}")
        }
      }
    }
  }

  private fun problemOf(e: Exception): UiMessage =
    when {
      // Telegram answers 404 to a token that isn't one at all.
      e is TelegramError && (e.code == 401 || e.code == 404) -> UiMessage(R.string.telegram_bad_token)
      e is TelegramError && e.code == 409 -> UiMessage(R.string.telegram_busy)
      e is TelegramError -> UiMessage(R.string.telegram_error, e.description)
      e is CurlMissing -> UiMessage(R.string.telegram_no_curl)
      else -> UiMessage(R.string.telegram_error, e.message ?: e.javaClass.simpleName)
    }

  private companion object {
    const val TAG = "HerdrSettings"

    /** How long the user has to send the bot a message. */
    const val CHAT_WAIT_MS = 2 * 60_000L
  }
}
