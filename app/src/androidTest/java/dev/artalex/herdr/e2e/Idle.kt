package dev.artalex.herdr.e2e

import androidx.compose.ui.test.junit4.ComposeTestRule

/** Lets time pass with the UI running: Compose tests advance frames only when the test asks. */
fun ComposeTestRule.idle(ms: Long) {
  val until = System.currentTimeMillis() + ms
  while (System.currentTimeMillis() < until) {
    waitForIdle()
    Thread.sleep(50)
  }
}
