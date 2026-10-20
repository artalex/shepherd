package dev.artalex.herdr.core.notify

import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.SessionSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * An agent as the notifications last saw it: its status and herdr's count of its status changes. The count tells
 * that a status was left and came back between two looks, as with a new question after the answer to the last one.
 */
@Serializable data class AgentMark(val status: AgentStatus, val seq: Long)

/** The agents of the snapshot by pane id. */
fun SessionSnapshot.agentMarks(): Map<String, AgentMark> = agents.associate { it.paneId to AgentMark(it.agentStatus, it.stateChangeSeq) }

/**
 * A status worth a notification: the agent asks something, or it finished and nobody has looked at it yet (herdr
 * turns `done` into `idle` once the pane is seen on the desktop).
 */
val AgentStatus.isNotable: Boolean get() = this == AgentStatus.BLOCKED || this == AgentStatus.DONE

/**
 * Agents that came to a notable status since [before], by pane id: new ones, ones that weren't in that status, and
 * ones that left it and came back. A first look at a server ([before] is null) is no news.
 */
fun notableChanges(before: Map<String, AgentMark>?, after: Map<String, AgentMark>): Map<String, AgentStatus> =
  if (before == null) emptyMap() else after.filter { (pane, mark) -> mark.status.isNotable && before[pane] != mark }.mapValues { it.value.status }

/** Marks as the phone keeps them between app runs. */
object AgentMarks {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = MapSerializer(String.serializer(), AgentMark.serializer())

  fun encode(marks: Map<String, AgentMark>): String = json.encodeToString(serializer, marks)

  /** The marks saved by [encode]; null for missing or unreadable text, which makes the next look a first one. */
  fun decode(text: String?): Map<String, AgentMark>? =
    try {
      if (text.isNullOrBlank()) null else json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
      null
    } catch (e: IllegalArgumentException) {
      null
    }
}
