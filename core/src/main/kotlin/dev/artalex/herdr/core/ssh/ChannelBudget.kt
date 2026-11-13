package dev.artalex.herdr.core.ssh

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Semaphore

/**
 * The session channels (exec, sftp) one SSH connection has room for at once: sshd refuses a connection more than its
 * `MaxSessions`. A channel waits here for room instead of failing at the server. Of the [size] places, [reserve] are
 * kept for terminal streams, so a pane's screen opens while transfers and lookups take the rest. Closed with its
 * connection, the budget lets nobody wait any longer.
 */
internal class ChannelBudget(size: Int, reserve: Int) {
  private val any = Semaphore(size)
  private val others = Semaphore(size - reserve)
  private val taken = ConcurrentHashMap.newKeySet<Permit>()
  @Volatile private var closed = false

  /** Waits for room for a channel; a [terminal] stream may take a place kept for those. Fails once the budget is closed. */
  suspend fun acquire(terminal: Boolean): Permit {
    // Others first, then any: nobody holds a place of any kind while waiting for one of the others, so no two waits
    // go round in a circle.
    if (!terminal) others.acquire()
    try {
      any.acquire()
    } catch (e: Throwable) {
      if (!terminal) others.release()
      throw e
    }
    val permit = Permit(terminal)
    taken += permit
    if (closed) {
      permit.release()
      throw IOException("SSH session is not connected")
    }
    return permit
  }

  /** The connection is closed, and its channels with it: their places go back, and those waiting for one stop. */
  fun close() {
    closed = true
    taken.forEach { it.release() }
  }

  /** A channel's place; [release] gives it back, and only the first call counts. */
  inner class Permit internal constructor(private val terminal: Boolean) {
    private val released = AtomicBoolean()

    fun release() {
      if (!released.compareAndSet(false, true)) return
      taken -= this
      any.release()
      if (!terminal) others.release()
    }
  }
}
