package dev.artalex.herdr.core.servers

import dev.artalex.herdr.core.model.SessionSnapshot

/** The remote home folder, as the folder field of a new agent takes it. */
const val HOME_FOLDER = "~"

/**
 * [folder], as the host resolves folders, the way the folder field shows it: [HOME_FOLDER] for the [home] folder and
 * `~/…` for the folders inside it. Others, and all of them while the home folder isn't known, stay as they are.
 */
fun shortFolder(folder: String, home: String?): String =
  when {
    home == null -> folder
    folder == home -> HOME_FOLDER
    folder.startsWith("$home/") -> "$HOME_FOLDER/${folder.removePrefix("$home/")}"
    else -> folder
  }

/** A folder on the menu of a new agent: [shown] as the field takes it, [folder] as the server keeps it; none for the home folder, which is always there. */
data class FolderChoice(val shown: String, val folder: String?)

/** The menu of a new agent: the home folder always, then the server's [folders], each once and short ([shortFolder]), in [folderOrder]. */
fun folderChoices(home: String?, folders: List<String>): List<FolderChoice> =
  (listOf(FolderChoice(HOME_FOLDER, null)) + folders.map { FolderChoice(shortFolder(it, home), it) })
    .distinctBy { it.shown }
    .sortedWith(compareBy(folderOrder) { it.shown })

/**
 * The home folder and the folders inside it first, then the others; alphabetically name by name along the path, so a
 * folder comes right before those inside it.
 */
val folderOrder: Comparator<String> =
  compareBy<String> { !it.startsWith(HOME_FOLDER) }.thenComparator { a, b -> compareNames(a.split('/'), b.split('/')) }.thenBy { it }

private fun compareNames(a: List<String>, b: List<String>): Int {
  for (i in 0 until minOf(a.size, b.size)) {
    val order = String.CASE_INSENSITIVE_ORDER.compare(a[i], b[i])
    if (order != 0) return order
  }
  return a.size.compareTo(b.size)
}

/**
 * Finds the folders of a server's panes as its snapshots come, for the server to keep: those of their shells and of
 * what runs in front. A folder is new when no pane of the previous snapshot was in it, so one the user took off the
 * menu comes back only once a pane shows up there again.
 */
class FolderSightings {
  private var last = emptySet<String>()

  /** The folders of [snapshot]'s panes that the previous snapshot didn't have. */
  @Synchronized
  fun newIn(snapshot: SessionSnapshot): Set<String> {
    val now = snapshot.panes.flatMap { listOfNotNull(it.cwd, it.foregroundCwd) }.filter { it.startsWith("/") }.toSet()
    return (now - last).also { last = now }
  }

  /** Starts over, as for another host or session: every folder of the next snapshot is new. */
  @Synchronized
  fun reset() {
    last = emptySet()
  }
}
