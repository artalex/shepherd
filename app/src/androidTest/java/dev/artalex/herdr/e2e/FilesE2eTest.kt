package dev.artalex.herdr.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The files window of the agent screen against real herdr and sshd: paths on the screen are looked up over SFTP,
 * a tap downloads a file into the app's folder in Download and opens it, a path not found shows once and is checked quietly
 * afterwards, and files picked on the phone land on the host with their path going into the prompt.
 * The activities the app starts (a viewer, the document picker) are intercepted.
 */
@OptIn(ExperimentalTestApi::class)
class FilesE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private val container get() = ApplicationProvider.getApplicationContext<HerdrApp>().container
  private lateinit var server: Server
  private val run = System.currentTimeMillis().toString().takeLast(5)
  private lateinit var home: String
  private val dirName = "files-$run"
  private val dir get() = "$home/.cache/herdr-android-e2e/$dirName"
  private val uploads get() = "$home/.cache/herdr-android-e2e/uploads-$run"
  private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()
  private val downloaded = mutableListOf<Uri>()

  @Before
  fun seed() {
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e)
    home = fixture.exec("printf %s \"\$HOME\"")
    server = fixture.connectApp(container)
    server.files.uploadDirOverride = "~/.cache/herdr-android-e2e/uploads-$run"
  }

  @After
  fun cleanUp() {
    if (::server.isInitialized) server.files.uploadDirOverride = null
    monitors.forEach { InstrumentationRegistry.getInstrumentation().removeMonitor(it) }
    val resolver = ApplicationProvider.getApplicationContext<HerdrApp>().contentResolver
    downloaded.forEach { runCatching { resolver.delete(it, null, null) } }
    if (::fixture.isInitialized) {
      if (::home.isInitialized) fixture.exec("rm -rf '$dir' '$uploads'")
      fixture.close()
    }
  }

  /** Answers the app's requests to start [action] with [answer], and keeps the intents. */
  private fun intercept(action: String, answer: Instrumentation.ActivityResult): MutableList<Intent> {
    val seen = CopyOnWriteArrayList<Intent>()
    val monitor =
      object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
          if (intent.action == action) answer.also { seen += intent } else null
      }
    InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
    monitors += monitor
    return seen
  }

  private fun openPane(paneId: String) {
    compose.scrollListTo(paneTag(paneId))
    compose.onNodeWithTag(paneTag(paneId)).performClick()
    compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
  }

  private fun openFiles() {
    compose.onNodeWithTag("files").performClick()
    compose.waitUntilExactlyOneExists(hasTestTag("filesSheet"), 10_000)
  }

  private fun sheetShown() = compose.onAllNodes(hasTestTag("filesSheet")).fetchSemanticsNodes().isNotEmpty()

  /** Back closes the window; with the keyboard up the first press only hides the keyboard. */
  private fun closeFiles() {
    repeat(2) {
      if (sheetShown()) {
        Espresso.pressBack()
        compose.waitForIdle()
      }
    }
    compose.waitUntil(10_000) { !sheetShown() }
  }

  /** The pane's PTY has the phone's width, as `stty size` in it says. */
  private fun waitForPhoneWidth(pane: String) {
    val deadline = System.currentTimeMillis() + 20_000
    while (true) {
      fixture.type(pane, "clear; stty size")
      val size = runCatching { fixture.waitForScreen(pane, 3_000, pump = { compose.waitForIdle() }) { fixture.sttySize(pane) != null }.let { fixture.sttySize(pane) } }.getOrNull()
      if (size != null && size.second < HostFixture.DESKTOP_SIZE.second) return
      check(System.currentTimeMillis() < deadline) { "the pane never took the phone's width: $size" }
      Thread.sleep(500)
    }
  }

  private fun exists(tag: String, list: String = "incoming") = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty().also {
    if (!it) runCatching { compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)) }
  }

  private fun waitFor(tag: String, list: String = "incoming", timeoutMs: Long = 15_000) = compose.waitUntil(timeoutMs) { exists(tag, list) }

  private fun scrollTo(tag: String, list: String = "incoming") = compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))

  @Test
  fun pathsOnTheScreenDownloadAndOpen() {
    // The long name wraps over several rows at the phone's width; the relative path starts in the shell's folder.
    val shot = "$dir/shot-$run.png"
    val long = "$dir/pictures/очень-длинное-имя-скриншота-чтобы-путь-не-влез-в-строку-$run.png"
    val apk = "$dir/build/app-$run.apk"
    val missing = "$dir/нет-такого-$run.txt"
    val spaced = "$dir/с пробелом $run.txt"
    fixture.exec(
      "mkdir -p '$dir/pictures' '$dir/build' && head -c 3000 /dev/urandom > '$shot' && echo картинка > '$long' && head -c 5000 /dev/urandom > '$apk' && echo текст > '$spaced'"
    )
    val shotBytes = fixture.exec("base64 -w0 '$shot'").let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
    val pane = fixture.workspace("e2e-files-$run")
    fixture.type(pane, "cd ~/.cache/herdr-android-e2e/$dirName && clear")
    val views = intercept(Intent.ACTION_VIEW, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))

    launchApp(compose, "pathsOnTheScreenDownloadAndOpen") {
      openPane(pane)
      // Printed once the pane has the phone's width, so the terminal wraps the long path.
      waitForPhoneWidth(pane)
      val tilde = "~/.cache/herdr-android-e2e/$dirName"
      fixture.type(pane, "clear; printf '%s\\n' 'Скриншот: $tilde/shot-$run.png.' 'Длинный: $tilde/pictures/очень-длинное-имя-скриншота-чтобы-путь-не-влез-в-строку-$run.png' 'Сборка: build/app-$run.apk' 'Нет: $tilde/нет-такого-$run.txt'")
      fixture.waitForScreen(pane, pump = { compose.waitForIdle() }) { it.contains("Нет:") }

      openFiles()
      waitFor("found-$shot")
      waitFor("found-$long")
      waitFor("found-$apk")
      waitFor("path-$missing")
      compose.onNodeWithTag("path-$missing").assert(hasText(text(R.string.files_missing), substring = true))
      scrollTo("found-$shot")
      Screens.take(compose, "12a-files-incoming")

      // A tap downloads the file and opens it in a viewer.
      compose.onNodeWithTag("found-$shot").performClick()
      compose.waitUntil(15_000) { views.isNotEmpty() }
      val view = views.single()
      assertEquals("image/png", view.type)
      assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
      val uri = checkNotNull(view.data).also { downloaded += it }
      val resolver = ApplicationProvider.getApplicationContext<HerdrApp>().contentResolver
      assertArrayEquals(shotBytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
      compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("found-$shot") and hasText("· " + text(R.string.files_downloaded), substring = true)).fetchSemanticsNodes().isNotEmpty() }

      // An APK opens in the package installer.
      scrollTo("found-$apk")
      compose.onNodeWithTag("found-$apk").performClick()
      compose.waitUntil(15_000) { views.size == 2 }
      assertEquals("application/vnd.android.package-archive", views[1].type)
      downloaded += checkNotNull(views[1].data)

      // A path with spaces isn't found on the screen; typed by hand it joins the list.
      compose.onNodeWithTag("pathField").performTextInput(spaced)
      compose.onNodeWithTag("addPath").performClick()
      waitFor("found-$spaced")
      compose.waitForIdle()
      assertTrue("adding a path should leave no error", compose.onAllNodes(hasText(text(R.string.files_no_connection))).fetchSemanticsNodes().isEmpty())
      scrollTo("found-$shot")
      Screens.take(compose, "12b-files-downloaded")
      closeFiles()

      // Opened again: the path not found stays hidden, the files are still there.
      openFiles()
      waitFor("found-$shot")
      waitFor("found-$long")
      compose.waitForIdle()
      Thread.sleep(1_000)
      assertTrue("a path not found shows only once", compose.onAllNodes(hasTestTag("path-$missing")).fetchSemanticsNodes().isEmpty())
      closeFiles()

      // Once the file turns up it's found, though it isn't shown as missing any more.
      fixture.exec("echo появился > '$missing'")
      openFiles()
      waitFor("found-$missing")

      // A swipe takes a file off the list.
      scrollTo("found-$long")
      compose.onNodeWithTag("found-$long").performTouchInput { swipeLeft() }
      compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("found-$long")).fetchSemanticsNodes().isEmpty() }
      assertTrue(server.files.shelf.book.value.found.none { it.path == long })
      closeFiles()
    }
  }

  @Test
  fun pickedFilesGoToTheHostAndTheirPathIntoThePrompt() {
    val pane = fixture.workspace("e2e-send-$run")
    fixture.fakeClaude(pane, "idle")
    val context = ApplicationProvider.getApplicationContext<HerdrApp>()
    val photo = File(context.cacheDir, "фото-$run.png").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
    intercept(Intent.ACTION_OPEN_DOCUMENT, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(photo))))

    try {
      launchApp(compose, "pickedFilesGoToTheHostAndTheirPathIntoThePrompt") {
        openPane(pane)
        compose.waitUntilExactlyOneExists(hasTestTag("prompt"), 15_000)
        openFiles()
        compose.onNodeWithTag("tab-outgoing").performClick()
        compose.onNodeWithTag("sendFiles").performClick()
        val first = "$uploads/фото-$run.png"
        waitFor("sent-$first", "outgoing")
        assertEquals("4096", fixture.exec("stat -c %s '$first'").trim())
        assertEquals(fixture.exec("sha256sum < '$first'").substringBefore(' '), sha256(photo.readBytes()))

        // The same name again: the first file stays, the new one is numbered.
        compose.onNodeWithTag("sendFiles").performClick()
        val second = "$uploads/фото-$run (2).png"
        waitFor("sent-$second", "outgoing")
        assertEquals("", fixture.exec("ls -A '$uploads' | grep -F .part || true").trim())
        Screens.take(compose, "12c-files-outgoing")

        // A tap puts the path into the prompt, in quotes for its space, and closes the window.
        closeFiles()
        compose.onNodeWithTag("prompt").performTextInput("посмотри")
        openFiles()
        compose.onNodeWithTag("tab-outgoing").performClick()
        compose.onNodeWithTag("sent-$second").performClick()
        compose.waitUntil(10_000) { !sheetShown() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("prompt") and hasText("посмотри \"$second\" ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        Screens.take(compose, "12d-files-path-in-prompt")
      }
    } finally {
      photo.delete()
    }
  }

  private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
