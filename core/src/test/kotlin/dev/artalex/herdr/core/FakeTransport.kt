package dev.artalex.herdr.core

import dev.artalex.herdr.core.transport.ByteStream
import dev.artalex.herdr.core.transport.HerdrTransport
import dev.artalex.herdr.core.transport.ProcessStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** In-memory herdr: [respond] maps each request to an answer line, or null to never answer. */
class FakeTransport(private val respond: (JsonObject) -> String?) : HerdrTransport {
  val requests = mutableListOf<JsonObject>()

  override fun openApi(): ByteStream = FakeStream()

  override fun exec(argv: List<String>): ProcessStream = error("exec is not faked")

  private inner class FakeStream : ByteStream {
    private val sent = ByteArrayOutputStream()
    @Volatile private var closed = false

    override val output: OutputStream =
      object : OutputStream() {
        override fun write(b: Int) = sent.write(b)
      }

    override val input: InputStream =
      object : InputStream() {
        private var answer: InputStream? = null

        override fun read(): Int {
          val stream =
            answer
              ?: run {
                val request = Json.parseToJsonElement(sent.toString(Charsets.UTF_8).trim()).jsonObject
                synchronized(requests) { requests += request }
                val line = respond(request)
                if (line == null) {
                  while (!closed) Thread.sleep(5)
                  throw IOException("closed")
                }
                ByteArrayInputStream((line + "\n").toByteArray()).also { answer = it }
              }
          return stream.read()
        }
      }

    override fun close() {
      closed = true
    }
  }
}

fun JsonObject.id(): String = getValue("id").jsonPrimitive.content

fun JsonObject.method(): String = getValue("method").jsonPrimitive.content

fun ok(request: JsonObject, result: String) = """{"id":"${request.id()}","result":$result}"""
