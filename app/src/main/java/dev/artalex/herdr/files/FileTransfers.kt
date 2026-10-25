package dev.artalex.herdr.files

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.artalex.herdr.PromptPart
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import dev.artalex.herdr.connection.ConnectionState
import dev.artalex.herdr.core.files.AddResult
import dev.artalex.herdr.core.files.FileBook
import dev.artalex.herdr.core.files.FileOrigin
import dev.artalex.herdr.core.files.FileShelf
import dev.artalex.herdr.core.files.FoundFile
import dev.artalex.herdr.core.files.LocalCopy
import dev.artalex.herdr.core.files.SentFile
import dev.artalex.herdr.core.files.safeFileName
import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.ui.agent.UiMessage
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A transfer under way, [done] of [total] bytes, or one that failed with [error]. */
data class Transfer(val done: Long, val total: Long?, val error: String? = null) {
  val percent: Int
    get() = total?.takeIf { it > 0 }?.let { (done * 100 / it).toInt().coerceIn(0, 100) } ?: 0
}

/**
 * A file on its way from the phone ([uri]) to the host, or one that didn't get there; [origin] is the pane it is sent
 * from or shared to. A shared file goes to the prompt of pane [forPane] too: its path once it is on the host, or that
 * it didn't get there.
 */
data class Upload(val id: Long, val uri: Uri, val name: String, val size: Long?, val transfer: Transfer, val origin: FileOrigin? = null, val forPane: String? = null)

/** A downloaded file to show: [uri] on the phone, opened as [type]. */
data class Opening(val uri: Uri, val type: String, val name: String)

/**
 * Files between the phone and the server's host, for the files window of the agent screen: the server's lists
 * ([shelf], saved on the phone), downloads into the app's folder in Download and uploads into the host's downloads folder.
 *
 * Transfers run in the app's scope, so they go on when the window closes or the screen changes. While one runs,
 * the connection stays up in the background and [TransferService] keeps Android from freezing the app; [active]
 * counts the transfers of all servers for it.
 */
class FileTransfers(private val app: Application, private val server: Server, private val active: MutableStateFlow<Int>) {
  private val scope get() = server.scope
  private val resolver get() = app.contentResolver
  private val bookFile get() = File(File(app.filesDir, "files"), "book-${server.id}.json")
  private var saving: Job? = null

  private val _downloads = MutableStateFlow<Map<String, Transfer>>(emptyMap())

  /** Downloads under way or failed, by the file's path on the host. */
  val downloads: StateFlow<Map<String, Transfer>> = _downloads.asStateFlow()

  private val _uploads = MutableStateFlow<List<Upload>>(emptyList())

  /** Uploads under way or failed, the latest first. */
  val uploads: StateFlow<List<Upload>> = _uploads.asStateFlow()

  private val _openings = MutableSharedFlow<Opening>(extraBufferCapacity = 4)

  /** Files downloaded for the user to see; only an open window shows them. */
  val openings: SharedFlow<Opening> = _openings.asSharedFlow()

  private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

  /** Problems for the open window to report. */
  val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

  private val jobs = ConcurrentHashMap<String, Job>()
  private var scanJob: Job? = null
  private val ids = AtomicLong()

  /** Tests send files here instead of the host's downloads folder; `~/` stands for the remote home. */
  @Volatile var uploadDirOverride: String? = null

  /** The server's lists, loaded when the files window first needs them. */
  val shelf: FileShelf by lazy {
    val file = bookFile
    FileShelf(FileBook.decode(file.takeIf { it.isFile }?.readText())).also { shelf ->
      saving = scope.launch(Dispatchers.IO) { shelf.book.drop(1).collect { save(file, it) } }
    }
  }

