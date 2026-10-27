package dev.artalex.herdr.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * there. The folder field has [NewAgent.folder] until the user types another or picks one from its menu,
 * [NewAgent.folders]; the × of a folder there calls [onForget].
 *
 * While a share waits ([sharing]), the dialog also takes a task for the agent, what to do with what was shared, which
 * goes with it; and, with a Telegram bot set up ([telegramReady]), whether the answer goes there, as the last task had
 * it ([answerInTelegram]). [onStart] gets the task, null for a blank one: then the agent's screen shows, as without a
 * task, with the share in its prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAgentDialog(
  form: NewAgent,
  onStart: (kind: AgentKind, folder: String, task: String?, telegram: Boolean) -> Unit,
  onForget: (String) -> Unit,
  onDismiss: () -> Unit,
  sharing: Boolean = false,
  telegramReady: Boolean = false,
  answerInTelegram: Boolean = false,
) {
  var chosen by rememberSaveable(form.serverId) { mutableStateOf<String?>(null) }
  // Null for the form's folder, which the host may yet make shorter.
  var typed by rememberSaveable(form.serverId) { mutableStateOf<String?>(null) }
  var menu by remember { mutableStateOf(false) }
  var task by rememberSaveable(form.serverId) { mutableStateOf("") }
  var telegram by rememberSaveable(form.serverId) { mutableStateOf(answerInTelegram) }
  val folder = typed ?: form.folder
  val agents = form.agents
  // The machine's first agent in AgentKind order, Claude Code if it has it, until the user picks another.
  val kind = agents?.firstOrNull { it.kind == chosen } ?: agents?.firstOrNull()
  val missing = form.missingFolder != null && form.missingFolder == folder.trim()
  val canStart = kind != null && folder.isNotBlank() && !form.starting
  val start = { if (canStart && kind != null) onStart(kind, folder, task.takeIf { sharing && it.isNotBlank() }, telegramReady && telegram) }

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
        ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
          OutlinedTextField(
            value = folder,
            onValueChange = {
              typed = it
              // Typing a folder of one's own: the menu is in the way.
              menu = false
            },
            label = { Text(stringResource(R.string.new_agent_folder)) },
            supportingText = { Text(stringResource(if (missing) R.string.new_agent_folder_missing else R.string.new_agent_folder_hint)) },
            trailingIcon = {
              ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable).testTag("newAgentFolders"))
            },
            isError = missing,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { start() }),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).testTag("newAgentFolder"),
          )
          ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (choice in form.folders) {
              DropdownMenuItem(
                text = { Text(choice.shown, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                onClick = {
                  typed = choice.shown
                  menu = false
                },
                trailingIcon =
                  choice.folder?.let { folder ->
                    {
                      IconButton(onClick = { onForget(folder) }, modifier = Modifier.testTag("forgetFolder-${choice.shown}")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.new_agent_forget_folder, choice.shown))
                      }
                    }
                  },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                modifier = Modifier.testTag("folderOption-${choice.shown}"),
              )
            }
          }
        }
        if (sharing) {
          OutlinedTextField(
            value = task,
            onValueChange = { task = it },
            label = { Text(stringResource(R.string.new_agent_task)) },
            supportingText = { Text(stringResource(R.string.new_agent_task_hint)) },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("newAgentTask"),
          )
          if (telegramReady) {
            Row(
              Modifier.fillMaxWidth()
                .toggleable(value = telegram, enabled = task.isNotBlank(), role = Role.Checkbox, onValueChange = { telegram = it })
                .padding(vertical = 4.dp)
                .testTag("newAgentTelegram"),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(checked = telegram, onCheckedChange = null, enabled = task.isNotBlank())
              Spacer(Modifier.width(12.dp))
              Text(stringResource(R.string.new_agent_telegram))
            }
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
