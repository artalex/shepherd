package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.parseInstalledAgents
import dev.artalex.herdr.core.model.AgentInfo
import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.rpc.ApiError
import dev.artalex.herdr.core.rpc.HerdrApi
import dev.artalex.herdr.core.store.agentName
import dev.artalex.herdr.core.store.promptWhenReady
import dev.artalex.herdr.core.store.startNamedAgent
import dev.artalex.herdr.core.store.startNamedAgentInNewPane
import dev.artalex.herdr.core.store.startSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PanesTest {
  @Test
  fun agentNameFollowsHerdrAlphabet() {
    val claude = AgentKind.CLAUDE
    assertEquals("my-app", agentName("/home/user/devel/my-app", claude))
    assertEquals("my-app-2", agentName("/home/user/devel/my-app/", claude, attempt = 2))
    assertEquals("my-project", agentName("/srv/My Project", claude))
    assertEquals("codex-2026-notes", agentName("/tmp/2026 notes", AgentKind.CODEX))
    assertEquals("claude", agentName("/home/u/проект", claude))
    assertEquals("agy", agentName(null, AgentKind.ANTIGRAVITY))
    val long = agentName("/x/" + "a".repeat(40), claude, attempt = 3)
    assertEquals(32, long.length)
    assertEquals("-3", long.takeLast(2))
  }

  @Test
  fun takenNameMovesOnToTheNext() = runBlocking {
    val transport = FakeTransport {
      val name = it.getValue("params").jsonObject.getValue("name").jsonPrimitive.content
      if (name == "proj") """{"id":"${it.id()}","error":{"code":"agent_name_taken","message":"agent name proj is already used"}}"""
      else ok(it, """{"type":"ok"}""")
    }
    HerdrApi(transport).startNamedAgent("w1:p2", AgentKind.CURSOR, "/home/u/proj")
    val params = transport.requests.map { it.getValue("params").jsonObject }
    assertEquals(listOf("proj", "proj-2"), params.map { it.getValue("name").jsonPrimitive.content })
    assertEquals("cursor", params.last().getValue("kind").jsonPrimitive.content)
  }

  @Test
  fun installedAgentsKeepCatalogueOrder() {
    assertEquals(listOf(AgentKind.CLAUDE, AgentKind.CURSOR, AgentKind.KIRO), parseInstalledAgents("kiro-cli\nclaude\ncursor-agent\nunknown\n"))
    assertEquals(emptyList(), parseInstalledAgents(""))
  }

  @Test
  fun otherStartErrorsAreNotRetried() = runBlocking {
    val transport = FakeTransport { """{"id":"${it.id()}","error":{"code":"agent_pane_busy","message":"not an available shell"}}""" }
    val error = assertFailsWith<ApiError> { HerdrApi(transport).startNamedAgent("w1:p2", AgentKind.CLAUDE, "/home/u/proj") }
    assertEquals("agent_pane_busy", error.code)
    assertEquals(1, transport.requests.size)
  }

  @Test
  fun newPaneWaitsForItsShell() = runBlocking {
    var busy = 2
    val transport = FakeTransport {
      if (busy-- > 0) """{"id":"${it.id()}","error":{"code":"agent_pane_busy","message":"not an available shell"}}"""
      else ok(it, """{"type":"ok"}""")
    }
    HerdrApi(transport).startNamedAgentInNewPane("w1:p2", AgentKind.CLAUDE, "/home/u/proj")
    assertEquals(3, transport.requests.size)
  }

  @Test
  fun sessionGetsATabWhereAPaneIsAndAWorkspaceOfItsOwnElsewhere() = runBlocking {
    // An agent's pane in proj, started from a shell in the home folder; nothing in notes.
    val snapshot =
      """{"snapshot":{"version":"0.9.5","protocol":22,"workspaces":[{"workspace_id":"w1","number":1}],"panes":[{"pane_id":"w1:p1","terminal_id":"t1","workspace_id":"w1","tab_id":"w1:t1","cwd":"/home/u","foreground_cwd":"/home/u/proj"}]}}"""
    val transport = FakeTransport {
      when (it.method()) {
        "session.snapshot" -> ok(it, snapshot)
        "tab.create" -> ok(it, """{"type":"tab_created","root_pane":{"pane_id":"w1:p2","terminal_id":"t2","workspace_id":"w1","tab_id":"w1:t2"}}""")
        "workspace.create" -> ok(it, """{"type":"workspace_created","root_pane":{"pane_id":"w2:p1","terminal_id":"t3","workspace_id":"w2","tab_id":"w2:t1"}}""")
        else -> ok(it, """{"type":"ok"}""")
      }
    }
    val api = HerdrApi(transport)
    assertEquals("w1:p2", api.startSession("/home/u/proj", AgentKind.CLAUDE))
    assertEquals("w2:p1", api.startSession("/home/u/notes", AgentKind.CODEX))
    val calls = transport.requests.map { it.method() to it.getValue("params").jsonObject }
    assertEquals(listOf("session.snapshot", "tab.create", "agent.start", "session.snapshot", "workspace.create", "agent.start"), calls.map { it.first })
    assertEquals(listOf("w1", "/home/u/proj"), listOf("workspace_id", "cwd").map { calls[1].second.getValue(it).jsonPrimitive.content })
    assertEquals("/home/u/notes", calls[4].second.getValue("cwd").jsonPrimitive.content)
    assertEquals(listOf("proj", "claude", "w1:p2"), listOf("name", "kind", "pane_id").map { calls[2].second.getValue(it).jsonPrimitive.content })
    assertEquals(listOf("notes", "codex", "w2:p1"), listOf("name", "kind", "pane_id").map { calls[5].second.getValue(it).jsonPrimitive.content })
  }

  private fun snapshot(status: AgentStatus, launching: Boolean = false) =
    SessionSnapshot(
      version = "0.9.5",
      protocol = 22,
      agents = listOf(AgentInfo("w1:p2", "t2", "w1", "w1:t2", agent = "claude", agentStatus = status, launchPending = launching)),
    )

  @Test
  fun promptsWaitForTheStartedAgentToBeReady() = runTest {
    val snapshots = MutableStateFlow(snapshot(AgentStatus.UNKNOWN, launching = true))
    val sent = mutableListOf<String>()
    val result = async { promptWhenReady("w1:p2", listOf("/effort max", "/compact"), snapshots, 180_000) { sent += it } }
    runCurrent()
    // Started, but herdr doesn't know yet what the agent shows; then the agent asks whether to trust its folder.
    snapshots.value = snapshot(AgentStatus.UNKNOWN)
    runCurrent()
    snapshots.value = snapshot(AgentStatus.BLOCKED)
    runCurrent()
    assertEquals(emptyList(), sent)
    snapshots.value = snapshot(AgentStatus.IDLE)
    assertEquals(emptyList(), result.await())
    assertEquals(listOf("/effort max", "/compact"), sent)
  }

  @Test
  fun promptRefusedForADialogGoesAgainLater() = runTest {
    var refusals = 1
    val sent = mutableListOf<String>()
    val left = promptWhenReady("w1:p2", listOf("/effort max"), MutableStateFlow(snapshot(AgentStatus.IDLE)), 180_000) {
      if (refusals-- > 0) throw ApiError("agent_blocked", "the agent shows a dialog")
      sent += it
    }
    assertEquals(emptyList(), left)
    assertEquals(listOf("/effort max"), sent)
  }

  @Test
  fun promptsNotSentInTimeComeBack() = runTest {
    val left = promptWhenReady("w1:p2", listOf("/effort max"), MutableStateFlow(snapshot(AgentStatus.BLOCKED)), 180_000) { error("never ready") }
    assertEquals(listOf("/effort max"), left)
  }

  @Test
  fun otherRefusalsAreNotRetried() = runTest {
    val error = assertFailsWith<ApiError> {
      promptWhenReady("w1:p2", listOf("/effort max"), MutableStateFlow(snapshot(AgentStatus.IDLE)), 180_000) { throw ApiError("agent_not_ready", "gone") }
    }
    assertEquals("agent_not_ready", error.code)
  }
}
