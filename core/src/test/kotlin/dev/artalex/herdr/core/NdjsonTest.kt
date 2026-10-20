package dev.artalex.herdr.core

import dev.artalex.herdr.core.proto.readNdjsonLine
import dev.artalex.herdr.core.proto.writeNdjsonLine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NdjsonTest {
  /** Hands out one byte per read, like a slow socket. */
  private class Trickle(bytes: ByteArray) : InputStream() {
    private val inner = ByteArrayInputStream(bytes)

    override fun read() = inner.read()

    override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, minOf(len, 1))
  }

  @Test
  fun readsLinesSplitAcrossReadsIncludingMultibyteUtf8() {
    val input = Trickle("{\"t\":\"привет\"}\n{\"n\":2}\n".toByteArray())
    assertEquals("{\"t\":\"привет\"}", input.readNdjsonLine())
    assertEquals("{\"n\":2}", input.readNdjsonLine())
    assertNull(input.readNdjsonLine())
  }

  @Test
  fun endOfStreamInsideLineIsAnError() {
    assertFailsWith<IOException> { ByteArrayInputStream("{\"a\":1".toByteArray()).readNdjsonLine() }
  }

  @Test
  fun rejectsOversizedLine() {
    assertFailsWith<IOException> { ByteArrayInputStream(ByteArray(100) { 'x'.code.toByte() }).readNdjsonLine(maxBytes = 10) }
  }

  @Test
  fun writesOneTerminatedLine() {
    val out = ByteArrayOutputStream()
    out.writeNdjsonLine("""{"id":"a1"}""")
    assertEquals("{\"id\":\"a1\"}\n", out.toString(Charsets.UTF_8))
    assertFailsWith<IllegalArgumentException> { out.writeNdjsonLine("a\nb") }
  }
}
