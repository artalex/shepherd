package dev.artalex.herdr.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger

/** Forwards JSch's own log to the host platform; [verbose] adds handshake and channel details. */
object SshLog {
  fun install(verbose: Boolean, sink: (level: String, message: String, error: Throwable?) -> Unit) {
    JSch.setLogger(
      object : Logger {
        override fun isEnabled(level: Int) = verbose || level >= Logger.WARN

        override fun log(level: Int, message: String) = sink(name(level), message, null)

        override fun log(level: Int, message: String, cause: Throwable?) = sink(name(level), message, cause)
      }
    )
  }

  private fun name(level: Int) =
    when (level) {
      Logger.DEBUG -> "debug"
      Logger.INFO -> "info"
      Logger.WARN -> "warn"
      Logger.ERROR -> "error"
      else -> "fatal"
    }
}
