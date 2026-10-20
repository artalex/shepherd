package dev.artalex.herdr.core.files

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** What is at [path] on the host: its size in bytes and modification time in seconds; [isFile] for a regular file. */
data class RemoteEntry(val path: String, val size: Long, val mtime: Long, val isFile: Boolean)

/**
 * Files on the herdr host. The app only reads them, and writes new files into one folder; nothing on the host is
 * overwritten or deleted, except its own unfinished uploads. Blocking, and for one caller at a time: each check
 * run or transfer opens its own.
 *
 * Transfers report the bytes done so far to `progress`; when it returns false they stop and throw
 * [java.util.concurrent.CancellationException].
 */
interface RemoteFiles : Closeable {
  /** The remote user's home folder. */
  val home: String

  /** What is at [path], symlinks followed; null when there's nothing there or it can't be read. */
  fun stat(path: String): RemoteEntry?

  /** Copies the file at [path] into [out]. */
  fun download(path: String, out: OutputStream, progress: (Long) -> Boolean)

  /**
   * Writes [input] as a new file named [name] in [dir], creating the folder if needed, and returns its path.
   * A name that is taken stays with its file: the new one becomes `name (2).ext`, `name (3).ext`, and so on.
   */
  fun upload(input: InputStream, dir: String, name: String, progress: (Long) -> Boolean): String
}

/**
 * [name] as a file name for the host: no slashes, control characters or leading dots (hidden files), none of the
 * `*`, `?` and `\` that SFTP clients read as patterns, and at most [MAX_NAME_BYTES] bytes of UTF-8.
 */
fun safeFileName(name: String): String {
  val cleaned = name.map { if (it == '/' || it == '\\' || it == '*' || it == '?' || it.isISOControl()) '_' else it }.joinToString("").trimStart('.').trim()
  var cut = cleaned
  while (cut.toByteArray().size > MAX_NAME_BYTES) cut = cut.dropLast(1)
  return cut.ifEmpty { "file" }
}

/** The [n]th choice of a name for a file called [name]: the name itself, then `name (2).ext`, `name (3).ext`. */
fun numberedName(name: String, n: Int): String {
  if (n <= 1) return name
  val dot = name.lastIndexOf('.')
  return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
}

private const val MAX_NAME_BYTES = 200
