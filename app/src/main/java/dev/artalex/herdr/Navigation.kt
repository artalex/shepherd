package dev.artalex.herdr

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.artalex.herdr.ui.agent.AgentScreen
import dev.artalex.herdr.ui.agent.AgentViewModel
import dev.artalex.herdr.ui.agents.AgentsScreen
import dev.artalex.herdr.ui.agents.AgentsViewModel
import dev.artalex.herdr.ui.settings.ServerScreen
import dev.artalex.herdr.ui.settings.SettingsScreen
import dev.artalex.herdr.ui.settings.SettingsViewModel
import dev.artalex.herdr.ui.terminal.TerminalScreen
import dev.artalex.herdr.ui.terminal.TerminalViewModel

/** The app's screens; [opening] is an agent to show over the list, from a tapped notification. */
@Composable
fun MainNavigation(opening: Agent?, onOpened: () -> Unit) {
  val container = (LocalContext.current.applicationContext as HerdrApp).container
  val servers = container.servers
  // A set-up app opens on the agent list; the first start goes to the form for a server.
  val backStack = rememberNavBackStack(if (servers.isEmpty()) ServerEditor(null) else Agents)
  val activity = LocalActivity.current
  // The last screen closes the app instead of leaving NavDisplay with an empty back stack.
  val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() else activity?.finish() }
  val toAgents: () -> Unit = {
    backStack.clear()
    backStack.add(Agents)
  }
  // Switching panes replaces the screen, so Back still leads to the list rather than through every pane seen.
  val showPane: (String, String) -> Unit = { serverId, paneId -> backStack[backStack.lastIndex] = Agent(serverId, paneId) }
  // A closed pane, or one of a deleted server, takes all its screens off the back stack.
  val forgetPane: (String, String) -> Unit = { serverId, paneId ->
    backStack.removeAll { (it is Agent && it.serverId == serverId && it.paneId == paneId) || (it is Terminal && it.serverId == serverId && it.paneId == paneId) }
    if (backStack.isEmpty()) backStack.add(Agents)
  }
  LaunchedEffect(opening) {
    if (opening != null) {
      backStack.clear()
      backStack.add(Agents)
      backStack.add(opening)
      onOpened()
    }
  }

  NavDisplay(
    backStack = backStack,
    onBack = back,
    entryProvider =
      entryProvider {
        entry<Agents> {
          AgentsScreen(
            viewModel { AgentsViewModel(servers, container.notifications, container.alerts) },
            onOpenPane = { serverId, paneId -> backStack.add(Agent(serverId, paneId)) },
            onOpenSettings = { backStack.add(Settings) },
            onAddServer = { backStack.add(ServerEditor(null)) },
          )
        }
        entry<Settings> {
          SettingsScreen(viewModel { SettingsViewModel(container) }, onBack = back, onOpenServer = { backStack.add(ServerEditor(it)) })
        }
        entry<ServerEditor> { key ->
          ServerScreen(
            viewModel { SettingsViewModel(container) },
            serverId = key.serverId,
            canGoBack = backStack.size > 1,
            onBack = back,
            onSaved = toAgents,
            onDeleted = back,
          )
        }
        entry<Agent> { key ->
          val server = servers[key.serverId]
          if (server == null) {
            LaunchedEffect(key) { forgetPane(key.serverId, key.paneId) }
          } else {
            // The user is looking at the agent: a notification about it has done its job.
            LaunchedEffect(key) { container.alerts.dismiss(key.serverId, key.paneId) }
            AgentScreen(
              viewModel(key = "agent-${key.serverId}/${key.paneId}") { AgentViewModel(servers, server, key.paneId, container.snippets) },
              onBack = back,
              onOpenTerminal = { backStack.add(Terminal(key.serverId, key.paneId)) },
              onSwitchPane = showPane,
              onPaneClosed = { forgetPane(key.serverId, key.paneId) },
            )
          }
        }
        entry<Terminal> { key ->
          val server = servers[key.serverId]
          if (server == null) {
            LaunchedEffect(key) { forgetPane(key.serverId, key.paneId) }
          } else {
            TerminalScreen(
              viewModel(key = "terminal-${key.serverId}/${key.paneId}") { TerminalViewModel(server, key.paneId) },
              title = key.paneId,
              onBack = back,
              onPaneClosed = { forgetPane(key.serverId, key.paneId) },
            )
          }
        }
      },
  )
}
