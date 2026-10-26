package dev.artalex.herdr.core.store

import dev.artalex.herdr.core.model.SessionSnapshot

/**
 * A thing kept for each pane of a session, made by [create] the first time it is asked for. A closed pane's goes with
 * it, as its id may come back as a new pane: closed means it was in a snapshot ([seen]) and a later one lacks it. A
 * pane just started may not be in a snapshot yet, and isn't closed for that. Not thread-safe: the app keeps it on the
 * main thread, where its snapshots come.
 */
class PerPane<T>(private val create: () -> T) {
  private val entries = mutableMapOf<String, Entry<T>>()

  /** The panes of the last snapshot; null before the first one. */
  private var panes: Set<String>? = null

  fun of(paneId: String): T =
    entries
      .getOrPut(paneId) {
        // A snapshot comes when the session changes, so the next one may be the one without the pane: a pane of the
        // last one counts as seen already.
        Entry(create(), seen = panes?.contains(paneId) == true)
      }
      .value

  /** A snapshot of the session: what was kept for a pane that was in one before and is missing now goes. */
  fun seen(snapshot: SessionSnapshot) {
    val now = snapshot.panes.mapTo(HashSet()) { it.paneId }
    panes = now
    entries.entries.removeAll { (paneId, entry) -> entry.seen && paneId !in now }
    for ((paneId, entry) in entries) if (paneId in now) entry.seen = true
  }

  /** [seen]: the pane has been in a snapshot. */
  private class Entry<T>(val value: T, var seen: Boolean)
}
