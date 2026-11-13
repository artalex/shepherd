package dev.artalex.herdr.ui.agent

import dev.artalex.herdr.connection.ConnectionProblem
import dev.artalex.herdr.connection.ConnectionState
import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.core.host.HostStatus
import dev.artalex.herdr.core.store.SessionState
import dev.artalex.herdr.core.terminal.CloseReason
import dev.artalex.herdr.core.transport.ByteStream
import dev.artalex.herdr.core.transport.HerdrTransport
import dev.artalex.herdr.core.transport.ProcessStream
import dev.artalex.herdr.ui.terminal.TerminalPhase
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenNoticeTest {
  private val host =
    HerdrHost(
      HostStatus(HostStatus.Client("0.9.5", "/usr/bin/herdr"), HostStatus.Server(running = true, version = "0.9.5", compatible = true, socket = "/run/herdr.sock")),
      "s",
      object : HerdrTransport {
        override suspend fun openApi(): ByteStream = throw UnsupportedOperationException()

        override suspend fun exec(argv: List<String>): ProcessStream = throw UnsupportedOperationException()
      },
    )
  private val connected = ConnectionState.Connected(host, restartNeeded = false)
  private val serverStopped = ConnectionState.Failed(ConnectionProblem.HerdrNotRunning, retryInMs = 2_000)

  // Nothing in the notice goes by the snapshot itself: one on its way stands for a poll that hasn't failed.
  private val polled = SessionState.Loading
  private val pollFailed = SessionState.Failed(IOException("session.snapshot: no answer within 10000 ms"), last = null)
  private val brokeOff = TerminalPhase.Closed(CloseReason.Other("server is shutting down"))

  @Test
  fun withoutAConnectionTheConnectionIsSaid() {
    assertEquals(ScreenNotice.Connection(serverStopped), screenNotice(serverStopped, pollFailed, brokeOff, drawn = true))
    assertEquals(ScreenNotice.Connection(ConnectionState.Connecting), screenNotice(ConnectionState.Connecting, polled, problem = null, drawn = false))
  }

  @Test
  fun aFailedPollIsSaidOverALiveStreamToo() {
    // A stream on a dead network stays live until SSH notices: the failed poll is the first to tell.
    assertEquals(ScreenNotice.NoAnswer("session.snapshot: no answer within 10000 ms"), screenNotice(connected, pollFailed, problem = null, drawn = true))
  }

  @Test
  fun aStreamThatBrokeOffIsSaidOverTheFrameItLeft() {
    assertEquals(ScreenNotice.Stream(brokeOff), screenNotice(connected, polled, brokeOff, drawn = true))
    // Without a frame the screen waits for the pane's screen, and says so.
    assertNull(screenNotice(connected, polled, brokeOff, drawn = false))
  }

  @Test
  fun aServerToRestartIsWhyTheStreamDoesNotOpen() {
    val restartNeeded = ConnectionState.Connected(host, restartNeeded = true)
    val failed = TerminalPhase.Failed("stream ended")
    assertEquals(ScreenNotice.Connection(restartNeeded), screenNotice(restartNeeded, polled, failed, drawn = false))
    assertNull(screenNotice(restartNeeded, polled, problem = null, drawn = false))
  }

  @Test
  fun allWellSaysNothing() {
    assertNull(screenNotice(connected, polled, problem = null, drawn = true))
  }

  @Test
  fun aProblemStaysWhileTheStreamOpensAgainAndGoesOnceItIsLive() {
    val failed = TerminalPhase.Failed("channel is not opened")
    val phases = listOf(TerminalPhase.Live, brokeOff, TerminalPhase.Connecting, failed, TerminalPhase.Connecting, TerminalPhase.Live, TerminalPhase.Connecting)
    val kept = phases.runningFold(null as TerminalPhase?, ::keptProblem).drop(1)
    assertEquals(listOf(null, brokeOff, brokeOff, failed, failed, null, null), kept)
  }

  @Test
  fun anotherClientsControlIsNoProblem() {
    assertNull(keptProblem(brokeOff, TerminalPhase.NeedsTakeover))
    assertNull(keptProblem(null, TerminalPhase.Closed(CloseReason.TakenOver)))
    // Away from the screen and back, the stream measures its new view: nothing of the old one is kept.
    assertNull(keptProblem(brokeOff, TerminalPhase.Measuring))
  }
}
