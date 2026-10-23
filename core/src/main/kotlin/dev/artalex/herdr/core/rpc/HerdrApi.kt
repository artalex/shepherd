package dev.artalex.herdr.core.rpc

import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.model.PaneRead
import dev.artalex.herdr.core.model.Pong
import dev.artalex.herdr.core.model.ReadSource
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.proto.readNdjsonLine
import dev.artalex.herdr.core.proto.writeNdjsonLine
import dev.artalex.herdr.core.transport.HerdrTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal val herdrJson = Json { ignoreUnknownKeys = true }

/**
 * Typed client for the herdr socket API.
 *
 * Only the methods in [ALLOWED_METHODS] can be called: the app reads state, sends input, and when the user
 * asks opens tabs and workspaces, starts agents and closes panes. It never moves focus on the desktop, closes
 * workspaces, or touches the server.
 */
class HerdrApi(private val transport: HerdrTransport, private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
  private val ids = AtomicLong()

  suspend fun ping(): Pong = decode(call("ping"))

  suspend fun snapshot(): SessionSnapshot = decode(call("session.snapshot").jsonObject.getValue("snapshot"))

  /** Reads the rendered screen; `recent` sources are not offered because they scroll idle agents. */
  suspend fun readPane(paneId: String, source: ReadSource = ReadSource.VISIBLE, ansi: Boolean = true): PaneRead {
    val params = buildJsonObject {
      put("pane_id", paneId)
      put("source", source.wire)
      put("format", if (ansi) "ansi" else "text")
      put("strip_ansi", !ansi)
    }
    return decode(call("pane.read", params).jsonObject.getValue("read"))
  }

  /**
   * Sends logical keys (`1`, `enter`, `esc`, `up`, `ctrl+c`, …) to the agent in a pane; herdr encodes them
   * for the pane's terminal modes. Fails with [ApiError] `agent_not_ready` unless the pane's foreground
   * process is still the agent, so an answer never lands in a shell after the agent has exited.
   */
  suspend fun sendAgentKeys(target: String, keys: List<String>) {
    call("agent.send_keys", buildJsonObject {
      put("target", target)
      putJsonArray("keys") { keys.forEach { add(it) } }
    })
  }

  /** Submits a prompt; fails with [ApiError] `agent_blocked` while the agent shows a dialog. */
  suspend fun promptAgent(target: String, text: String) {
    call("agent.prompt", buildJsonObject {
      put("target", target)
      put("text", text)
    })
  }

  /** Sends logical keys to a pane without a recognized agent. */
  suspend fun sendPaneKeys(paneId: String, keys: List<String>) {
    call("pane.send_keys", buildJsonObject {
      put("pane_id", paneId)
      putJsonArray("keys") { keys.forEach { add(it) } }
    })
  }

  /**
   * Opens a tab in [workspaceId] with a shell in [cwd]; it gets the whole window, and the desktop stays on its
   * tab. Returns the tab's pane.
   */
  suspend fun createTab(workspaceId: String, cwd: String?): PaneInfo {
    val params = buildJsonObject {
      put("workspace_id", workspaceId)
      cwd?.let { put("cwd", it) }
      put("focus", false)
    }
    return decode(call("tab.create", params).jsonObject.getValue("root_pane"))
  }

  /**
   * Opens a workspace with a shell in [cwd], and the desktop stays on its own workspace. Returns the workspace's pane.
   * herdr 0.9.5 opens a folder that doesn't exist, `~` paths included, as the home folder: [cwd] is to be one that does.
   */
  suspend fun createWorkspace(cwd: String): PaneInfo {
    val params = buildJsonObject {
      put("cwd", cwd)
      put("focus", false)
    }
    return decode(call("workspace.create", params).jsonObject.getValue("root_pane"))
  }

  /**
   * Starts an interactive agent of [kind] named [name] in [paneId], which must be at a shell prompt.
   * herdr types the command and answers at once; the agent shows up in snapshots a few seconds later.
   * Fails with [ApiError] `agent_name_taken`, or `agent_pane_busy` when the pane runs something else.
   */
  suspend fun startAgent(paneId: String, name: String, kind: String = "claude") {
    call("agent.start", buildJsonObject {
      put("name", name)
      put("kind", kind)
      put("pane_id", paneId)
    })
  }

  /** Closes the pane and everything running in it; closing the last pane closes its workspace too. */
  suspend fun closePane(paneId: String) {
    call("pane.close", buildJsonObject { put("pane_id", paneId) })
  }

  internal suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
    require(method in ALLOWED_METHODS) { "herdr method $method is not allowed in this client" }
    val id = "a${ids.incrementAndGet()}"
    val request = buildJsonObject {
      put("id", id)
      put("method", method)
      put("params", params)
    }
    val stream = withContext(Dispatchers.IO) { transport.openApi() }
    val line = blockingIoWithDeadline(stream, timeoutMs, method) {
      stream.output.writeNdjsonLine(request.toString())
      stream.input.buffered().readNdjsonLine() ?: throw IOException("$method: connection closed without an answer")
    }
    val response = herdrJson.parseToJsonElement(line).jsonObject
    response["error"]?.let { error ->
      val obj = error.jsonObject
      throw ApiError(obj["code"]?.jsonPrimitive?.content ?: "unknown", obj["message"]?.jsonPrimitive?.content ?: "")
    }
    val echoed = response["id"]?.jsonPrimitive?.content
    if (echoed != id) throw IOException("$method: answer for request $echoed, expected $id")
    return response["result"] ?: throw IOException("$method: answer without result")
  }

  private inline fun <reified T> decode(element: JsonElement): T = herdrJson.decodeFromJsonElement(element)

  companion object {
    const val DEFAULT_TIMEOUT_MS = 5_000L

    val ALLOWED_METHODS =
      setOf(
        "ping",
        "session.snapshot",
        "pane.read",
        "agent.send_keys",
        "agent.prompt",
        "pane.send_keys",
        "tab.create",
        "workspace.create",
        "agent.start",
        "pane.close",
      )
  }
}
