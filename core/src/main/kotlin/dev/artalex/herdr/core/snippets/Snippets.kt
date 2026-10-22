package dev.artalex.herdr.core.snippets

import dev.artalex.herdr.core.model.AgentKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * A text the user keeps for the prompt, such as `/effort max`. [onStart] is the kind of agent ([AgentKind.kind]) it
 * also goes to by itself when the phone starts a session of that agent.
 */
@Serializable
data class Snippet(val text: String, val onStart: String? = null)

/** The snippets in their order, as the phone stores them. */
object SnippetList {
  // As for the servers: defaults written out, fields of a later version skipped.
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val serializer = ListSerializer(Snippet.serializer())

  fun encode(snippets: List<Snippet>): String = json.encodeToString(serializer, snippets)

  /** The snippets saved by [encode], or as plain texts by 2.7 to 2.9; none for missing or unreadable text. */
  fun decode(text: String?): List<Snippet> =
    try {
      if (text.isNullOrBlank()) emptyList()
      else json.parseToJsonElement(text).jsonArray.map { if (it is JsonPrimitive) Snippet(it.content) else json.decodeFromJsonElement(Snippet.serializer(), it) }
    } catch (e: SerializationException) {
      emptyList()
    } catch (e: IllegalArgumentException) {
      emptyList()
    }
}

/** [text] added at the end, without the spaces around it; a blank text or one already on the list changes nothing. */
fun List<Snippet>.withSnippet(text: String): List<Snippet> = (this + Snippet(text)).tidy()

/** The list as an edit leaves it: each text without the spaces around it, blank ones and repeats gone. */
fun List<Snippet>.tidy(): List<Snippet> = map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }.distinctBy { it.text }

/** The texts that go to a [kind] agent the phone starts, in the list's order. */
fun List<Snippet>.onStart(kind: AgentKind): List<String> = filter { it.onStart == kind.kind }.map { it.text }
