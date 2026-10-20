package dev.artalex.herdr.core

import dev.artalex.herdr.core.files.AddResult
import dev.artalex.herdr.core.files.FileBook
import dev.artalex.herdr.core.files.FileShelf
import dev.artalex.herdr.core.files.LocalCopy
import dev.artalex.herdr.core.files.RemoteEntry
import dev.artalex.herdr.core.files.RemoteFiles
import dev.artalex.herdr.core.files.ScanEntry
import dev.artalex.herdr.core.files.ScanState
import dev.artalex.herdr.core.files.SentFile
import dev.artalex.herdr.core.files.numberedName
import dev.artalex.herdr.core.files.safeFileName
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class FileShelfTest {
  private val home = Files.createTempDirectory("shelf").toFile()
  private val project = File(home, "proj").apply { mkdirs() }
  private val shot = File(home, "shot.png").apply { writeText("png") }
  private val apk = File(project, "app/out.apk").apply { parentFile.mkdirs(); writeText("apk!") }

  init {
    File(home, "pics").mkdirs()
  }

  @AfterTest
  fun cleanUp() {
    home.deleteRecursively()
  }

  /** The host's files, here a folder on this machine; [gate] holds every check until it opens. */
  private inner class LocalFiles(private val gate: CountDownLatch? = null) : RemoteFiles {
    override val home: String = this@FileShelfTest.home.path

    override fun stat(path: String): RemoteEntry? {
      gate?.await()
      val f = File(path)
      return if (f.exists()) RemoteEntry(path, f.length(), f.lastModified() / 1000, f.isFile) else null
    }

    override fun download(path: String, out: OutputStream, progress: (Long) -> Boolean) = error("not used")

    override fun upload(input: InputStream, dir: String, name: String, progress: (Long) -> Boolean): String = error("not used")

    override fun close() = Unit
  }

  private val screen = "Скриншот ~/shot.png\nсобрано: app/out.apk\nнет ~/missing.txt, папка ~/pics"

  private fun entries(shelf: FileShelf) = checkNotNull(shelf.scan.value).map { it.state to it.shown }

  @Test
  fun newPathsShowWhileChecked() = runBlocking {
    val shelf = FileShelf(FileBook())
    val gate = CountDownLatch(1)
    val scan = async(Dispatchers.Default) { shelf.scan(screen, null, project.path, LocalFiles(gate)) }
    withTimeout(5_000) { shelf.scan.first { it != null } }
    assertEquals(
      listOf(ScanState.CHECKING to shot.path, ScanState.CHECKING to apk.path, ScanState.CHECKING to "${home.path}/missing.txt", ScanState.CHECKING to "${home.path}/pics"),
      entries(shelf),
    )
    gate.countDown()
    scan.await()
    // The folder is gone from the window, the missing file is marked; both are remembered as absent.
    assertEquals(listOf(ScanState.FOUND to shot.path, ScanState.FOUND to apk.path, ScanState.MISSING to "${home.path}/missing.txt"), entries(shelf))
    val book = shelf.book.value
    assertEquals(listOf(apk.path, shot.path), book.found.map { it.path })
    assertEquals(listOf("${home.path}/pics", "${home.path}/missing.txt"), book.absent)
    assertEquals(home.path, book.home)
    assertEquals(4L, book.found.first().size)
  }

  @Test
  fun absentPathsStayHiddenUntilTheyTurnUp() = runBlocking {
    val shelf = FileShelf(FileBook())
    shelf.scan(screen, null, project.path, LocalFiles())
    shelf.closeScan()
    assertNull(shelf.scan.value)

    shelf.scan(screen, null, project.path, LocalFiles())
    assertEquals(listOf(ScanState.FOUND to shot.path, ScanState.FOUND to apk.path), entries(shelf))
    shelf.closeScan()

    File(home, "missing.txt").writeText("now here")
    shelf.scan(screen, null, project.path, LocalFiles())
    assertEquals(listOf(ScanState.FOUND to shot.path, ScanState.FOUND to apk.path, ScanState.FOUND to "${home.path}/missing.txt"), entries(shelf))
    assertEquals(listOf("${home.path}/pics"), shelf.book.value.absent)
  }

  @Test
  fun theRestOfTheListIsCheckedToo() = runBlocking {
    val shelf = FileShelf(FileBook())
    shelf.scan(screen, null, project.path, LocalFiles())
    shot.writeText("a bigger png")
    apk.delete()
    shelf.scan("nothing here", null, project.path, LocalFiles())
    assertEquals(emptyList<ScanEntry>(), shelf.scan.value)
    val found = shelf.book.value.found.associateBy { it.path }
    assertEquals(12L, found.getValue(shot.path).size)
    assertFalse(found.getValue(shot.path).gone)
    assertTrue(found.getValue(apk.path).gone)
  }

  @Test
  fun aListedFileOnTheScreenIsFoundAtOnce() = runBlocking {
    val shelf = FileShelf(FileBook())
    shelf.scan(screen, null, project.path, LocalFiles())
    shelf.closeScan()
    val gate = CountDownLatch(1)
    val scan = async(Dispatchers.Default) { shelf.scan("~/shot.png", null, project.path, LocalFiles(gate)) }
    withTimeout(5_000) { shelf.scan.first { it != null } }
    assertEquals(listOf(ScanState.FOUND to shot.path), entries(shelf))
    gate.countDown()
    scan.await()
    assertEquals(listOf(shot.path, apk.path), shelf.book.value.found.map { it.path })
  }

  @Test
  fun forgottenFilesLeaveTheWindowToo() = runBlocking {
    val shelf = FileShelf(FileBook())
    shelf.scan(screen, null, project.path, LocalFiles())
    shelf.forget(shot.path)
    assertEquals(listOf(ScanState.FOUND to apk.path, ScanState.MISSING to "${home.path}/missing.txt"), entries(shelf))
    assertEquals(listOf(apk.path), shelf.book.value.found.map { it.path })
    shelf.forgetAll()
    assertEquals(listOf(ScanState.MISSING to "${home.path}/missing.txt"), entries(shelf))
  }

  @Test
  fun typedPathsAreAdded() = runBlocking {
    val shelf = FileShelf(FileBook())
    assertEquals(AddResult.ADDED, shelf.add(" \"~/shot.png\" ", project.path, LocalFiles()))
    assertEquals(AddResult.ADDED, shelf.add("app/out.apk", project.path, LocalFiles()))
    assertEquals(AddResult.NOT_A_FILE, shelf.add("~/pics", project.path, LocalFiles()))
    assertEquals(AddResult.MISSING, shelf.add("nope.txt", project.path, LocalFiles()))
    assertEquals(AddResult.MISSING, shelf.add("nope.txt", null, LocalFiles()))
    assertEquals(listOf(apk.path, shot.path), shelf.book.value.found.map { it.path })
  }

  @Test
  fun bookKeepsCopiesAndSurvivesSaving() {
    val entry = RemoteEntry("/h/a.png", 10, 100, true)
    var book = FileBook().absent("/h/a.png").seen(entry).seen(RemoteEntry("/h/b.png", 1, 1, true))
    assertEquals(listOf("/h/b.png", "/h/a.png"), book.found.map { it.path })
    assertEquals(emptyList(), book.absent)
    book = book.downloaded("/h/a.png", LocalCopy("content://x/1", 10, 100))
    assertTrue(book.found.last().fresh)
    book = book.checked(entry.copy(size = 11))
    assertFalse(book.found.last().fresh)
    // Seen again: to the top, with its copy.
    book = book.seen(entry.copy(size = 11))
    assertEquals("content://x/1", book.found.first().copy?.uri)
    book = book.sent(SentFile("/h/Downloads/herdr-android/c.jpg", 5))
    assertEquals(book, FileBook.decode(book.encode()))
    assertEquals(FileBook(), FileBook.decode("{не json"))
    assertEquals(FileBook(), FileBook.decode(null))
  }

  @Test
  fun absentPathsAreCapped() {
    val book = (1..FileBook.MAX_ABSENT + 10).fold(FileBook()) { b, i -> b.absent("/p$i") }
    assertEquals(FileBook.MAX_ABSENT, book.absent.size)
    assertEquals("/p${FileBook.MAX_ABSENT + 10}", book.absent.first())
  }

  @Test
  fun uploadNamesSuitTheHost() {
    assertEquals("_a_b_.png", safeFileName("../a/b?.png"))
    assertEquals("file", safeFileName("..."))
    assertEquals("Скриншот 1.png", safeFileName("Скриншот 1.png"))
    assertTrue(safeFileName("я".repeat(300)).toByteArray().size <= 200)
    assertEquals("a.png", numberedName("a.png", 1))
    assertEquals("a (2).png", numberedName("a.png", 2))
    assertEquals("README (3)", numberedName("README", 3))
    assertEquals(".bashrc (2)", numberedName(".bashrc", 2))
  }
}
