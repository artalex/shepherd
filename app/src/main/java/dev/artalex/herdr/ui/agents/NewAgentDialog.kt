package dev.artalex.herdr.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.artalex.herdr.R
import dev.artalex.herdr.core.model.AgentKind

/**
 * Starts an agent on a server from the agent list: one of the agents herdr can start on its machine, in a folder
 * there. The folder field starts with [NewAgent.folder]; a tap on one of the panes' folders puts it in.
 */
@Composable
fun NewAgentDialog(form: NewAgent, onStart: (AgentKind, String) -> Unit, onDismiss: () -> Unit) {
  var chosen by rememberSaveable(form.serverId) { mutableStateOf<String?>(null) }
  var folder by rememberSaveable(form.serverId) { mutableStateOf(form.folder) }
  val agents = form.agents
  // The machine's first agent in AgentKind order, Claude Code if it has it, until the user picks another.
  val kind = agents?.firstOrNull { it.kind == chosen } ?: agents?.firstOrNull()
  val missing = form.missingFolder != null && form.missingFolder == folder.trim()
  val canStart = kind != null && folder.isNotBlank() && !form.starting
  val start = { if (canStart && kind != null) onStart(kind, folder) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.new_agent_title, form.serverTitle)) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
          agents == null -> Text(stringResource(R.string.pane_agents_loading))
          // A lookup that failed says so below.
          agents.isEmpty() -> if (form.problem == null) Text(stringResource(R.string.pane_agents_none))
          else -> for (agent in agents) AgentOption(agent, selected = agent == kind) { chosen = agent.kind }
        }
        OutlinedTextField(
          value = folder,
          onValueChange = { folder = it },
          label = { Text(stringResource(R.string.new_agent_folder)) },
          supportingText = { Text(stringResource(if (missing) R.string.new_agent_folder_missing else R.string.new_agent_folder_hint)) },
          isError = missing,
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { start() }),
          modifier = Modifier.fillMaxWidth().testTag("newAgentFolder"),
        )
        if (form.folders.isNotEmpty()) {
          Text(stringResource(R.string.new_agent_folders), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          for (path in form.folders) {
            Text(
              path,
              maxLines = 1,
              overflow = TextOverflow.MiddleEllipsis,
              modifier = Modifier.fillMaxWidth().clickable { folder = path }.padding(vertical = 8.dp).testTag("folderOption-$path"),
            )
          }
        }
        form.problem?.let { Text(stringResource(it.text, it.arg ?: ""), color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      TextButton(onClick = start, enabled = canStart, modifier = Modifier.testTag("startNewAgent")) {
        Text(stringResource(if (form.starting) R.string.new_agent_starting else R.string.new_agent_start))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss, enabled = !form.starting) { Text(stringResource(R.string.cancel)) } },
  )
}

@Composable
private fun AgentOption(agent: AgentKind, selected: Boolean, onSelect: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp).testTag("newAgentKind-${agent.kind}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Spacer(Modifier.width(12.dp))
    Text(agent.title)
  }
}
