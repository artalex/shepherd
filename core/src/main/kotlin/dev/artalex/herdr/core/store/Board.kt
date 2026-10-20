package dev.artalex.herdr.core.store

import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.model.TabInfo
import dev.artalex.herdr.core.model.WorkspaceInfo

/** What the agent list shows: workspaces with their agents first and the remaining panes after. */
data class Board(val groups: List<WorkspaceGroup>) {
  val blockedCount: Int get() = groups.sumOf { g -> g.agents.count { it.pane.agentStatus == AgentStatus.BLOCKED } }
}

data class WorkspaceGroup(val workspace: WorkspaceInfo, val agents: List<PaneEntry>, val otherPanes: List<PaneEntry>) {
  val needsAnswer: Boolean get() = agents.any { it.pane.agentStatus == AgentStatus.BLOCKED }
}

data class PaneEntry(val pane: PaneInfo, val tab: TabInfo?)

/** Attention order: waiting for an answer, finished, working, idle, unknown. */
private val statusRank =
  mapOf(AgentStatus.BLOCKED to 0, AgentStatus.DONE to 1, AgentStatus.WORKING to 2, AgentStatus.IDLE to 3, AgentStatus.UNKNOWN to 4)

/** Workspaces with an agent waiting for an answer come first; otherwise herdr's own numbering. */
fun buildBoard(snapshot: SessionSnapshot): Board {
  val tabs = snapshot.tabs.associateBy { it.tabId }
  val paneOrder = snapshot.panes.withIndex().associate { (i, p) -> p.paneId to i }
  fun entry(p: PaneInfo) = PaneEntry(p, tabs[p.tabId])
  val byTabThenPane = compareBy<PaneEntry>({ it.tab?.number ?: Int.MAX_VALUE }, { paneOrder.getValue(it.pane.paneId) })

  val groups =
    snapshot.workspaces.map { ws ->
      val panes = snapshot.panes.filter { it.workspaceId == ws.workspaceId }.map(::entry)
      val (agents, others) = panes.partition { it.pane.agent != null }
      WorkspaceGroup(
        workspace = ws,
        agents = agents.sortedWith(compareBy<PaneEntry> { statusRank.getValue(it.pane.agentStatus) }.then(byTabThenPane)),
        otherPanes = others.sortedWith(byTabThenPane),
      )
    }
  return Board(groups.sortedWith(compareBy({ !it.needsAnswer }, { it.workspace.number })))
}

/**
 * Grid for rendering [paneId]'s screen: rows from the pane's viewport, columns from its layout rectangle.
 * The rectangle includes herdr's borders, so it is a few columns wider than the PTY; that only adds margin.
 */
fun SessionSnapshot.paneGrid(paneId: String): PaneGrid? {
  val pane = panes.firstOrNull { it.paneId == paneId } ?: return null
  val rect = layouts.asSequence().flatMap { it.panes }.firstOrNull { it.paneId == paneId }?.rect ?: return null
  return PaneGrid(rows = pane.scroll?.viewportRows ?: rect.height, cols = rect.width)
}

data class PaneGrid(val rows: Int, val cols: Int)
