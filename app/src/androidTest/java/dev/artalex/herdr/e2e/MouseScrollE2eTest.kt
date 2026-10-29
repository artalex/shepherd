package dev.artalex.herdr.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.Server
import java.util.Base64
import kotlin.math.abs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real SGR wheel input through herdr: as in Codex, the fixed first row is outside the scrolling body. */
@OptIn(ExperimentalTestApi::class)
class MouseScrollE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var server: Server
  private lateinit var pane: String

  @Before
  fun seed() {
    fixture = HostFixture(E2eArgs.require())
    pane = fixture.workspace("e2e-mouse-${System.currentTimeMillis().toString().takeLast(5)}")
    val encoded = Base64.getEncoder().encodeToString(MOUSE_APP.toByteArray())
    fixture.type(pane, "(exec -a codex python3 -u -c \"import base64; exec(base64.b64decode('$encoded'))\")")
    fixture.waitForScreen(pane) { it.contains("FIXED HEADER") }
    fixture.reportAgent(pane, "idle", "codex")
    server = fixture.connectApp(ApplicationProvider.getApplicationContext<HerdrApp>().container)
  }

  @After
  fun closeFixture() {
    if (::fixture.isInitialized) fixture.close()
  }

  @Test
  fun swipesReachMouseBodyOnAgentAndTerminalScreens() {
    launchApp(compose, "swipesReachMouseBodyOnAgentAndTerminalScreens") { scenario ->
      compose.scrollListTo(paneTag(pane))
      compose.onNodeWithTag(paneTag(pane)).performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("liveScreen"), 15_000)
      compose.awaitPaneFitsView(fixture, server, pane)
      checkSwipes("liveScreen")

      compose.onNodeWithTag("openTerminal").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("extraKeys"), 10_000)
      compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("terminalOverlay")).fetchSemanticsNodes().isEmpty() }
      compose.raiseTerminalKeyboard(scenario, fixture, server, pane)
      checkSwipes("terminal")
    }
  }

  private fun checkSwipes(tag: String) {
    val (rows, cols) = server.viewGrid(pane)
    // Reset the app's counters after a resize; no wheel is sent by this input.
    fixture.raw("pane.send_input", buildJsonObject {
      put("pane_id", pane)
      put("text", "r")
    })
    fixture.waitForScreen(pane, pump = { compose.waitForIdle() }) { it.contains("OFFSET 1000") && it.contains("UP none") }

    swipe(tag, columnFraction = 0.25f, from = 0.3f, to = 0.7f)
    val up = fixture.waitForScreen(pane, pump = { compose.waitForIdle() }) { offset(it) < 1000 && travelled(it, "UP", rows) }
    assertWheel(up, "UP", cols / 4, rows)
    val scrolled = offset(up)

    swipe(tag, columnFraction = 0.75f, from = 0.7f, to = 0.3f)
    val down = fixture.waitForScreen(pane, pump = { compose.waitForIdle() }) { offset(it) > scrolled && travelled(it, "DOWN", rows) }
    assertWheel(down, "DOWN", cols * 3 / 4, rows)
  }

  /** Stops before lifting so a fling cannot obscure the finger's last coordinates. */
  private fun swipe(tag: String, columnFraction: Float, from: Float, to: Float) {
    compose.onNodeWithTag(tag).performTouchInput {
      down(Offset(width * columnFraction, height * from))
      repeat(30) { moveBy(Offset(0f, height * (to - from) / 30)) }
      advanceEventTime(500)
      up()
    }
    compose.waitForIdle()
  }

  private fun offset(screen: String): Int = checkNotNull(Regex("OFFSET (-?\\d+)").find(screen)).groupValues[1].toInt()

  private fun wheel(screen: String, direction: String): List<Int>? =
    Regex("$direction (\\d+) (\\d+) (\\d+) (\\d+)").find(screen)?.groupValues?.drop(1)?.map(String::toInt)

  private fun travelled(screen: String, direction: String, rows: Int): Boolean =
    wheel(screen, direction)?.let { it[3] - it[2] >= rows / 4 } == true

  private fun assertWheel(screen: String, direction: String, column: Int, rows: Int) {
    val (minCol, maxCol, minRow, maxRow) = checkNotNull(wheel(screen, direction)) { screen }
    assertTrue("wheel follows the finger's column: $screen", abs(minCol - column) <= 1 && abs(maxCol - column) <= 1)
    assertTrue("wheel stays below the fixed header: $screen", minRow > 0 && maxRow < rows)
    assertTrue("wheel follows the finger down/up the body: $screen", maxRow - minRow >= rows / 4)
    assertEquals("no wheel should land outside the body", "REJECTED 0", screen.lines().first { it.startsWith("REJECTED ") }.trim())
    assertEquals("the header stays fixed", "FIXED HEADER", screen.lineSequence().first().trim())
  }

  companion object {
    /** Uses only Python's standard library; the test session's PTY carries the actual SGR reports from herdr. */
    private val MOUSE_APP = """
      import os, re, select, signal, sys, termios, tty
      fd = sys.stdin.fileno()
      saved = termios.tcgetattr(fd)
      tty.setraw(fd)
      offset, rejected = 1000, 0
      seen = {}
      dirty = True
      def resized(*args):
          global dirty
          dirty = True
      signal.signal(signal.SIGWINCH, resized)
      def draw():
          lines = ['FIXED HEADER', 'OFFSET ' + str(offset), 'REJECTED ' + str(rejected)]
          for direction in ('UP', 'DOWN'):
              values = seen.get(direction)
              lines.append(direction + ' ' + (' '.join(map(str, values)) if values else 'none'))
          sys.stdout.write('\x1b[2J\x1b[H' + '\r\n'.join(lines))
          sys.stdout.flush()
      sys.stdout.write('\x1b[?1049h\x1b[?1000h\x1b[?1006h\x1b[?25l')
      buf = b''
      try:
          while True:
              if dirty:
                  draw()
                  dirty = False
              if not select.select([fd], [], [], .05)[0]:
                  continue
              data = os.read(fd, 4096)
              if not data:
                  break
              buf += data
              while buf:
                  if buf[:1] == b'r':
                      offset, rejected, seen = 1000, 0, {}
                      buf = buf[1:]
                      dirty = True
                      continue
                  match = re.match(rb'\x1b\[<(\d+);(\d+);(\d+)([Mm])', buf)
                  if not match:
                      if b'\x1b[<'.startswith(buf) or (buf.startswith(b'\x1b[<') and len(buf) < 64):
                          break
                      buf = buf[1:]
                      continue
                  button, col, row = map(int, match.groups()[:3])
                  buf = buf[match.end():]
                  if button not in (64, 65) or match[4] != b'M':
                      continue
                  col, row = col - 1, row - 1
                  cols, rows = os.get_terminal_size(fd)
                  if not (0 <= col < cols and 1 <= row < rows):
                      rejected += 1
                  else:
                      direction = 'UP' if button == 64 else 'DOWN'
                      offset += -1 if button == 64 else 1
                      values = seen.setdefault(direction, [col, col, row, row])
                      values[:] = [min(values[0], col), max(values[1], col), min(values[2], row), max(values[3], row)]
                  dirty = True
      finally:
          sys.stdout.write('\x1b[?1006l\x1b[?1000l\x1b[?1049l\x1b[?25h')
          sys.stdout.flush()
          termios.tcsetattr(fd, termios.TCSANOW, saved)
    """.trimIndent()
  }
}
