package dev.artalex.herdr.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.artalex.herdr.R
import dev.artalex.herdr.core.ssh.SshKeyPair

/**
 * The servers, each opening its form, the notifications, and the phone's key, which every server needs in its
 * authorized_keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenServer: (serverId: String?) -> Unit) {
  val servers by viewModel.servers.collectAsStateWithLifecycle()
  val key by viewModel.key.collectAsStateWithLifecycle()

  Scaffold(
    topBar = {
      TopAppBar(
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.agent_back)) }
        },
        title = { Text(stringResource(R.string.settings_title)) },
      )
    }
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
      Text(stringResource(R.string.servers_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
      for (server in servers) {
        ListItem(
          headlineContent = { Text(server.title) },
          supportingContent = { Text(stringResource(R.string.server_address, server.user, server.host, server.port, server.session)) },
          modifier = Modifier.clickable { onOpenServer(server.id) }.testTag("serverRow-${server.id}"),
        )
      }
      OutlinedButton(onClick = { onOpenServer(null) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("addServer")) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(stringResource(R.string.server_add), Modifier.padding(start = 8.dp))
      }
      HorizontalDivider(Modifier.padding(vertical = 16.dp))
      NotificationSection(viewModel)
      HorizontalDivider(Modifier.padding(vertical = 16.dp))
      Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { KeySection(key, onGenerate = viewModel::generateKey) }
    }
  }
}

/** The phone's public key to put on servers, or the button that makes the key. */
@Composable
internal fun KeySection(key: SshKeyPair?, onGenerate: () -> Unit) {
  val context = LocalContext.current
  Text(stringResource(R.string.key_title), style = MaterialTheme.typography.titleMedium)
  if (key == null) {
    Text(stringResource(R.string.key_missing))
    Button(onClick = onGenerate, modifier = Modifier.testTag("generateKey")) { Text(stringResource(R.string.key_generate)) }
    return
  }
  Text(stringResource(R.string.key_hint), style = MaterialTheme.typography.bodySmall)
  SelectionContainer {
    Text(key.publicKeyLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("publicKey"))
  }
  Text(stringResource(R.string.key_fingerprint, key.fingerprint), style = MaterialTheme.typography.bodySmall)
  val copied = stringResource(R.string.key_copied)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = {
      context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ssh public key", key.publicKeyLine))
      Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }) { Text(stringResource(R.string.key_copy)) }
    OutlinedButton(onClick = {
      val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key.publicKeyLine)
      context.startActivity(Intent.createChooser(send, null))
    }) { Text(stringResource(R.string.key_share)) }
  }
}
