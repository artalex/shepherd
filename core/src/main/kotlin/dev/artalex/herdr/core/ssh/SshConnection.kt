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
import dev.artalex.herdr.core.transport.ByteStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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
 * One SSH session to the herdr host. Channels are cheap: herdr API requests open a streamlocal channel each.
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

  /** A connection to a Unix socket on the server, like `ssh -L local:/path/to.sock`. Blocking: not on the main thread. */
  fun openStreamLocal(socketPath: String): ByteStream {
    val channel = session().openChannel("direct-streamlocal@openssh.com") as ChannelDirectStreamLocal
    channel.setSocketPath(socketPath)
    return channel.open()
  }

  /** Runs [command] through the user's shell on the server, without a PTY; stderr is discarded. Blocking: not on the main thread. */
  fun exec(command: String): ByteStream {
    val channel = session().openChannel("exec") as ChannelExec
    channel.setCommand(command)
    channel.setErrStream(ByteArrayOutputStream(), true)
    return channel.open()
  }

  /** Files on the server over an SFTP channel of its own. Blocking: not on the main thread. */
  fun openFiles(): RemoteFiles {
    val channel = session().openChannel("sftp") as ChannelSftp
    try {
      channel.connect(CHANNEL_TIMEOUT_MS)
      return SftpFiles(channel) { closeLater { channel.disconnect() } }
    } catch (e: Exception) {
      closeLater { channel.disconnect() }
      throw if (e is JSchException) IOException("cannot open SFTP channel: ${e.message}", e) else e
    }
  }

  /** Returns at once; the session is disconnected on the close thread. */
  override fun close() {
    val s = session ?: return
    session = null
    closeLater { s.disconnect() }
    closer.shutdown()
  }

  private fun closeLater(action: () -> Unit) {
    try {
      closer.execute(action)
    } catch (e: RejectedExecutionException) {
      // The session is already being disconnected, which closes its channels too.
    }
  }

  private fun session(): Session = session?.takeIf { it.isConnected } ?: throw IOException("SSH session is not connected")

  private fun Channel.open(): ByteStream {
    val input = inputStream
    val output = outputStream
    try {
      connect(CHANNEL_TIMEOUT_MS)
    } catch (e: JSchException) {
      throw IOException("cannot open SSH channel: ${e.message}", e)
    }
    val channel = this
    return object : ByteStream {
      override val input: InputStream = input
      override val output: OutputStream = output

      override fun close() = closeLater { channel.disconnect() }
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
    const val KEEPALIVE_MS = 10_000
    const val KEEPALIVE_COUNT = 3
  }
}
