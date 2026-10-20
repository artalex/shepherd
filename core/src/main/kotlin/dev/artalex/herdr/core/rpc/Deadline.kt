package dev.artalex.herdr.core.rpc

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs blocking I/O on [resource] with a deadline.
 *
 * Both the deadline and caller cancellation are enforced by closing [resource], which makes the
 * blocked read fail. The thread is never interrupted: an interrupt inside JSch can cut an SSH packet
 * in half, and the server then drops the whole session ("message authentication code incorrect").
 */
internal suspend fun <T> blockingIoWithDeadline(resource: Closeable, timeoutMs: Long, what: String, block: () -> T): T =
  coroutineScope {
    val timedOut = AtomicBoolean(false)
    val watchdog = launch {
      delay(timeoutMs)
      timedOut.set(true)
      resource.close()
    }
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        awaitCancellation()
      } finally {
        resource.close()
      }
    }
    try {
      withContext(Dispatchers.IO) { block() }
    } catch (e: IOException) {
      if (timedOut.get()) throw HerdrTimeoutException("$what: no answer within $timeoutMs ms")
      // Closed because the caller was cancelled: report the cancellation, not the closed stream.
      coroutineContext.ensureActive()
      throw e
    } finally {
      watchdog.cancel()
      closer.cancel()
    }
  }
