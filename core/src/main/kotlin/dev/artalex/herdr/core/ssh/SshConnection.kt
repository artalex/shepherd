package dev.artalex.herdr.core.ssh

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectStreamLocal
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchHostKeyException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dev.artalex.herdr.core.files.RemoteFiles
import dev.artalex.herdr.core.rpc.HerdrTimeoutException
import dev.artalex.herdr.core.transport.ByteStream
import dev.artalex.herdr.core.transport.ProcessStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

data class SshTarget(val host: String, val port: Int = 22, val user: String)

/** Why an SSH connection could not be established; the app shows each case differently. */
sealed class SshFailure(message: String, cause: Throwable? = null) : IOException(message, cause) {
  /** First contact: the user has to compare and accept [presented]. */
  class UnknownHostKey(val presented: HostKey) : SshFailure("host key ${presented.fingerprint} is not trusted yet")

  /** The server's key differs from the pinned one: possible interception or reinstalled server. */
  class HostKeyChanged(val pinned: HostKey, val presented: HostKey) :
    SshFailure("host key changed from ${pinned.fingerprint} to ${presented.fingerprint}")

  class AuthFailed(cause: Throwable) : SshFailure("public key was not accepted", cause)

  class Unreachable(cause: Throwable) : SshFailure("cannot reach SSH server: ${cause.message}", cause)
}

/**
 * One SSH session to the herdr host: herdr API requests open a streamlocal channel each, commands and terminal streams
 * an exec channel, files an SFTP channel.
 *
 * Every channel opens the one way, [opened]: a caller cancelled meanwhile leaves no channel open, the open has a
 * deadline, and exec and SFTP channels wait for room on the connection ([SESSION_CHANNELS]) rather than fail at the
 * server.
 *
 * The host key is checked against [pinnedHostKey] only; there is no known_hosts file on the phone.
 */
class SshConnection(private val target: SshTarget, private val key: SshKeyPair, private val pinnedHostKey: HostKey?) : Closeable {
  private val jsch = JSch()
  @Volatile private var session: Session? = null

  /**
   * Closing a channel or the session sends SSH packets. Android refuses network I/O on the main thread,
   * and JSch swallows that error after it has already encrypted the packet: the client's cipher stream
   * then runs ahead of the server's, and the next packet is garbage to it ("message authentication code
   * incorrect"). So every close runs on this connection's own thread, whichever thread asks for it.
   */
  private val closer = Executors.newSingleThreadExecutor { task -> Thread(task, "ssh-close ${target.host}").apply { isDaemon = true } }

  /**
   * Where channels open: each to its end, whatever becomes of the caller that asked for it, as a thread inside JSch is
   * never interrupted (see [dev.artalex.herdr.core.rpc.blockingIoWithDeadline]).
   */
  private val opener = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val budget = ChannelBudget(SESSION_CHANNELS, TERMINAL_CHANNELS)

  /** Done once the connection is closed: an open that waits for room on the server stops waiting. */
  private val closing = CompletableDeferred<Unit>()

  val isConnected: Boolean get() = session?.isConnected == true

  /** Blocking. Throws [SshFailure]. */
  fun connect() {
    val repository = PinnedHostKeyRepository(pinnedHostKey)
    jsch.setHostKeyRepository(repository)
    jsch.addIdentity("herdr-android", key.privateKey.copyOf(), key.publicKeyLine.toByteArray(), null)
    val s = jsch.getSession(target.user, target.host, target.port)
    s.setConfig("StrictHostKeyChecking", "yes")
    s.setConfig("PreferredAuthentications", "publickey")
    s.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
    s.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
    // Ask for the Ed25519 host key first, so the fingerprint the app shows is the one
    // `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` prints on the server.
    s.setConfig("server_host_key", preferEd25519(JSch.getConfig("server_host_key")))
    s.setServerAliveInterval(KEEPALIVE_MS)
    s.setServerAliveCountMax(KEEPALIVE_COUNT)
    s.userInfo = NoPrompts
    try {
      s.connect(CONNECT_TIMEOUT_MS)
    } catch (e: JSchException) {
      val presented = repository.presented
      throw when {
        e is JSchHostKeyException && presented != null && pinnedHostKey == null -> SshFailure.UnknownHostKey(presented)
        e is JSchHostKeyException && presented != null && pinnedHostKey != null -> SshFailure.HostKeyChanged(pinnedHostKey, presented)
        // JSch reports a rejected key only through the message ("Auth fail for methods 'publickey'").
        e.message?.startsWith("Auth fail") == true -> SshFailure.AuthFailed(e)
        else -> SshFailure.Unreachable(e)
      }
    }
    session = s
  }

