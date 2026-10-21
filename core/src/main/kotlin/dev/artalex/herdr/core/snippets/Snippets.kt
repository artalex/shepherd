package dev.artalex.herdr.core.snippets

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Texts the user keeps for the prompt, such as `/effort max`, in their order, as the phone stores them. */
object SnippetList {
  private val serializer = ListSerializer(String.serializer())

  fun encode(snippets: List<String>): String = Json.encodeToString(serializer, snippets)

  /** The snippets saved by [encode]; none for missing or unreadable text. */
  fun decode(text: String?): List<String> =
    try {
      if (text.isNullOrBlank()) emptyList() else Json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
      emptyList()
    } catch (e: IllegalArgumentException) {
      emptyList()
    }
}

/** [text] added at the end, without the spaces around it; a blank text or one already on the list changes nothing. */
fun List<String>.withSnippet(text: String): List<String> = (this + text).tidy()

/** The list as an edit leaves it: each text without the spaces around it, blank ones and repeats gone. */
fun List<String>.tidy(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct()
