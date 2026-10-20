package dev.artalex.herdr.core

import dev.artalex.herdr.core.rpc.ApiError
import dev.artalex.herdr.core.rpc.HerdrApi
import dev.artalex.herdr.core.rpc.HerdrTimeoutException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HerdrApiTest {
  private val snapshotResult = fixture("session-snapshot.json").substringAfter("\"result\":").removeSuffix("\n").removeSuffix("}")

  @Test
  fun snapshotRoundTrip() = runBlocking {
    val transport = FakeTransport { ok(it, snapshotResult) }
    val snapshot = HerdrApi(transport).snapshot()
    assertEquals(2, snapshot.workspaces.size)
    assertEquals("session.snapshot", transport.requests.single().method())
  }

  @Test
  fun eachCallUsesItsOwnConnectionAndId() = runBlocking {
    val transport = FakeTransport { ok(it, """{"type":"pong","version":"0.9.5","protocol":22}""") }
    val api = HerdrApi(transport)
    api.ping()
    api.ping()
    assertEquals(listOf("a1", "a2"), transport.requests.map { it.id() })
  }

  @Test
  fun sendsLogicalKeysAndPrompt() = runBlocking {
    val transport = FakeTransport { ok(it, """{"type":"ok"}""") }
    val api = HerdrApi(transport)
    api.sendAgentKeys("w1:p2", listOf("1", "enter"))
    api.promptAgent("w1:p1", "проверь тесты")
    val (keys, prompt) = transport.requests
    assertEquals("agent.send_keys", keys.method())
    assertEquals(listOf("1", "enter"), keys.getValue("params").jsonObject.getValue("keys").jsonArray.map { it.jsonPrimitive.content })
    assertEquals("проверь тесты", prompt.getValue("params").jsonObject.getValue("text").jsonPrimitive.content)
  }

  @Test
  fun opensTabsStartsAgentsAndClosesPanes() = runBlocking {
    val transport = FakeTransport {
      when (it.method()) {
        "tab.create" ->
          ok(it, """{"type":"tab_created","tab":{"tab_id":"w1:t2","workspace_id":"w1","number":2},"root_pane":{"pane_id":"w1:p3","terminal_id":"term_3","workspace_id":"w1","tab_id":"w1:t2","cwd":"/home/u/proj"}}""")
        else -> ok(it, """{"type":"ok"}""")
      }
    }
    val api = HerdrApi(transport)
    val created = api.createTab("w1", "/home/u/proj")
    api.startAgent(created.paneId, "proj")
    api.closePane("w1:p1")
    val (tab, start, close) = transport.requests.map { it.method() to it.getValue("params").jsonObject }
    assertEquals("w1:p3", created.paneId)
    assertEquals("tab.create", tab.first)
    assertEquals(listOf("w1", "/home/u/proj", "false"), listOf("workspace_id", "cwd", "focus").map { tab.second.getValue(it).jsonPrimitive.content })
    assertEquals("agent.start", start.first)
    assertEquals(listOf("proj", "claude", "w1:p3"), listOf("name", "kind", "pane_id").map { start.second.getValue(it).jsonPrimitive.content })
    assertEquals("pane.close" to "w1:p1", close.first to close.second.getValue("pane_id").jsonPrimitive.content)
  }

  @Test
  fun readPaneAsksForVisibleAnsi() = runBlocking {
    val transport = FakeTransport { ok(it, fixture("pane-read-visible-ansi.json").substringAfter("\"result\":").trim().removeSuffix("}")) }
    val read = HerdrApi(transport).readPane("w1:p2")
    val params = transport.requests.single().getValue("params").jsonObject
    assertEquals("visible", params.getValue("source").jsonPrimitive.content)
    assertEquals("ansi", params.getValue("format").jsonPrimitive.content)
    assertEquals("false", params.getValue("strip_ansi").jsonPrimitive.content)
    assertEquals("ansi", read.format)
  }

  @Test
  fun capturedHerdrErrorBecomesApiError() = runBlocking {
    val transport = FakeTransport { fixture("error-agent-not-found.json").trim().replace("\"f1\"", "\"${it.id()}\"") }
    val e = assertFailsWith<ApiError> { HerdrApi(transport).sendAgentKeys("nope", listOf("esc")) }
    assertEquals("agent_not_found", e.code)
  }

  @Test
  fun herdrErrorBecomesApiError() = runBlocking {
    val transport = FakeTransport { """{"id":"${it.id()}","error":{"code":"agent_blocked","message":"agent is waiting"}}""" }
    val e = assertFailsWith<ApiError> { HerdrApi(transport).promptAgent("w1:p2", "hi") }
    assertEquals("agent_blocked", e.code)
  }

  @Test
  fun answerForAnotherRequestIsRejected() = runBlocking {
    val transport = FakeTransport { """{"id":"zzz","result":{}}""" }
    assertFailsWith<IOException> { HerdrApi(transport).ping() }
    Unit
  }

  @Test
  fun silentServerTimesOut() = runBlocking {
    val transport = FakeTransport { null }
    assertFailsWith<HerdrTimeoutException> { HerdrApi(transport, timeoutMs = 200).ping() }
    Unit
  }

  @Test
  fun methodsOutsideWhitelistAreRefusedBeforeConnecting() = runBlocking {
    val transport = FakeTransport { ok(it, "{}") }
    for (method in listOf("pane.focus", "agent.focus", "server.stop", "workspace.create", "workspace.close", "tab.close", "pane.split")) {
      assertFailsWith<IllegalArgumentException> { HerdrApi(transport).call(method) }
    }
    assertEquals(0, transport.requests.size)
  }
}