  /**
   * A connection to a Unix socket on the server, like `ssh -L local:/path/to.sock`. sshd doesn't count these among the
   * session channels it limits, so they don't wait for room; one the server refuses, as when nothing listens on the
   * socket, fails at once.
   */
  suspend fun streamLocal(socketPath: String): ByteStream =
    opened("SSH channel", permit = null) { s ->
      val channel = s.openChannel("direct-streamlocal@openssh.com") as ChannelDirectStreamLocal
      channel.setSocketPath(socketPath)
      ChannelStream(channel, permit = null).also { channel.connect(CHANNEL_TIMEOUT_MS) }
    }

  /**
   * Runs [command] through the user's shell on the server, without a PTY. A [terminal] stream may take a channel kept for
   * terminal streams.
   */
  suspend fun execute(command: String, terminal: Boolean = false): ProcessStream {
    val permit = budget.acquire(terminal)
    return opened("SSH channel", permit) { s ->
      val channel = s.openChannel("exec") as ChannelExec
      channel.setCommand(command)
      val errors = ByteArrayOutputStream()
      channel.setErrStream(errors, true)
      Command(channel, permit, errors).also { connectSession(channel) }
    }
  }

  /** Files on the server over an SFTP channel of its own. */
  suspend fun sftp(): RemoteFiles {
    val permit = budget.acquire(terminal = false)
    return opened("SFTP channel", permit) { s ->
      val channel = s.openChannel("sftp") as ChannelSftp
      connectSession(channel)
      try {
        SftpFiles(channel) { disconnectLater(channel, permit) }
      } catch (e: Exception) {
        // On the open's own thread, never the main one: it may close the channel itself.
        channel.disconnect()
        throw e
      }
    }
  }

  /** [streamLocal] for a caller outside coroutines, such as a test's own line to the host. Blocking: not on the main thread. */
  fun openStreamLocal(socketPath: String): ByteStream = runBlocking { streamLocal(socketPath) }

  /** [execute] for a caller outside coroutines, such as a test's own line to the host. Blocking: not on the main thread. */
  fun exec(command: String): ProcessStream = runBlocking { execute(command) }

  /** Returns at once; the session is disconnected on the close thread. Those who wait for room for a channel stop. */
  override fun close() {
    budget.close()
    closing.complete(Unit)
    val s = session ?: return
    session = null
    closeLater { s.disconnect() }
    closer.shutdown()
  }

  /**
   * A channel that [open] opens on the session, with the room [permit] holds for it, if it needs any. The open runs to
   * its end on its own, and the caller waits for it [OPEN_TIMEOUT_MS] at most; one the server has no room for is opened
   * again meanwhile ([Refused]), and a timeout says so. A caller that stops waiting, cancelled or past the deadline,
   * leaves nothing open: the channel is closed once it is open.
   */
  private suspend fun <T : Closeable> opened(what: String, permit: ChannelBudget.Permit?, open: (Session) -> T): T {
    val wanted = AtomicBoolean(true)
    val refused = AtomicBoolean(false)
    val opening =
      opener.async {
        try {
          whenRoom(wanted, refused) { open(session()) }
        } catch (e: Throwable) {
          permit?.release()
          throw if (e is JSchException) IOException("cannot open $what: ${e.message}", e) else e
        }
      }
    val channel =
      try {
        withTimeoutOrNull(OPEN_TIMEOUT_MS) { opening.await() }
      } catch (e: CancellationException) {
        closeOnceOpen(opening, wanted)
        throw e
      }
    if (channel == null) {
      closeOnceOpen(opening, wanted)
      val why = if (refused.get()) ": the server had no room for another session channel (sshd MaxSessions)" else ""
      throw HerdrTimeoutException("cannot open $what within $OPEN_TIMEOUT_MS ms$why")
    }
    return channel
  }

