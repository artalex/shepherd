package dev.artalex.herdr.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Runs an action that connects to a server, asking for access to the local network first where Android wants that
 * (17 and later). The action runs whatever the answer: without access the connection fails and says why.
 */
@Composable
fun rememberLocalNetworkAccess(): (() -> Unit) -> Unit {
  val context = LocalContext.current
  val pending = remember { arrayOfNulls<() -> Unit>(1) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      pending[0]?.invoke()
      pending[0] = null
    }
  return { action ->
    val needed =
      Build.VERSION.SDK_INT >= 37 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
    if (needed) {
      pending[0] = action
      launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    } else {
      action()
    }
  }
}
