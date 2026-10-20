package dev.artalex.herdr.core

import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.model.TabInfo
import dev.artalex.herdr.core.model.WorkspaceInfo
import dev.artalex.herdr.core.rpc.herdrJson
import dev.artalex.herdr.core.store.PaneGrid
import dev.artalex.herdr.core.store.buildBoard
import dev.artalex.herdr.core.store.paneGrid
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class BoardTest {
  private fun pane(id: String, ws: String, agent: String?, status: AgentStatus, tab: String = "$ws:t1") =
    PaneInfo(paneId = id, terminalId = "term_$id", workspaceId = ws, tabId = tab, agent = agent, agentStatus = status)

  private val snapshot =
    SessionSnapshot(
      version = "0.9.5",
      protocol = 22,
      workspaces = listOf(WorkspaceInfo("w1", 1, "alpha"), WorkspaceInfo("w2", 2, "beta"), WorkspaceInfo("w3", 3, "gamma")),
      tabs = listOf(TabInfo("w1:t1", "w1", 1), TabInfo("w2:t1", "w2", 1), TabInfo("w2:t2", "w2", 2), TabInfo("w3:t1", "w3", 1)),
      panes =
        listOf(
          pane("w1:p1", "w1", "claude", AgentStatus.IDLE),
          pane("w1:p2", "w1", null, AgentStatus.UNKNOWN),
          pane("w2:p1", "w2", "claude", AgentStatus.WORKING),
          pane("w2:p2", "w2", null, AgentStatus.UNKNOWN, tab = "w2:t2"),
          pane("w2:p3", "w2", "codex", AgentStatus.DONE, tab = "w2:t2"),
          pane("w2:p4", "w2", "claude", AgentStatus.BLOCKED, tab = "w2:t2"),
          pane("w3:p1", "w3", "claude", AgentStatus.WORKING),
        ),
    )

  @Test
  fun workspacesWithBlockedAgentsComeFirstThenByNumber() {
    assertEquals(listOf("w2", "w1", "w3"), buildBoard(snapshot).groups.map { it.workspace.workspaceId })
  }

  @Test
  fun agentsSortedByAttentionAndPlainPanesSeparated() {
    val w2 = buildBoard(snapshot).groups.first()
    assertEquals(listOf("w2:p4", "w2:p3", "w2:p1"), w2.agents.map { it.pane.paneId })
    assertEquals(listOf("w2:p2"), w2.otherPanes.map { it.pane.paneId })
    assertEquals(2, w2.otherPanes.single().tab?.number)
  }

  @Test
  fun paneGridComesFromViewportAndLayout() {
    val lab = herdrJson.decodeFromJsonElement(
      SessionSnapshot.serializer(),
      herdrJson.parseToJsonElement(fixture("session-snapshot.json")).jsonObject.getValue("result").jsonObject.getValue("snapshot"),
    )
    assertEquals(PaneGrid(rows = 37, cols = 60), lab.paneGrid("w1:p1"))
    assertEquals(null, lab.paneGrid("w9:p9"))
  }

  @Test
  fun countsAgentsWaitingForAnswer() {
    assertEquals(1, buildBoard(snapshot).blockedCount)
  }
}
