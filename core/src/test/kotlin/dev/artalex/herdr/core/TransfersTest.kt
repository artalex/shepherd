package dev.artalex.herdr.core

import dev.artalex.herdr.core.files.Downloaded
import dev.artalex.herdr.core.files.FileBook
import dev.artalex.herdr.core.files.FileShelf
import dev.artalex.herdr.core.files.FoundFile
import dev.artalex.herdr.core.files.HostSide
import dev.artalex.herdr.core.files.LocalCopy
import dev.artalex.herdr.core.files.PhoneSide
import dev.artalex.herdr.core.files.RemoteEntry
import dev.artalex.herdr.core.files.RemoteFiles
import dev.artalex.herdr.core.files.Transfers
import dev.artalex.herdr.core.files.Upload
import dev.artalex.herdr.core.files.numberedName
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Transfers between a phone and a host in memory: what reaches each end, what the window shows, what a cancel lets go. */
class TransfersTest {
  /** The host's files by path, shared by its channels as by SFTP channels of one session. */
  private class Host : HostSide {
    val files = ConcurrentHashMap<String, ByteArray>()
    val channels = CopyOnWriteArrayList<Channel>()

    /** Transfers wait in a read, as on a dead link, until their channel is closed. */
    @Volatile var stuck = false

    /** The next upload fails with this. */
    @Volatile var failNext: String? = null

    /** Downloads break off after this many bytes, as on a dropped connection. */
    @Volatile var breakAfter: Int? = null

    override suspend fun <T> withFiles(block: suspend (files: RemoteFiles, uploadDir: suspend () -> String) -> T): T =
      Channel(this).also { channels += it }.use { block(it) { "~/Downloads/herdr-android" } }
  }

  private class Channel(private val host: Host) : RemoteFiles {
    @Volatile var closed = false

    override val home = "/home/user"

    override fun stat(path: String): RemoteEntry? = host.files[path]?.let { RemoteEntry(path, it.size.toLong(), MTIME, true) }

    override fun download(path: String, out: OutputStream, progress: (Long) -> Boolean) {
      waitWhileStuck()
      val bytes = host.files[path] ?: throw IOException("no $path")
      host.breakAfter?.let {
        out.write(bytes, 0, it)
        throw IOException("connection lost")
      }
      out.write(bytes)
      if (!progress(bytes.size.toLong())) throw CancellationException("download of $path stopped")
    }

    override fun upload(input: InputStream, dir: String, name: String, progress: (Long) -> Boolean): String {
      host.failNext?.let {
        host.failNext = null
        throw IOException(it)
      }
      val bytes = input.readBytes()
      waitWhileStuck()
      if (!progress(bytes.size.toLong())) throw CancellationException("upload of $name stopped")
      // A taken name stays with its file, as SftpFiles publishes.
      return (1..100).map { "$dir/${numberedName(name, it)}" }.first { host.files.putIfAbsent(it, bytes) == null }
    }

    private fun waitWhileStuck() {
      while (host.stuck) {
        if (closed) throw IOException("channel closed")
        Thread.sleep(10)
      }
    }

    override fun close() {
      closed = true
    }
  }

  /** The phone: files to send by their URIs, and downloads saved in memory, each in a new place as on Android. */
  private class Phone : PhoneSide {
    val sources = ConcurrentHashMap<String, ByteArray>()
    val saved = ConcurrentHashMap<String, ByteArray>()
    private val saves = AtomicInteger()

    override fun describe(source: String): Pair<String, Long?> = source.substringAfterLast('/') to sources[source]?.size?.toLong()

    override fun read(source: String): InputStream? = sources[source]?.inputStream()

    override fun exists(location: String): Boolean = saved.containsKey(location)

