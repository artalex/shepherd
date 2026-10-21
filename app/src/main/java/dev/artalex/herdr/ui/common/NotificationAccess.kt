package dev.artalex.herdr.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Asks for the notification permission if the app doesn't have it, then tells whether notifications can show. Once
 * the user refused for good, Android doesn't ask any more and the answer comes at once.
 */
@Composable
fun rememberNotificationAccess(): ((allowed: Boolean) -> Unit) -> Unit {
  val context = LocalContext.current
  val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      pending[0]?.invoke(notificationsAllowed(context))
      pending[0] = null
    }
  return { then ->
    val needed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    if (needed) {
      pending[0] = then
      launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      then(notificationsAllowed(context))
    }
  }
}

fun notificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

/** The app's notification settings in Android: on and off for each channel, sound, the permission itself. */
fun openNotificationSettings(context: Context) {
  context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}
