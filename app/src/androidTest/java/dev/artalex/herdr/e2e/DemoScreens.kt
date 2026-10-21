package dev.artalex.herdr.e2e

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.MainActivity
import dev.artalex.herdr.R
import dev.artalex.herdr.Server
import dev.artalex.herdr.core.ssh.SshKeyPair
import dev.artalex.herdr.e2e.Device.shell
import dev.artalex.herdr.e2e.Device.waitFor
import dev.artalex.herdr.notify.Alerts
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Screenshots of the app for posts about it, in the emulator's language: English on the e2e AVD. The scene:
 * two servers with agents of Claude Code, Codex and Gemini CLI that ask, work, are done and are idle, a shell, files to
 * download and to send, and a notification. The agents are demo_agent.py drawing what such an agent shows, in projects
 * that live in ~/projects on the host for the run. The status bar is Android's demo one.
 *
 * Only scripts/screenshots.sh runs it (it sets `demoScreens`); the e2e run skips it.
 */
@OptIn(ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = 33)
class DemoScreens {
  @get:Rule
  val permissions: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK", "android.permission.POST_NOTIFICATIONS")
  @get:Rule val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val args = InstrumentationRegistry.getArguments()
  private val app get() = ApplicationProvider.getApplicationContext<HerdrApp>()
  private val container get() = app.container
  private val notifications get() = app.getSystemService(NotificationManager::class.java)

  private lateinit var work: HostFixture
  private lateinit var build: HostFixture
  private lateinit var home: String
  private lateinit var workServer: Server
  private var madeProjects = false
  private var nightMode = "no"
  private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()
  private val downloaded = CopyOnWriteArrayList<Uri>()

  private lateinit var ask: String
  private lateinit var codex: String
  private lateinit var shellPane: String
  private lateinit var done: String
  private lateinit var gemini: String

  @Before
  fun stage() {
    assumeTrue("screenshots: run scripts/screenshots.sh", args.getString("demoScreens") != null)
    val e2e = E2eArgs.require()
    work = HostFixture(e2e)
    build = HostFixture(e2e, e2e.session2)
    home = work.exec("printf %s \"\$HOME\"")
    check(work.exec("[ -e ~/projects ] && echo taken || true").isBlank()) { "~/projects exists on the host: the demo needs it for its projects, which it removes after" }
    madeProjects = true
    makeProjects()
    work.exec("mkdir -p ~/.cache/herdr-android-e2e && cat > ~/.cache/herdr-android-e2e/demo_agent.py", asset("demo_agent.py"))
    stageWorkstation()
    stageBuildServer()

    instrumentation.runOnMainSync {
      container.servers.clear()
      container.notifications.setEnabled(true)
      container.notifications.setWatchMinutes(5)
    }
    // The test key under the name a phone would give it: the comment plays no part in signing in.
    container.keys.store(SshKeyPair(e2e.key.privateKey, e2e.key.publicKeyLine.split(' ').take(2).joinToString(" ") + " herdr-android@Pixel-9"))
    workServer = work.addServer(container, WORK, "workstation")
    build.addServer(container, BUILD, "build-server")
    workServer.files.uploadDirOverride = "~/projects/shop-api/uploads"

    nightMode = shell("cmd uimode night").substringAfter(':').trim()
    shell("cmd uimode night yes")
    demoStatusBar(true)
    snoozeOthers()
  }

  @After
  fun cleanUp() {
    if (!::work.isInitialized) return
    demoStatusBar(false)
    shell("cmd uimode night $nightMode")
    monitors.forEach { instrumentation.removeMonitor(it) }
    downloaded.forEach { runCatching { app.contentResolver.delete(it, null, null) } }
    if (::workServer.isInitialized) workServer.files.uploadDirOverride = null
    instrumentation.runOnMainSync {
      container.watch.stopWatch()
      container.notifications.setEnabled(false)
    }
    notifications.cancelAll()
    if (madeProjects) work.exec("rm -rf ~/projects ~/.cache/herdr-android-e2e/demo_agent.py")
    work.close()
    build.close()
  }

