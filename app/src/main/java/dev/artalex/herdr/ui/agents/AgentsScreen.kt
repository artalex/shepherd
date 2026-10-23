package dev.artalex.herdr.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.artalex.herdr.R
import dev.artalex.herdr.connection.ConnectionProblem
import dev.artalex.herdr.connection.ConnectionState
import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.ssh.HostKey
import dev.artalex.herdr.core.store.PaneEntry
import dev.artalex.herdr.core.store.WorkspaceGroup
import dev.artalex.herdr.theme.Amber
import dev.artalex.herdr.ui.common.StatusChip
import dev.artalex.herdr.ui.common.problemText
import dev.artalex.herdr.ui.common.rememberLocalNetworkAccess
import dev.artalex.herdr.ui.common.rememberNotificationAccess
import kotlinx.coroutines.delay

/**
 * Agents of every server, under the server's name and its switch. A server that is off shows only that line and
 * isn't connected; switching it on connects it, and a host key to accept is asked about right here. The + by a
 * connected server's name starts an agent there, in a session without panes too, and shows it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(viewModel: AgentsViewModel, onOpenPane: (serverId: String, paneId: String) -> Unit, onOpenSettings: () -> Unit, onAddServer: () -> Unit) {
  val sections by viewModel.sections.collectAsStateWithLifecycle()
  val newAgent by viewModel.newAgent.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel) { viewModel.opened.collect { (serverId, paneId) -> onOpenPane(serverId, paneId) } }
  var refreshing by remember { mutableStateOf(false) }
  LaunchedEffect(refreshing) {
    if (refreshing) {
      delay(600) // a snapshot takes milliseconds; just let the indicator be seen
      refreshing = false
    }
  }
  val localNetwork = rememberLocalNetworkAccess()
  val toggle: (String, Boolean) -> Unit = { id, on -> if (on) localNetwork { viewModel.setActive(id, true) } else viewModel.setActive(id, false) }
  // Asked once a server is connected, so the question doesn't come on top of a host key to accept.
  val notificationAccess = rememberNotificationAccess()
  val connected = sections?.any { it.connection is ConnectionState.Connected } == true
  LaunchedEffect(connected) {
    if (connected && viewModel.shouldAskForNotifications()) {
      viewModel.askedForNotifications()
      notificationAccess {}
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.agents_title)) },
        actions = {
          IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("openSettings")) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.agents_settings))
          }
        },
      )
    }
  ) { padding ->
    PullToRefreshBox(
      isRefreshing = refreshing,
      onRefresh = {
        refreshing = true
        viewModel.refresh()
      },
      modifier = Modifier.padding(padding).fillMaxSize(),
    ) {
      val list = sections
      when {
        list == null -> Hint(stringResource(R.string.agents_loading))
        list.isEmpty() -> NoServers(onAddServer)
        else -> ServerList(list, onOpenPane, toggle, viewModel::openNewAgent)
      }
    }
  }

  sections?.let { HostKeyDialog(it, onTrust = viewModel::trustHostKey, onDecline = { id -> viewModel.setActive(id, false) }) }
  newAgent?.let { NewAgentDialog(it, onStart = viewModel::startNewAgent, onDismiss = viewModel::closeNewAgent) }
}

@Composable
private fun Hint(text: String) {
  // Inside a lazy list so pull-to-refresh works on empty states too.
  LazyColumn(Modifier.fillMaxSize()) { item { Text(text, modifier = Modifier.padding(16.dp)) } }
}

@Composable
private fun NoServers(onAddServer: () -> Unit) {
  LazyColumn(Modifier.fillMaxSize()) {
    item {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.agents_no_servers))
        OutlinedButton(onClick = onAddServer, modifier = Modifier.testTag("addServer")) {
          Icon(Icons.Filled.Add, contentDescription = null)
          Text(stringResource(R.string.server_add), Modifier.padding(start = 8.dp))
        }
      }
    }
  }
}

@Composable
private fun ServerList(sections: List<ServerSection>, onOpenPane: (String, String) -> Unit, onToggle: (String, Boolean) -> Unit, onNewAgent: (String) -> Unit) {
  LazyColumn(Modifier.fillMaxSize().testTag("board")) {
    for (section in sections) {
      val id = section.config.id
      // The server's name stays in sight while its agents scroll by.
      stickyHeader(key = "server-$id") { ServerHeader(section, onToggle, onNewAgent) }
      if (section.config.active) board(id, section, onOpenPane)
    }
  }
}

/** The server's agents and other panes, or a line on why there are none to show. */
private fun LazyListScope.board(id: String, section: ServerSection, onOpenPane: (String, String) -> Unit) {
  when (val state = section.board) {
    // Not connected: the header says why.
    BoardState.Loading -> if (section.connection is ConnectionState.Connected) item(key = "hint-$id") { HintText(stringResource(R.string.agents_loading)) }
    is BoardState.Ready -> {
      state.staleReason?.let { reason ->
        item(key = "stale-$id") { HintText(stringResource(R.string.agents_stale, reason), MaterialTheme.colorScheme.error) }
      }
      if (state.board.groups.all { it.agents.isEmpty() && it.otherPanes.isEmpty() }) item(key = "hint-$id") { HintText(stringResource(R.string.agents_empty)) }
      for (group in state.board.groups) {
        val ws = "$id/${group.workspace.workspaceId}"
        item(key = "ws-$ws") { WorkspaceHeader(ws, group) }
        items(group.agents, key = { "pane-$id/${it.pane.paneId}" }) { PaneRow(id, it, onOpenPane) }
        if (group.otherPanes.isNotEmpty()) {
          item(key = "other-$ws") {
            Text(
              stringResource(R.string.agents_other_panes),
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
          }
          items(group.otherPanes, key = { "pane-$id/${it.pane.paneId}" }) { PaneRow(id, it, onOpenPane) }
        }
        item(key = "div-$ws") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
      }
    }
  }
}

@Composable
private fun HintText(text: String, color: Color = Color.Unspecified) = Text(text, color = color, modifier = Modifier.padding(16.dp))

/**
 * The server's name, how its connection is doing, and the switch that turns it on and off; once the server is
 * connected, the + that starts an agent on it.
 */
@Composable
private fun ServerHeader(section: ServerSection, onToggle: (String, Boolean) -> Unit, onNewAgent: (String) -> Unit) {
  val config = section.config
  Row(
    Modifier.fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .toggleable(value = config.active, role = Role.Switch, onValueChange = { onToggle(config.id, it) })
      .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
      .testTag("server-${config.id}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(config.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      serverStatus(section)?.let { (text, color) -> Text(text, style = MaterialTheme.typography.bodySmall, color = color) }
    }
    Spacer(Modifier.width(12.dp))
    // A button of its own inside the switch's row: TalkBack reads it apart from the switch.
    if (config.active && section.connection is ConnectionState.Connected) {
      IconButton(onClick = { onNewAgent(config.id) }, modifier = Modifier.testTag("newAgent-${config.id}")) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.agents_new_agent))
      }
      Spacer(Modifier.width(4.dp))
    }
    // The row does the toggling: one target, read by TalkBack as one switch with the server's name.
    Switch(checked = config.active, onCheckedChange = null)
  }
}

