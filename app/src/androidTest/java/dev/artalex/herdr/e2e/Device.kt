package dev.artalex.herdr.e2e

import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.artalex.herdr.AppContainer

/** The emulator itself, for tests that take the app off the screen or lock the device. */
object Device {
  private const val PIN = "1111"
  private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
  private val keyguard get() = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
  private val power get() = instrumentation.targetContext.getSystemService(PowerManager::class.java)

  /** Runs [command] as the shell user and returns its output once it's done. */
  fun shell(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }

  /** Waits until [condition] holds; fails naming [what] after [timeoutMs]. */
  fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "$what: still not so after $timeoutMs ms" }
      Thread.sleep(200)
    }
  }

  /** Takes the app off the screen as the home button does; ProcessLifecycleOwner tells the app 700 ms later. */
  fun goHome(container: AppContainer) {
    shell("input keyevent KEYCODE_HOME")
    waitFor("app in background") { !container.servers.foreground.value }
  }

  /**
   * Runs [block] with the device locked by a PIN, as a phone in a pocket, and takes the PIN off after. The AVD keeps
   * its data between runs, so a PIN left by a run that died is cleared first.
   */
  fun <T> locked(block: () -> T): T {
    shell("locksettings clear --old $PIN")
    shell("locksettings set-pin $PIN")
    try {
      wake()
      shell("input keyevent KEYCODE_POWER")
      waitFor("device locked") { !power.isInteractive && keyguard.isDeviceLocked }
      return block()
    } finally {
      shell("locksettings clear --old $PIN")
      wake()
    }
  }

  /**
   * Turns the screen on and takes the keyguard away. A wake key that comes while the screen is still going off gets
   * lost, and the tests after would run on a dark screen: it goes again until the screen is on.
   */
  fun wake() {
    waitFor("screen on") { power.isInteractive.also { if (!it) shell("input keyevent KEYCODE_WAKEUP") } }
    waitFor("keyguard gone") { !keyguard.isKeyguardLocked.also { if (it) shell("wm dismiss-keyguard") } }
  }
}
