package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.HostCheck
import dev.artalex.herdr.core.host.BadSessionName
import dev.artalex.herdr.core.host.HerdrNotFound
import dev.artalex.herdr.core.host.HerdrStatusFailed
import dev.artalex.herdr.core.host.check
import dev.artalex.herdr.core.ssh.HostKey
import dev.artalex.herdr.core.ssh.SshConnection
import dev.artalex.herdr.core.ssh.SshFailure
import dev.artalex.herdr.core.ssh.SshKeyPair
import dev.artalex.herdr.core.ssh.SshTarget
import dev.artalex.herdr.core.ssh.discoverHerdrHost
import dev.artalex.herdr.core.terminal.CloseReason
import dev.artalex.herdr.core.terminal.TerminalEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * The phone's path, from the JVM: JSch to the local sshd with the localhost-only test key
 * (`from="127.0.0.1,::1"` in authorized_keys), then herdr through streamlocal and exec.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SshIntegrationTest {
  private val keyFile = File(System.getenv("HERDR_SSH_TEST_KEY") ?: "${System.getProperty("user.home")}/.ssh/herdr_android_test")
  private val target = SshTarget("127.0.0.1", 22, System.getProperty("user.name"))
  private lateinit var server: HerdrTestServer
  private lateinit var key: SshKeyPair
  private lateinit var hostKey: HostKey

  @BeforeAll
  fun setUp() {
    assumeTrue(HerdrTestServer.available && keyFile.canRead(), "herdr or the SSH test key is missing")
    server = HerdrTestServer(isolatedConfig = false)
    key = SshKeyPair(keyFile.readBytes(), File("${keyFile.path}.pub").readText().trim())
    hostKey = assertIs<SshFailure.UnknownHostKey>(assertFailsWith<SshFailure> { SshConnection(target, key, null).connect() }).presented
  }

  @AfterAll
  fun tearDown() {
    if (::server.isInitialized) server.close()
  }

  private fun connected() = SshConnection(target, key, hostKey).also { it.connect() }

  @Test
  fun unknownHostKeyIsReportedWithSshKeygenFingerprint() {
    val type = hostKey.type.removePrefix("ssh-").substringBefore('-').let { if (it == "ecdsa") "ecdsa" else it }
    val pub = File("/etc/ssh/ssh_host_${type}_key.pub")
    if (pub.canRead()) {
      val out = ProcessBuilder("ssh-keygen", "-lf", pub.path).start().inputStream.readAllBytes().toString(Charsets.UTF_8)
      assertTrue(out.contains(hostKey.fingerprint), "ssh-keygen says $out, client saw ${hostKey.fingerprint}")
    }
  }

  @Test
  fun changedHostKeyIsRefused() {
    val other = HostKey("ssh-ed25519", hostKey.blob.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() })
    val e = assertIs<SshFailure.HostKeyChanged>(assertFailsWith<SshFailure> { SshConnection(target, key, other).connect() })
    assertEquals(hostKey, e.presented)
  }

  @Test
  fun unknownClientKeyFailsAuthentication() {
    assertIs<SshFailure.AuthFailed>(assertFailsWith<SshFailure> { SshConnection(target, SshKeyPair.generate("not authorized"), hostKey).connect() })
  }

  @Test
  fun closedPortIsUnreachable() {
    assertIs<SshFailure.Unreachable>(assertFailsWith<SshFailure> { SshConnection(target.copy(port = 1), key, hostKey).connect() })
  }

  @Test
  fun discoversHerdrAndUsesApiOverSsh() = runBlocking {
    connected().use { connection ->
      val host = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session)
      assertEquals(HostCheck.Ok, host.status.check())
      assertEquals(server.socket, host.status.server.socket)
      assertEquals(SUPPORTED_PROTOCOL, host.api.ping().protocol)
      host.api.snapshot()
      Unit
    }
  }

  @Test
  fun parallelAndCancelledRequestsKeepTheSessionAlive() = runBlocking {
    connected().use { connection ->
      val host = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session)
      val pane = host.api.snapshot().panes.firstOrNull()?.paneId
        ?: server.raw("workspace.create").getValue("root_pane").let { (it as kotlinx.serialization.json.JsonObject).getValue("pane_id").toString().trim('"') }
      coroutineScope {
        repeat(4) { worker ->
          launch(Dispatchers.Default) {
            repeat(25) { i ->
              if ((worker + i) % 5 == 0) {
                // Cancelled mid-request, like a screen that leaves while a poll is in flight.
                withTimeoutOrNull(1) { host.api.readPane(pane) }
              } else {
                host.api.snapshot()
                host.api.readPane(pane)
              }
            }
          }
        }
      }
      assertTrue(connection.isConnected, "SSH session died under parallel and cancelled requests")
      assertEquals(SUPPORTED_PROTOCOL, host.api.ping().protocol)
    }
  }

  @Test
  fun filesOverSftp() = runBlocking {
    val dir = Files.createTempDirectory(Path.of(System.getenv("XDG_RUNTIME_DIR") ?: "/tmp"), "hsftp").toFile()
    try {
      File(dir, "отчёт.txt").writeText("привет")
      connected().use { connection ->
        connection.openFiles().use { files ->
          assertEquals(System.getProperty("user.home"), files.home)
          val report = checkNotNull(files.stat("$dir/отчёт.txt"))
          assertTrue(report.isFile)
          assertEquals("привет".toByteArray().size.toLong(), report.size)
          assertFalse(checkNotNull(files.stat(dir.path)).isFile)
          assertNull(files.stat("$dir/нет.txt"))

          val out = ByteArrayOutputStream()
          var reported = 0L
          files.download("$dir/отчёт.txt", out) { reported = it; true }
          assertEquals("привет", out.toString(Charsets.UTF_8))
          assertEquals(report.size, reported)

          // A taken name stays with its file; the folder is created on the way.
          val first = files.upload("один".byteInputStream(), "$dir/up/sub", "фото.png") { true }
          val second = files.upload("два".byteInputStream(), "$dir/up/sub", "фото.png") { true }
          assertEquals("$dir/up/sub/фото.png", first)
          assertEquals("$dir/up/sub/фото (2).png", second)
          assertEquals("один", File(first).readText())
          assertEquals("два", File(second).readText())

          // A stopped upload leaves nothing behind, not even its unfinished part.
          assertFailsWith<CancellationException> { files.upload(ByteArray(1 shl 20).inputStream(), "$dir/up/sub", "big.bin") { false } }
          assertEquals(listOf("фото (2).png", "фото.png"), File(dir, "up/sub").list()!!.sorted())
        }
        val uploads = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session).uploadDir()
        assertTrue(uploads.startsWith("/") && uploads.endsWith("/herdr-android"), uploads)
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun wrongHerdrPathIsReported() = runBlocking {
    connected().use { connection ->
      val e = assertFailsWith<HerdrNotFound> { discoverHerdrHost(connection, "~/nowhere/herdr", server.session) }
      assertEquals("~/nowhere/herdr", e.path)
    }
  }

  @Test
  fun badSessionNameIsReported() = runBlocking {
    connected().use { connection ->
      val e = assertFailsWith<BadSessionName> { discoverHerdrHost(connection, "~/.local/bin/herdr", "my session") }
      assertEquals("my session", e.session)
      assertEquals("session name may only contain ASCII letters, numbers, '.', '_' and '-'", e.reason)
    }
  }

  @Test
  fun herdrPathThatIsNoProgramIsReported() = runBlocking {
    connected().use { connection ->
      val e = assertFailsWith<HerdrStatusFailed> { discoverHerdrHost(connection, server.dir.toString(), server.session) }
      assertTrue(e.detail.endsWith("${server.dir}: Is a directory"), e.detail)
    }
  }

  @Test
  fun stoppedServerIsReportedNotRunning() = runBlocking {
    // A session of its own: the shared one stays up for the other tests.
    HerdrTestServer(isolatedConfig = false).use { stopped ->
      stopped.stop()
      connected().use { connection ->
        assertEquals(HostCheck.ServerNotRunning, discoverHerdrHost(connection, "~/.local/bin/herdr", stopped.session).status.check())
      }
    }
  }

  @Test
  fun terminalControlOverSsh() = runBlocking {
    val created = server.raw("workspace.create")
    val terminalId = created.getValue("root_pane").let { kotlinx.serialization.json.Json.parseToJsonElement(it.toString()) }
      .let { (it as kotlinx.serialization.json.JsonObject).getValue("terminal_id").toString().trim('"') }
    connected().use { connection ->
      val host = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session)
      val session = host.openTerminal(terminalId, 40, 12, takeover = false)
      val frame = withTimeout(10_000) { session.events.first() }
      assertIs<TerminalEvent.Frame>(frame)
      assertEquals(40 to 12, frame.width to frame.height)
    }
    // A fresh controller proves the previous one was released when the channel closed.
    connected().use { connection ->
      val host = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session)
      val session = host.openTerminal(terminalId, 40, 12, takeover = false)
      val events = withTimeout(10_000) {
        session.events.take(1).toList()
      }
      assertIs<TerminalEvent.Frame>(events.single())
      session.release()
    }
  }
}
