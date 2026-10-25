package dev.artalex.herdr.core

import dev.artalex.herdr.core.files.RemoteEntry
import dev.artalex.herdr.core.files.RemoteFiles
import dev.artalex.herdr.core.files.guarded
import dev.artalex.herdr.core.rpc.HerdrTimeoutException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A transfer that is cancelled or gets stuck lets go of its files: closing them is what ends a read that blocks. */
class GuardedFilesTest {
  /**
   * Files whose download sends a chunk every 10 ms while [moving], up to [chunks]. Otherwise it waits in a read that only
   * closing the files ends, as over SFTP.
   */
  private class Files(private val chunks: Long = Long.MAX_VALUE) : RemoteFiles {
    @Volatile var moving = true
    @Volatile var closed = false
    @Volatile var stoppedWhileOpen = false

    override val home = "/home/user"

    override fun stat(path: String): RemoteEntry? = null

    override fun download(path: String, out: OutputStream, progress: (Long) -> Boolean) {
      var done = 0L
      while (done < chunks) {
        if (closed) throw IOException("channel closed")
        if (moving) {
          done++
          if (!progress(done)) {
            // Stopped, it tidies up over the open channel, as SftpFiles removes the part of a stopped upload.
            stoppedWhileOpen = !closed
            throw CancellationException("download of $path stopped")
          }
        }
        Thread.sleep(10)
      }
    }

    override fun upload(input: InputStream, dir: String, name: String, progress: (Long) -> Boolean): String = throw UnsupportedOperationException()

    override fun close() {
      closed = true
    }
  }

  @Test
  fun cancelledTransferStuckInAReadHasItsFilesClosed() = runBlocking {
    val files = Files().apply { moving = false }
    val transfer = launch { files.guarded("download", stallMs = 60_000, graceMs = 200) { files.download("/f", OutputStream.nullOutputStream()) { true } } }
    delay(100)
    transfer.cancel()
    withTimeout(2_000) { transfer.join() }
    assertTrue(files.closed)
  }

  @Test
  fun cancelledTransferThatMovesStopsAndTidiesUpBeforeTheClose() = runBlocking {
    val files = Files()
    val transfer = launch {
      val job = coroutineContext.job
      files.guarded("download", stallMs = 60_000, graceMs = 5_000) { progressed ->
        files.download("/f", OutputStream.nullOutputStream()) {
          progressed()
          job.isActive
        }
      }
    }
    delay(100)
    transfer.cancel()
    // Long before the grace is over: the transfer stopped at its next chunk.
    withTimeout(1_000) { transfer.join() }
    assertTrue(files.stoppedWhileOpen)
    assertTrue(files.closed)
  }

  @Test
  fun transferThatStopsMovingTimesOut() = runBlocking {
    val files = Files()
    val e =
      assertFailsWith<HerdrTimeoutException> {
        files.guarded("download of /f", stallMs = 200, graceMs = 0) { progressed ->
          files.download("/f", OutputStream.nullOutputStream()) { done ->
            progressed()
            if (done == 5L) files.moving = false
            true
          }
        }
      }
    assertTrue(e.message!!.startsWith("download of /f"), e.message)
    assertTrue(files.closed)
  }

  @Test
  fun cancelledCheckHasItsFilesClosedAtOnce() = runBlocking {
    // A check, as of the paths on a screen, has nothing to tidy up: no grace.
    val files = Files()
    val check = launch { files.guarded("checking", graceMs = 0) { delay(60_000) } }
    delay(100)
    check.cancel()
    withTimeout(500) { check.join() }
    assertTrue(files.closed)
  }

  @Test
  fun progressKeepsATransferGoingPastTheStallTime() = runBlocking {
    // A hundred chunks 10 ms apart: a second, three times the stall time, with progress all along.
    val files = Files(chunks = 100)
    files.guarded("download", stallMs = 300, graceMs = 0) { progressed ->
      files.download("/f", OutputStream.nullOutputStream()) {
        progressed()
        true
      }
    }
    assertTrue(files.closed)
  }
}