  @Test
  fun screens() {
    launchApp(compose, "demo") {
      // The agent list: two servers and every status.
      waitStatus(WORK, ask, R.string.status_blocked)
      waitStatus(WORK, codex, R.string.status_working)
      waitStatus(WORK, done, R.string.status_done)
      waitStatus(BUILD, gemini, R.string.status_idle)
      compose.scrollListTo(paneTag(shellPane, WORK))
      compose.onNodeWithTag("board").performScrollToNode(hasTestTag("server-$WORK"))
      shoot("01-agents")

      // An agent with a question, answered with the keys under its screen.
      compose.onNodeWithTag(paneTag(ask, WORK)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      work.waitForScreen(ask, 20_000, pump = { compose.waitForIdle() }) { it.contains("Do you want to proceed?") && it.contains("╭───") }
      settle()
      shoot("02-agent")

      compose.onNodeWithTag("paneSwitcher").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("paneSwitcherMenu"), 5_000)
      shoot("03-pane-switcher")
      Espresso.pressBack()

      compose.onNodeWithTag("paneMenu").performClick()
      settle()
      shoot("04-pane-menu")
      Espresso.pressBack()

      // Files the agent mentions, one of them downloaded, and files sent from the phone.
      intercept(Intent.ACTION_VIEW, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)) { intent -> intent.data?.let(downloaded::add) }
      compose.onNodeWithTag("files").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("filesSheet"), 10_000)
      val flow = "$home/projects/shop-api/docs/checkout-flow.png"
      val code = "$home/projects/shop-api/src/checkout/total.ts"
      compose.waitUntil(15_000) { listOf(flow, code).all { compose.onAllNodes(hasTestTag("found-$it")).fetchSemanticsNodes().isNotEmpty() } }
      compose.onNodeWithTag("found-$flow").performClick()
      compose.waitUntil(15_000) {
        compose.onAllNodes(hasTestTag("found-$flow") and hasText(text(R.string.files_downloaded), substring = true)).fetchSemanticsNodes().isNotEmpty()
      }
      settle()
      shoot("05-files-incoming")

      val photo = phoneFile("whiteboard.jpg", 184_320)
      val log = phoneFile("crash-log.txt", 12_288)
      val picked = Intent().apply { clipData = ClipData.newRawUri("", Uri.fromFile(photo)).apply { addItem(ClipData.Item(Uri.fromFile(log))) } }
      intercept(Intent.ACTION_OPEN_DOCUMENT, Instrumentation.ActivityResult(Activity.RESULT_OK, picked))
      compose.onNodeWithTag("tab-outgoing").performClick()
      compose.onNodeWithTag("sendFiles").performClick()
      val uploads = "$home/projects/shop-api/uploads"
      compose.waitUntil(15_000) {
        listOf("whiteboard.jpg", "crash-log.txt").all { compose.onAllNodes(hasTestTag("sent-$uploads/$it")).fetchSemanticsNodes().isNotEmpty() }
      }
      settle()
      shoot("06-files-outgoing")
      repeat(2) {
        if (compose.onAllNodes(hasTestTag("filesSheet")).fetchSemanticsNodes().isNotEmpty()) {
          Espresso.pressBack()
          compose.waitForIdle()
        }
      }
      compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filesSheet")).fetchSemanticsNodes().isEmpty() }

