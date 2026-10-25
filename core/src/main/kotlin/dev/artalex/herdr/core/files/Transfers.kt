package dev.artalex.herdr.core.files

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A transfer under way, [done] of [total] bytes, or one that failed with [error]. */
data class Transfer(val done: Long, val total: Long?, val error: String? = null) {
  val percent: Int
    get() = total?.takeIf { it > 0 }?.let { (done * 100 / it).toInt().coerceIn(0, 100) } ?: 0
}

/**
 * A file on its way from the phone ([source], where the phone keeps it) to the host, or one that didn't get there;
 * [origin] is the pane it is sent from or shared to. A shared file goes to the prompt of pane [forPane] too: its path
 * once it is on the host, or that it didn't get there.
 */
data class Upload(val id: Long, val source: String, val name: String, val size: Long?, val transfer: Transfer, val origin: FileOrigin? = null, val forPane: String? = null)

/** A file on the phone for the user to see: where the phone keeps it, and its name. */
data class Downloaded(val location: String, val name: String)

/** The phone's end of transfers, blocking: where uploads come from and downloads go. */
interface PhoneSide {
  /** The name and size of the file at [source]. */
  fun describe(source: String): Pair<String, Long?>

  /** The file at [source] to read; null if the phone can't open it. */
  fun read(source: String): InputStream?

  /** Whether the copy at [location] is still there: the user may have deleted it in another app. */
  fun exists(location: String): Boolean

  /**
   * A downloaded file named [name], written by [write] over the copy at [previous] if the phone can still write that
   * one, or as a new file otherwise; returns where it went.
   */
  fun save(name: String, previous: String?, write: (OutputStream) -> Unit): String
}

/** The host's end of transfers. */
interface HostSide {
  /**
   * Runs [block] with files on the connected host, opened for it, and where uploads go there (a path, or one from
   * `~/`): waits for the connection a while, and keeps it up meanwhile.
   */
  suspend fun <T> withFiles(block: suspend (files: RemoteFiles, uploadDir: suspend () -> String) -> T): T
}

/**
 * Transfers between the phone and the host for the files window: downloads to the phone, uploads to the host's
 * downloads folder, their progress and failures, cancelling and retrying. Each runs in [scope] on its own, so it goes
 * on when the window closes or the screen changes; one that is cancelled or gets stuck lets go of its files
 * ([guarded]). [phone] and [host] are the two ends, [shelf] the server's lists. A shared file names the pane it comes
 * from by [originOf] if no snapshot had that pane yet, and [uploadEnded] hears how each upload ended, for the prompt of
 * the pane it was shared to. [stallMs] and [graceMs] are as for [guarded].
 */
