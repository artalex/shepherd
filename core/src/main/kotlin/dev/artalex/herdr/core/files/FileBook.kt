package dev.artalex.herdr.core.files

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A file on the host found on the screen, with its size and time when last seen; [copy] once it is on the phone. */
@Serializable
data class FoundFile(val path: String, val size: Long, val mtime: Long, val copy: LocalCopy? = null, val gone: Boolean = false) {
  /** The phone has this version of the file: same size and time on the host as when it was downloaded. */
  val fresh: Boolean get() = copy != null && copy.size == size && copy.mtime == mtime
}

/** A downloaded file on the phone ([uri]) and the size and time the host file had then. */
@Serializable
data class LocalCopy(val uri: String, val size: Long, val mtime: Long)

/** A file sent from the phone to the host, at [path]. */
@Serializable
data class SentFile(val path: String, val size: Long)

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
  /** [entry] is on the screen again, or for the first time: it goes to the top. */
  fun seen(entry: RemoteEntry): FileBook {
    val old = found.firstOrNull { it.path == entry.path }
    val file = FoundFile(entry.path, entry.size, entry.mtime, old?.copy)
    return copy(found = listOf(file) + found.filter { it.path != entry.path }, absent = absent - entry.path)
  }

  /** Fresh size and time for a listed file, in its place. */
  fun checked(entry: RemoteEntry) = mapFound(entry.path) { it.copy(size = entry.size, mtime = entry.mtime, gone = false) }

  /** A listed file is no longer on the host. */
  fun gone(path: String) = mapFound(path) { it.copy(gone = true) }

  fun downloaded(path: String, copy: LocalCopy) = mapFound(path) { it.copy(size = copy.size, mtime = copy.mtime, copy = copy, gone = false) }

  fun absent(path: String) = copy(absent = (listOf(path) + absent.filter { it != path }).take(MAX_ABSENT))

  fun forget(path: String) = copy(found = found.filter { it.path != path })

  fun forgetAll() = copy(found = emptyList())

  fun sent(file: SentFile) = copy(sent = listOf(file) + sent.filter { it.path != file.path })

  fun forgetSent(path: String) = copy(sent = sent.filter { it.path != path })

  fun forgetAllSent() = copy(sent = emptyList())

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