      // The full terminal of the same pane, the keyboard up.
      compose.onNodeWithTag("openTerminal").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("extraKeys"), 10_000)
      compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("terminalOverlay")).fetchSemanticsNodes().isEmpty() }
      compose.onNodeWithTag("terminal").performClick()
      settle(2_500)
      shoot("07-terminal")
      repeat(3) {
        Espresso.pressBack()
        compose.waitForIdle()
      }
      compose.waitUntilExactlyOneExists(hasTestTag("board"), 10_000)

      // Off screen, Codex asks something: a notification, and the watch's own.
      Device.goHome(container)
      waitFor("watch on") { container.watch.until.value != null }
      report(work, codex, "codex", "blocked")
      waitFor("notification") { notifications.activeNotifications.any { it.tag == Alerts.tag(WORK, codex) } }
      shade("08-notification")
      app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
      waitFor("app on screen") { container.servers.foreground.value }
      // Its icon would stay in the status bar of the last shot.
      notifications.cancel(Alerts.tag(WORK, codex), Alerts.ALERT_ID)

      // The notification settings, scrolled up to the top of the screen.
      compose.waitUntilExactlyOneExists(hasTestTag("openSettings"), 10_000)
      compose.onNodeWithTag("openSettings").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("lastCheck"), 10_000)
      val servers = compose.onNodeWithText(text(R.string.servers_title)).fetchSemanticsNode().boundsInRoot.top
      val section = compose.onNodeWithText(text(R.string.notify_title)).fetchSemanticsNode().boundsInRoot.top
      compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, section - servers) }
      settle()
      shoot("09-settings")
    }
  }

  private fun shoot(name: String) {
    // One posted since the last snooze would show its icon.
    if (snoozeOthers()) settle()
    Screens.take(compose, "demo-$name")
  }

  /** Lets the screen catch up with what the host drew. */
  private fun settle(ms: Long = 1_500) {
    val until = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < until) {
      compose.waitForIdle()
      Thread.sleep(100)
    }
  }

  private fun waitStatus(server: String, pane: String, status: Int) {
    compose.scrollListTo(paneTag(pane, server))
    compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(paneTag(pane, server)).assert(hasAnyDescendant(hasText(text(status)))) }.isSuccess }
  }

  /**
   * Snoozes the emulator's own notifications for the length of a run: their icons would be in the status bar of the
   * shots, and they in the shade. Returns whether there were any. In the dump only those on show have `key=`; a key is
   * user|package|id|tag|uid, and the command goes without a shell, so it needs no quotes.
   */
  private fun snoozeOthers(): Boolean {
    val keys =
      Regex("""key=(-?\d+\|([\w.]+)\|[^\s:]+)""").findAll(shell("dumpsys notification --noredact"))
        .filter { it.groupValues[2] != app.packageName }
        .map { it.groupValues[1] }
        .toSet()
    keys.forEach { shell("cmd notification snooze --for 600000 $it") }
    return keys.isNotEmpty()
  }

  /** The notification shade with this app's notifications only. */
  private fun shade(name: String) {
    snoozeOthers()
    shell("cmd statusbar expand-notifications")
    Thread.sleep(2_000)
    Screens.take("demo-$name")
    shell("cmd statusbar collapse")
  }

  /** Android's demo status bar: a full battery and Wi-Fi. The clock stays real, as the times in the shots are. */
  private fun demoStatusBar(on: Boolean) {
    fun demo(vararg extras: String) = shell("am broadcast -a com.android.systemui.demo -e command ${extras.joinToString(" ")}")
    if (!on) {
      demo("exit")
      shell("settings put global sysui_demo_allowed 0")
      return
    }
    shell("settings put global sysui_demo_allowed 1")
    demo("enter")
    demo("battery -e level 100 -e plugged false")
    demo("network -e wifi show -e level 4 -e fully true -e ssid Home")
    demo("network -e mobile hide")
  }

  /** Answers the app's requests to start [action] with [answer], telling [seen] about each. */
  private fun intercept(action: String, answer: Instrumentation.ActivityResult, seen: (Intent) -> Unit = {}) {
    val monitor =
      object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? = if (intent.action == action) answer.also { seen(intent) } else null
      }
    instrumentation.addMonitor(monitor)
    monitors += monitor
  }

  private fun phoneFile(name: String, size: Int): File = File(app.cacheDir, name).apply { writeBytes(ByteArray(size) { (it * 31).toByte() }) }

  private fun asset(name: String): String = instrumentation.context.assets.open(name).use { it.readBytes().decodeToString() }

  /** The projects the agents work in, with the files they mention. */
  private fun makeProjects() {
    work.exec("mkdir -p ~/projects/shop-api/src/checkout ~/projects/shop-api/src/middleware ~/projects/shop-api/test ~/projects/shop-api/docs ~/projects/shop-api/uploads ~/projects/mobile-app/docs ~/projects/data-pipeline")
    work.exec("cat > ~/projects/shop-api/src/checkout/total.ts", TOTAL_TS)
    work.exec("cat > ~/projects/shop-api/test/checkout.test.ts", TEST_TS)
    work.exec("cat > ~/projects/shop-api/src/checkout/giftCards.ts", "export const MAX_CARDS_PER_ORDER = 3;\n")
    work.exec("cat > ~/projects/shop-api/src/middleware/rateLimit.ts", "export const LIMIT_PER_MINUTE = 120;\n")
    work.exec("cat > ~/projects/mobile-app/docs/deps-2026-10.md", "# Dependencies, October 2026\n\n14 packages updated; no breaking changes.\n")
    work.exec("python3 - ~/projects/shop-api/docs/checkout-flow.png", FLOW_PNG)
  }

  /** shop-api and mobile-app; a fresh session has no workspace yet, so these are №1 and №2. */
  private fun stageWorkstation() {
    ask = workspace(work, "shop-api")
    agent(work, ask, "~/projects/shop-api", "claude", "claude-ask", "Fix checkout with gift cards", "blocked")
    codex = split(work, ask, "projects/shop-api")
    agent(work, codex, "~/projects/shop-api", "codex", "codex-work", "Add per-client rate limits", "working")
    shellPane = split(work, ask, "projects/shop-api")
    prompt(work, shellPane, "~/projects/shop-api")
    rename(work, shellPane, "shell")
    done = workspace(work, "mobile-app")
    agent(work, done, "~/projects/mobile-app", "claude", "claude-done", "Update dependencies", "working", "idle")
  }

  private fun stageBuildServer() {
    gemini = workspace(build, "data-pipeline")
    agent(build, gemini, "~/projects/data-pipeline", "gemini", "gemini-idle", "Profile the nightly import", "idle")
  }

  /** A workspace for the project [name] in ~/projects; returns its pane. */
  private fun workspace(host: HostFixture, name: String): String =
    host.raw("workspace.create", buildJsonObject { put("label", name); put("cwd", "$home/projects/$name") }).getValue("root_pane").jsonObject.id("pane_id")

  private fun JsonObject.id(key: String) = getValue(key).jsonPrimitive.content

  private fun split(host: HostFixture, target: String, dir: String): String =
    host.raw("pane.split", buildJsonObject { put("target_pane_id", target); put("direction", "down"); put("cwd", "$home/$dir") })
      .getValue("pane").jsonObject.id("pane_id")

  /** A prompt that names no real user or host, in [dir]. */
  private fun prompt(host: HostFixture, pane: String, dir: String) {
    host.type(pane, "cd $dir && PS1='\\[\\e]0;dev@workstation: \\w\\a\\]\\[\\e[32m\\]dev@workstation\\[\\e[0m\\]:\\[\\e[34m\\]\\w\\[\\e[0m\\]\\\$ ' && clear")
  }

  /** The demo agent [scene] in [pane], passing for [kind], with the pane labelled [label] and [states] reported in turn. */
  private fun agent(host: HostFixture, pane: String, dir: String, kind: String, scene: String, label: String, vararg states: String) {
    prompt(host, pane, dir)
    host.type(pane, "(exec -a $kind python3 -u ~/.cache/herdr-android-e2e/demo_agent.py $scene)")
    Thread.sleep(1_000)
    for (state in states) {
      report(host, pane, kind, state)
      Thread.sleep(300)
    }
    rename(host, pane, label)
  }

  private fun report(host: HostFixture, pane: String, kind: String, state: String) {
    host.raw("pane.report_agent", buildJsonObject { put("pane_id", pane); put("source", "custom:e2e"); put("agent", kind); put("state", state) })
  }

  private fun rename(host: HostFixture, pane: String, label: String) {
    host.raw("pane.rename", buildJsonObject { put("pane_id", pane); put("label", label) })
  }

  private companion object {
    const val WORK = "workstation"
    const val BUILD = "build"

    val TOTAL_TS =
      """
      |import { Cart, Money } from "../model";
      |import { taxFor } from "./tax";
      |
      |/** What the customer pays: the items, then tax, then gift cards, never below zero. */
      |export function total(cart: Cart): Money {
      |  const items = cart.lines.reduce((sum, line) => sum + line.price * line.quantity, 0);
      |  const taxed = items + taxFor(cart.address, items);
      |  const giftCards = cart.giftCards.reduce((sum, card) => sum + card.balance, 0);
      |  return Math.max(0, taxed - giftCards);
      |}
      |"""
        .trimMargin()

    val TEST_TS =
      """
      |import { total } from "../src/checkout/total";
      |
      |test("a gift card larger than the order leaves nothing to pay", () => {
      |  const cart = { lines: [{ price: 20, quantity: 1 }], giftCards: [{ balance: 50 }], address: { country: "DE" } };
      |  expect(total(cart)).toBe(0);
      |});
      |"""
        .trimMargin()

    /** A plain picture for the flow diagram: three boxes on a dark gradient, written as a PNG without libraries. */
    val FLOW_PNG =
      """
      |import struct, sys, zlib
      |w, h = 960, 600
      |boxes = [(80, 240, 300, 360, (242, 184, 75)), (370, 240, 590, 360, (124, 184, 245)), (660, 240, 880, 360, (123, 216, 143))]
      |rows = bytearray()
      |for y in range(h):
      |    rows.append(0)
      |    for x in range(w):
      |        colour = (22 + 18 * x // w, 27 + 14 * y // h, 34 + 20 * (x + y) // (w + h))
      |        for x0, y0, x1, y1, c in boxes:
      |            if x0 <= x < x1 and y0 <= y < y1:
      |                colour = c
      |        rows.extend(colour)
      |def chunk(kind, data):
      |    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
      |png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b"")
      |open(sys.argv[1], "wb").write(png)
      |"""
        .trimMargin()
  }
}
