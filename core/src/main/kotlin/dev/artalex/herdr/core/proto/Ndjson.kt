package dev.artalex.herdr.core.proto

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Longest NDJSON line the client accepts; snapshots of large sessions stay far below this. */
const val MAX_LINE_BYTES = 16 * 1024 * 1024

/** Reads one `\n`-terminated UTF-8 line, or returns null on end of stream before any byte. */
fun InputStream.readNdjsonLine(maxBytes: Int = MAX_LINE_BYTES): String? {
  val buf = ByteArrayOutputStream()
  while (true) {
    val b = read()
    if (b == -1) {
      if (buf.size() == 0) return null
      throw IOException("stream ended inside an NDJSON line")
    }
    if (b == '\n'.code) return buf.toString(Charsets.UTF_8)
    if (buf.size() >= maxBytes) throw IOException("NDJSON line exceeds $maxBytes bytes")
    buf.write(b)
  }
}

fun OutputStream.writeNdjsonLine(json: String) {
  require('\n' !in json) { "NDJSON line must not contain a newline" }
  write(json.toByteArray(Charsets.UTF_8))
  write('\n'.code)
  flush()
}
