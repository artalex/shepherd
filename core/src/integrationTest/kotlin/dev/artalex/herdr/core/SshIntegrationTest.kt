package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.HostCheck
import dev.artalex.herdr.core.host.BadSessionName
import dev.artalex.herdr.core.host.CurlMissing
import dev.artalex.herdr.core.host.FolderCheck
import dev.artalex.herdr.core.host.HerdrNotFound
import dev.artalex.herdr.core.host.HerdrStatusFailed
import dev.artalex.herdr.core.host.TelegramBot
import dev.artalex.herdr.core.host.TelegramBotApi
import dev.artalex.herdr.core.host.TelegramChat
import dev.artalex.herdr.core.host.TelegramError
import dev.artalex.herdr.core.host.check
import dev.artalex.herdr.core.host.checkFolders
import dev.artalex.herdr.core.host.removeTelegramToken
import dev.artalex.herdr.core.host.resolveFolder
import dev.artalex.herdr.core.host.saveTelegramToken
import dev.artalex.herdr.core.ssh.HostKey
import dev.artalex.herdr.core.ssh.SshConnection
import dev.artalex.herdr.core.ssh.SshFailure
import dev.artalex.herdr.core.ssh.SshKeyPair
import dev.artalex.herdr.core.ssh.SshTarget
import dev.artalex.herdr.core.ssh.discoverHerdrHost
import dev.artalex.herdr.core.terminal.CloseReason
import dev.artalex.herdr.core.terminal.TerminalEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * The phone's path, from the JVM: JSch to the local sshd with the localhost-only test key
 * (`from="127.0.0.1,::1"` in authorized_keys), then herdr through streamlocal and exec.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SshIntegrationTest {
  private val keyFile = File(System.getenv("HERDR_SSH_TEST_KEY") ?: "${System.getProperty("user.home")}/.ssh/herdr_android_test")
  private val target = SshTarget("127.0.0.1", 22, System.getProperty("user.name"))
  private lateinit var server: HerdrTestServer
  private lateinit var key: SshKeyPair
  private lateinit var hostKey: HostKey

  @BeforeAll
  fun setUp() {
    assumeTrue(HerdrTestServer.available && keyFile.canRead(), "herdr or the SSH test key is missing")
    server = HerdrTestServer(isolatedConfig = false)
    key = SshKeyPair(keyFile.readBytes(), File("${keyFile.path}.pub").readText().trim())
    hostKey = assertIs<SshFailure.UnknownHostKey>(assertFailsWith<SshFailure> { SshConnection(target, key, null).connect() }).presented
  }

  @AfterAll
  fun tearDown() {
    if (::server.isInitialized) server.close()
  }

  private fun connected() = SshConnection(target, key, hostKey).also { it.connect() }

  @Test
  fun unknownHostKeyIsReportedWithSshKeygenFingerprint() {
    val type = hostKey.type.removePrefix("ssh-").substringBefore('-').let { if (it == "ecdsa") "ecdsa" else it }
    val pub = File("/etc/ssh/ssh_host_${type}_key.pub")
    if (pub.canRead()) {
      val out = ProcessBuilder("ssh-keygen", "-lf", pub.path).start().inputStream.readAllBytes().toString(Charsets.UTF_8)
      assertTrue(out.contains(hostKey.fingerprint), "ssh-keygen says $out, client saw ${hostKey.fingerprint}")
    }
  }

  @Test
  fun changedHostKeyIsRefused() {
    val other = HostKey("ssh-ed25519", hostKey.blob.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() })
    val e = assertIs<SshFailure.HostKeyChanged>(assertFailsWith<SshFailure> { SshConnection(target, key, other).connect() })
    assertEquals(hostKey, e.presented)
  }

  @Test
  fun unknownClientKeyFailsAuthentication() {
    assertIs<SshFailure.AuthFailed>(assertFailsWith<SshFailure> { SshConnection(target, SshKeyPair.generate("not authorized"), hostKey).connect() })
  }

  @Test
  fun closedPortIsUnreachable() {
    assertIs<SshFailure.Unreachable>(assertFailsWith<SshFailure> { SshConnection(target.copy(port = 1), key, hostKey).connect() })
  }

  @Test
  fun discoversHerdrAndUsesApiOverSsh() = runBlocking {
    connected().use { connection ->
      val host = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session)
      assertEquals(HostCheck.Ok, host.status.check())
      assertEquals(server.socket, host.status.server.socket)
      assertEquals(SUPPORTED_PROTOCOL, host.api.ping().protocol)
      host.api.snapshot()
      Unit
    }
  }

  @Test
  fun parallelAndCancelledRequestsKeepTheSessionAlive() = runBlocking {
    connected().use { connection ->
      val host = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session)
      val pane = host.api.snapshot().panes.firstOrNull()?.paneId
        ?: server.raw("workspace.create").getValue("root_pane").let { (it as kotlinx.serialization.json.JsonObject).getValue("pane_id").toString().trim('"') }
      coroutineScope {
        repeat(4) { worker ->
          launch(Dispatchers.Default) {
            repeat(25) { i ->
              if ((worker + i) % 5 == 0) {
                // Cancelled mid-request, like a screen that leaves while a poll is in flight.
                withTimeoutOrNull(1) { host.api.readPane(pane) }
              } else {
                host.api.snapshot()
                host.api.readPane(pane)
              }
            }
          }
        }
      }
      assertTrue(connection.isConnected, "SSH session died under parallel and cancelled requests")
      assertEquals(SUPPORTED_PROTOCOL, host.api.ping().protocol)
    }
  }

  @Test
  fun filesOverSftp() = runBlocking {
    val dir = Files.createTempDirectory(Path.of(System.getenv("XDG_RUNTIME_DIR") ?: "/tmp"), "hsftp").toFile()
    try {
      File(dir, "отчёт.txt").writeText("привет")
      connected().use { connection ->
        connection.openFiles().use { files ->
          assertEquals(System.getProperty("user.home"), files.home)
          val report = checkNotNull(files.stat("$dir/отчёт.txt"))
          assertTrue(report.isFile)
          assertEquals("привет".toByteArray().size.toLong(), report.size)
          assertFalse(checkNotNull(files.stat(dir.path)).isFile)
          assertNull(files.stat("$dir/нет.txt"))

          val out = ByteArrayOutputStream()
          var reported = 0L
          files.download("$dir/отчёт.txt", out) { reported = it; true }
          assertEquals("привет", out.toString(Charsets.UTF_8))
          assertEquals(report.size, reported)

          // A taken name stays with its file; the folder is created on the way.
          val first = files.upload("один".byteInputStream(), "$dir/up/sub", "фото.png") { true }
          val second = files.upload("два".byteInputStream(), "$dir/up/sub", "фото.png") { true }
          assertEquals("$dir/up/sub/фото.png", first)
          assertEquals("$dir/up/sub/фото (2).png", second)
          assertEquals("один", File(first).readText())
          assertEquals("два", File(second).readText())

          // A stopped upload leaves nothing behind, not even its unfinished part.
          assertFailsWith<CancellationException> { files.upload(ByteArray(1 shl 20).inputStream(), "$dir/up/sub", "big.bin") { false } }
          assertEquals(listOf("фото (2).png", "фото.png"), File(dir, "up/sub").list()!!.sorted())
        }
        val uploads = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session).uploadDir()
        assertTrue(uploads.startsWith("/") && uploads.endsWith("/herdr-android"), uploads)
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun uploadsOfOneNameAtOnceKeepEveryFile() = runBlocking {
    val dir = Files.createTempDirectory(Path.of(System.getenv("XDG_RUNTIME_DIR") ?: "/tmp"), "hsftp").toFile()
    try {
      connected().use { connection ->
        // Each over a channel of its own, as the app sends the files picked together; sshd allows 10 per connection.
        val count = 8
        val paths =
          (1..count)
            .map { i -> async(Dispatchers.IO) { connection.openFiles().use { it.upload("файл $i".byteInputStream(), dir.path, "фото.png") { true } } } }
            .awaitAll()
        assertEquals(count, paths.toSet().size, "uploads took one name: $paths")
        assertEquals((1..count).map { "файл $it" }.toSet(), paths.map { File(it).readText() }.toSet())
        // Nothing else is left, not even the parts.
        assertEquals(paths.map { it.substringAfterLast('/') }.sorted(), dir.list()!!.sorted())
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun foldersAreLookedUpOnTheHost() = runBlocking {
    // Under the home folder, for paths with ~ and paths relative to it; resolved, as herdr shows the folders of panes.
    val home = File(System.getProperty("user.home")).canonicalFile
    val dir = Files.createTempDirectory(Files.createDirectories(home.toPath().resolve(".cache")), "hfolder").toFile().canonicalFile
    try {
      val odd = File(dir, "it's a \"folder\"").also { it.mkdir() }
      val link = File(dir, "link").also { Files.createSymbolicLink(it.toPath(), odd.toPath()) }
      val file = File(dir, "notes.txt").also { it.writeText("") }
      connected().use { connection ->
        val transport = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session).transport
        val relative = dir.path.removePrefix("${home.path}/")
        assertEquals(home.path, transport.resolveFolder("~"))
        assertEquals(dir.path, transport.resolveFolder("~/$relative"))
        assertEquals(dir.path, transport.resolveFolder(relative))
        assertEquals(dir.path, transport.resolveFolder("${dir.path}/"))
        assertEquals(odd.path, transport.resolveFolder(odd.path))
        assertEquals(odd.path, transport.resolveFolder(link.path))
        assertNull(transport.resolveFolder("${dir.path}/нет"))
        assertNull(transport.resolveFolder(file.path))
        assertNull(transport.resolveFolder("-P"))
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun foldersAgentsStartedInAreCheckedOnTheHost() = runBlocking {
    val home = File(System.getProperty("user.home")).canonicalFile
    val dir = Files.createTempDirectory(Files.createDirectories(home.toPath().resolve(".cache")), "hfolder").toFile().canonicalFile
    try {
      val odd = File(dir, "it's a \"folder\"\nin two lines").also { it.mkdir() }
      val locked = File(dir, "locked").also {
        it.mkdir()
        it.setExecutable(false, false)
      }
      val file = File(dir, "notes.txt").also { it.writeText("") }
      connected().use { connection ->
        val transport = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session).transport
        // In their order, without those the host doesn't have or won't let in; the home folder resolved, as `~` is.
        val check = transport.checkFolders(listOf(odd.path, "${dir.path}/нет", file.path, locked.path, dir.path, "-P"))
        assertEquals(FolderCheck(home.path, listOf(odd.path, dir.path)), check)
        assertEquals(FolderCheck(home.path, emptyList()), transport.checkFolders(emptyList()))
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun telegramTokenIsSavedForTheUserAlone() = runBlocking {
    val home = File(System.getProperty("user.home")).canonicalFile
    val dir = Files.createTempDirectory(Files.createDirectories(home.toPath().resolve(".cache")), "htoken").toFile().canonicalFile
    try {
      val folder = File(dir, "config dir/herdr-android")
      connected().use { connection ->
        val transport = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session).transport
        val path = transport.saveTelegramToken("1:old", folder.path)
        assertEquals(File(folder, "telegram-bot-token").path, path)
        // A later token replaces the earlier one whole, as it is, and only the user may read it or the folder.
        assertEquals(path, transport.saveTelegramToken("123:a\"b\$c\\d'e", folder.path))
        assertEquals("123:a\"b\$c\\d'e", File(path).readText())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(File(path).toPath()))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(folder.toPath()))
        assertEquals(listOf("telegram-bot-token"), folder.list()?.toList())
        // Taken off with the bot, and taking off what isn't there is no error.
        transport.removeTelegramToken(folder.path)
        assertEquals(emptyList(), folder.list()?.toList())
        transport.removeTelegramToken(folder.path)
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun telegramIsReachedThroughCurlOnTheHost() = runBlocking {
    val home = File(System.getProperty("user.home")).canonicalFile
    val dir = Files.createTempDirectory(Files.createDirectories(home.toPath().resolve(".cache")), "hcurl").toFile().canonicalFile
    try {
      val curl = File(dir, "curl").also {
        it.writeText(FAKE_CURL)
        it.setExecutable(true, true)
      }
      val calls = File(dir, "calls")
      connected().use { connection ->
        val transport = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session).transport
        val bot = TelegramBotApi(transport, "1:good", curl.path)
        assertEquals(TelegramBot("test_bot"), bot.me())
        // The message from before the look is left out: the chat is the one that writes after it.
        assertEquals(TelegramChat(42, "Ann Lee"), bot.nextChat(waitMs = 10_000, pollSeconds = 1))
        bot.send(42, "line one\nline \"two\"")
        val config = calls.readText()
        assertTrue(config.contains("data-urlencode = \"offset=-1\""), config)
        assertTrue(config.contains("data-urlencode = \"offset=7\""), config)
        assertTrue(config.contains("data-urlencode = \"chat_id=42\"\ndata-urlencode = \"text=line one\\nline \\\"two\\\"\""), config)
        assertEquals(401, assertFailsWith<TelegramError> { TelegramBotApi(transport, "1:wrong", curl.path).me() }.code)
        assertEquals(409, assertFailsWith<TelegramError> { TelegramBotApi(transport, "2:busy", curl.path).nextChat(waitMs = 1_000) }.code)
        assertFailsWith<CurlMissing> { TelegramBotApi(transport, "1:good", File(dir, "no-curl").path).me() }
        Unit
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun wrongHerdrPathIsReported() = runBlocking {
    connected().use { connection ->
      val e = assertFailsWith<HerdrNotFound> { discoverHerdrHost(connection, "~/nowhere/herdr", server.session) }
      assertEquals("~/nowhere/herdr", e.path)
    }
  }

  @Test
  fun badSessionNameIsReported() = runBlocking {
    connected().use { connection ->
      val e = assertFailsWith<BadSessionName> { discoverHerdrHost(connection, "~/.local/bin/herdr", "my session") }
      assertEquals("my session", e.session)
      assertEquals("session name may only contain ASCII letters, numbers, '.', '_' and '-'", e.reason)
    }
  }

  @Test
  fun herdrPathThatIsNoProgramIsReported() = runBlocking {
    connected().use { connection ->
      val e = assertFailsWith<HerdrStatusFailed> { discoverHerdrHost(connection, server.dir.toString(), server.session) }
      assertTrue(e.detail.endsWith("${server.dir}: Is a directory"), e.detail)
    }
  }

  @Test
  fun stoppedServerIsReportedNotRunning() = runBlocking {
    // A session of its own: the shared one stays up for the other tests.
    HerdrTestServer(isolatedConfig = false).use { stopped ->
      stopped.stop()
      connected().use { connection ->
        assertEquals(HostCheck.ServerNotRunning, discoverHerdrHost(connection, "~/.local/bin/herdr", stopped.session).status.check())
      }
    }
  }

  @Test
  fun terminalControlOverSsh() = runBlocking {
    val created = server.raw("workspace.create")
    val terminalId = created.getValue("root_pane").let { kotlinx.serialization.json.Json.parseToJsonElement(it.toString()) }
      .let { (it as kotlinx.serialization.json.JsonObject).getValue("terminal_id").toString().trim('"') }
    connected().use { connection ->
      val host = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session)
      val session = host.openTerminal(terminalId, 40, 12, takeover = false)
      val frame = withTimeout(10_000) { session.events.first() }
      assertIs<TerminalEvent.Frame>(frame)
      assertEquals(40 to 12, frame.width to frame.height)
    }
    // A fresh controller proves the previous one was released when the channel closed.
    connected().use { connection ->
      val host = discoverHerdrHost(connection, "~/.local/bin/herdr", server.session)
      val session = host.openTerminal(terminalId, 40, 12, takeover = false)
      val events = withTimeout(10_000) {
        session.events.take(1).toList()
      }
      assertIs<TerminalEvent.Frame>(events.single())
      session.release()
    }
  }
}

/**
 * curl for the tests, standing in for the Bot API: keeps the config it gets on stdin in `calls` beside it and answers
 * as Telegram would. Bot 1:good has an old message and a new one from Ann Lee, 2:busy is polled by another program,
 * any other token is wrong. A token on its command line fails the call.
 */
private val FAKE_CURL =
  """
  #!/bin/sh
  case "${'$'}*" in *bot*) echo "the token is on the command line: ${'$'}*" >&2; exit 2 ;; esac
  config=${'$'}(cat)
  printf '%s\n' "${'$'}config" >> "${'$'}(dirname "${'$'}0")/calls"
  case "${'$'}config" in
    *'/bot1:good/'*) ;;
    *'/bot2:busy/'*) echo '{"ok":false,"error_code":409,"description":"Conflict: terminated by other getUpdates request"}'; exit 0 ;;
    *) echo '{"ok":false,"error_code":401,"description":"Unauthorized"}'; exit 0 ;;
  esac
  case "${'$'}config" in
    *'/getMe"'*) echo '{"ok":true,"result":{"id":1,"is_bot":true,"first_name":"Test","username":"test_bot"}}' ;;
    *'/getUpdates"'*'"offset=-1"'*) echo '{"ok":true,"result":[{"update_id":5,"message":{"message_id":1,"chat":{"id":7,"type":"private","first_name":"Old"}}}]}' ;;
    *'/getUpdates"'*'"offset=6"'*) echo '{"ok":true,"result":[{"update_id":6,"message":{"message_id":2,"chat":{"id":42,"type":"private","first_name":"Ann","last_name":"Lee"}}}]}' ;;
    *'/getUpdates"'*) echo '{"ok":true,"result":[]}' ;;
    *'/sendMessage"'*) echo '{"ok":true,"result":{"message_id":3}}' ;;
  esac
  """
    .trimIndent() + "\n"