  /** Finds the paths on [paneId]'s screen and checks them on the host; see [FileShelf.scan]. */
  fun scan(paneId: String, width: Int?, cwd: String?, origin: FileOrigin?) {
    scanJob?.cancel()
    scanJob =
      scope.launch {
        try {
          val host = server.host ?: throw IOException("not connected")
          val screen = host.api.readPane(paneId, ansi = false).text
          withContext(Dispatchers.IO) { host.openFiles() }.use { files -> shelf.scan(screen, width, cwd, files, origin) }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "checking the paths on the screen failed: ${e.message}")
          _messages.tryEmit(UiMessage(R.string.error_connection, e.message ?: e.javaClass.simpleName))
        }
      }
  }

  /** The window closed; checks still running finish quietly. */
  fun closeScan() = shelf.closeScan()

  /** Adds the file at a path typed in the files window of the pane of [origin]; null without a connection. */
  suspend fun add(text: String, cwd: String?, origin: FileOrigin?): AddResult? {
    val host = server.host ?: return null
    return withContext(Dispatchers.IO) { host.openFiles() }.use { shelf.add(text, cwd, it, origin) }
  }

  /** Shows [file]: the copy on the phone if it is the current version, or downloads it first. */
  fun open(file: FoundFile) {
    if (jobs[downloadKey(file.path)]?.isActive == true) return
    scope.launch {
      val name = file.path.substringAfterLast('/')
      val copy = file.copy?.uri?.toUri()?.takeIf { (file.fresh || file.gone) && withContext(Dispatchers.IO) { PhoneDownloads.exists(resolver, it) } }
      if (copy != null) _openings.tryEmit(Opening(copy, viewType(name), name)) else download(file)
    }
  }

  fun cancelDownload(path: String) {
    jobs[downloadKey(path)]?.cancel()
  }

  /** Sends the files the user picked or shared to the host, each on its own; see [Upload]. */
  fun upload(uris: List<Uri>, origin: FileOrigin?, forPane: String? = null) {
    for (uri in uris) {
      scope.launch {
        val (name, size) = withContext(Dispatchers.IO) { pickedFile(resolver, uri) }
        send(Upload(ids.incrementAndGet(), uri, name, size, Transfer(0, size), origin, forPane))
      }
    }
  }

  fun retry(upload: Upload) = send(upload)

  fun cancel(upload: Upload) {
    jobs[uploadKey(upload.id)]?.cancel()
  }

  fun dismiss(upload: Upload) = _uploads.update { list -> list.filter { it.id != upload.id } }

  private fun download(file: FoundFile) {
    _downloads.update { it + (file.path to Transfer(0, file.size)) }
    launchTransfer(downloadKey(file.path)) {
      try {
        val opening = transfer { host -> withContext(Dispatchers.IO) { fetch(host, shelf, file) } }
        _downloads.update { it - file.path }
        if (opening != null) _openings.tryEmit(opening)
      } catch (e: CancellationException) {
        _downloads.update { it - file.path }
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "download of ${file.path} failed: ${e.message}")
        _downloads.update { it + (file.path to Transfer(0, file.size, e.message ?: e.javaClass.simpleName)) }
      }
    }
  }

  /**
   * Copies the file into the app's folder in Download, over its earlier copy if the app can still write that one, and returns
   * what to show; null when the host no longer has the file.
   */
  private suspend fun fetch(host: HerdrHost, shelf: FileShelf, file: FoundFile): Opening? {
    val job = currentCoroutineContext().job
    host.openFiles().use { files ->
      val entry = files.stat(file.path)?.takeIf { it.isFile }
      if (entry == null) {
        shelf.gone(file.path)
        return null
      }
      val name = file.path.substringAfterLast('/')
      val download: (OutputStream) -> Unit = { out -> files.download(file.path, out) { done -> report(file.path, Transfer(done, entry.size)); job.isActive } }
      // A copy made before a reinstall, or deleted since, can't be written: then a new one.
      val old = file.copy?.uri?.toUri()
      val target =
        if (old != null && writeInto(old, "wt", download)) {
          old
        } else {
          val created = PhoneDownloads.create(resolver, PhoneDownloads.folder(app), name)
          try {
            if (!writeInto(created, "w", download)) throw IOException("cannot write $created")
            PhoneDownloads.publish(resolver, created)
          } catch (e: Throwable) {
            PhoneDownloads.delete(resolver, created)
            throw e
          }
          created
        }
      shelf.downloaded(file.path, LocalCopy(target.toString(), entry.size, entry.mtime))
      return Opening(target, viewType(name), name)
    }
  }

  /** Runs [write] on the file at [uri]; false if the app can't open it for writing. */
  private fun writeInto(uri: Uri, mode: String, write: (OutputStream) -> Unit): Boolean {
    val out =
      try {
        resolver.openOutputStream(uri, mode)
      } catch (e: Exception) {
        null
      } ?: return false
    out.use(write)
    return true
  }

  private fun send(item: Upload) {
    _uploads.update { list -> listOf(item.copy(transfer = Transfer(0, item.size))) + list.filter { it.id != item.id } }
    launchTransfer(uploadKey(item.id)) {
      try {
        val path = transfer { host -> withContext(Dispatchers.IO) { put(host, shelf, item) } }
        dismiss(item)
        item.forPane?.let { server.inbox.put(it, PromptPart.Path(path)) }
      } catch (e: CancellationException) {
        dismiss(item)
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "upload of ${item.name} failed: ${e.message}")
        val error = e.message ?: e.javaClass.simpleName
        _uploads.update { list -> list.map { if (it.id == item.id) it.copy(transfer = it.transfer.copy(error = error)) else it } }
        item.forPane?.let { server.inbox.put(it, PromptPart.Failed(item.name, error)) }
      }
    }
  }

  /** Sends [item] to the host and returns its path there. */
  private suspend fun put(host: HerdrHost, shelf: FileShelf, item: Upload): String {
    val job = currentCoroutineContext().job
    val override = uploadDirOverride
    val dir = override ?: host.uploadDir()
    host.openFiles().use { files ->
      var sent = 0L
      val input = resolver.openInputStream(item.uri) ?: throw IOException("cannot read ${item.name}")
      val path =
        input.use { stream ->
          files.upload(stream, if (dir.startsWith("~/")) files.home + dir.substring(1) else dir, safeFileName(item.name)) { done ->
            sent = done
            _uploads.update { list -> list.map { if (it.id == item.id) it.copy(transfer = Transfer(done, item.size)) else it } }
            job.isActive
          }
        }
      // A share to an agent just started may come before a snapshot has its pane.
      shelf.sent(SentFile(path, item.size ?: sent, item.origin ?: item.forPane?.let(server::originOf)), files.home)
      return path
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

  /** Runs [block] with the connected host: waits for the connection a while, and keeps it and the app up meanwhile. */
  private suspend fun <T> transfer(block: suspend (HerdrHost) -> T): T {
    if (active.getAndUpdate { it + 1 } == 0) startService()
    try {
      return server.connection.holding {
        val host =
          withTimeoutOrNull(CONNECT_WAIT_MS) { server.connection.state.mapNotNull { (it as? ConnectionState.Connected)?.host }.first() }
            ?: throw IOException("no connection to the host")
        block(host)
      }
    } finally {
      active.update { it - 1 }
    }
  }

  private fun startService() {
    try {
      ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java))
    } catch (e: Exception) {
      // Android refuses a foreground service started from the background; the transfer goes on without it.
      Log.w(TAG, "cannot start the transfer service: ${e.message}")
    }
  }

  /** The server is deleted: its transfers stop and its lists go. */
  fun dispose() {
    scanJob?.cancel()
    jobs.values.forEach { it.cancel() }
    saving?.cancel()
    bookFile.delete()
  }

  private fun save(file: File, book: FileBook) {
    try {
      file.parentFile?.mkdirs()
      val part = File(file.path + ".part")
      part.writeText(book.encode())
      Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: IOException) {
      Log.w(TAG, "cannot save the file lists: ${e.message}")
    }
  }

  private companion object {
    const val TAG = "HerdrFiles"
    const val CONNECT_WAIT_MS = 30_000L

    fun downloadKey(path: String) = "download:$path"

    fun uploadKey(id: Long) = "upload:$id"
  }
}
