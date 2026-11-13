package dev.artalex.herdr.core

import dev.artalex.herdr.core.rpc.HerdrTimeoutException
import dev.artalex.herdr.core.rpc.blockingIoWithDeadline
import java.io.Closeable
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/** A deadline is kept by closing the resource under a blocked read: whatever ends that read, it is a timeout. */
class DeadlineTest {
  /**
   * The output of a command over a JSch channel: [first] comes at once, the rest never; closing the channel ends a read
   * that waits as the other side ending it would, with the end of the stream rather than an error.
   */
  private class Command(private val first: String) : Closeable {
    @Volatile private var closed = false
    private val start = first.toByteArray().inputStream()

    val output: InputStream =
      object : InputStream() {
        override fun read(): Int {
          val b = start.read()
          if (b >= 0) return b
          while (!closed) Thread.sleep(5)
          return -1
        }
      }

    override fun close() {
      closed = true
    }
  }

  @Test
  fun readThatTheDeadlineEndsIsATimeoutNotAShortAnswer() = runBlocking {
    val command = Command("/home/us")
    val e = assertFailsWith<HerdrTimeoutException> { blockingIoWithDeadline(command, 200, "folder lookup") { command.output.readAllBytes() } }
    assertEquals("folder lookup: no answer within 200 ms", e.message)
  }

  @Test
  fun answerInTimeIsTheAnswer() = runBlocking {
    val command = Command("/home/user")
    assertEquals("/home/user", blockingIoWithDeadline(command, 5_000, "folder lookup") { command.output.readNBytes(10).decodeToString() })
  }
}
