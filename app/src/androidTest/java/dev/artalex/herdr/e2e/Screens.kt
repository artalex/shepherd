package dev.artalex.herdr.e2e

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Saves a screenshot to [DIR] on the device; scripts/e2e.sh pulls the folder to app/build/e2e-screens.
 * screencap runs as the shell user, which can write there even after the app is uninstalled.
 */
object Screens {
  const val DIR = "/data/local/tmp/herdr-e2e"

  /**
   * Compose tests drive frames with a test clock: while a test sleeps or waits on herdr, nothing
   * recomposes. Let the UI catch up first, or the screenshot shows an old frame.
   */
  fun take(compose: ComposeTestRule, name: String) {
    compose.waitForIdle()
    take(name)
  }

  fun take(name: String) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.executeShellCommand("mkdir -p $DIR").close()
    automation.executeShellCommand("screencap -p $DIR/$name.png").use { pipe ->
      // Wait for screencap to finish: the pipe closes when the command exits.
      android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).readBytes()
    }
  }
}
