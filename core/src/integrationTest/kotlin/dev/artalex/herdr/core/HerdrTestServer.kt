package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.HostStatus
import dev.artalex.herdr.core.host.compareVersions
import dev.artalex.herdr.core.proto.readNdjsonLine
import dev.artalex.herdr.core.proto.writeNdjsonLine
import dev.artalex.herdr.core.transport.ByteStream
import dev.artalex.herdr.core.transport.HerdrTransport
import dev.artalex.herdr.core.transport.ProcessStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A throwaway herdr server for integration tests.
 *
 * By default XDG_CONFIG_HOME and XDG_STATE_HOME point into a fresh directory, so the server, its socket
 * and its session state never touch the user's real herdr sessions. The directory lives in
 * XDG_RUNTIME_DIR because Unix socket paths must fit into 108 bytes.
 *
 * Tests that reach herdr over SSH can't pass that environment through, so with [isolatedConfig] false
 * the server runs as an ordinary named session `hdrt-<random>` that is stopped and deleted afterwards.
 *
 * `agent.start` runs `claude` from the pane shell's PATH; [FAKE_CLAUDE] comes first on it, so tests get a
 * line echo herdr recognizes as Claude instead of a real session.
 */
class HerdrTestServer(private val isolatedConfig: Boolean = true) : AutoCloseable {
  val dir: Path = Files.createTempDirectory(Path.of(System.getenv("XDG_RUNTIME_DIR") ?: "/tmp"), "hdrt")
  val session = if (isolatedConfig) "it" else "hdrt-" + dir.fileName.toString().takeLast(8).lowercase()
  private val env =
    (if (isolatedConfig) mapOf("XDG_CONFIG_HOME" to "$dir/c", "XDG_STATE_HOME" to "$dir/s") else mapOf("HERDR_CONFIG_PATH" to "${ownConfig()}")) +
      ("PATH" to "${fakeClaudeDir()}:${System.getenv("PATH")}")
  private val server: Process = start(listOf(binary, "--session", session, "server"), File("$dir/server.log"))
  val socket: String
  val transport: HerdrTransport

  init {
    check(session.startsWith("hdrt-") || isolatedConfig) { "refusing to run tests against session $session" }
    socket = waitUntilRunning()
    if (isolatedConfig) check(socket.startsWith(dir.toString())) { "test server socket $socket escaped $dir" }
    else check(socket.contains("/sessions/$session/")) { "test server socket $socket is not session $session" }
    transport = LocalTransport()
  }

  /** Raw API call for test setup; deliberately bypasses the client's method whitelist. */
  fun raw(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
    transport.openApi().use { stream ->
      stream.output.writeNdjsonLine(buildJsonObject { put("id", "setup"); put("method", method); put("params", params) }.toString())
      val response = Json.parseToJsonElement(checkNotNull(stream.input.buffered().readNdjsonLine())).jsonObject
      check("error" !in response) { "$method failed: ${response["error"]}" }
      return response.getValue("result").jsonObject
    }
  }

  /**
   * Settings of a named session's own: the user's may make pane shells login shells, and the profile they read would put
   * the real claude before [FAKE_CLAUDE].
   */
  private fun ownConfig(): Path = dir.resolve("config.toml").also { Files.writeString(it, "onboarding = false\n") }

  private fun fakeClaudeDir(): Path {
    val bin = Files.createDirectories(dir.resolve("bin"))
    bin.resolve("claude").toFile().apply {
      writeText(FAKE_CLAUDE)
      setExecutable(true)
    }
    return bin
  }

  /** Stops the server the way `herdr server stop` does; the session stays, as on a host where herdr was stopped. */
  fun stop() {
    run(listOf(binary, "--session", session, "server", "stop"))
    check(server.waitFor(10, TimeUnit.SECONDS)) { "herdr test server did not stop; see $dir/server.log" }
  }

  override fun close() {
    runCatching { run(listOf(binary, "--session", session, "server", "stop")) }
    server.destroy()
    if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly()
    if (!isolatedConfig) runCatching { run(listOf(binary, "session", "delete", session)) }
    dir.toFile().deleteRecursively()
  }

  private fun waitUntilRunning(): String {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    while (System.nanoTime() < deadline) {
      val out = runCatching { run(listOf(binary, "--session", session, "status", "--json")) }.getOrNull()
      // A status the client can't decode fails the test instead of counting as "not up yet", which hid that a
      // stopped server's status didn't decode.
      val status = out?.takeIf { it.isNotBlank() }?.let { HostStatus.parse(it) }
      if (status?.server?.running == true) return status.server.socket
      Thread.sleep(100)
    }
    error("herdr test server did not start; see $dir/server.log")
  }

  private fun start(argv: List<String>, log: File? = null, errors: File? = null): Process =
    ProcessBuilder(argv)
      .apply {
        environment().keys.removeIf { it.startsWith("HERDR_") }
        environment().putAll(env)
        when {
          log != null -> redirectOutput(log).redirectErrorStream(true)
          errors != null -> redirectError(errors)
          else -> redirectError(ProcessBuilder.Redirect.DISCARD)
        }
      }
      .start()

  private fun run(argv: List<String>): String {
    val p = start(argv)
    val out = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
    check(p.waitFor(10, TimeUnit.SECONDS)) { "${argv.joinToString(" ")} hung" }
    return out
  }

  /** API over the Unix socket, exec as a local process with the isolated environment. */
  private inner class LocalTransport : HerdrTransport {
    private val execs = AtomicInteger()

    override fun openApi(): ByteStream {
      val channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))
      return object : ByteStream {
        override val input: InputStream = Channels.newInputStream(channel)
        override val output: OutputStream = Channels.newOutputStream(channel)

        override fun closeOutput() {
          channel.shutdownOutput()
        }

        override fun close() = channel.close()
      }
    }

    override fun exec(argv: List<String>): ProcessStream {
      val errors = File("$dir/exec-${execs.incrementAndGet()}.stderr")
      val p = start(argv, errors = errors)
      return object : ProcessStream {
        override val input: InputStream = p.inputStream
        override val output: OutputStream = p.outputStream
        override val stderr: String get() = errors.readText()

        override fun exitStatus(): Int = p.waitFor()

        override fun close() {
          p.destroy()
        }
      }
    }
  }

  companion object {
    /** Stand-in for Claude Code: a line echo started as argv0 `claude`, which is how herdr recognizes it. */
    const val FAKE_CLAUDE = "#!/usr/bin/env bash\nexec -a claude python3 -u -c 'import sys\nfor line in sys.stdin:\n    sys.stdout.write(line)\n    sys.stdout.flush()'\n"

    val binary: String = System.getenv("HERDR_BIN") ?: "${System.getProperty("user.home")}/.local/bin/herdr"

    /** herdr ≥ 0.9.5 is installed here; otherwise the integration tests are skipped. */
    val available: Boolean by lazy {
      if (!File(binary).canExecute()) return@lazy false
      val version = ProcessBuilder(binary, "--version").start().inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
      compareVersions(version.removePrefix("herdr "), MIN_HERDR_VERSION) >= 0
    }
  }
}
