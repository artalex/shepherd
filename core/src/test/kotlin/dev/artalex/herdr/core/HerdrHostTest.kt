package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.core.host.HostStatus
import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.rpc.HerdrTimeoutException
import dev.artalex.herdr.core.transport.ByteStream
import dev.artalex.herdr.core.transport.HerdrTransport
import dev.artalex.herdr.core.transport.ProcessStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/** What a herdr host looks up once for its connection: a lookup that failed is no answer to keep, and the next one asks again. */
class HerdrHostTest {
  /**
   * A host whose commands print [answer], or, while [slow], nothing yet: as over a JSch channel, closing a command that
   * waits ends its output as if it had printed all.
   */
  private class Host(private val answer: String) : HerdrTransport {
    @Volatile var slow = true
    var commands = 0

    override suspend fun openApi(): ByteStream = error("not used")

    override suspend fun exec(argv: List<String>): ProcessStream {
      commands++
      return Command(if (slow) null else answer)
    }
  }

  private class Command(answer: String?) : ProcessStream {
    @Volatile private var closed = false
    private val printed = answer?.byteInputStream()

    override val input: InputStream =
      object : InputStream() {
        override fun read(): Int {
          printed?.let { return it.read() }
          while (!closed) Thread.sleep(5)
          return -1
        }
      }

    override val output: OutputStream = OutputStream.nullOutputStream()
    override val stderr = ""

    override fun exitStatus(): Int? = if (printed != null) 0 else null

    override fun close() {
      closed = true
    }
  }

  private fun herdrHost(transport: HerdrTransport) =
    HerdrHost(HostStatus(HostStatus.Client("0.9.5", "/usr/bin/herdr"), HostStatus.Server(running = true, socket = "/run/herdr.sock")), "s", transport, lookupTimeoutMs = 200)

  @Test
  fun agentLookupPastItsDeadlineIsMadeAgain() = runBlocking {
    val transport = Host("claude\ncodex\n")
    val host = herdrHost(transport)
    assertFailsWith<HerdrTimeoutException> { host.installedAgents() }
    transport.slow = false
    assertEquals(listOf(AgentKind.CLAUDE, AgentKind.CODEX), host.installedAgents())
    // Found, it is kept for the connection.
    assertEquals(listOf(AgentKind.CLAUDE, AgentKind.CODEX), host.installedAgents())
    assertEquals(2, transport.commands)
  }

  @Test
  fun uploadsFolderLookupPastItsDeadlineIsMadeAgain() = runBlocking {
    val transport = Host("/home/user/Downloads/herdr-android")
    val host = herdrHost(transport)
    assertFailsWith<HerdrTimeoutException> { host.uploadDir() }
    transport.slow = false
    assertEquals("/home/user/Downloads/herdr-android", host.uploadDir())
    assertEquals("/home/user/Downloads/herdr-android", host.uploadDir())
    assertEquals(2, transport.commands)
  }
}
