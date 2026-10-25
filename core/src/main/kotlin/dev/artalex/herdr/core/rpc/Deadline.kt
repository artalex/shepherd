package dev.artalex.herdr.core.rpc

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs blocking I/O on [resource] with a deadline, and closes [resource] when done.
 *
 * Both the deadline and caller cancellation are enforced by closing [resource], which makes the
 * blocked read fail. The thread is never interrupted: an interrupt inside JSch can cut an SSH packet
 * in half, and the server then drops the whole session ("message authentication code incorrect").
 */
internal suspend fun <T> blockingIoWithDeadline(resource: Closeable, timeoutMs: Long, what: String, block: () -> T): T =
  blockingIoWhileProgressing(resource, timeoutMs, graceMs = 0, timeout = { "$what: no answer within $timeoutMs ms" }) { block() }

/**
 * Blocking I/O on [resource] as in [blockingIoWithDeadline], for a transfer: [block] tells of its progress through the
 * function it gets, and the deadline is [stallMs] after the last progress rather than after the start. Cancelled, the
 * block gets [graceMs] to stop by itself, as a transfer does at its next chunk once its progress callback says so, and
 * to tidy up over the open resource; one stuck in a read doesn't stop, and the close ends it. [timeout] is the message
 * of the [HerdrTimeoutException] for a stall.
 */
internal suspend fun <T> blockingIoWhileProgressing(resource: Closeable, stallMs: Long, graceMs: Long, timeout: () -> String, block: suspend (progressed: () -> Unit) -> T): T =
  coroutineScope {
    val lastProgress = AtomicLong(System.nanoTime())
    val timedOut = AtomicBoolean(false)
    val done = CompletableDeferred<Unit>()
    val watchdog = launch {
      while (true) {
        val left = stallMs - (System.nanoTime() - lastProgress.get()) / 1_000_000
        if (left <= 0) break
        delay(left)
      }
      timedOut.set(true)
      resource.close()
    }
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) { withTimeoutOrNull(graceMs) { done.await() } }
        resource.close()
      }
    }
    try {
      withContext(Dispatchers.IO) { block { lastProgress.set(System.nanoTime()) } }
    } catch (e: IOException) {
      if (timedOut.get()) throw HerdrTimeoutException(timeout())
      // Closed because the caller was cancelled: report the cancellation, not the closed stream.
      coroutineContext.ensureActive()
      throw e
    } finally {
      done.complete(Unit)
      watchdog.cancel()
      closer.cancel()
    }
  }
