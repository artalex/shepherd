package dev.artalex.herdr.e2e

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.MainActivity
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import dev.artalex.herdr.ShareActivity
import dev.artalex.herdr.core.snippets.Snippet
import dev.artalex.herdr.data.TelegramBotSettings
import dev.artalex.herdr.notify.Alerts
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Sharing from other apps against real herdr and sshd. A share opens the agent list, which asks for the agent; the
 * text goes into the prompt of the agent picked, the files to the host with their paths after the text. The first share
 * starts the app, the next ones come to it while it shows an agent. The picture comes from the shell as from another
 * app, with read access to it only, as the share menu gives. A new agent started for a share may get a task with it,
 * which goes on in the background, the app off the screen.
 */
@OptIn(ExperimentalTestApi::class)
class ShareE2eTest {
  @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK", "android.permission.POST_NOTIFICATIONS")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var server: Server
  private val context get() = ApplicationProvider.getApplicationContext<HerdrApp>()
  private val run = System.currentTimeMillis().toString().takeLast(5)
  private lateinit var home: String
  private val uploads get() = "$home/.cache/herdr-android-e2e/share-$run"
  private val tokens get() = "$home/.cache/herdr-android-e2e/telegram-$run"
  private val phoneFiles = mutableListOf<File>()
  private val folders = mutableListOf<String>()
  private var picture: String? = null
  private var snippets: List<Snippet> = emptyList()

  @Before
  fun seed() {
    // The shares start the app themselves: no activity of an earlier test to come to instead.
    finishActivities()
    val e2e = E2eArgs.require()
    fixture = HostFixture(e2e)
    home = fixture.exec("printf %s \"\$HOME\"")
    server = fixture.connectApp(context.container)
    server.files.uploadDirOverride = "~/.cache/herdr-android-e2e/share-$run"
    snippets = context.container.snippets.snippets.value
    // The token for agents goes to a folder of the test's own, never to the user's config folder on the machine.
    context.container.telegram.tokenDirOverride = tokens
  }

