package dev.artalex.herdr

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.artalex.herdr.notify.Showing
import dev.artalex.herdr.ui.agent.AgentScreen
import dev.artalex.herdr.ui.agent.AgentViewModel
import dev.artalex.herdr.ui.agents.AgentsScreen
import dev.artalex.herdr.ui.agents.AgentsViewModel
import dev.artalex.herdr.ui.settings.ServerScreen
import dev.artalex.herdr.ui.settings.SettingsScreen
import dev.artalex.herdr.ui.settings.SettingsViewModel
import dev.artalex.herdr.ui.terminal.TerminalScreen
import dev.artalex.herdr.ui.terminal.TerminalViewModel

/**
 * The app's screens; [opening] is an agent to show over the list, from a tapped notification. [share] came from another
 * app: the list asks which agent it goes to, and [onShared] tells that it went to one or was dropped. [shareCount]
 * counts the shares that came.
 */
@Composable
fun MainNavigation(opening: Agent?, onOpened: () -> Unit, share: Share?, shareCount: Int, onShared: () -> Unit) {
  val container = (LocalContext.current.applicationContext as HerdrApp).container
  val servers = container.servers
  // The share as it is now: the list's callbacks may have been made before it came.
  val currentShare = rememberUpdatedState(share)
  // A set-up app opens on the agent list; the first start goes to the form for a server.
  val backStack = rememberNavBackStack(if (servers.isEmpty()) ServerEditor(null) else Agents)
  val activity = LocalActivity.current
  // Back from the last screen leaves the app as Home does: the activity stays in its task, which goes behind the others,
  // and comes back as it was. A share waiting for its agent goes. NavDisplay takes Back only while there is a screen to
  // go back to, and Android would close an activity that the launcher didn't start.
  val leave: () -> Unit = {
    if (currentShare.value != null) onShared()
    activity?.moveTaskToBack(true)
  }
  val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() else leave() }
  BackHandler(enabled = backStack.size == 1, onBack = leave)
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
  // The notifications take what the screen on top shows of the agents as seen; the rest stays news for when the app
  // leaves the screen. Only here is it known which screen that is: the screens' polling outlives them and reaches
  // further than they show.
  val top = backStack.lastOrNull()
  DisposableEffect(top) {
    container.watch.showing(showingOf(top))
    onDispose { container.watch.showing(Showing.NoAgents) }
  }
  LaunchedEffect(opening) {
    if (opening != null) {
      backStack.clear()
      backStack.add(Agents)
      backStack.add(opening)
      onOpened()
    }
  }
  // Each share starts on the agent list, as a notification does: the user picks its agent there.
  LaunchedEffect(shareCount) { if (share != null) toAgents() }

  NavDisplay(
    backStack = backStack,
    onBack = back,
    // A screen's models live while it is on the back stack, not as long as the activity: a pane's agent and terminal
    // models, and its stream they hold, go once the user leaves the pane.
    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
    entryProvider =
      entryProvider {
        entry<Agents> {
          AgentsScreen(
            viewModel { AgentsViewModel(servers, container.notifications, container.alerts, container.snippets, container.agentTasks, container.telegram) },
            share = currentShare.value,
            onOpenPane = { serverId, paneId ->
              // The pane picked for a share gets it, a new agent started from the list too.
              currentShare.value?.let { share ->
                servers[serverId]?.let { share.sendTo(it, paneId) }
                onShared()
              }
              backStack.add(Agent(serverId, paneId))
            },
            onDropShare = onShared,
            // The task goes on in the background: back to the app the share came from. The activity stays, and with it
            // the access to the shared files, which are still on their way.
            onGaveTask = {
              onShared()
              activity?.moveTaskToBack(true)
            },
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
              viewModel { AgentViewModel(servers, server, key.paneId, container.snippets, container.filesSettings) },
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
              viewModel { TerminalViewModel(server, key.paneId) },
              title = key.paneId,
              onBack = back,
              onPaneClosed = { forgetPane(key.serverId, key.paneId) },
            )
          }
        }
      },
  )
}

/** What the screen of [key] shows of the agents. A terminal shows the program in its pane, not its status. */
private fun showingOf(key: NavKey?): Showing =
  when (key) {
    Agents -> Showing.AllAgents
    is Agent -> Showing.OneAgent(key.serverId, key.paneId)
    else -> Showing.NoAgents
  }
