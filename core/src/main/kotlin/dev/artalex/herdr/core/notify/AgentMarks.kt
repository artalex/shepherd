package dev.artalex.herdr.core.notify

import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.SessionSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * An agent as the notifications last saw it: its status, herdr's count of its status changes and the change on which
 * it last finished work. The count tells that a status was left and came back between two looks, as with a new
 * question after the answer to the last one. [completion] has no default: marks saved before it existed don't read,
 * and the look after the update is a first one rather than news of every agent that finished before it.
 */
@Serializable data class AgentMark(val status: AgentStatus, val seq: Long, val completion: Long?)

/** The agents of the snapshot by pane id. */
fun SessionSnapshot.agentMarks(): Map<String, AgentMark> =
  agents.associate { it.paneId to AgentMark(it.agentStatus, it.stateChangeSeq, it.completionSeq) }

/**
 * Agents with news since [before], by pane id: [AgentStatus.BLOCKED] for one that asks something, new, after another
 * status or after leaving it and coming back; [AgentStatus.DONE] for one that finished work. A finish counts whatever
 * the status: herdr says `done` only if nobody saw the pane, and a pane in the active tab counts as seen even with no
 * desktop attached, so its agent finishes `idle`. A first look at a server ([before] is null) is no news.
 */
fun notableChanges(before: Map<String, AgentMark>?, after: Map<String, AgentMark>): Map<String, AgentStatus> {
  if (before == null) return emptyMap()
  return buildMap {
    for ((pane, mark) in after) {
      val was = before[pane]
      when {
        mark.status == AgentStatus.BLOCKED && was != mark -> put(pane, AgentStatus.BLOCKED)
        mark.completion != null && mark.completion != was?.completion -> put(pane, AgentStatus.DONE)
      }
    }
  }
}

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
