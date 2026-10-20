package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.parseInstalledAgents
import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.rpc.ApiError
import dev.artalex.herdr.core.rpc.HerdrApi
import dev.artalex.herdr.core.store.agentName
import dev.artalex.herdr.core.store.startNamedAgent
import dev.artalex.herdr.core.store.startNamedAgentInNewPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
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
}