/** A line under the server's name, and its colour; none while all is well. */
@Composable
private fun serverStatus(section: ServerSection): Pair<String, Color>? {
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  val error = MaterialTheme.colorScheme.error
  if (!section.config.active) return stringResource(R.string.server_off) to muted
  return when (val state = section.connection) {
    is ConnectionState.Connected -> if (state.restartNeeded) stringResource(R.string.state_restart_needed) to error else null
    ConnectionState.Connecting -> stringResource(R.string.state_connecting) to muted
    ConnectionState.Disconnected -> stringResource(R.string.state_disconnected) to muted
    ConnectionState.NotConfigured -> stringResource(R.string.state_no_key) to error
    is ConnectionState.Failed -> {
      val retry = state.retryInMs?.let { " " + stringResource(R.string.state_retry, (it / 1000).toInt()) } ?: ""
      problemText(state.problem) + retry to error
    }
  }
}

@Composable
private fun WorkspaceHeader(key: String, group: WorkspaceGroup) {
  Row(
    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).testTag("workspace-$key"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      stringResource(R.string.agents_workspace, group.workspace.label ?: group.workspace.workspaceId, group.workspace.number),
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.weight(1f),
    )
    if (group.needsAnswer) {
      Text(stringResource(R.string.agents_waiting_badge), color = Amber, style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
private fun PaneRow(serverId: String, entry: PaneEntry, onOpenPane: (String, String) -> Unit) {
  val pane = entry.pane
  Row(
    Modifier.fillMaxWidth()
      .clickable { onOpenPane(serverId, pane.paneId) }
      .padding(horizontal = 16.dp, vertical = 10.dp)
      .testTag("pane-$serverId/${pane.paneId}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (pane.agent != null) {
      StatusChip(pane.agentStatus)
      Spacer(Modifier.width(12.dp))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(paneTitle(pane), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(paneSubtitle(pane), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/**
 * The first server that waits for its host key to be accepted. Declining switches the server off: switched on again,
 * it asks again.
 */
@Composable
private fun HostKeyDialog(sections: List<ServerSection>, onTrust: (String, HostKey) -> Unit, onDecline: (String) -> Unit) {
  val (section, problem) =
    sections.firstNotNullOfOrNull { s ->
      val problem = (s.connection as? ConnectionState.Failed)?.problem
      if (s.config.active && (problem is ConnectionProblem.UnknownHostKey || problem is ConnectionProblem.HostKeyChanged)) s to problem else null
    } ?: return
  val id = section.config.id
  val decline = @Composable { TextButton(onClick = { onDecline(id) }) { Text(stringResource(R.string.cancel)) } }
  when (problem) {
    is ConnectionProblem.UnknownHostKey ->
      AlertDialog(
        onDismissRequest = { onDecline(id) },
        title = { Text(stringResource(R.string.host_key_title, section.config.title)) },
        text = { Text(stringResource(R.string.host_key_text, problem.key.fingerprint), Modifier.testTag("hostKeyFingerprint")) },
        confirmButton = {
          TextButton(onClick = { onTrust(id, problem.key) }, modifier = Modifier.testTag("trustHostKey")) { Text(stringResource(R.string.host_key_trust)) }
        },
        dismissButton = decline,
      )
    is ConnectionProblem.HostKeyChanged ->
      AlertDialog(
        onDismissRequest = { onDecline(id) },
        title = { Text(stringResource(R.string.host_key_changed_title, section.config.title)) },
        text = { Text(stringResource(R.string.host_key_changed_text, problem.pinned.fingerprint, problem.presented.fingerprint)) },
        confirmButton = { TextButton(onClick = { onTrust(id, problem.presented) }) { Text(stringResource(R.string.host_key_trust_new)) } },
        dismissButton = decline,
      )
    else -> Unit
  }
}

internal fun paneTitle(pane: PaneInfo): String = pane.label ?: pane.title ?: pane.terminalTitle ?: pane.paneId

/** Agent kind, tab and the last two directories of the working directory. */
internal fun paneSubtitle(pane: PaneInfo): String {
  val cwd = (pane.foregroundCwd ?: pane.cwd)?.trimEnd('/')?.split('/')?.takeLast(2)?.joinToString("/")
  return listOfNotNull(pane.displayAgent ?: pane.agent, pane.paneId, cwd).joinToString(" · ")
}
