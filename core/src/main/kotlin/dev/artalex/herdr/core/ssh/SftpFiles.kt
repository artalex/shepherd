package dev.artalex.herdr.core.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import dev.artalex.herdr.core.files.RemoteEntry
import dev.artalex.herdr.core.files.RemoteFiles
import dev.artalex.herdr.core.files.numberedName
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CancellationException

/** [RemoteFiles] over an SFTP channel of the SSH session; [close] disconnects the channel. */
internal class SftpFiles(private val channel: ChannelSftp, private val onClose: () -> Unit) : RemoteFiles {
  override val home: String = sftp("home folder") { channel.home }

  override fun stat(path: String): RemoteEntry? =
    try {
      channel.stat(path).let { RemoteEntry(path, it.size, it.mTime.toLong() and 0xFFFFFFFFL, it.isReg) }
    } catch (e: SftpException) {
      if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE || e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) null else throw IOException("stat $path: ${e.message}", e)
    }

  override fun download(path: String, out: OutputStream, progress: (Long) -> Boolean) {
    val monitor = Progress(progress)
    sftp("download $path") { channel.get(path, out, monitor) }
    if (monitor.stopped) throw CancellationException("download of $path stopped")
  }

  override fun upload(input: InputStream, dir: String, name: String, progress: (Long) -> Boolean): String {
    makeDirs(dir)
    // Written aside and given its name once complete, so nobody on the host reads half a file.
    val part = "$dir/.$name.${UUID.randomUUID().toString().take(8)}.part"
    try {
      val monitor = Progress(progress)
      sftp("upload $name") { channel.put(input, part, monitor, ChannelSftp.OVERWRITE) }
      if (monitor.stopped) throw CancellationException("upload of $name stopped")
      return publish(part, dir, name)
    } catch (e: Throwable) {
      runCatching { channel.rm(part) }
      throw e
    }
  }

  /**
   * Gives [part] the first free one of [name]'s numbered names in [dir]. A hard link takes a name only if nobody has it,
   * so uploads of one name running at once each get their own. Where the host can't link (no `hardlink@openssh.com`,
   * or a file system without hard links), [part] is renamed to the first name that is free when looked up: JSch renames
   * with posix-rename, which would replace a file another upload put there in between.
   */
  private fun publish(part: String, dir: String, name: String): String {
    for (n in 1..MAX_NUMBER) {
      val target = "$dir/${numberedName(name, n)}"
      try {
        channel.hardlink(part, target)
      } catch (e: SftpException) {
        if (stat(target) != null) continue
        sftp("rename to $target") { channel.rename(part, target) }
        return target
      }
      runCatching { channel.rm(part) }
      return target
    }
    throw IOException("no free name for $name in $dir")
  }

  override fun close() = onClose()

  private fun makeDirs(dir: String) {
    var path = ""
    for (part in dir.split('/').filter { it.isNotEmpty() }) {
      path += "/$part"
      val entry = stat(path)
      if (entry == null) sftp("create $path") { channel.mkdir(path) } else if (entry.isFile) throw IOException("$path is a file, not a folder")
    }
  }

  private inline fun <T> sftp(what: String, block: () -> T): T =
    try {
      block()
    } catch (e: SftpException) {
      throw IOException("$what: ${e.message}", e)
    }

  /** JSch reports bytes per chunk; [report] gets the running total and stops the transfer by returning false. */
  private class Progress(private val report: (Long) -> Boolean) : SftpProgressMonitor {
    private var done = 0L
    var stopped = false
      private set

    override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit

    override fun count(count: Long): Boolean {
      done += count
      if (!report(done)) stopped = true
      return !stopped
    }

    override fun end() = Unit
  }

  private companion object {
    const val MAX_NUMBER = 1000
  }
}
