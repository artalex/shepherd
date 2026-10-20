package dev.artalex.herdr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.artalex.herdr.R
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.servers.ServerList
import dev.artalex.herdr.ui.common.rememberLocalNetworkAccess

/** The form survives rotation as text: a server's settings are a handful of strings. */
private val FormSaver = Saver<ServerConfig, String>(save = { ServerList.encode(listOf(it)) }, restore = { ServerList.decode(it).firstOrNull() })

/**
 * The form of server [serverId], or of a new one. Saving goes to the agent list, where the server connects and, the
 * first time, asks to accept its host key. The phone's key is here too: a new server needs it before it lets the
 * phone in. [canGoBack] is false on the first start, when there is nothing to go back to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(viewModel: SettingsViewModel, serverId: String?, canGoBack: Boolean, onBack: () -> Unit, onSaved: () -> Unit, onDeleted: () -> Unit) {
  val key by viewModel.key.collectAsStateWithLifecycle()
  var form by rememberSaveable(stateSaver = FormSaver) { mutableStateOf(serverId?.let(viewModel::server) ?: ServerConfig.new()) }
  var confirmDelete by remember { mutableStateOf(false) }
  val localNetwork = rememberLocalNetworkAccess()
  val focus = LocalFocusManager.current

  Scaffold(
    topBar = {
      TopAppBar(
        navigationIcon = {
          if (canGoBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.agent_back)) }
        },
        title = { Text(stringResource(if (serverId == null) R.string.server_new else R.string.server_edit)) },
        actions = {
          if (serverId != null) {
            IconButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("deleteServer")) {
              Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.server_delete))
            }
          }
        },
      )
    },
    // The fields stay above the keyboard; the form scrolls instead.
    contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
  ) { padding ->
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      ServerForm(form, onChange = { form = it })
      Button(
        onClick = {
          focus.clearFocus()
          localNetwork {
            viewModel.save(form)
            onSaved()
          }
        },
        enabled = form.isComplete && key != null,
        modifier = Modifier.testTag("save"),
      ) {
        Text(stringResource(R.string.server_save))
      }
      HorizontalDivider()
      KeySection(key, onGenerate = viewModel::generateKey)
    }
  }

  if (confirmDelete && serverId != null) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text(stringResource(R.string.server_delete_title)) },
      text = { Text(stringResource(R.string.server_delete_text, viewModel.server(serverId)?.title ?: form.title)) },
      confirmButton = {
        TextButton(
          onClick = {
            confirmDelete = false
            viewModel.delete(serverId)
            onDeleted()
          },
          modifier = Modifier.testTag("confirmDelete"),
        ) {
          Text(stringResource(R.string.server_delete_confirm), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

@Composable
private fun ServerForm(form: ServerConfig, onChange: (ServerConfig) -> Unit) {
  // Left empty, the name is the host and the session: the line under the field shows what the list will say.
  val listed = if (form.name.isBlank() && form.defaultTitle.isNotBlank()) stringResource(R.string.server_name_default, form.defaultTitle) else null
  Field(R.string.server_name, form.name, "name", KeyboardType.Text, supporting = listed) { onChange(form.copy(name = it)) }
  Field(R.string.server_host, form.host, "host", KeyboardType.Uri) { onChange(form.copy(host = it)) }
  Field(R.string.server_port, form.port.toString(), "port", KeyboardType.Number) { value ->
    value.filter(Char::isDigit).take(5).toIntOrNull()?.let { onChange(form.copy(port = it)) }
  }
  Field(R.string.server_user, form.user, "user", KeyboardType.Ascii) { onChange(form.copy(user = it)) }
  Field(R.string.server_herdr_path, form.herdrPath, "herdrPath", KeyboardType.Uri) { onChange(form.copy(herdrPath = it)) }
  Field(R.string.server_session, form.session, "session", KeyboardType.Ascii) { onChange(form.copy(session = it)) }
}

@Composable
private fun Field(label: Int, value: String, tag: String, type: KeyboardType, supporting: String? = null, onChange: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(stringResource(label)) },
    supportingText = supporting?.let { { Text(it) } },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
    modifier = Modifier.fillMaxWidth().testTag(tag),
  )
}
