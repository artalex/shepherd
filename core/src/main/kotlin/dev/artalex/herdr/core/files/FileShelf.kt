package dev.artalex.herdr.core.files

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A path from the screen while the files window is open: being checked, found (the file at [path]), or not found. */
data class ScanEntry(val shown: String, val state: ScanState, val path: String? = null)

enum class ScanState { CHECKING, FOUND, MISSING }

/** What [FileShelf.add] made of a typed path. */
enum class AddResult { ADDED, MISSING, NOT_A_FILE }

/**
 * The files window's model for one server: its lists ([book]; the app saves every change) and the paths on the
 * screen at the last press of the button ([scan]).
 */
class FileShelf(initial: FileBook) {
  private val _book = MutableStateFlow(initial)
  val book: StateFlow<FileBook> = _book.asStateFlow()

  private val _scan = MutableStateFlow<List<ScanEntry>?>(null)

  /** The paths on the screen in its order while the window is open; null when it is closed. */
  val scan: StateFlow<List<ScanEntry>?> = _scan.asStateFlow()

  private val scans = AtomicInteger()

  /**
   * Opens the window on the paths in [screen] and checks them on the host through [files], one after another.
   * A listed file shows as found and gets its size and time refreshed. A new path shows while it is checked: a file
   * goes to the top of the list, anything else shows as not found and is remembered as absent. A path remembered
   * as absent stays hidden but is checked again, so a file that turned up since joins the list. Folders never show.
   *
   * Then the rest of the list is checked: files get their current size and time, missing ones are marked gone.
   * Checks go on after the window closes; their results only reach the list then. Files found are files of the pane
   * of [origin], whose screen it is.
   */
  suspend fun scan(screen: String, width: Int?, cwd: String?, files: RemoteFiles, origin: FileOrigin? = null) {
    val id = scans.incrementAndGet()
    val home = files.home
    _book.update { it.copy(home = home) }
    val known = _book.value
    val items =
      findPathMentions(screen, width)
        .mapNotNull { mention -> mention.variants.mapNotNull { resolvePath(it, home, cwd) }.distinct().takeIf { it.isNotEmpty() } }
        .map { paths ->
          val listed = known.found.firstOrNull { it.path in paths }
          when {
            listed != null -> Item(paths, ScanEntry(listed.path, ScanState.FOUND, listed.path))
            paths.first() in known.absent -> Item(paths, null)
            else -> Item(paths, ScanEntry(paths.first(), ScanState.CHECKING))
          }
        }
        .toMutableList()
    fun show() {
      if (scans.get() == id) _scan.value = visible(items.mapNotNull { it.entry })
    }
    show()
    for ((i, item) in items.withIndex()) {
      val found = firstFile(item.paths, files)
      val entry = item.entry
      items[i] =
        when {
          found is Lookup.File -> {
            _book.update { it.seen(found.entry, origin) }
            Item(item.paths, ScanEntry(found.entry.path, ScanState.FOUND, found.entry.path))
          }
          entry?.state == ScanState.FOUND -> {
            _book.update { it.gone(checkNotNull(entry.path)) }
            item
          }
          else -> {
            _book.update { it.absent(item.paths.first()) }
            // A folder isn't something to download: no "not found" for it either.
            Item(item.paths, if (found == Lookup.Folder || entry == null) null else entry.copy(state = ScanState.MISSING))
          }
        }
      show()
    }
    val onScreen = items.mapNotNull { it.entry?.path }.toSet()
    for (file in _book.value.found.filter { it.path !in onScreen }) {
      val entry = withContext(Dispatchers.IO) { files.stat(file.path) }
      _book.update { if (entry != null && entry.isFile) it.checked(entry) else it.gone(file.path) }
    }
  }

  /** The window closed: paths not found this time won't show again. */
  fun closeScan() {
    scans.incrementAndGet()
    _scan.value = null
  }

  /**
   * Adds the file at a path the user typed in the files window of the pane of [origin], relative to [cwd] unless it is
   * absolute or starts with `~/`.
   */
  suspend fun add(text: String, cwd: String?, files: RemoteFiles, origin: FileOrigin? = null): AddResult {
    val typed = text.trim().removeSurrounding("\"").removeSurrounding("'")
    val path = resolvePath(typed, files.home, cwd) ?: return AddResult.MISSING
    val entry = withContext(Dispatchers.IO) { files.stat(path) } ?: return AddResult.MISSING
    if (!entry.isFile) return AddResult.NOT_A_FILE
    _book.update { it.copy(home = files.home).seen(entry, origin) }
    return AddResult.ADDED
  }

  fun downloaded(path: String, copy: LocalCopy) = _book.update { it.downloaded(path, copy) }

  fun gone(path: String) = _book.update { it.gone(path) }

  /** [file] went to the host whose home folder is [home]. */
  fun sent(file: SentFile, home: String) = _book.update { it.copy(home = home).sent(file) }

  fun forget(path: String) = forget(listOf(path))

  /** The found files at [paths] leave the list, and the paths on the screen too. */
  fun forget(paths: Collection<String>) {
    _book.update { it.forget(paths) }
    _scan.update { it?.let(::visible) }
  }

  fun forgetSent(path: String) = forgetSent(listOf(path))

  fun forgetSent(paths: Collection<String>) = _book.update { it.forgetSent(paths) }

  /**
   * Found files the user has removed from the list don't show among the paths on the screen either; two paths on
   * the screen that turn out to be the same file show once.
   */
  private fun visible(entries: List<ScanEntry>): List<ScanEntry> {
    val listed = _book.value.found.mapTo(HashSet()) { it.path }
    return entries.filter { it.state != ScanState.FOUND || it.path in listed }.distinctBy { it.path ?: it.shown }
  }

  /** The first of [paths] that is a file; otherwise whether one of them is something else, such as a folder. */
  private suspend fun firstFile(paths: List<String>, files: RemoteFiles): Lookup {
    var folder = false
    for (path in paths) {
      val entry = withContext(Dispatchers.IO) { files.stat(path) } ?: continue
      if (entry.isFile) return Lookup.File(entry)
      folder = true
    }
    return if (folder) Lookup.Folder else Lookup.Nothing
  }

  private sealed interface Lookup {
    data class File(val entry: RemoteEntry) : Lookup

    data object Folder : Lookup

    data object Nothing : Lookup
  }

  /** A path on the screen, as the absolute paths it may stand for, and how the window shows it (null: hidden). */
  private data class Item(val paths: List<String>, val entry: ScanEntry?)
}
