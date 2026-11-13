package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.BadSessionName
import dev.artalex.herdr.core.host.HerdrNotFound
import dev.artalex.herdr.core.host.HerdrStatusFailed
import dev.artalex.herdr.core.host.HostCheck
import dev.artalex.herdr.core.host.HostStatus
import dev.artalex.herdr.core.host.check
import dev.artalex.herdr.core.host.compareVersions
import dev.artalex.herdr.core.host.hostStatusOf
import dev.artalex.herdr.core.host.shellQuote
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.Pong
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.rpc.herdrJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ModelTest {
  private fun snapshot(): SessionSnapshot =
    herdrJson.decodeFromJsonElement(
      SessionSnapshot.serializer(),
      herdrJson.parseToJsonElement(fixture("session-snapshot.json")).jsonObject.getValue("result").jsonObject.getValue("snapshot"),
    )

  @Test
  fun agentStartedButNotRecognizedYetDecodes() {
    // Right after agent.start herdr lists the agent without its kind (seen with herdr 0.9.5).
    val json = """{"version":"0.9.5","protocol":22,"agents":[{"pane_id":"w1:p3","terminal_id":"t3","workspace_id":"w1","tab_id":"w1:t1","agent_status":"unknown","name":"proj","launch_pending":true}]}"""
    val s = herdrJson.decodeFromString(SessionSnapshot.serializer(), json)
    assertEquals(null, s.agents.single().agent)
    assertTrue(s.agents.single().launchPending)
  }

  @Test
  fun decodesLabSnapshot() {
    val s = snapshot()
    assertEquals(22, s.protocol)
    assertEquals(listOf("alpha", "beta"), s.workspaces.map { it.label })
    assertEquals(listOf(AgentStatus.WORKING, AgentStatus.BLOCKED), s.agents.map { it.agentStatus })
    val plain = s.panes.single { it.agent == null }
    assertEquals("w2:p1", plain.paneId)
    assertTrue(plain.terminalId.startsWith("term_"))
    assertEquals(60, s.layouts.first().panes.first().rect.width)
  }

  @Test
  fun answersWithoutTheProtocolNumberDecode() {
    // The number is herdr's private protocol, which nothing in the client goes by: a herdr that leaves it out of its
    // answers still shows its session.
    fun withoutProtocol(answer: String, vararg path: String): JsonObject {
      val result = path.fold(herdrJson.parseToJsonElement(fixture(answer)).jsonObject.getValue("result")) { e, key -> e.jsonObject.getValue(key) }
      return JsonObject(result.jsonObject - "protocol")
    }
    val s = herdrJson.decodeFromJsonElement(SessionSnapshot.serializer(), withoutProtocol("session-snapshot.json", "snapshot"))
    assertNull(s.protocol)
    assertEquals(listOf("alpha", "beta"), s.workspaces.map { it.label })
    assertEquals(listOf(AgentStatus.WORKING, AgentStatus.BLOCKED), s.agents.map { it.agentStatus })
    val pong = herdrJson.decodeFromJsonElement(Pong.serializer(), withoutProtocol("ping.json"))
    assertEquals("0.9.5", pong.version)
    assertNull(pong.protocol)
  }

  @Test
  fun unknownStatusFromNewerServerFallsBack() {
    assertEquals(AgentStatus.UNKNOWN, AgentStatus.fromWire("thinking-hard"))
    assertEquals(AgentStatus.DONE, AgentStatus.fromWire("done"))
  }

  @Test
  fun hostStatusOfUpdatedLabServerIsOk() {
    val status = HostStatus.parse(fixture("status.json"))
    assertEquals("/home/user/.local/bin/herdr", status.client.binary)
    assertTrue(status.server.socket.endsWith("/sessions/hdr-lab/herdr.sock"))
    assertEquals(HostCheck.Ok, status.check())
  }

  @Test
  fun hostStatusOfStoppedServerSaysNotRunning() {
    // What only a running server can tell comes as null: version, protocol, compatible.
    assertEquals(HostCheck.ServerNotRunning, HostStatus.parse(fixture("status-not-running.json")).check())
  }

  // What `herdr status --json` leaves when it prints no status, as herdr 0.9.5 and bash over SSH wrote it.
  @Test
  fun missingHerdrIsNotFound() {
    val e = assertFailsWith<HerdrNotFound> { hostStatusOf("~/nowhere/herdr", "hdr-lab", "", fixture("status-not-found.stderr"), 127) }
    assertEquals("~/nowhere/herdr", e.path)
  }

  @Test
  fun sessionNameHerdrRefusesIsReportedWithItsReason() {
    val e = assertFailsWith<BadSessionName> { hostStatusOf("~/.local/bin/herdr", "my session", "", fixture("status-bad-session.stderr"), 2) }
    assertEquals("my session", e.session)
    assertEquals("session name may only contain ASCII letters, numbers, '.', '_' and '-'", e.reason)
  }

  @Test
  fun otherFailuresKeepWhatTheShellSaid() {
    val e = assertFailsWith<HerdrStatusFailed> { hostStatusOf("~/.local", "hdr-lab", "", fixture("status-not-executable.stderr"), 126) }
    assertEquals("bash: line 1: /home/user/.local: Is a directory", e.detail)
  }

  @Test
  fun hostChecksReportWhatToFix() {
    val ok = HostStatus.parse(fixture("status.json"))
    assertEquals(HostCheck.RestartNeeded, ok.copy(server = ok.server.copy(compatible = false)).check())
    assertEquals(HostCheck.TooOld("0.9.0"), ok.copy(server = ok.server.copy(version = "0.9.0")).check())
  }

  /** The lab server's `herdr status --json`, as another herdr build would print it: [edit] applied to the real one. */
  private fun statusOf(edit: (String) -> String): HostStatus = HostStatus.parse(edit(fixture("status.json")))

  @Test
  fun aServerOnTheNextProtocolConnects() {
    // `protocol` numbers herdr's private protocol between CLI and server, changed six times from 0.7.0 to 0.9.0; the
    // JSON API is no part of it. Here CLI and server of one install, a release after 0.9.5, are on a number the client
    // has never seen.
    val next = statusOf { it.replace("\"version\":\"0.9.5\"", "\"version\":\"0.9.6\"").replace("\"protocol\":22", "\"protocol\":23") }
    assertEquals(true, next.server.compatible)
    assertEquals(HostCheck.Ok, next.check())
  }

  @Test
  fun anUpdatedCliAsksForARestartAndTheRestartedServerConnects() {
    // `herdr update` to a release on the next protocol replaced the binary: the CLI is 0.9.6 on 23, the server still
    // runs 0.9.5 on 22. herdr status works out the rest as herdr does: not compatible, for the protocols differ, and the
    // server's binary stale, for the versions do. The API works; the terminal stream, which goes through the CLI, needs
    // the server restarted.
    val updated = statusOf {
      it.replaceFirst("\"version\":\"0.9.5\"", "\"version\":\"0.9.6\"")
        .replaceFirst("\"protocol\":22", "\"protocol\":23")
        .replace("\"compatible\":true", "\"compatible\":false")
        .replace("\"server_binary_stale\":false", "\"server_binary_stale\":true")
    }
    assertEquals("0.9.6", updated.client.version)
    assertEquals("0.9.5", updated.server.version)
    assertEquals(HostCheck.RestartNeeded, updated.check())
    val restarted = statusOf { it.replace("\"version\":\"0.9.5\"", "\"version\":\"0.9.6\"").replace("\"protocol\":22", "\"protocol\":23") }
    assertEquals(HostCheck.Ok, restarted.check())
  }

  @Test
  fun aPreviewIsNoOlderThanTheReleaseItWasBuiltAfter() {
    // herdr names a preview build `<release>-preview.<build>`, after the release it was built after.
    fun preview(version: String) = statusOf { it.replace("\"version\":\"0.9.5\"", "\"version\":\"$version\"").replace("\"channel\":\"stable\"", "\"channel\":\"preview\"") }
    assertEquals(HostCheck.Ok, preview("0.9.5-preview.2026-11-02-0123456789ab").check())
    // The preview channel's build of 2026-10-14 came after 0.9.4: older than 0.9.5, as herdr itself counts it.
    val before = "0.9.4-preview.2026-10-14-fbd2ae23927f"
    assertEquals(HostCheck.TooOld(before), preview(before).check())
  }

  @Test
  fun comparesReleaseAndPreviewVersions() {
    assertTrue(compareVersions("0.9.5", "0.9.5") == 0)
    assertTrue(compareVersions("0.10.0", "0.9.5") > 0)
    // A real preview, built after the 0.9.3 release and before 0.9.4.
    assertTrue(compareVersions("0.9.3-preview.2026-10-12-6d697e1e75ff", "0.9.3") > 0)
    assertTrue(compareVersions("0.9.3", "0.9.3-preview.2026-10-12-6d697e1e75ff") < 0)
    assertTrue(compareVersions("0.9.3-preview.2026-10-12-6d697e1e75ff", "0.9.4") < 0)
    // Builds after one release aren't ordered among themselves, as herdr doesn't order them; one without a build
    // number, as herdr names a build that has none, comes after the release too.
    assertTrue(compareVersions("0.9.5-preview.2026-11-02-0123456789ab", "0.9.5-preview.2026-11-09-abcdef012345") == 0)
    assertTrue(compareVersions("0.9.5-preview", "0.9.5") > 0)
    assertTrue(compareVersions("1.0", "0.9.5") > 0)
  }

  @Test
  fun quotesArgvForRemoteShell() {
    assertEquals("/home/a/.local/bin/herdr --session default", shellQuote(listOf("/home/a/.local/bin/herdr", "--session", "default")))
    assertEquals("echo 'it'\\''s' 'a b' ''", shellQuote(listOf("echo", "it's", "a b", "")))
  }
}
