package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.TelegramChat
import dev.artalex.herdr.core.host.TelegramError
import dev.artalex.herdr.core.host.curlConfig
import dev.artalex.herdr.core.host.curlQuoted
import dev.artalex.herdr.core.host.firstChat
import dev.artalex.herdr.core.host.telegramResult
import dev.artalex.herdr.core.host.updateIds
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the phone tells curl on the host for the Telegram Bot API, and what it makes of Telegram's answers. */
class TelegramTest {
  @Test
  fun curlConfigHasTheAddressAndTheFieldsQuoted() {
    val config = curlConfig("123:abc", "sendMessage", mapOf("chat_id" to "42", "text" to "a \"quote\", a \\ and\ntwo lines\t"))
    assertEquals(
      """
      url = "https://api.telegram.org/bot123:abc/sendMessage"
      data-urlencode = "chat_id=42"
      data-urlencode = "text=a \"quote\", a \\ and\ntwo lines\t"
      """.trimIndent() + "\n",
      config,
    )
    assertEquals("\"\\r\"", curlQuoted("\r"))
  }

  @Test
  fun resultOfAnAnswerThatIsOk() {
    val result = telegramResult("getMe", """{"ok":true,"result":{"id":1,"is_bot":true,"username":"test_bot"}}""", "", 0)
    assertEquals("test_bot", result.jsonObject.getValue("username").jsonPrimitive.content)
  }

  @Test
  fun refusalsKeepTelegramsCodeAndWords() {
    val wrongToken = assertFailsWith<TelegramError> { telegramResult("getMe", """{"ok":false,"error_code":401,"description":"Unauthorized"}""", "", 0) }
    assertEquals(401, wrongToken.code)
    assertEquals("Unauthorized", wrongToken.description)
    val busy = assertFailsWith<TelegramError> { telegramResult("getUpdates", """{"ok":false,"error_code":409,"description":"Conflict: terminated by other getUpdates request"}""", "", 0) }
    assertEquals(409, busy.code)
  }

  @Test
  fun noAnswerTellsWhatCurlSaid() {
    val offline = assertFailsWith<IOException> { telegramResult("getMe", "", "curl: (6) Could not resolve host: api.telegram.org\n", 6) }
    assertEquals("Telegram getMe: curl: (6) Could not resolve host: api.telegram.org", offline.message)
    assertEquals("Telegram getMe: no answer, exit status 7", assertFailsWith<IOException> { telegramResult("getMe", " ", "", 7) }.message)
    assertEquals("Telegram getMe: not a Bot API answer: <html>", assertFailsWith<IOException> { telegramResult("getMe", "<html>", "", 0) }.message)
  }

  @Test
  fun chatOfTheFirstMessage() {
    val updates =
      Json.parseToJsonElement(
        """
        [
          {"update_id":5,"my_chat_member":{"chat":{"id":7,"type":"private","first_name":"Old"}}},
          {"update_id":6,"message":{"message_id":1,"chat":{"id":42,"type":"private","first_name":"Ann","last_name":"Lee","username":"ann"},"text":"hi"}},
          {"update_id":7,"message":{"message_id":2,"chat":{"id":-100,"type":"group","title":"Kitchen"},"text":"hi"}}
        ]
        """
      )
    assertEquals(listOf(5L, 6L, 7L), updateIds(updates))
    assertEquals(6L to TelegramChat(42, "Ann Lee"), firstChat(updates))
    assertEquals(TelegramChat(-100, "Kitchen"), firstChat(Json.parseToJsonElement("""[{"update_id":7,"message":{"chat":{"id":-100,"type":"group","title":"Kitchen"}}}]"""))?.second)
    assertEquals(TelegramChat(9, "@bob"), firstChat(Json.parseToJsonElement("""[{"update_id":8,"message":{"chat":{"id":9,"type":"private","username":"bob"}}}]"""))?.second)
    assertNull(firstChat(Json.parseToJsonElement("[]")))
  }
}