class Transfers(
  private val scope: CoroutineScope,
  private val phone: PhoneSide,
  private val host: HostSide,
  private val shelf: () -> FileShelf,
  private val originOf: (paneId: String) -> FileOrigin? = { null },
  private val uploadEnded: (Upload, Result<String>) -> Unit = { _, _ -> },
  private val log: (String) -> Unit = {},
  private val stallMs: Long = TRANSFER_STALL_MS,
  private val graceMs: Long = TRANSFER_GRACE_MS,
) {
  private val _downloads = MutableStateFlow<Map<String, Transfer>>(emptyMap())

  /** Downloads under way or failed, by the file's path on the host. */
  val downloads: StateFlow<Map<String, Transfer>> = _downloads.asStateFlow()

  private val _uploads = MutableStateFlow<List<Upload>>(emptyList())

  /** Uploads under way or failed, the latest first. */
  val uploads: StateFlow<List<Upload>> = _uploads.asStateFlow()

  private val _downloaded = MutableSharedFlow<Downloaded>(extraBufferCapacity = 4)

  /** Files on the phone for the user to see, downloaded or already there; only an open window shows them. */
  val downloaded: SharedFlow<Downloaded> = _downloaded.asSharedFlow()

  private val jobs = ConcurrentHashMap<String, Job>()
  private val ids = AtomicLong()

  /** Shows [file]: the copy on the phone if it is the current version, or downloads it first. */
  fun open(file: FoundFile) {
    if (jobs[downloadKey(file.path)]?.isActive == true) return
    scope.launch {
      val name = file.path.substringAfterLast('/')
      val copy = file.copy?.uri?.takeIf { (file.fresh || file.gone) && withContext(Dispatchers.IO) { phone.exists(it) } }
      if (copy != null) _downloaded.tryEmit(Downloaded(copy, name)) else download(file)
    }
  }

  fun cancelDownload(path: String) {
    jobs[downloadKey(path)]?.cancel()
  }

  /** Sends the files at [sources] to the host, each on its own; see [Upload]. */
  fun upload(sources: List<String>, origin: FileOrigin?, forPane: String? = null) {
    for (source in sources) {
      scope.launch {
        val (name, size) = withContext(Dispatchers.IO) { phone.describe(source) }
        send(Upload(ids.incrementAndGet(), source, name, size, Transfer(0, size), origin, forPane))
      }
    }
  }

  fun retry(upload: Upload) = send(upload)

  fun cancel(upload: Upload) {
    jobs[uploadKey(upload.id)]?.cancel()
  }

  fun dismiss(upload: Upload) = _uploads.update { list -> list.filter { it.id != upload.id } }

  /** The server is deleted: its transfers stop. */
  fun dispose() {
    jobs.values.forEach { it.cancel() }
  }

  private fun download(file: FoundFile) {
    _downloads.update { it + (file.path to Transfer(0, file.size)) }
    launchTransfer(downloadKey(file.path)) {
      try {
        val downloaded = host.withFiles { files, _ -> fetch(files, file) }
        _downloads.update { it - file.path }
        if (downloaded != null) _downloaded.tryEmit(downloaded)
      } catch (e: CancellationException) {
        _downloads.update { it - file.path }
        throw e
      } catch (e: Exception) {
        log("download of ${file.path} failed: ${e.message}")
        _downloads.update { it + (file.path to Transfer(0, file.size, e.message ?: e.javaClass.simpleName)) }
      }
    }
  }

  /** Copies the file to the phone and returns what to show; null when the host no longer has it. */
  private suspend fun fetch(files: RemoteFiles, file: FoundFile): Downloaded? {
    val job = currentCoroutineContext().job
    return files.guarded("download of ${file.path}", stallMs, graceMs) { progressed ->
      val entry = files.stat(file.path)?.takeIf { it.isFile }
      if (entry == null) {
        shelf().gone(file.path)
        return@guarded null
      }
      val name = file.path.substringAfterLast('/')
      val location =
        phone.save(name, file.copy?.uri) { out ->
          files.download(file.path, out) { done ->
            progressed()
            report(file.path, Transfer(done, entry.size))
            job.isActive
          }
        }
      shelf().downloaded(file.path, LocalCopy(location, entry.size, entry.mtime))
      Downloaded(location, name)
    }
  }

  private fun send(item: Upload) {
    _uploads.update { list -> listOf(item.copy(transfer = Transfer(0, item.size))) + list.filter { it.id != item.id } }
    launchTransfer(uploadKey(item.id)) {
      try {
        val path = host.withFiles { files, uploadDir -> put(files, uploadDir, item) }
        dismiss(item)
        uploadEnded(item, Result.success(path))
      } catch (e: CancellationException) {
        dismiss(item)
        throw e
      } catch (e: Exception) {
        log("upload of ${item.name} failed: ${e.message}")
        val error = e.message ?: e.javaClass.simpleName
        _uploads.update { list -> list.map { if (it.id == item.id) it.copy(transfer = it.transfer.copy(error = error)) else it } }
        uploadEnded(item, Result.failure(IOException(error, e)))
      }
    }
  }

  /** Sends [item] to the host and returns its path there. */
  private suspend fun put(files: RemoteFiles, uploadDir: suspend () -> String, item: Upload): String {
    val job = currentCoroutineContext().job
    return files.guarded("upload of ${item.name}", stallMs, graceMs) { progressed ->
      val dir = uploadDir()
      var sent = 0L
      val input = phone.read(item.source) ?: throw IOException("cannot read ${item.name}")
      val path =
        input.use { stream ->
          files.upload(stream, if (dir.startsWith("~/")) files.home + dir.substring(1) else dir, safeFileName(item.name)) { done ->
            progressed()
            sent = done
            _uploads.update { list -> list.map { if (it.id == item.id) it.copy(transfer = Transfer(done, item.size)) else it } }
            job.isActive
          }
        }
      // A share to an agent just started may come before a snapshot has its pane.
      shelf().sent(SentFile(path, item.size ?: sent, item.origin ?: item.forPane?.let(originOf)), files.home)
      path
    }
  }

  /** A download's progress, passed on when the percentage changes. */
  private fun report(path: String, transfer: Transfer) {
    if (_downloads.value[path]?.percent != transfer.percent) _downloads.update { it + (path to transfer) }
  }

  private fun launchTransfer(key: String, block: suspend () -> Unit) {
    val job = scope.launch { block() }
    jobs[key] = job
    job.invokeOnCompletion { jobs.remove(key, job) }
  }

  private companion object {
    fun downloadKey(path: String) = "download:$path"

    fun uploadKey(id: Long) = "upload:$id"
  }
}