  @After
  fun cleanUp() {
    finishActivities()
    if (::server.isInitialized) server.files.uploadDirOverride = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      context.container.snippets.set(snippets)
      context.container.telegram.set(null)
      context.container.telegram.setAnswerThere(false)
      context.container.telegram.tokenDirOverride = null
    }
    phoneFiles.forEach { it.delete() }
    picture?.let { Device.shell("content delete --uri $it") }
    context.getSystemService(NotificationManager::class.java).cancelAll()
    if (::fixture.isInitialized) {
      if (::home.isInitialized) {
        for (folder in folders) fixture.panes().filter { it.field("cwd") == folder }.forEach { fixture.closePane(checkNotNull(it.field("pane_id"))) }
        fixture.exec("rm -rf '$uploads' '$tokens' ${folders.joinToString(" ") { "'$it'" }}")
      }
      fixture.close()
    }
  }

  @Test
  fun sharesGoToTheAgentPicked() {
    val first = fixture.workspace("e2e-share-$run")
    fixture.fakeClaude(first, "idle")
    val second = fixture.workspace("e2e-share2-$run")
    fixture.fakeClaude(second, "idle")
    val shell = fixture.workspace("e2e-share3-$run")
    shotOnFailure(compose, "sharesGoToTheAgentPicked") {
      // An error copied in a browser starts the app on the list, which asks for the agent.
      share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "TypeError: x is undefined $run\n    at main.js:12\n"))
      waitForPicking("TypeError: x is undefined $run")
      compose.scrollListTo(paneTag(first))
      Screens.take(compose, "18a-share-pick-agent")
      pick(first)
      waitForPrompt("TypeError: x is undefined $run\n    at main.js:12 ")
      Screens.take(compose, "18b-share-text-in-prompt")

      // A picture from another app with a line about it comes to the app on screen, not to a second copy of it, and
      // leaves the agent shown for the list again. Its path follows the line once the picture is on the host.
      val bytes = picturePng()
      val uri = pictureFromAnotherApp("shot-$run.png", bytes)
      Device.shell(
        "am start -n ${context.packageName}/${ShareActivity::class.java.name} -a android.intent.action.SEND -t image/png " +
          "-d $uri --eu android.intent.extra.STREAM $uri --es android.intent.extra.TEXT look_at_this --grant-read-uri-permission"
      )
      waitForPicking("look_at_this · shot-$run.png")
      assertEquals("activities of the app", 1, mainActivities().size)
      pick(second)
      val shot = "$uploads/shot-$run.png"
      waitForPrompt("look_at_this $shot ", timeoutMs = 30_000)
      assertEquals(sha256(bytes), fixture.sha256(shot))

      // Two files from the phone: both go to the host, the path with a space goes into the prompt in quotes.
      val logs = listOf(phoneFile("сборка $run.log", "собрано $run"), phoneFile("тесты-$run.log", "прошли $run"))
      share(Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(logs.map(Uri::fromFile))))
      waitForPicking("сборка $run.log · тесты-$run.log")
      pick(first)
      waitForPrompt("\"$uploads/сборка $run.log\" ", timeoutMs = 30_000)
      waitForPrompt("$uploads/тесты-$run.log ", timeoutMs = 30_000)
      assertEquals("собрано $run", fixture.exec("cat '$uploads/сборка $run.log'"))
      assertEquals("прошли $run", fixture.exec("cat '$uploads/тесты-$run.log'"))
      Screens.take(compose, "18c-share-files-in-prompt")

      // A file that doesn't get to the host says so on the agent screen.
      val missing = File(context.cacheDir, "нет-$run.log")
      share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(missing)))
      waitForPicking(missing.name)
      pick(first)
      compose.waitUntil(30_000) { shows(hasText(missing.name, substring = true) and hasText(textAfterArg(R.string.share_not_sent, args = 2), substring = true)) }

      // A shell has no prompt field: the text and the path go to the clipboard together, for its terminal to paste.
      val note = phoneFile("заметка-$run.txt", "заметка $run")
      share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "cat").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(note)))
      waitForPicking("cat · заметка-$run.txt")
      pick(shell)
      compose.waitUntil(30_000) { clipboard() == "cat $uploads/заметка-$run.txt" }
      compose.waitUntil(10_000) { shows(hasText(text(R.string.share_copied))) }

      // The cross drops a share: the list is the usual one again, and Back from it leaves the app.
      share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "не нужно $run"))
      waitForPicking("не нужно $run")
      compose.onNodeWithTag("dropShare").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("openSettings"), 10_000)
      assertTrue(compose.onAllNodes(hasText(text(R.string.share_title))).fetchSemanticsNodes().isEmpty())
      Espresso.closeSoftKeyboard()
      Espresso.pressBackUnconditionally()
      Device.waitFor("the app closed") { mainActivities().isEmpty() }
    }
  }

  @Test
  fun taskGoesToANewAgentInTheBackground() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { context.container.snippets.set(listOf(Snippet("/effort max", onStart = "claude"))) }
    val folder = hostFolder()
    shotOnFailure(compose, "taskGoesToANewAgentInTheBackground") {
      // A picture from another app, and a task for a new agent with it.
      val bytes = picturePng()
      val uri = pictureFromAnotherApp("dish-$run.png", bytes)
      Device.shell(
        "am start -n ${context.packageName}/${ShareActivity::class.java.name} -a android.intent.action.SEND -t image/png " +
          "-d $uri --eu android.intent.extra.STREAM $uri --grant-read-uri-permission"
      )
      waitForPicking("dish-$run.png")
      openNewAgent(folder)
      // Without a bot in the settings the answer can't go to Telegram.
      assertTrue(compose.onAllNodes(hasTestTag("newAgentTelegram")).fetchSemanticsNodes().isEmpty())
      compose.onNodeWithTag("newAgentTask").performTextReplacement("Find the recipe of this dish $run")
      Screens.take(compose, "18d-share-task")
      compose.onNodeWithTag("startNewAgent").performClick()

      // The app goes off the screen, back to where the share came from, and the rest happens in the background.
      Device.waitFor("the app left the screen") { !context.container.servers.foreground.value }
      assertEquals("activities of the app", 1, mainActivities().size)
      val pane = newAgentIn(folder)
      // Longer than Android lets an app in the background keep its network on its own.
      Thread.sleep(8_000)
      fixture.reportAgent(pane, "idle")
      val shot = "$uploads/dish-$run.png"
      val screen = fixture.waitForScreen(pane, timeoutMs = 30_000) { it.contains("Find the recipe of this dish $run") && it.contains(shot) }
      // The start snippet first, then the task with the path of the picture.
      assertTrue(screen, screen.indexOf("/effort max") in 0 until screen.indexOf("Find the recipe of this dish $run"))
      assertEquals(sha256(bytes), fixture.sha256(shot))
      assertEquals("", draftOf(pane))
      assertTrue("the app stayed off the screen", !context.container.servers.foreground.value)
    }
  }

  @Test
  fun taskAsksForTheAnswerInTelegram() {
    val token = "4242:e2e-token-$run"
    InstrumentationRegistry.getInstrumentation().runOnMainSync { context.container.telegram.set(TelegramBotSettings(token, "e2e_bot", 4242, "E2E")) }
    val folder = hostFolder()
    shotOnFailure(compose, "taskAsksForTheAnswerInTelegram") {
      // A link from a browser, and what to do with it: the link goes with the task, after it.
      share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/recipe-$run"))
      waitForPicking("https://example.com/recipe-$run")
      openNewAgent(folder)
      assertEquals("", compose.onNodeWithTag("newAgentTask").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
      compose.onNodeWithTag("newAgentTask").performTextReplacement("Summarize this page $run")
      compose.onNodeWithTag("newAgentTelegram").performClick()
      Screens.take(compose, "18e-share-task-telegram")
      compose.onNodeWithTag("startNewAgent").performClick()

      Device.waitFor("the app left the screen") { !context.container.servers.foreground.value }
      val pane = newAgentIn(folder)
      fixture.reportAgent(pane, "idle")
      val tokenFile = "$tokens/telegram-bot-token"
      // The agent learns where the token is, not the token itself: that waits in a file only the user may read, and goes
      // to curl on its stdin. The terminal's echo of the pasted prompt and the fake agent's copy of it come mixed up: the
      // copy is whole once it is all there. The prompt's long line runs over several rows, which lose the spaces they
      // end with: the screen is read without any.
      val send = "printf 'url = \"https://api.telegram.org/bot%s/sendMessage\"\\n' \"\$(cat $tokenFile)\" | curl -sS -K - -d chat_id=4242 --data-urlencode".bare()
      val screen = fixture.waitForScreen(pane, timeoutMs = 30_000) { it.bare().let { rows -> rows.contains(send) && rows.contains("Summarizethispage$run") } }
      val rows = screen.bare()
      // The task first, then the link.
      assertTrue(screen, rows.indexOf("Summarizethispage$run") in 0 until rows.indexOf("https://example.com/recipe-$run"))
      assertTrue(screen, !rows.contains(token))
      assertEquals(token, fixture.exec("cat '$tokenFile'"))
      assertEquals("600", fixture.exec("stat -c %a '$tokenFile'").trim())
      // The next task starts out asking for Telegram too.
      assertTrue(context.container.telegram.answerThere.value)
    }
  }

  @Test
  fun taskThatDoesNotGetThroughWaitsInThePrompt() {
    val folder = hostFolder()
    val manager = context.getSystemService(NotificationManager::class.java)
    shotOnFailure(compose, "taskThatDoesNotGetThroughWaitsInThePrompt") {
      val missing = File(context.cacheDir, "нет-$run.log")
      share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(missing)))
      waitForPicking(missing.name)
      openNewAgent(folder)
      compose.onNodeWithTag("newAgentTask").performTextReplacement("Read the log $run")
      compose.onNodeWithTag("startNewAgent").performClick()

      // The file never gets to the host: a notification says so, and the task waits in the agent's prompt.
      Device.waitFor("the app left the screen") { !context.container.servers.foreground.value }
      val pane = newAgentIn(folder)
      val tag = Alerts.tag(HostFixture.SERVER_ID, pane)
      Device.waitFor("task notification", timeoutMs = 30_000) { manager.activeNotifications.any { it.id == Alerts.TASK_ID && it.tag == tag } }
      val shown = manager.activeNotifications.first { it.id == Alerts.TASK_ID && it.tag == tag }
      val said = shown.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
      assertTrue(said, said.contains(missing.name))
      assertTrue(draftOf(pane).startsWith("Read the log $run"))

      // A tap opens the agent with the task in its prompt, and the notification has done its job.
      shown.notification.contentIntent.send()
      fixture.reportAgent(pane, "idle")
      waitForPrompt("Read the log $run", timeoutMs = 30_000)
      Device.waitFor("notification taken down") { manager.activeNotifications.none { it.id == Alerts.TASK_ID && it.tag == tag } }
    }
  }

  @Test
  fun blankTaskShowsTheNewAgent() {
    val folder = hostFolder()
    shotOnFailure(compose, "blankTaskShowsTheNewAgent") {
      // No task: the agent's screen shows, as it always did, for the user to write the prompt there.
      val log = phoneFile("журнал-$run.log", "журнал $run")
      share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(log)))
      waitForPicking(log.name)
      openNewAgent(folder)
      compose.onNodeWithTag("startNewAgent").performClick()
      compose.waitUntil(30_000) { shows(hasTestTag("quickKeys")) }
      assertTrue(context.container.servers.foreground.value)
      Device.waitFor("the file on the host", timeoutMs = 30_000) { fixture.exec("cat '$uploads/${log.name}' 2>/dev/null") == "журнал $run" }
    }
  }

  /** A new folder on the host for a test's agent, as the host resolves it; it goes after the test with its panes. */
  private fun hostFolder(): String = fixture.tempFolder("task").also { folders += it }

  /** Opens the dialog for a new agent on the e2e server, for the share, with [folder] in its folder field. */
  private fun openNewAgent(folder: String) {
    compose.openNewAgent()
    compose.onNodeWithTag("newAgentFolder").performTextReplacement(folder)
  }

  /** The agent the app started in [folder]: its pane, once herdr runs it. */
  private fun newAgentIn(folder: String): String =
    checkNotNull(fixture.waitForPanes(timeoutMs = 30_000) { panes -> panes.any { it.field("cwd") == folder && it.field("agent") == "claude" } }.first { it.field("cwd") == folder }.field("pane_id"))

  /** This text without its whitespace. */
  private fun String.bare(): String = filterNot { it.isWhitespace() }

  /** The text in the prompt field of pane [paneId], whether its screen shows or not. */
  private fun draftOf(paneId: String): String {
    var text = ""
    InstrumentationRegistry.getInstrumentation().runOnMainSync { text = server.drafts.of(paneId).value.text }
    return text
  }

  /**
   * Shares as another app does through the share menu: the share activity, in a task of its own, with read access to
   * the files given outright, as Android 18 will want it.
   */
  private fun share(intent: Intent) {
    context.startActivity(intent.setClass(context, ShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
  }

  /**
   * Whether nodes matching [matcher] show. Until a share activity is gone, the app's activity is paused, and Compose
   * tests find no screen at all: then nothing shows yet.
   */
  private fun shows(matcher: SemanticsMatcher) = runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

  /** The list asks for the agent of a share whose text and file names read [summary]. */
  private fun waitForPicking(summary: String) {
    compose.waitUntil(15_000) { shows(hasTestTag("shareSummary") and hasText(summary, substring = true)) }
    compose.waitUntilExactlyOneExists(hasText(text(R.string.share_title)), 5_000)
  }

  /** Picks pane [paneId] on the list for the share. */
  private fun pick(paneId: String) {
    compose.scrollListTo(paneTag(paneId))
    compose.onNodeWithTag(paneTag(paneId)).performClick()
  }

  private fun waitForPrompt(text: String, timeoutMs: Long = 15_000) = compose.waitUntil(timeoutMs) { shows(hasTestTag("prompt") and hasText(text, substring = true)) }

  /** The text on the clipboard: the app on screen may read it. Waited for with Compose, which lets the screen get there. */
  private fun clipboard(): String? {
    var text: String? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
    }
    return text
  }

  private fun phoneFile(name: String, text: String) = File(context.cacheDir, name).apply { writeText(text) }.also { phoneFiles += it }

  /** A small PNG, as a screenshot would be. */
  private fun picturePng(): ByteArray {
    val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(0x4c, 0xaf, 0x50)) }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }

  /**
   * A picture in the phone's gallery that the shell owns, as another app's would be: the app may read it only with
   * the access a share gives. Returns its content URI; [name] must have no spaces, as shell arguments go apart at them.
   */
  private fun pictureFromAnotherApp(name: String, bytes: ByteArray): String {
    val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    Device.shell("content insert --uri $images --bind _display_name:s:$name --bind mime_type:s:image/png")
    val row = Device.shell("content query --uri $images --projection _id --where _display_name='$name'")
    val uri = "$images/" + checkNotNull(Regex("_id=(\\d+)").find(row)) { "no picture $name in the gallery: $row" }.groupValues[1]
    picture = uri
    Device.shell("content write --uri $uri", stdin = bytes)
    return uri
  }

  /** The app's main activities alive now, in any stage. */
  private fun mainActivities(): List<Activity> {
    var found = emptyList<Activity>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val monitor = ActivityLifecycleMonitorRegistry.getInstance()
      found = Stage.entries.filter { it != Stage.DESTROYED }.flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<MainActivity>()
    }
    return found
  }

  /** Closes the app's main activities, as ActivityScenario closes the one it starts. */
  private fun finishActivities() {
    val open = mainActivities()
    InstrumentationRegistry.getInstrumentation().runOnMainSync { open.forEach { it.finish() } }
    Device.waitFor("activities closed") { mainActivities().isEmpty() }
  }
}
