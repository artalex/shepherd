package dev.artalex.herdr.e2e

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import dev.artalex.herdr.MainActivity

/**
 * Runs [body] with the app open; [body] gets the scenario to reach the activity. On failure it saves
 * a screenshot first: once the scenario closes, the activity is gone and a screenshot would be blank.
 */
fun launchApp(compose: ComposeTestRule, testName: String, body: (ActivityScenario<MainActivity>) -> Unit) {
  ActivityScenario.launch(MainActivity::class.java).use { scenario -> shotOnFailure(compose, testName) { body(scenario) } }
}

/** Runs [body]; if it fails, saves a screenshot of the app as it is then, named after [testName]. */
fun shotOnFailure(compose: ComposeTestRule, testName: String, body: () -> Unit) {
  try {
    body()
  } catch (e: Throwable) {
    runCatching { Screens.take(compose, "FAILED-$testName") }
    throw e
  }
}
