package dev.artalex.herdr.files

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
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
import dev.artalex.herdr.core.files.HostSide
import dev.artalex.herdr.core.files.PhoneSide
import dev.artalex.herdr.core.files.RemoteFiles
import dev.artalex.herdr.core.files.Transfer
import dev.artalex.herdr.core.files.Transfers
import dev.artalex.herdr.core.files.Upload
import dev.artalex.herdr.core.files.UploadEnd
import dev.artalex.herdr.core.files.guarded
import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.core.host.uploadDir
import dev.artalex.herdr.ui.agent.UiMessage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A downloaded file to show: [uri] on the phone, opened as [type]. */
data class Opening(val uri: Uri, val type: String, val name: String)

/**
 * Files between the phone and the server's host, for the files window of the agent screen: the server's lists
 * ([shelf], saved on the phone), and the transfers ([Transfers]) with Android at the phone's end, downloads going into
 * the app's folder in Download, and the connection at the host's.
 *
 * While a transfer runs, the connection stays up in the background and [TransferService] keeps Android from freezing
 * the app; [active] counts the transfers of all servers for it.
 */
class FileTransfers(private val app: Application, private val server: Server, private val active: MutableStateFlow<Int>) {
  private val scope get() = server.scope
  private val bookFile get() = File(File(app.filesDir, "files"), "book-${server.id}.json")
  private var saving: Job? = null
  private var scanJob: Job? = null

  private val _messages = Channel<UiMessage>(Channel.BUFFERED)

  /** Problems for the files window to report. One from a check that failed at once waits for the window to listen. */
  val messages: Flow<UiMessage> = _messages.receiveAsFlow()

  /** Tests send files here instead of the host's downloads folder; `~/` stands for the remote home. */
  @Volatile var uploadDirOverride: String? = null

  /** The server's lists, loaded when the files window first needs them. */
  val shelf: FileShelf by lazy {
    val file = bookFile
    FileShelf(FileBook.decode(file.takeIf { it.isFile }?.readText())).also { shelf ->
      saving = scope.launch(Dispatchers.IO) { shelf.book.drop(1).collect { save(file, it) } }
    }
  }

  private val transfers =
    Transfers(
      scope,
      Phone(app.contentResolver),
      Host(),
      shelf = { shelf },
      originOf = server::originOf,
      uploadEnded = { upload, result ->
        upload.forPane?.let { pane -> server.inbox.put(pane, result.fold({ PromptPart.Path(it) }, { PromptPart.Failed(upload.name, it.message ?: it.javaClass.simpleName) })) }
      },
      log = { Log.w(TAG, it) },
    )

  /** Downloads under way or failed, by the file's path on the host. */
  val downloads: StateFlow<Map<String, Transfer>> = transfers.downloads

  /** Uploads under way or failed, the latest first. */
  val uploads: StateFlow<List<Upload>> = transfers.uploads

  /** Files downloaded for the user to see; only an open window shows them. */
  val openings: Flow<Opening> = transfers.downloaded.map { Opening(it.location.toUri(), viewType(it.name), it.name) }

  /** Finds the paths on [paneId]'s screen and checks them on the host; see [FileShelf.scan]. */
  fun scan(paneId: String, width: Int?, cwd: String?, origin: FileOrigin?) {
    scanJob?.cancel()
    scanJob =
      scope.launch {
        val host = server.host
        if (host == null) {
          _messages.trySend(UiMessage(R.string.state_disconnected))
          return@launch
        }
        try {
          val screen = host.api.readPane(paneId, ansi = false).text
          val files = withContext(Dispatchers.IO) { host.openFiles() }
          files.guarded("checking the paths on the screen", graceMs = 0) { shelf.scan(screen, width, cwd, files, origin) }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "checking the paths on the screen failed: ${e.message}")
          _messages.trySend(UiMessage(R.string.error_connection, e.message ?: e.javaClass.simpleName))
        }
      }
  }

  /** The window closed; checks still running finish quietly. */
  fun closeScan() = shelf.closeScan()

  /** Adds the file at a path typed in the files window of the pane of [origin]; null without a connection. */
  suspend fun add(text: String, cwd: String?, origin: FileOrigin?): AddResult? {
    val host = server.host ?: return null
    val files = withContext(Dispatchers.IO) { host.openFiles() }
    return files.guarded("adding $text", graceMs = 0) { shelf.add(text, cwd, files, origin) }
  }

  /** Shows [file]: the copy on the phone if it is the current version, or downloads it first. */
  fun open(file: FoundFile) = transfers.open(file)

  fun cancelDownload(path: String) = transfers.cancelDownload(path)

  /** Sends the files the user picked or shared to the host, each on its own; see [Upload]. */
  fun upload(uris: List<Uri>, origin: FileOrigin?, forPane: String? = null) = transfers.upload(uris.map(Uri::toString), origin, forPane)

  /** Sends shared files to the host for a prompt of the caller's own and waits for them; see [Transfers.uploadAll]. */
  suspend fun uploadAll(uris: List<Uri>, origin: FileOrigin?, forPane: String? = null): List<UploadEnd> = transfers.uploadAll(uris.map(Uri::toString), origin, forPane)

  fun retry(upload: Upload) = transfers.retry(upload)

  fun cancel(upload: Upload) = transfers.cancel(upload)

  fun dismiss(upload: Upload) = transfers.dismiss(upload)

  /** The server is deleted: its transfers stop and its lists go. */
  fun dispose() {
    scanJob?.cancel()
    transfers.dispose()
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

  /** The host's end: the connected host, waited for a while and kept up, with the app, while a transfer runs. */
  private inner class Host : HostSide {
    override suspend fun <T> withFiles(block: suspend (files: RemoteFiles, uploadDir: suspend () -> String) -> T): T {
      if (active.getAndUpdate { it + 1 } == 0) TransferService.start(app)
      try {
        return server.connection.holding {
          val host =
            withTimeoutOrNull(CONNECT_WAIT_MS) { server.connection.state.mapNotNull { (it as? ConnectionState.Connected)?.host }.first() }
              ?: throw IOException("no connection to the host")
          withContext(Dispatchers.IO) { host.openFiles() }.use { files -> block(files) { uploadDirOverride ?: host.uploadDir() } }
        }
      } finally {
        active.update { it - 1 }
      }
    }
  }

  /** The phone's end: picked and shared files by their content URIs, downloads into the app's folder in Download. */
  private inner class Phone(private val resolver: ContentResolver) : PhoneSide {
    override fun describe(source: String): Pair<String, Long?> = pickedFile(resolver, source.toUri())

    override fun read(source: String): InputStream? = resolver.openInputStream(source.toUri())

    override fun exists(location: String): Boolean = PhoneDownloads.exists(resolver, location.toUri())

    override fun save(name: String, previous: String?, write: (OutputStream) -> Unit): String =
      PhoneDownloads.save(resolver, PhoneDownloads.folder(app), name, previous?.toUri(), write).toString()
  }

  private companion object {
    const val TAG = "HerdrFiles"
    const val CONNECT_WAIT_MS = 30_000L
  }
}
