package dev.artalex.herdr

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.artalex.herdr.notify.Alerts
import dev.artalex.herdr.theme.HerdrTheme

class MainActivity : ComponentActivity() {
  /** The agent of a tapped notification, until the navigation shows it. */
  private var opening by mutableStateOf<Agent?>(null)

  /**
   * What another app shared (see [ShareActivity]), until the user picks an agent for it or drops it. It lives no
   * longer than the activity, as the access to its files does.
   */
  private var sharing by mutableStateOf<Share?>(null)

  /** Counts the shares that came: each one opens the list, one the same as the share still waiting too. */
  private var shareCount by mutableIntStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A restored activity has shown its notification's agent already; a share went with the activity before it.
    if (savedInstanceState == null) {
      opening = Alerts.agentOf(intent)
      receiveShare(intent)
    }

    // The app is always dark: light system bar icons whatever the system theme.
    enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
    setContent {
      HerdrTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(opening, onOpened = { opening = null }, sharing, shareCount, onShared = { sharing = null })
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Alerts.agentOf(intent)?.let { opening = it }
    receiveShare(intent)
  }

  private fun receiveShare(intent: Intent) {
    val share = Share.of(intent) ?: return
    sharing = share
    shareCount++
  }

  companion object {
    /**
     * An intent for this activity in the app's own task: the activity there already gets it, as from a tapped
     * notification, and a new one starts only if there is none.
     */
    fun intent(context: Context): Intent =
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
  }
}
