package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.HostCheck
import dev.artalex.herdr.core.host.HostStatus
import dev.artalex.herdr.core.host.check
import dev.artalex.herdr.core.host.compareVersions
import dev.artalex.herdr.core.host.shellQuote
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.rpc.herdrJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
  fun hostChecksReportWhatToFix() {
    val ok = HostStatus.parse(fixture("status.json"))
    assertEquals(HostCheck.RestartNeeded, ok.copy(server = ok.server.copy(compatible = false)).check())
    assertEquals(HostCheck.TooOld("0.9.0"), ok.copy(server = ok.server.copy(version = "0.9.0")).check())
    assertEquals(HostCheck.ServerNotRunning, ok.copy(server = ok.server.copy(running = false)).check())
  }

  @Test
  fun comparesReleaseAndPreviewVersions() {
    assertTrue(compareVersions("0.9.5", "0.9.5") == 0)
    assertTrue(compareVersions("0.10.0", "0.9.5") > 0)
    assertTrue(compareVersions("0.9.3-preview.2026-10-12-6d697e1e75ff", "0.9.3") < 0)
    assertTrue(compareVersions("1.0", "0.9.5") > 0)
  }

  @Test
  fun quotesArgvForRemoteShell() {
    assertEquals("/home/a/.local/bin/herdr --session default", shellQuote(listOf("/home/a/.local/bin/herdr", "--session", "default")))
    assertEquals("echo 'it'\\''s' 'a b' ''", shellQuote(listOf("echo", "it's", "a b", "")))
  }
}
