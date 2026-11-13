package dev.artalex.herdr.core

import com.jcraft.jsch.Session
import dev.artalex.herdr.core.files.FileBook
import dev.artalex.herdr.core.files.FileShelf
import dev.artalex.herdr.core.files.HostSide
import dev.artalex.herdr.core.files.PhoneSide
import dev.artalex.herdr.core.files.RemoteFiles
import dev.artalex.herdr.core.files.Transfers
import dev.artalex.herdr.core.host.checkFolders
import dev.artalex.herdr.core.host.installedAgents
import dev.artalex.herdr.core.host.readHostStatus
import dev.artalex.herdr.core.host.removeTelegramToken
import dev.artalex.herdr.core.host.resolveFolder
import dev.artalex.herdr.core.host.saveTelegramToken
import dev.artalex.herdr.core.host.uploadDir
import dev.artalex.herdr.core.rpc.HerdrApi
import dev.artalex.herdr.core.rpc.HerdrTimeoutException
import dev.artalex.herdr.core.ssh.HostKey
import dev.artalex.herdr.core.ssh.SshConnection
import dev.artalex.herdr.core.ssh.SshFailure
import dev.artalex.herdr.core.ssh.SshHerdrTransport
import dev.artalex.herdr.core.ssh.SshKeyPair
import dev.artalex.herdr.core.ssh.SshTarget
import dev.artalex.herdr.core.transport.HerdrTransport
import dev.artalex.herdr.core.transport.ProcessStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * The channels of one SSH connection, against the local sshd with the localhost-only test key: sshd lets a connection
 * have 10 session channels (exec, sftp) at once, and what the app opens beyond its room waits instead of failing, with
 * room kept for terminal streams; a caller cancelled while its channel opens leaves nothing open; a deadline that ends a
 * command on the host is a timeout, not a short answer. No herdr is needed: the API socket is one of the test's own, and
 * exec runs plain shell commands.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class SshChannelsTest {
  private val keyFile = File(System.getenv("HERDR_SSH_TEST_KEY") ?: "${System.getProperty("user.home")}/.ssh/herdr_android_test")
  private val target = SshTarget("127.0.0.1", 22, System.getProperty("user.name"))
  private lateinit var key: SshKeyPair
  private lateinit var hostKey: HostKey
  private lateinit var dir: Path

  @BeforeAll
  fun setUp() {
    assumeTrue(keyFile.canRead(), "the SSH test key is missing")
    key = SshKeyPair(keyFile.readBytes(), File("${keyFile.path}.pub").readText().trim())
    hostKey = assertIs<SshFailure.UnknownHostKey>(assertFailsWith<SshFailure> { SshConnection(target, key, null).connect() }).presented
    dir = Files.createTempDirectory(Path.of(System.getenv("XDG_RUNTIME_DIR") ?: "/tmp"), "hchan")
  }

  @AfterAll
  fun tearDown() {
    if (::dir.isInitialized) dir.toFile().deleteRecursively()
  }

  private fun connected(port: Int = 22) = SshConnection(target.copy(port = port), key, hostKey).also { it.connect() }

  private fun tempDir(): File = Files.createTempDirectory(dir, "up").toFile()

  @Test
  fun twelveUploadsAtOnceAllReachTheHost() = runBlocking {
    val uploads = tempDir()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      connected().use { connection ->
        // Twelve photos shared at once, read as slowly as a phone sends them over Wi-Fi: their transfers overlap.
        val phone = SlowPhone(count = 12, size = 256 * 1024)
        val transfers = Transfers(scope, phone, Host(SshHerdrTransport(connection, socketPath = null), uploads.path), { FileShelf(FileBook()) })
        val ends = transfers.uploadAll(phone.sources, origin = null)
        assertEquals(emptyList(), ends.filter { it.path.isFailure }.map { "${it.name}: ${it.path.exceptionOrNull()?.message}" })
        assertEquals(phone.sources.map { it.substringAfterLast('/') }.sorted(), uploads.list()!!.sorted())
        assertEquals(List(12) { 256 * 1024L }, uploads.listFiles()!!.map { it.length() })
      }
    } finally {
      scope.cancel()
      uploads.deleteRecursively()
    }
  }

  @Test
  fun cancelledOpensLeaveNoChannels() = runBlocking {
    UnixServer(dir.resolve("api.sock")).use { api ->
      // A channel opens in a round trip or more: 200 ms each through the proxy, so the callers below are cancelled
      // while their channels open.
      TcpProxy(22, delayMs = 100).use { proxy ->
        connected(proxy.port).use { connection ->
          val transport = SshHerdrTransport(connection, api.path)
          repeat(5) { withTimeoutOrNull(60) { HerdrApi(transport).ping() } }
          // cat ends once its channel closes: whatever stays on the host is the channel itself.
          repeat(5) { withTimeoutOrNull(60) { transport.exec(listOf("cat")) }?.close() }
          repeat(5) { withTimeoutOrNull(150) { transport.openFiles() }?.close() }
          // The opens end a moment after their callers gave up; then nothing of theirs is left open.
          withTimeoutOrNull(10_000) { while (channels(connection) > 0 || api.open.get() > 0) delay(100) }
          assertEquals(0 to 0, channels(connection) to api.open.get(), "channels left open, and API connections")
          // sshd has room for as many channels at once as ever.
          val files = (1..SshConnection.SESSION_CHANNELS - SshConnection.TERMINAL_CHANNELS).map { async { transport.openFiles() } }
          val terminals = (1..SshConnection.TERMINAL_CHANNELS).map { async { transport.openTerminal(listOf("cat")) } }
          (files + terminals).awaitAll().forEach { it.close() }
        }
      }
    }
  }

  @Test
  fun terminalStreamsHaveRoomWhenTheOtherChannelsAreTaken() = runBlocking {
    connected().use { connection ->
      val transport = SshHerdrTransport(connection, socketPath = null)
      // Transfers and lookups take all the room they may have, and one more waits for its turn.
      val taken = (1..SshConnection.SESSION_CHANNELS - SshConnection.TERMINAL_CHANNELS).map { transport.openFiles() }
      val waiting = async { transport.openFiles() }
      delay(1_000)
      assertFalse(waiting.isCompleted, "a channel beyond the room for transfers and lookups opened")
      // Terminal streams still open, in the room kept for them, and work.
      val terminals = (1..SshConnection.TERMINAL_CHANNELS).map { withTimeout(5_000) { transport.openTerminal(listOf("cat")) } }
      terminals.first().output.apply { write("hi\n".toByteArray()) }.flush()
      assertEquals("hi\n", withContext(Dispatchers.IO) { terminals.first().input.readNBytes(3).decodeToString() })
      // Once a transfer ends, the one waiting gets its room.
      taken.first().close()
      val next = withTimeout(5_000) { waiting.await() }
      (taken.drop(1) + next + terminals).forEach { it.close() }
    }
  }

  @Test
  fun channelTheServerHasNoRoomForWaitsUntilItHas() = runBlocking {
    connected().use { connection ->
      val transport = SshHerdrTransport(connection, socketPath = null)
      // Commands that go on after their channels close, as a lookup past its deadline does: sshd keeps a session until its
      // command ends, while the room in the connection's budget is free again at once.
      val others = SshConnection.SESSION_CHANNELS - SshConnection.TERMINAL_CHANNELS
      repeat(others) { transport.exec(listOf("sh", "-c", "sleep 2")).close() }
      // sshd has room for fewer channels than the budget gives now: the rest wait for the commands to end.
      (1..others).map { async { transport.openFiles() } }.awaitAll().forEach { it.close() }
    }
  }

  @Test
  fun channelTheServerKeepsRefusingEndsInATimeoutThatSaysWhy() = runBlocking {
    connected().use { connection ->
      val transport = SshHerdrTransport(connection, socketPath = null)
      // Commands that outlast the deadline of an open take every session sshd allows the connection, each channel closed
      // at once: the budget has room, the server none.
      val seconds = SshConnection.OPEN_TIMEOUT_MS / 1_000 + 4
      repeat(10) { transport.exec(listOf("sh", "-c", "sleep $seconds")).close() }
      val e = assertFailsWith<HerdrTimeoutException> { transport.openFiles() }
      assertTrue(e.message!!.contains("no room"), e.message)
    }
  }

  /** As a slow host: every `sh -c` and `sh -lc` script waits [seconds] first. */
  private class SlowHost(private val inner: HerdrTransport, private val seconds: Int) : HerdrTransport by inner {
    override suspend fun exec(argv: List<String>): ProcessStream =
      inner.exec(if (argv.size >= 3 && argv[0] == "sh" && argv[1] in setOf("-c", "-lc")) listOf("sh", argv[1], "sleep $seconds; ${argv[2]}") + argv.drop(3) else argv)
  }

  @Test
  fun everyCommandPastItsDeadlineTimesOut() = runBlocking {
    val home = File(System.getProperty("user.home")).canonicalPath
    val herdr = File(dir.toFile(), "slow-herdr").apply {
      writeText("#!/bin/sh\nsleep $SLOW_SECONDS\necho '{\"client\":{\"version\":\"0.9.5\",\"binary\":\"/x\"},\"server\":{\"running\":true,\"socket\":\"/s\"}}'\n")
      setExecutable(true)
    }
    val tokens = File(dir.toFile(), "tokens")
    connected().use { connection ->
      val plain = SshHerdrTransport(connection, socketPath = null)
      val slow = SlowHost(plain, SLOW_SECONDS)
      // Controls: given the time, the lookups see the host as it is.
      assertEquals(home, plain.resolveFolder("~"))
      assertEquals(true, plain.readHostStatus(herdr.path, "s").server.running)

      // Past the deadline, each is a timeout: not what the host printed until then, which looks like an answer.
      val deadline = 1_000L
      val results =
        mapOf(
          "uploadDir" to runCatching { slow.uploadDir(deadline) },
          "resolveFolder" to runCatching { slow.resolveFolder(home, deadline) },
          "checkFolders" to runCatching { slow.checkFolders(listOf(home), deadline) },
          "installedAgents" to runCatching { slow.installedAgents(deadline) },
          "readHostStatus" to runCatching { plain.readHostStatus(herdr.path, "s", deadline) },
          "saveTelegramToken" to runCatching { slow.saveTelegramToken("1:test", tokens.path, deadline) },
          "removeTelegramToken" to runCatching { slow.removeTelegramToken(tokens.path, deadline) },
        )
      val notTimeouts = results.filterValues { it.exceptionOrNull() !is HerdrTimeoutException }.mapValues { (_, r) -> r.fold({ "returned $it" }, { "$it" }) }
      assertEquals(emptyMap(), notTimeouts)
    }
    // The host goes on with each command after its channel closed: their ends come before the folder goes.
    delay(SLOW_SECONDS * 1_000L + 500)
  }

  @Test
  fun execStreamWeClosedFailsInsteadOfEnding() = runBlocking {
    connected().use { connection ->
      val stream = connection.execute("echo started; exec cat")
      val read = async(Dispatchers.IO) { runCatching { stream.input.readAllBytes() } }
      delay(500)
      // As a deadline does: what came before the close is no whole answer.
      stream.close()
      assertIs<IOException>(withTimeout(5_000) { read.await() }.exceptionOrNull())
      assertFailsWith<IOException> { stream.exitStatus() }
      Unit
    }
  }

  /** How many channels the connection's JSch session has open, as JSch counts them. */
  private fun channels(connection: SshConnection): Int {
    val session = SshConnection::class.java.getDeclaredField("session").apply { isAccessible = true }.get(connection) as Session
    val list = Session::class.java.getDeclaredField("channels").apply { isAccessible = true }.get(session) as List<*>
    return synchronized(list) { list.size }
  }

  /** The phone: [count] picked files of [size] bytes, read as slowly as a phone's Wi-Fi sends them. */
  private class SlowPhone(count: Int, private val size: Int) : PhoneSide {
    val sources = (1..count).map { "content://picker/IMG_$it.jpg" }

    override fun describe(source: String): Pair<String, Long?> = source.substringAfterLast('/') to size.toLong()

    override fun read(source: String): InputStream =
      object : InputStream() {
        var left = size

        override fun read(): Int = if (left-- > 0) 0 else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (left <= 0) return -1
          Thread.sleep(20)
          val n = minOf(len, left, 32 * 1024)
          left -= n
          return n
        }
      }

    override fun exists(location: String) = false

    override fun save(name: String, previous: String?, write: (OutputStream) -> Unit) = error("not used")
  }

  /**
   * The host's end as the app has it: an SFTP channel of its own for each transfer, and the uploads folder looked up on
   * the host once, by the first upload while it holds its channel, as `HerdrHost.uploadDir` does.
   */
  private class Host(private val transport: HerdrTransport, private val dir: String) : HostSide {
    private val lookup = Mutex()
    private var found: String? = null

    override suspend fun <T> withFiles(block: suspend (files: RemoteFiles, uploadDir: suspend () -> String) -> T): T =
      transport.openFiles().use { files -> block(files) { uploadDir() } }

    private suspend fun uploadDir(): String = lookup.withLock { found ?: checkNotNull(transport.resolveFolder(dir)).also { found = it } }
  }

  private companion object {
    const val SLOW_SECONDS = 3
  }
}

