package dev.artalex.herdr.core.files

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Uploads of one host under way at once; the rest wait for their turn. Each has an SFTP channel of its own, and the
 * other channels the connection has room for go to downloads ([PARALLEL_DOWNLOADS]), lookups on the host, checks of
 * paths and the screen's terminal stream. An upload looks up the uploads folder over a channel of its own while it holds
 * its SFTP channel, so transfers may never take all the room there is
 * ([dev.artalex.herdr.core.ssh.SshConnection.SESSION_CHANNELS]).
 */
const val PARALLEL_UPLOADS = 3

/**
 * Downloads of one host under way at once, in a queue of their own: a file the user opens doesn't wait for the uploads
 * before it, which take the other way of the connection anyway.
 */
const val PARALLEL_DOWNLOADS = 1

/** A transfer under way, [done] of [total] bytes, or one that failed with [error]; [waiting] for its turn yet. */
data class Transfer(val done: Long, val total: Long?, val error: String? = null, val waiting: Boolean = false) {
  val percent: Int
    get() = total?.takeIf { it > 0 }?.let { (done * 100 / it).toInt().coerceIn(0, 100) } ?: 0
}

/**
 * A file on its way from the phone ([source], where the phone keeps it) to the host, or one that didn't get there;
 * [origin] is the pane it is sent from or shared to. A shared file goes to the prompt of pane [forPane] too: its path
 * once it is on the host, or that it didn't get there.
 */
data class Upload(val id: Long, val source: String, val name: String, val size: Long?, val transfer: Transfer, val origin: FileOrigin? = null, val forPane: String? = null)

/** How a file of [Transfers.uploadAll] ended: its [name], and its [path] on the host or why it didn't get there. */
data class UploadEnd(val name: String, val path: Result<String>)

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
   * A downloaded file named [name], written by [write] into a new file; returns where it went. Only a whole file takes
   * the place of the copy at [previous]: a [write] that fails leaves that copy as it was, and nothing else behind.
   * A copy the phone can't remove, such as one made before a reinstall, is left alone beside the new file.
   */
  fun save(name: String, previous: String?, write: (OutputStream) -> Unit): String
}

/** The host's end of transfers. */
interface HostSide {
  /**
   * Runs [block], a transfer from start to end, its wait for its turn too: keeps up meanwhile what a transfer needs, such
   * as the connection.
   */
  suspend fun <T> whileTransferring(block: suspend () -> T): T = block()

  /** Waits a while for the connection to the host, for a transfer that is to wait for its turn; [NoConnection] if none comes. */
  suspend fun awaitConnection() = Unit

  /**
   * Runs [block] with files on the connected host, opened for it, and where uploads go there (a path, or one from
   * `~/`); [NoConnection], before [block] runs, if there is no connection now.
   */
  suspend fun <T> withFiles(block: suspend (files: RemoteFiles, uploadDir: suspend () -> String) -> T): T
}

/** The host has no connection now. */
class NoConnection : IOException("no connection to the host")

