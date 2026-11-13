package dev.artalex.herdr.notify

/**
 * What the app's screen on top shows of the agents. The notifications take what it shows as seen; whatever the agents
 * off it come to stays news, for when the app leaves the screen ([AgentWatch.showing]).
 */
sealed interface Showing {
  /** Whether the agent in pane [paneId] of server [serverId] is in sight. */
  fun shows(serverId: String, paneId: String): Boolean

  /** The agent list: every agent of the servers that are on. */
  data object AllAgents : Showing {
    override fun shows(serverId: String, paneId: String) = true
  }

  /**
   * An agent's screen: its own agent alone. Its pane switcher has the others' statuses too, but in a menu, open for a
   * moment to pick a pane, and it polls every server for it: their snapshots come all the same.
   */
  data class OneAgent(val serverId: String, val paneId: String) : Showing {
    override fun shows(serverId: String, paneId: String) = serverId == this.serverId && paneId == this.paneId
  }

  /** The terminal, the settings and the server form show no agent's status. */
  data object NoAgents : Showing {
    override fun shows(serverId: String, paneId: String) = false
  }
}
