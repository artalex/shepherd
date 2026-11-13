package dev.artalex.herdr.core

import dev.artalex.herdr.core.files.PARALLEL_DOWNLOADS
import dev.artalex.herdr.core.files.PARALLEL_UPLOADS
import dev.artalex.herdr.core.ssh.ChannelBudget
import dev.artalex.herdr.core.ssh.SshConnection
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Room for channels on one SSH connection: a channel that doesn't fit waits, and terminal streams have places of their own. */
class ChannelBudgetTest {
  private suspend fun ChannelBudget.within(terminal: Boolean) = withTimeoutOrNull(100) { acquire(terminal) }

  @Test
  fun channelsBeyondTheRoomWaitWhileTerminalsHaveTheirPlaces() = runBlocking {
    val budget = ChannelBudget(size = 4, reserve = 1)
    val others = List(3) { budget.acquire(terminal = false) }
    // The place left is kept for terminal streams.
    assertNull(budget.within(terminal = false))
    val terminal = assertNotNull(budget.within(terminal = true))
    // Full now: a terminal stream waits too, and gets the first place that is given back.
    val waiting = async { budget.acquire(terminal = true) }
    assertNull(budget.within(terminal = false))
    others.first().release()
    assertNotNull(withTimeout(1_000) { waiting.await() })
    terminal.release()
    assertNotNull(budget.within(terminal = false))
    Unit
  }

  @Test
  fun terminalsMayTakeEveryPlace() = runBlocking {
    val budget = ChannelBudget(size = 4, reserve = 1)
    val terminals = List(4) { budget.acquire(terminal = true) }
    assertNull(budget.within(terminal = true))
    assertNull(budget.within(terminal = false))
    // A terminal that ends gives its place to whoever needs one.
    terminals.first().release()
    assertNotNull(budget.within(terminal = false))
    Unit
  }

  @Test
  fun aPlaceGoesBackOnceHoweverOftenItIsReleased() = runBlocking {
    val budget = ChannelBudget(size = 2, reserve = 0)
    val first = budget.acquire(terminal = false)
    budget.acquire(terminal = false)
    repeat(3) { first.release() }
    assertNotNull(budget.within(terminal = false))
    assertNull(budget.within(terminal = false))
    Unit
  }

  @Test
  fun aCancelledWaitTakesNoPlace() = runBlocking {
    val budget = ChannelBudget(size = 2, reserve = 1)
    // Waits for a place of the others' first, then for one of any kind: each cancelled, and nothing is kept of either.
    val other = budget.acquire(terminal = false)
    repeat(3) { assertNull(budget.within(terminal = false)) }
    other.release()
    val terminals = List(2) { budget.acquire(terminal = true) }
    repeat(3) { assertNull(budget.within(terminal = false)) }
    terminals.forEach { it.release() }
    assertNotNull(budget.within(terminal = false))
    assertNotNull(budget.within(terminal = true))
    Unit
  }

  @Test
  fun transfersLeaveRoomForWhatTheyOpenMeanwhile() {
    // An upload holds its channel while it looks up the uploads folder over another: with all the room taken by uploads
    // and downloads, it would wait for good. Nothing else holds a channel while it waits for one.
    assertTrue(PARALLEL_UPLOADS + PARALLEL_DOWNLOADS < SshConnection.SESSION_CHANNELS - SshConnection.TERMINAL_CHANNELS)
  }

  @Test
  fun closingTheBudgetEndsTheWaitAndRefusesNewChannels() = runBlocking {
    val budget = ChannelBudget(size = 2, reserve = 1)
    budget.acquire(terminal = false)
    budget.acquire(terminal = true)
    val waiting = async { runCatching { budget.acquire(terminal = false) } }
    val waitingTerminal = async { runCatching { budget.acquire(terminal = true) } }
    budget.close()
    assertEquals("SSH session is not connected", withTimeout(1_000) { waiting.await() }.exceptionOrNull()?.message)
    assertEquals("SSH session is not connected", withTimeout(1_000) { waitingTerminal.await() }.exceptionOrNull()?.message)
    assertFailsWith<IOException> { budget.acquire(terminal = true) }
    Unit
  }
}