  /**
   * [open], once more after a pause each time the server has no room for the channel, which [refused] tells, as long as
   * it is [wanted]. A command of the connection that goes on after its channel closed, as one past its deadline, keeps its
   * session on the server until it ends, and our budget doesn't see that. A close of the connection ends the pause.
   */
  private suspend fun <T> whenRoom(wanted: AtomicBoolean, refused: AtomicBoolean, open: () -> T): T {
    var pause = REFUSED_PAUSE_MS
    while (true) {
      if (!wanted.get()) throw IOException("nobody waits for the channel any longer")
      try {
        return open()
      } catch (e: Refused) {
        refused.set(true)
      }
      withTimeoutOrNull(pause) { closing.await() }
      pause = minOf(2 * pause, REFUSED_PAUSE_MAX_MS)
    }
  }

  /** Closes what [opening] opens, once it has: nobody waits for it any longer, nor is it [wanted]. */
  private fun closeOnceOpen(opening: Deferred<Closeable>, wanted: AtomicBoolean) {
    wanted.set(false)
    opener.launch { runCatching { opening.await() }.getOrNull()?.close() }
  }

  /**
   * Connects [channel], a session channel; [Refused] if the server has no room for it. One that fails is left closed by
   * JSch. Its streams are to be taken before.
   */
  private fun connectSession(channel: Channel) {
    try {
      channel.connect(CHANNEL_TIMEOUT_MS)
    } catch (e: JSchException) {
      // JSch keeps the reason code of the server's refusal as the channel's exit status; a channel that timed out has none.
      throw if (e.message == NOT_OPENED && channel.exitStatus > 0) Refused() else e
    }
  }

  /** sshd refused a session channel, as it does past its `MaxSessions`. */
  private class Refused : JSchException(NOT_OPENED)

  /**
   * Disconnects [channel] on the close thread, and gives its room back after that: the close goes to the server before
   * the open of a channel that takes the room. Returns at once.
   */
  private fun disconnectLater(channel: Channel, permit: ChannelBudget.Permit?) {
    try {
      closer.execute {
        try {
          channel.disconnect()
        } finally {
          permit?.release()
        }
      }
    } catch (e: RejectedExecutionException) {
      // The session is being disconnected already, which closes its channels too.
      permit?.release()
    }
  }

  private fun closeLater(action: () -> Unit) {
    try {
      closer.execute(action)
    } catch (e: RejectedExecutionException) {
      // The session is being disconnected already, which closes its channels too.
    }
  }

  private fun session(): Session = session?.takeIf { it.isConnected } ?: throw IOException("SSH session is not connected")

