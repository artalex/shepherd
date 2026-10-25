package dev.artalex.herdr.core.files

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A file on the host found on the screen, with its size and time when last seen; [copy] once it is on the phone.
 * [origins] are the panes whose screens or files windows it came up in, the latest first.
 */
@Serializable
data class FoundFile(
  val path: String,
  val size: Long,
  val mtime: Long,
  val copy: LocalCopy? = null,
  val gone: Boolean = false,
  val origins: List<FileOrigin> = emptyList(),
) {
  /** The phone has this version of the file: same size and time on the host as when it was downloaded. */
  val fresh: Boolean get() = copy != null && copy.size == size && copy.mtime == mtime
}

/** A downloaded file on the phone ([uri]) and the size and time the host file had then. */
@Serializable
data class LocalCopy(val uri: String, val size: Long, val mtime: Long)

/** A file sent from the phone to the host, at [path]; [origin] is the pane it was sent from or shared to. */
@Serializable
data class SentFile(val path: String, val size: Long, val origin: FileOrigin? = null)

/** Where a file came up: the terminal of a pane, which no other pane ever has, and the pane's workspace. */
@Serializable
data class FileOrigin(val terminal: String, val workspace: String)

/**
 * Which files the files window lists: those of the pane of [origin], or with [workspace] those of all panes of its
 * workspace. Files listed before the app told panes apart have no origin: they show for the whole workspace only.
 */
data class FileScope(val origin: FileOrigin, val workspace: Boolean) {
  fun has(origins: Collection<FileOrigin>): Boolean =
    if (workspace) origins.isEmpty() || origins.any { it.workspace == origin.workspace } else origins.any { it.terminal == origin.terminal }

  fun has(file: FoundFile): Boolean = has(file.origins)

  fun has(file: SentFile): Boolean = has(listOfNotNull(file.origin))
}

/**
 * The files window's lists for one server, kept on the phone. Entries leave them only when the user removes them,
 * except [absent], which is the window's memory and keeps the latest [MAX_ABSENT].
 */
@Serializable
data class FileBook(
  /** Files found on the screen, the most recently seen first. */
  val found: List<FoundFile> = emptyList(),
  /** Files sent to the host, the latest first. */
  val sent: List<SentFile> = emptyList(),
  /** Paths from the screen that weren't files on the host, the latest first: shown once, then checked quietly. */
  val absent: List<String> = emptyList(),
  /** The remote home folder, for showing paths in it as `~/…`. */
  val home: String? = null,
) {
  /** [entry] came up again, or for the first time, in the pane of [origin]: it goes to the top. */
  fun seen(entry: RemoteEntry, origin: FileOrigin? = null): FileBook {
    val old = found.firstOrNull { it.path == entry.path }
    val origins = (listOfNotNull(origin) + old?.origins.orEmpty()).distinct()
    val file = FoundFile(entry.path, entry.size, entry.mtime, old?.copy, origins = origins)
    return copy(found = listOf(file) + found.filter { it.path != entry.path }, absent = absent - entry.path)
  }

  /** Fresh size and time for a listed file, in its place. */
  fun checked(entry: RemoteEntry) = mapFound(entry.path) { it.copy(size = entry.size, mtime = entry.mtime, gone = false) }

  /** A listed file is no longer on the host. */
  fun gone(path: String) = mapFound(path) { it.copy(gone = true) }

  fun downloaded(path: String, copy: LocalCopy) = mapFound(path) { it.copy(size = copy.size, mtime = copy.mtime, copy = copy, gone = false) }

  fun absent(path: String) = copy(absent = (listOf(path) + absent.filter { it != path }).take(MAX_ABSENT))

  fun forget(path: String) = forget(listOf(path))

  /** The found files at [paths] leave the list: those the window shows, when it clears them. */
  fun forget(paths: Collection<String>): FileBook {
    val gone = paths.toSet()
    return copy(found = found.filter { it.path !in gone })
  }

  fun sent(file: SentFile) = copy(sent = listOf(file) + sent.filter { it.path != file.path })

  fun forgetSent(path: String) = forgetSent(listOf(path))

  /** The sent files at [paths] leave the list: those the window shows, when it clears them. */
  fun forgetSent(paths: Collection<String>): FileBook {
    val gone = paths.toSet()
    return copy(sent = sent.filter { it.path !in gone })
  }

  fun encode(): String = json.encodeToString(serializer(), this)

  private fun mapFound(path: String, change: (FoundFile) -> FoundFile) = copy(found = found.map { if (it.path == path) change(it) else it })

  companion object {
    const val MAX_ABSENT = 500

    private val json = Json { ignoreUnknownKeys = true }

    /** The book saved by [encode]; an empty one for a missing or unreadable file. */
    fun decode(text: String?): FileBook =
      try {
        if (text.isNullOrBlank()) FileBook() else json.decodeFromString(serializer(), text)
      } catch (e: SerializationException) {
        FileBook()
      } catch (e: IllegalArgumentException) {
        FileBook()
      }
  }
}
