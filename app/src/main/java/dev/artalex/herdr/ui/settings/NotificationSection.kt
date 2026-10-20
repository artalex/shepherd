package dev.artalex.herdr.ui.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.artalex.herdr.R
import dev.artalex.herdr.data.BackgroundCheck
import dev.artalex.herdr.data.NotificationSettings
import dev.artalex.herdr.ui.common.notificationsAllowed
import dev.artalex.herdr.ui.common.openNotificationSettings
import dev.artalex.herdr.ui.common.rememberNotificationAccess

/**
 * Notifications about agents: on or off, how long to watch after the app leaves the screen, the channels, Android's
 * limits on background work and when the last background check ran.
 */
@Composable
internal fun NotificationSection(viewModel: SettingsViewModel) {
  val context = LocalContext.current
  val enabled by viewModel.notificationsOn.collectAsStateWithLifecycle()
  val watchMinutes by viewModel.watchMinutes.collectAsStateWithLifecycle()
  val lastCheck by viewModel.lastCheck.collectAsStateWithLifecycle()
  // Both change in the system settings, from where the user comes back here.
  var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
  var unrestricted by remember { mutableStateOf(batteryUnrestricted(context)) }
  LifecycleResumeEffect(Unit) {
    allowed = notificationsAllowed(context)
    unrestricted = batteryUnrestricted(context)
    onPauseOrDispose {}
  }
  val access = rememberNotificationAccess()
  val on = enabled && allowed

  Text(stringResource(R.string.notify_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
  ListItem(
    headlineContent = { Text(stringResource(R.string.notify_switch)) },
    supportingContent = { Text(stringResource(if (enabled && !allowed) R.string.notify_not_allowed else R.string.notify_switch_hint)) },
    trailingContent = { Switch(checked = on, onCheckedChange = null) },
    modifier =
      Modifier.toggleable(value = on, role = Role.Switch) { want ->
          viewModel.setNotifications(want)
          if (want) {
            access { granted ->
              allowed = granted
              // Refused for good, the permission is only in the system settings.
              if (!granted) openNotificationSettings(context)
            }
          }
        }
        .testTag("notifications"),
  )
  if (!on) return
  ListItem(
    headlineContent = { Text(stringResource(R.string.notify_watch)) },
    supportingContent = {
      Text(if (watchMinutes > 0) stringResource(R.string.notify_watch_hint, watchMinutes) else stringResource(R.string.notify_watch_none))
    },
  )
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
    val choices = NotificationSettings.WATCH_CHOICES
    choices.forEachIndexed { i, minutes ->
      SegmentedButton(
        selected = minutes == watchMinutes,
        onClick = { viewModel.setWatchMinutes(minutes) },
        shape = SegmentedButtonDefaults.itemShape(i, choices.size),
        modifier = Modifier.testTag("watch-$minutes"),
      ) {
        Text(if (minutes == 0) stringResource(R.string.notify_watch_off) else stringResource(R.string.notify_watch_minutes, minutes))
      }
    }
  }
  ListItem(
    headlineContent = { Text(stringResource(R.string.notify_channels)) },
    supportingContent = { Text(stringResource(R.string.notify_channels_hint)) },
    modifier = Modifier.clickable { openNotificationSettings(context) },
  )
  ListItem(
    headlineContent = { Text(stringResource(R.string.notify_battery)) },
    supportingContent = { Text(stringResource(if (unrestricted) R.string.notify_battery_unrestricted else R.string.notify_battery_restricted)) },
    modifier = Modifier.clickable { openBatterySettings(context, unrestricted) }.testTag("battery"),
  )
  Text(
    lastCheckText(context, lastCheck),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp).testTag("lastCheck"),
  )
}

@Composable
private fun lastCheckText(context: Context, check: BackgroundCheck?): String {
  if (check == null) return stringResource(R.string.notify_no_check)
  val time = DateUtils.formatDateTime(context, check.atMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
  return stringResource(R.string.notify_last_check, time, check.reached, check.total)
}

private fun batteryUnrestricted(context: Context): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/**
 * Limited, the app asks to lift the limits: one dialog of Android's. Unlimited, it opens the app's page in the
 * system settings, where the limits come back on.
 */
@SuppressLint("BatteryLife") // the app isn't on Google Play, which restricts asking
private fun openBatterySettings(context: Context, unrestricted: Boolean) {
  val app = Uri.fromParts("package", context.packageName, null)
  val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app)
  val page = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
  try {
    context.startActivity(if (unrestricted) page else ask)
  } catch (e: ActivityNotFoundException) {
    context.startActivity(page)
  }
}
