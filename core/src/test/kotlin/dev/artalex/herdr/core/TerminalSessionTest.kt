package dev.artalex.herdr.core

import dev.artalex.herdr.core.terminal.CloseReason
import dev.artalex.herdr.core.terminal.TerminalEvent
import dev.artalex.herdr.core.terminal.TerminalSession
import dev.artalex.herdr.core.terminal.terminalControlArgv
import dev.artalex.herdr.core.transport.ByteStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TerminalSessionTest {
  private class Recorded(stdout: ByteArray) : ByteStream {
    val sent = ByteArrayOutputStream()
    override val input = ByteArrayInputStream(stdout)
    override val output = sent

    override fun close() = Unit
  }

  @Test
  fun parsesCapturedControlStream() = runBlocking {
    val events = TerminalSession(Recorded(fixture("terminal-control-stream.ndjson").toByteArray())).events.toList()
    val first = assertIs<TerminalEvent.Frame>(events.first())
    assertTrue(first.full)
    assertEquals(24 to 6, first.width to first.height)
    assertTrue(String(first.bytes).startsWith("\u001b[?2026h"))
    assertEquals(TerminalEvent.Closed(CloseReason.Detached), events.last())
    assertEquals(1, events.count { it is TerminalEvent.Closed })
  }

  @Test
  fun streamWithoutClosedRecordEndsAsEnded() = runBlocking {
    val stdout = fixture("terminal-control-stream.ndjson").lines().first() + "\n"
    val events = TerminalSession(Recorded(stdout.toByteArray())).events.toList()
    assertEquals(TerminalEvent.Closed(CloseReason.Ended), events.last())
  }

  /** A stream that never sends anything and only unblocks when closed, like an idle SSH channel. */
  private class Silent : ByteStream {
    @Volatile var closed = false
    override val output = ByteArrayOutputStream()
    override val input =
      object : java.io.InputStream() {
        override fun read(): Int {
          while (!closed) Thread.sleep(5)
          throw java.io.IOException("closed")
        }
      }

    override fun close() {
      closed = true
    }
  }

  @Test
  fun cancellingCollectorClosesBlockedStream() = runBlocking {
    val stream = Silent()
    val job = launch { TerminalSession(stream).events.collect {} }
    delay(100)
    withTimeout(2_000) { job.cancelAndJoin() }
    assertTrue(stream.closed)
  }

  @Test
  fun commandsUseHerdrWireNames() = runBlocking {
    val stream = Recorded(ByteArray(0))
    val session = TerminalSession(stream)
    session.sendText("ls\r")
    session.sendBytes(byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte()))
    session.paste("a\nb\u001b[201~c")
    session.resize(60, 24)
    session.scroll(3)
    session.scroll(-6)
    session.release()
    assertEquals(
      listOf(
        """{"type":"terminal.input","text":"ls\r"}""",
        """{"type":"terminal.input","bytes":"G1tB"}""",
        """{"type":"terminal.input","text":"\u001b[200~a\nb[201~c\u001b[201~"}""",
        """{"type":"terminal.resize","cols":60,"rows":24}""",
        """{"type":"terminal.scroll","direction":"up","lines":3}""",
        """{"type":"terminal.scroll","direction":"down","lines":6}""",
        """{"type":"terminal.release"}""",
      ),
      stream.sent.toString(Charsets.UTF_8).trimEnd('\n').split('\n'),
    )
  }

  @Test
  fun classifiesCloseReasonsSeenInLab() {
    assertEquals(CloseReason.TakenOver, CloseReason.classify("terminal attach taken over"))
    assertEquals(
      CloseReason.NeedsTakeover,
      CloseReason.classify("terminal attach failed: terminal term_1 already has an attached client; retry with --takeover"),
    )
    assertEquals(CloseReason.NotFound, CloseReason.classify("terminal attach ended: terminal term_1 not found"))
  }

  @Test
  fun takeoverFlagGoesAfterTarget() {
    assertEquals(
      listOf("/h", "--session", "default", "terminal", "session", "control", "term_1", "--takeover", "--cols", "50", "--rows", "20"),
      terminalControlArgv("/h", "default", "term_1", 50, 20, takeover = true),
    )
  }
}