  /**
   * The streams of [channel], taken before it connects. Once we close it, its input fails rather than ends: JSch ends it
   * as if the other side had, and what came before the close, cut short by a deadline, would pass for the whole answer.
   */
  private open inner class ChannelStream(protected val channel: Channel, private val permit: ChannelBudget.Permit?) : ByteStream {
    private val closed = AtomicBoolean()

    /** We closed the channel. */
    protected val closedHere: Boolean
      get() = closed.get()

    override val input: InputStream =
      object : FilterInputStream(channel.inputStream) {
        override fun read(): Int = super.read().also { failIfClosed() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { failIfClosed() }
      }

    override val output: OutputStream = channel.outputStream

    private fun failIfClosed() {
      if (closed.get()) throw IOException("SSH channel closed")
    }

    /** Returns at once; the channel is disconnected on the close thread. */
    override fun close() {
      if (closed.compareAndSet(false, true)) disconnectLater(channel, permit)
    }
  }

  /** A command's channel: its stdin and stdout, and what it writes to [errors]. */
  private inner class Command(channel: ChannelExec, permit: ChannelBudget.Permit?, private val errors: ByteArrayOutputStream) :
    ChannelStream(channel, permit), ProcessStream {
    override val stderr: String
      get() = errors.toByteArray().toString(Charsets.UTF_8)

    // The server sends the exit status before it closes the channel. Our close ends the wait too, without one, and
    // without waiting for JSch to have the channel closed.
    override fun exitStatus(): Int? {
      while (!channel.isClosed && !closedHere) Thread.sleep(EXIT_POLL_MS)
      val status = channel.exitStatus
      if (status < 0 && closedHere) throw IOException("SSH channel closed before the command ended")
      return status.takeIf { it >= 0 }
    }
  }

  private class PinnedHostKeyRepository(private val pinned: HostKey?) : HostKeyRepository {
    @Volatile var presented: HostKey? = null

    override fun check(host: String, key: ByteArray): Int {
      val actual = HostKey.fromBlob(key)
      presented = actual
      return when (pinned) {
        null -> HostKeyRepository.NOT_INCLUDED
        actual -> HostKeyRepository.OK
        else -> HostKeyRepository.CHANGED
      }
    }

    override fun add(hostkey: com.jcraft.jsch.HostKey, ui: UserInfo?) = Unit

    override fun remove(host: String, type: String) = Unit

    override fun remove(host: String, type: String, key: ByteArray) = Unit

    override fun getKnownHostsRepositoryID() = "herdr-android-pinned"

    override fun getHostKey(): Array<com.jcraft.jsch.HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<com.jcraft.jsch.HostKey> = emptyArray()
  }

  /** The app never answers interactive prompts: no passwords, no "trust this host?" questions inside JSch. */
  private object NoPrompts : UserInfo {
    override fun getPassphrase(): String? = null

    override fun getPassword(): String? = null

    override fun promptPassword(message: String?) = false

    override fun promptPassphrase(message: String?) = false

    override fun promptYesNo(message: String?) = false

    override fun showMessage(message: String?) = Unit
  }

  companion object {
    internal fun preferEd25519(algorithms: String): String =
      (listOf("ssh-ed25519") + algorithms.split(',').filter { it.isNotBlank() && it != "ssh-ed25519" }).joinToString(",")

    const val CONNECT_TIMEOUT_MS = 8_000
    const val CHANNEL_TIMEOUT_MS = 5_000

    /**
     * How long a channel may take to open once the budget has room for it, waits for room on the server included: JSch
     * waits [CHANNEL_TIMEOUT_MS] for the server to open it, and as long again for it to take the command or the SFTP
     * subsystem. Past this the caller has a timeout, though JSch may still be stuck in the open, as behind a write that a
     * dead link holds up.
     */
    const val OPEN_TIMEOUT_MS = 2L * CHANNEL_TIMEOUT_MS + 2_000

    /**
     * Exec and SFTP channels open at once on a connection. sshd allows 10 by default (`MaxSessions`), and frees one a
     * moment after our close of it reaches the server, or once its command ends, if that goes on; the API's streamlocal
     * channels don't count.
     */
    const val SESSION_CHANNELS = 8

    /** Of [SESSION_CHANNELS], those kept for terminal streams: a pane's screen opens while transfers and lookups take the rest. */
    const val TERMINAL_CHANNELS = 2

    /** The first pause before a channel the server had no room for is opened again; it doubles up to the second. */
    const val REFUSED_PAUSE_MS = 50L
    const val REFUSED_PAUSE_MAX_MS = 1_000L

    /** What JSch says of a channel the server didn't open, refused or with no answer. */
    private const val NOT_OPENED = "channel is not opened."

    const val KEEPALIVE_MS = 10_000
    const val KEEPALIVE_COUNT = 3
    const val EXIT_POLL_MS = 10L
  }
}
