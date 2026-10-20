package dev.artalex.herdr

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.artalex.herdr.notify.Alerts
import dev.artalex.herdr.theme.HerdrTheme

class MainActivity : ComponentActivity() {
  /** The agent of a tapped notification, until the navigation shows it. */
  private var opening by mutableStateOf<Agent?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A restored activity has shown its notification's agent already.
    if (savedInstanceState == null) opening = Alerts.agentOf(intent)

    // The app is always dark: light system bar icons whatever the system theme.
    enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
    setContent {
      HerdrTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(opening, onOpened = { opening = null })
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Alerts.agentOf(intent)?.let { opening = it }
  }
}