/**
 * Transfers between the phone and the host for the files window: downloads to the phone, uploads to the host's
 * downloads folder, their progress and failures, cancelling and retrying. Each runs in [scope] on its own, so it goes
 * on when the window closes or the screen changes; one that is cancelled or gets stuck lets go of its files
 * ([guarded]). They take turns, [uploadsAtOnce] uploads and [downloadsAtOnce] downloads at a time, each kind in a queue
 * of its own: each has files of its own, a channel of the host's connection, which has room for a few only. [phone] and
 * [host] are the two ends, [shelf] the server's lists. A shared file names the pane it comes from by [originOf] if no
 * snapshot had that pane yet, and [uploadEnded] hears how each upload ended, for the prompt of the pane it was shared
 * to. [stallMs] and [graceMs] are as for [guarded].
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
  uploadsAtOnce: Int = PARALLEL_UPLOADS,
  downloadsAtOnce: Int = PARALLEL_DOWNLOADS,
) {
  private val uploadTurns = Semaphore(uploadsAtOnce)
  private val downloadTurns = Semaphore(downloadsAtOnce)

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

  /** The uploads of an [uploadAll] that haven't ended yet, by their ids: their ends go to it. */
  private val waiting = ConcurrentHashMap<Long, CompletableDeferred<UploadEnd>>()

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

  /**
   * Sends the files at [sources] to the host as [upload] does and waits for them: how each ended, in the order of
   * [sources]. The caller puts the paths into a prompt itself, so [uploadEnded] doesn't hear of these; a retry from the
   * files window later does, as of any upload.
   */
  suspend fun uploadAll(sources: List<String>, origin: FileOrigin?, forPane: String? = null): List<UploadEnd> =
    sources
      .map { source ->
        val end = CompletableDeferred<UploadEnd>()
        scope
          .launch {
            val (name, size) = withContext(Dispatchers.IO) { phone.describe(source) }
            val item = Upload(ids.incrementAndGet(), source, name, size, Transfer(0, size), origin, forPane)
            waiting[item.id] = end
            send(item)
          }
          // Cancelled before its transfer began, as when the server goes; a transfer that began tells how it ended.
          .invokeOnCompletion { cause -> if (cause != null) end.complete(UploadEnd(source.substringAfterLast('/'), Result.failure(cause))) }
        end
      }
      .awaitAll()

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
    _downloads.update { it + (file.path to Transfer(0, file.size, waiting = true)) }
    launchTransfer(downloadKey(file.path)) {
      try {
        val started = { _downloads.update { it + (file.path to Transfer(0, file.size)) } }
        val downloaded = inTurn(downloadTurns, started) { files, _ -> fetch(files, file) }
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
    _uploads.update { list -> listOf(item.copy(transfer = Transfer(0, item.size, waiting = true))) + list.filter { it.id != item.id } }
    val job =
      launchTransfer(uploadKey(item.id)) {
        try {
          val started = { _uploads.update { list -> list.map { if (it.id == item.id) it.copy(transfer = Transfer(0, item.size)) else it } } }
          val path = inTurn(uploadTurns, started) { files, uploadDir -> put(files, uploadDir, item) }
          dismiss(item)
          ended(item, Result.success(path))
        } catch (e: CancellationException) {
          dismiss(item)
          throw e
        } catch (e: Exception) {
          log("upload of ${item.name} failed: ${e.message}")
          val error = e.message ?: e.javaClass.simpleName
          _uploads.update { list -> list.map { if (it.id == item.id) it.copy(transfer = it.transfer.copy(error = error)) else it } }
          ended(item, Result.failure(IOException(error, e)))
        }
      }
    // A cancel, before the transfer began too, ends it for whoever waits.
    job.invokeOnCompletion { cause ->
      if (cause != null) waiting.remove(item.id)?.complete(UploadEnd(item.name, Result.failure(IOException("upload of ${item.name} cancelled", cause))))
    }
  }

  /** How [item] ended, for the [uploadAll] that waits for it, if any, otherwise for [uploadEnded]. */
  private fun ended(item: Upload, result: Result<String>) {
    val waiter = waiting.remove(item.id)
    if (waiter != null) waiter.complete(UploadEnd(item.name, result)) else uploadEnded(item, result)
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

  /** Runs [block] with files on the host once a transfer's turn of [turns] comes; [started] hears when it has them. */
  private suspend fun <T> inTurn(turns: Semaphore, started: () -> Unit, block: suspend (files: RemoteFiles, uploadDir: suspend () -> String) -> T): T =
    host.whileTransferring {
      connectedInTurn(turns) { files, uploadDir ->
        started()
        block(files, uploadDir)
      }
    }

  /**
   * [block] with files on the host in a turn of [turns]. The connection is waited for before the turn, by all transfers
   * at once: in a turn each would wait for it after the one before it had waited in vain. One gone while a transfer waited
   * for its turn is waited for again, away from the turns.
   */
  private suspend fun <T> connectedInTurn(turns: Semaphore, block: suspend (files: RemoteFiles, uploadDir: suspend () -> String) -> T): T {
    while (true) {
      host.awaitConnection()
      try {
        return turns.withPermit { host.withFiles(block) }
      } catch (e: NoConnection) {
        // Gone while the transfer waited for its turn: once more, and the turn goes to the next.
      }
    }
  }

  /** A download's progress, passed on when the percentage changes. */
  private fun report(path: String, transfer: Transfer) {
    if (_downloads.value[path]?.percent != transfer.percent) _downloads.update { it + (path to transfer) }
  }

  private fun launchTransfer(key: String, block: suspend () -> Unit): Job {
    val job = scope.launch { block() }
    jobs[key] = job
    job.invokeOnCompletion { jobs.remove(key, job) }
    return job
  }

  private companion object {
    fun downloadKey(path: String) = "download:$path"

    fun uploadKey(id: Long) = "upload:$id"
  }
}
