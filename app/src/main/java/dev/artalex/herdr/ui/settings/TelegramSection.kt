package dev.artalex.herdr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.artalex.herdr.R

/**
 * The Telegram bot that agents send answers through, for tasks given from a share: the bot and the user's chat with it,
 * or the button that connects one.
 */
@Composable
internal fun TelegramSection(viewModel: SettingsViewModel) {
  val bot by viewModel.telegramBot.collectAsStateWithLifecycle()
  val setup by viewModel.telegramSetup.collectAsStateWithLifecycle()

  Text(stringResource(R.string.telegram_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
  val connected = bot
  if (connected == null) {
    Text(
      stringResource(R.string.telegram_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    OutlinedButton(onClick = viewModel::openTelegramSetup, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("connectTelegram")) {
      Text(stringResource(R.string.telegram_connect))
    }
  } else {
    ListItem(
      headlineContent = { Text(stringResource(R.string.telegram_connected, connected.bot, connected.chat)) },
      supportingContent = { Text(stringResource(R.string.telegram_hint)) },
      trailingContent = {
        TextButton(onClick = viewModel::disconnectTelegram, modifier = Modifier.testTag("disconnectTelegram")) { Text(stringResource(R.string.telegram_disconnect)) }
      },
      // The item merges its texts into one node: the tag goes there.
      modifier = Modifier.testTag("telegramBot"),
    )
  }
  setup?.let { TelegramDialog(it, viewModel) }
}

/** Connects a bot: its token, then a wait for the user's message to it, which tells the chat. */
@Composable
private fun TelegramDialog(setup: TelegramSetup, viewModel: SettingsViewModel) {
  var token by rememberSaveable { mutableStateOf("") }
  val hello = stringResource(R.string.telegram_hello)
  val typing = setup is TelegramSetup.Token
  AlertDialog(
    onDismissRequest = viewModel::closeTelegramSetup,
    title = { Text(stringResource(R.string.telegram_connect)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = token,
          onValueChange = { token = it },
          label = { Text(stringResource(R.string.telegram_token)) },
          supportingText = { Text(stringResource(R.string.telegram_token_hint)) },
          enabled = typing,
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
          modifier = Modifier.fillMaxWidth().testTag("telegramToken"),
        )
        when (setup) {
          is TelegramSetup.Token -> setup.problem?.let { Text(stringResource(it.text, it.arg ?: ""), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("telegramProblem")) }
          TelegramSetup.Checking -> Waiting(stringResource(R.string.telegram_checking))
          is TelegramSetup.Waiting -> Waiting(stringResource(R.string.telegram_waiting, setup.bot))
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { viewModel.connectTelegram(token, hello) }, enabled = typing && token.isNotBlank(), modifier = Modifier.testTag("connectTelegramBot")) {
        Text(stringResource(R.string.telegram_connect_button))
      }
    },
    dismissButton = { TextButton(onClick = viewModel::closeTelegramSetup) { Text(stringResource(R.string.cancel)) } },
  )
}

@Composable
private fun Waiting(text: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("telegramWaiting")) {
    CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
    Text(text)
  }
}
