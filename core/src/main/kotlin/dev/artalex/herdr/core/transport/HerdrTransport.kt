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

/**
 * How the client reaches a herdr host.
 *
 * herdr serves exactly one request per API connection, so [openApi] is called once per request.
 */
interface HerdrTransport {
  /** Opens a fresh connection to the herdr API socket. */
  fun openApi(): ByteStream

  /** Starts [argv] on the herdr host and connects to its stdin/stdout. */
  fun exec(argv: List<String>): ByteStream

  /** Files on the herdr host; test transports without them throw. */
  fun openFiles(): RemoteFiles = throw UnsupportedOperationException("this transport has no file access")
}
