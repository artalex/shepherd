package dev.artalex.herdr

import dev.artalex.herdr.core.model.SessionSnapshot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Something for a pane's prompt from outside its screen. */
sealed interface PromptPart {
  /** Text shared from another app. */
  data class Text(val text: String) : PromptPart

  /** The path of a shared file, now on the host. */
  data class Path(val path: String) : PromptPart

  /** A shared file named [name] that didn't get to the host, and why. */
  data class Failed(val name: String, val error: String) : PromptPart
}

/**
 * What goes into the prompts of a server's panes from outside their screens: text shared from another app and the
 * paths of the files shared with it, as they reach the host. The agent screen of a pane takes its parts as they come;
 * until the screen shows, they wait. What waits for a closed pane goes with it: its id may come back as a new pane.
 * Main thread only.
 */
class PromptInbox {
  private val boxes = mutableMapOf<String, Box>()

  fun put(paneId: String, part: PromptPart) {
    box(paneId).parts.trySend(part)
  }

  /** The parts for pane [paneId], each taken once; the pane as it is when collecting starts. */
  fun of(paneId: String): Flow<PromptPart> = flow { emitAll(box(paneId).parts.receiveAsFlow()) }

  /** A snapshot of the session: a pane that was in one before and is missing now is closed. */
  fun seen(snapshot: SessionSnapshot) {
    val panes = snapshot.panes.mapTo(HashSet()) { it.paneId }
    boxes.entries.removeAll { (paneId, box) -> box.seen && paneId !in panes }
    for ((paneId, box) in boxes) if (paneId in panes) box.seen = true
  }

  private fun box(paneId: String) = boxes.getOrPut(paneId) { Box() }

  private class Box {
    val parts = Channel<PromptPart>(Channel.UNLIMITED)

    /** The pane has been in a snapshot; a pane just started may not be yet. */
    var seen = false
  }
}