/** A Unix socket that accepts connections and never answers, like a herdr that waits for a request; [open] counts them. */
private class UnixServer(socket: Path) : AutoCloseable {
  val path = socket.toString()
  val open = AtomicInteger()
  private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket)) }
  private val clients = CopyOnWriteArrayList<SocketChannel>()

  init {
    thread(isDaemon = true, name = "test-unix-accept") {
      while (true) {
        val client = try { server.accept() } catch (e: IOException) { break }
        clients += client
        open.incrementAndGet()
        thread(isDaemon = true, name = "test-unix-read") {
          val buf = ByteBuffer.allocate(4096)
          try {
            var read = 0
            while (read >= 0) read = client.read(buf.clear())
          } catch (e: IOException) {
          }
          open.decrementAndGet()
        }
      }
    }
  }

  override fun close() {
    server.close()
    clients.forEach { runCatching { it.close() } }
  }
}

/** A TCP proxy to the local sshd that delays what it passes by [delayMs] each way, like a phone's link. */
private class TcpProxy(private val targetPort: Int, private val delayMs: Long) : AutoCloseable {
  private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
  val port: Int get() = server.localPort
  private val sockets = CopyOnWriteArrayList<Socket>()

  init {
    thread(isDaemon = true, name = "test-proxy-accept") {
      while (true) {
        val client = try { server.accept() } catch (e: IOException) { break }
        val upstream = Socket("127.0.0.1", targetPort)
        sockets += client
        sockets += upstream
        pump(client, upstream)
        pump(upstream, client)
      }
    }
  }

  private fun pump(from: Socket, to: Socket) {
    val queue = LinkedBlockingQueue<Pair<Long, ByteArray?>>()
    thread(isDaemon = true, name = "test-proxy-read") {
      val buf = ByteArray(16 * 1024)
      try {
        while (true) {
          val n = from.getInputStream().read(buf)
          if (n < 0) break
          queue.put(System.nanoTime() + delayMs * 1_000_000 to buf.copyOf(n))
        }
      } catch (e: IOException) {
      }
      queue.put(0L to null)
    }
    thread(isDaemon = true, name = "test-proxy-write") {
      try {
        while (true) {
          val (at, bytes) = queue.take()
          if (bytes == null) {
            to.shutdownOutput()
            break
          }
          val wait = (at - System.nanoTime()) / 1_000_000
          if (wait > 0) Thread.sleep(wait)
          to.getOutputStream().write(bytes)
        }
      } catch (e: IOException) {
      }
    }
  }

  override fun close() {
    server.close()
    sockets.forEach { runCatching { it.close() } }
  }
}
