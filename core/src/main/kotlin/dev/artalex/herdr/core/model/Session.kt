package dev.artalex.herdr.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `session.snapshot` result: the whole herdr session in one read. */
@Serializable
data class SessionSnapshot(
  val version: String,
  val protocol: Int,
  val workspaces: List<WorkspaceInfo> = emptyList(),
  val tabs: List<TabInfo> = emptyList(),
  val panes: List<PaneInfo> = emptyList(),
  val agents: List<AgentInfo> = emptyList(),
  val layouts: List<TabLayout> = emptyList(),
)

@Serializable
data class WorkspaceInfo(
  @SerialName("workspace_id") val workspaceId: String,
  val number: Int,
  val label: String? = null,
  val focused: Boolean = false,
  @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.UNKNOWN,
  @SerialName("active_tab_id") val activeTabId: String? = null,
)

@Serializable
data class TabInfo(
  @SerialName("tab_id") val tabId: String,
  @SerialName("workspace_id") val workspaceId: String,
  val number: Int,
  val label: String? = null,
  val focused: Boolean = false,
)

@Serializable
data class PaneInfo(
  @SerialName("pane_id") val paneId: String,
  @SerialName("terminal_id") val terminalId: String,
  @SerialName("workspace_id") val workspaceId: String,
  @SerialName("tab_id") val tabId: String,
  val focused: Boolean = false,
  /** Recognized agent kind such as `claude`, or null for a plain terminal. */
  val agent: String? = null,
  @SerialName("display_agent") val displayAgent: String? = null,
  @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.UNKNOWN,
  val label: String? = null,
  val title: String? = null,
  @SerialName("terminal_title_stripped") val terminalTitle: String? = null,
  val cwd: String? = null,
  @SerialName("foreground_cwd") val foregroundCwd: String? = null,
  val scroll: PaneScroll? = null,
)

@Serializable
data class PaneScroll(@SerialName("viewport_rows") val viewportRows: Int)

@Serializable
data class AgentInfo(
  @SerialName("pane_id") val paneId: String,
  @SerialName("terminal_id") val terminalId: String,
  @SerialName("workspace_id") val workspaceId: String,
  @SerialName("tab_id") val tabId: String,
  /** Null while herdr has typed the command of a just-started agent but not recognized it yet. */
  val agent: String? = null,
  @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.UNKNOWN,
  /** An agent started with `agent.start` that isn't ready for input yet; herdr refuses prompts until then. */
  @SerialName("launch_pending") val launchPending: Boolean = false,
  /** Grows on every status change; lets the UI notice transitions between polls. */
  @SerialName("state_change_seq") val stateChangeSeq: Long = 0,
)

@Serializable
data class TabLayout(
  @SerialName("tab_id") val tabId: String,
  val panes: List<LayoutPane> = emptyList(),
)

/** Pane rectangle in the tab's cell grid; the PTY is slightly smaller (borders). */
@Serializable
data class LayoutPane(@SerialName("pane_id") val paneId: String, val rect: CellRect)

@Serializable
data class CellRect(val x: Int, val y: Int, val width: Int, val height: Int)
