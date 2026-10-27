package dev.artalex.herdr.e2e

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import dev.artalex.herdr.data.TelegramBotSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Connecting a Telegram bot in the settings, against real sshd: the app reaches Telegram from the machine, with curl,
 * here a curl of the test's own that answers as the Bot API would. Bot 1:good gets a message from Ann Lee after an
 * old one from someone else, 3:slow gets it a while later, 2:busy is polled by another program, any other token is
 * wrong.
 */
@OptIn(ExperimentalTestApi::class)
class TelegramE2eTest {
  @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK", "android.permission.POST_NOTIFICATIONS")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var dir: String
  private val container get() = ApplicationProvider.getApplicationContext<HerdrApp>().container
  private val run = System.currentTimeMillis().toString().takeLast(5)

  @Before
  fun seed() {
    fixture = HostFixture(E2eArgs.require())
    dir = fixture.exec("printf %s \"\$HOME/.cache/herdr-android-e2e/telegram-$run\"")
    fixture.exec("mkdir -p '$dir' && cat > '$dir/curl' && chmod +x '$dir/curl'", FAKE_CURL)
    fixture.connectApp(container)
    InstrumentationRegistry.getInstrumentation().runOnMainSync { container.telegram.set(null) }
    container.telegram.curl = "$dir/curl"
    // The token for agents goes to a folder of the test's own, never to the user's config folder on the machine.
    container.telegram.tokenDirOverride = "$dir/token"
  }

  @After
  fun cleanUp() {
    container.telegram.curl = "curl"
    container.telegram.tokenDirOverride = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { container.telegram.set(null) }
    if (::fixture.isInitialized) {
      if (::dir.isInitialized) fixture.exec("rm -rf '$dir'")
      fixture.close()
    }
  }

  @Test
  fun botIsConnectedWithTheChatThatWritesToIt() {
    launchApp(compose, "botIsConnectedWithTheChatThatWritesToIt") {
      openTelegramSetup()

      // A token Telegram doesn't know, then a bot whose messages another program gets.
      connect("1:wrong")
      compose.waitUntilExactlyOneExists(hasTestTag("telegramProblem") and hasText(text(R.string.telegram_bad_token)), 20_000)
      connect("2:busy")
      compose.waitUntilExactlyOneExists(hasTestTag("telegramProblem") and hasText(text(R.string.telegram_busy)), 20_000)

      // A good one: the chat is the one that wrote after the look, and it hears from the app at once.
      connect("1:good")
      compose.waitUntilExactlyOneExists(hasTestTag("telegramBot") and hasText(text(R.string.telegram_connected, "test_bot", "Ann Lee")), 20_000)
      Screens.take(compose, "19-settings-telegram")
      assertEquals(TelegramBotSettings("1:good", "test_bot", 42, "Ann Lee"), container.telegram.bot.value)
      val calls = fixture.exec("cat '$dir/calls'")
      assertTrue(calls, calls.contains("data-urlencode = \"chat_id=42\"\ndata-urlencode = \"text=${text(R.string.telegram_hello)}\""))

      // Disconnected, the bot is gone from the phone, and its token from the server, where a task left it.
      fixture.exec("mkdir -p '$dir/token' && printf %s 1:good > '$dir/token/telegram-bot-token'")
      compose.onNodeWithTag("disconnectTelegram").performScrollTo().performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("connectTelegram"), 5_000)
      assertNull(container.telegram.bot.value)
      Device.waitFor("the token gone from the server") { fixture.exec("ls -A '$dir/token'").isBlank() }
    }
  }

  @Test
  fun chatIsFoundWithTheAppInTheBackground() {
    val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(NotificationManager::class.java)
    launchApp(compose, "chatIsFoundWithTheAppInTheBackground") {
      openTelegramSetup()
      connect("3:slow")
      compose.waitUntilExactlyOneExists(hasText(text(R.string.telegram_waiting, "test_bot")), 20_000)
      Screens.take(compose, "19a-settings-telegram-waiting")
      // The user goes to Telegram to write to the bot, and the app to the background: it keeps going, and says so.
      Device.goHome(container)
      Device.waitFor("the app kept going") {
        manager.activeNotifications.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == text(R.string.telegram_waiting_notification) }
      }
      Device.waitFor("the bot connected in the background", timeoutMs = 60_000) { container.telegram.bot.value != null }
      assertEquals(TelegramBotSettings("3:slow", "test_bot", 42, "Ann Lee"), container.telegram.bot.value)
      Device.waitFor("the app let go") { manager.activeNotifications.none { it.notification.channelId == "transfers" } }
    }
  }

  private fun openTelegramSetup() {
    compose.waitUntilExactlyOneExists(hasTestTag("newAgent-${HostFixture.SERVER_ID}"), 30_000)
    compose.onNodeWithTag("openSettings").performClick()
    compose.onNodeWithTag("connectTelegram").performScrollTo().performClick()
    compose.waitUntilExactlyOneExists(hasTestTag("telegramToken"), 5_000)
  }

  private fun connect(token: String) {
    compose.onNodeWithTag("telegramToken").performTextReplacement(token)
    compose.waitUntilExactlyOneExists(hasTestTag("connectTelegramBot"), 5_000)
    compose.onNodeWithTag("connectTelegramBot").performClick()
  }

  private companion object {
    /**
     * curl standing in for the Bot API: keeps the config it gets on stdin in `calls` beside it, and answers. Bot 3:slow
     * gets its message 15 seconds into the wait, long enough for an app in the background to lose a network it doesn't
     * hold on to. A token on its command line fails the call.
     */
    val FAKE_CURL =
      """
      #!/bin/sh
      case "${'$'}*" in *bot*) echo "the token is on the command line: ${'$'}*" >&2; exit 2 ;; esac
      config=${'$'}(cat)
      printf '%s\n' "${'$'}config" >> "${'$'}(dirname "${'$'}0")/calls"
      case "${'$'}config" in
        *'/bot1:good/'*|*'/bot2:busy/'*|*'/bot3:slow/'*) ;;
        *) echo '{"ok":false,"error_code":401,"description":"Unauthorized"}'; exit 0 ;;
      esac
      case "${'$'}config" in
        *'/getMe"'*) echo '{"ok":true,"result":{"id":1,"is_bot":true,"first_name":"Test","username":"test_bot"}}' ;;
        *'/bot2:busy/getUpdates"'*) echo '{"ok":false,"error_code":409,"description":"Conflict: terminated by other getUpdates request"}' ;;
        *'"offset=-1"'*) echo '{"ok":true,"result":[{"update_id":5,"message":{"message_id":1,"chat":{"id":7,"type":"private","first_name":"Old"}}}]}' ;;
        *'"offset=6"'*)
          case "${'$'}config" in *'/bot3:slow/'*) sleep 15 ;; esac
          echo '{"ok":true,"result":[{"update_id":6,"message":{"message_id":2,"chat":{"id":42,"type":"private","first_name":"Ann","last_name":"Lee"}}}]}' ;;
        *'/getUpdates"'*) echo '{"ok":true,"result":[]}' ;;
        *'/sendMessage"'*) echo '{"ok":true,"result":{"message_id":3}}' ;;
      esac
      """
        .trimIndent() + "\n"
  }
}
