package dev.artalex.herdr.core.transport

import dev.artalex.herdr.core.files.RemoteFiles
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** A bidirectional byte stream: a socket connection or the stdio of a remote process. */
interface ByteStream : Closeable {
  val input: InputStream
  val output: OutputStream

  /** Signals end of input to the peer without closing the read side. */
  fun closeOutput() = output.close()
}

/** A command started by [HerdrTransport.exec]: its stdin and stdout, and what it wrote to stderr. */
interface ProcessStream : ByteStream {
  /** What the command has written to stderr so far. */
  val stderr: String

  /** Waits for the command to end; its exit status, or null if the host didn't report one. Blocking. */
  fun exitStatus(): Int?
}

/**
 * How the client reaches a herdr host.
 *
 * herdr serves exactly one request per API connection, so [openApi] is called once per request. What a transport opens
 * it opens safely for any caller: one cancelled meanwhile leaves nothing open, and an open that gets no answer ends in
 * [dev.artalex.herdr.core.rpc.HerdrTimeoutException].
 */
interface HerdrTransport {
  /** Opens a fresh connection to the herdr API socket. */
  suspend fun openApi(): ByteStream

  /** Starts [argv] on the herdr host and connects to its stdin/stdout. */
  suspend fun exec(argv: List<String>): ProcessStream

  /**
   * Starts [argv], `herdr terminal session control` for a live terminal stream, as [exec] does. Over SSH it may take a
   * channel kept for terminal streams, so a pane's screen opens while transfers and lookups take the rest.
   */
  suspend fun openTerminal(argv: List<String>): ProcessStream = exec(argv)

  /** Files on the herdr host; test transports without them throw. */
  suspend fun openFiles(): RemoteFiles = throw UnsupportedOperationException("this transport has no file access")
}