    override fun save(name: String, previous: String?, write: (OutputStream) -> Unit): String {
      val bytes = ByteArrayOutputStream().also(write).toByteArray()
      val location = "content://downloads/${saves.incrementAndGet()}/$name"
      saved[location] = bytes
      previous?.let(saved::remove)
      return location
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val host = Host()
  private val phone = Phone()
  private val shelf = FileShelf(FileBook())
  private val ended = CopyOnWriteArrayList<Pair<Upload, Result<String>>>()
  private val transfers = Transfers(scope, phone, host, { shelf }, uploadEnded = { upload, result -> ended += upload to result }, stallMs = 1_000, graceMs = 100)

  @AfterTest
  fun stop() = scope.cancel()

  private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

  @Test
  fun uploadReachesTheHostAndLeavesTheList() = runBlocking {
    phone.sources["content://picker/фото.png"] = "один".toByteArray()
    transfers.upload(listOf("content://picker/фото.png"), origin = null, forPane = "w1:p1")
    until { ended.isNotEmpty() }
    val (upload, result) = ended.single()
    assertEquals("w1:p1", upload.forPane)
    assertEquals("/home/user/Downloads/herdr-android/фото.png", result.getOrThrow())
    assertEquals("один", host.files.getValue(result.getOrThrow()).decodeToString())
    assertEquals(emptyList(), transfers.uploads.value)
    assertEquals(listOf(result.getOrThrow()), shelf.book.value.sent.map { it.path })
    assertTrue(host.channels.all { it.closed })
  }

  @Test
  fun uploadsOfOneNameKeepBothFiles() = runBlocking {
    phone.sources["content://a/фото.png"] = "один".toByteArray()
    phone.sources["content://b/фото.png"] = "два".toByteArray()
    transfers.upload(listOf("content://a/фото.png", "content://b/фото.png"), origin = null)
    until { ended.size == 2 }
    val paths = ended.map { it.second.getOrThrow() }.toSet()
    assertEquals(setOf("/home/user/Downloads/herdr-android/фото.png", "/home/user/Downloads/herdr-android/фото (2).png"), paths)
    assertEquals(setOf("один", "два"), paths.map { host.files.getValue(it).decodeToString() }.toSet())
  }

  @Test
  fun failedUploadKeepsItsErrorUntilARetryDelivers() = runBlocking {
    phone.sources["content://picker/отчёт.txt"] = "текст".toByteArray()
    host.failNext = "disk full"
    transfers.upload(listOf("content://picker/отчёт.txt"), origin = null, forPane = "w1:p1")
    until { ended.isNotEmpty() }
    assertEquals("disk full", ended.single().second.exceptionOrNull()?.message)
    val failed = transfers.uploads.value.single()
    assertEquals("disk full", failed.transfer.error)
    transfers.retry(failed)
    until { ended.size == 2 }
    assertTrue(ended.last().second.isSuccess)
    assertEquals(emptyList(), transfers.uploads.value)
  }

  @Test
  fun uploadAllWaitsForThePathsInTheOrderGiven() = runBlocking {
    phone.sources["content://a/big.bin"] = ByteArray(64 * 1024)
    phone.sources["content://b/фото.png"] = "один".toByteArray()
    val ends = transfers.uploadAll(listOf("content://a/big.bin", "content://b/фото.png"), origin = null, forPane = "w1:p1")
    assertEquals(listOf("big.bin", "фото.png"), ends.map { it.name })
    val paths = ends.map { it.path.getOrThrow() }
    assertEquals(listOf("/home/user/Downloads/herdr-android/big.bin", "/home/user/Downloads/herdr-android/фото.png"), paths)
    assertEquals("один", host.files.getValue(paths[1]).decodeToString())
    // The caller puts the paths into the prompt: none goes to the pane's prompt on its own.
    assertEquals(emptyList(), ended)
    assertEquals(emptyList(), transfers.uploads.value)
    assertEquals(paths.toSet(), shelf.book.value.sent.map { it.path }.toSet())
  }

  @Test
  fun uploadAllTellsAFailureAndARetryGoesToThePrompt() = runBlocking {
    phone.sources["content://picker/отчёт.txt"] = "текст".toByteArray()
    host.failNext = "disk full"
    val result = transfers.uploadAll(listOf("content://picker/отчёт.txt", "content://picker/нет.txt"), origin = null, forPane = "w1:p1")
    assertEquals(listOf("отчёт.txt", "нет.txt"), result.map { it.name })
    assertEquals("disk full", result[0].path.exceptionOrNull()?.message)
    assertEquals("cannot read нет.txt", result[1].path.exceptionOrNull()?.message)
    assertEquals(emptyList(), ended)
    // A retry from the files window, once the waiting is over, ends as any upload does.
    transfers.retry(transfers.uploads.value.single { it.name == "отчёт.txt" })
    until { ended.isNotEmpty() }
    assertEquals("/home/user/Downloads/herdr-android/отчёт.txt", ended.single().second.getOrThrow())
    assertEquals("w1:p1", ended.single().first.forPane)
  }

  @Test
  fun uploadAllTellsACancel() = runBlocking {
    phone.sources["content://picker/big.bin"] = ByteArray(1024)
    host.stuck = true
    val result = async { transfers.uploadAll(listOf("content://picker/big.bin"), origin = null) }
    until { transfers.uploads.value.isNotEmpty() && host.channels.isNotEmpty() }
    transfers.cancel(transfers.uploads.value.single())
    assertEquals("upload of big.bin cancelled", withTimeout(5_000) { result.await() }.single().path.exceptionOrNull()?.message)
    assertEquals(emptyList(), ended)
  }

  @Test
  fun cancelledStuckUploadLetsGoOfItsChannel() = runBlocking {
    phone.sources["content://picker/big.bin"] = ByteArray(1024)
    host.stuck = true
    transfers.upload(listOf("content://picker/big.bin"), origin = null)
    until { transfers.uploads.value.isNotEmpty() && host.channels.isNotEmpty() }
    transfers.cancel(transfers.uploads.value.single())
    until { transfers.uploads.value.isEmpty() }
    until { host.channels.single().closed }
    assertEquals(emptyList(), ended)
    assertTrue(host.files.isEmpty())
  }

  @Test
  fun downloadSavesThePhonesCopyAndShowsIt() = runBlocking {
    host.files["/srv/report.txt"] = "отчёт".toByteArray()
    val file = FoundFile("/srv/report.txt", 10, MTIME)
    val shown = async { withTimeout(5_000) { transfers.downloaded.first() } }
    delay(50)
    transfers.open(file)
    val downloaded: Downloaded = shown.await()
    assertEquals("report.txt", downloaded.name)
    assertEquals("отчёт", phone.saved.getValue(downloaded.location).decodeToString())
    until { transfers.downloads.value.isEmpty() }
    assertTrue(host.channels.all { it.closed })
  }

  @Test
  fun fileTheHostLostIsMarkedGone() = runBlocking {
    val shelf = FileShelf(FileBook(found = listOf(FoundFile("/srv/gone.txt", 3, MTIME))))
    val transfers = Transfers(scope, phone, host, { shelf })
    transfers.open(shelf.book.value.found.single())
    until { shelf.book.value.found.single().gone }
    until { transfers.downloads.value.isEmpty() }
    assertTrue(phone.saved.isEmpty())
  }

  @Test
  fun freshCopyOnThePhoneOpensWithoutADownload() = runBlocking {
    phone.saved["content://downloads/report.txt"] = "отчёт".toByteArray()
    val file = FoundFile("/srv/report.txt", 10, MTIME, copy = LocalCopy("content://downloads/report.txt", 10, MTIME))
    val shown = async { withTimeout(5_000) { transfers.downloaded.first() } }
    delay(50)
    transfers.open(file)
    assertEquals(Downloaded("content://downloads/report.txt", "report.txt"), shown.await())
    assertTrue(host.channels.isEmpty())
  }

  @Test
  fun changedFileReplacesThePhonesCopyOnlyOnceWhole() = runBlocking {
    val previous = LocalCopy("content://downloads/report.txt", 10, MTIME - 1)
    phone.saved[previous.uri] = "отчёт".toByteArray()
    host.files["/srv/report.txt"] = "новый отчёт".toByteArray()
    val shelf = FileShelf(FileBook(found = listOf(FoundFile("/srv/report.txt", 21, MTIME, copy = previous))))
    val transfers = Transfers(scope, phone, host, { shelf }, stallMs = 1_000, graceMs = 100)
    // The connection drops partway: the shelf keeps the previous copy, the one the phone still has.
    host.breakAfter = 4
    transfers.open(shelf.book.value.found.single())
    until { transfers.downloads.value["/srv/report.txt"]?.error != null }
    assertEquals(previous, shelf.book.value.found.single().copy)
    assertEquals(listOf(previous.uri), phone.saved.keys.toList())
    // The next go brings the whole file, and the shelf has it where the phone put it.
    host.breakAfter = null
    val shown = async { withTimeout(5_000) { transfers.downloaded.first() } }
    delay(50)
    transfers.open(shelf.book.value.found.single())
    val location = shown.await().location
    assertEquals(LocalCopy(location, 21, MTIME), shelf.book.value.found.single().copy)
    assertEquals(listOf(location), phone.saved.keys.toList())
    assertEquals("новый отчёт", phone.saved.getValue(location).decodeToString())
  }

  @Test
  fun cancelledStuckDownloadLetsGoOfItsChannel() = runBlocking {
    host.files["/srv/big.bin"] = ByteArray(1024)
    host.stuck = true
    transfers.open(FoundFile("/srv/big.bin", 1024, MTIME))
    until { transfers.downloads.value.isNotEmpty() && host.channels.isNotEmpty() }
    transfers.cancelDownload("/srv/big.bin")
    until { transfers.downloads.value.isEmpty() }
    until { host.channels.single().closed }
    assertNotNull(host.files["/srv/big.bin"])
    assertTrue(phone.saved.isEmpty())
  }

  @Test
  fun stuckTransferEndsWithAnError() = runBlocking {
    phone.sources["content://picker/big.bin"] = ByteArray(1024)
    host.stuck = true
    transfers.upload(listOf("content://picker/big.bin"), origin = null)
    // Nothing moves for the stall time (a second here): the channel goes, and the window shows why.
    until { transfers.uploads.value.singleOrNull()?.transfer?.error != null }
    assertTrue(transfers.uploads.value.single().transfer.error!!.contains("nothing moved"))
    assertTrue(host.channels.single().closed)
  }

  private companion object {
    const val MTIME = 1_000L
  }
}
