package dev.artalex.herdr.e2e

import android.app.Activity
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.artalex.herdr.MainActivity

/** The app's main activities alive now, in any stage. */
fun mainActivities(): List<Activity> {
  var found = emptyList<Activity>()
  InstrumentationRegistry.getInstrumentation().runOnMainSync {
    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    found = Stage.entries.filter { it != Stage.DESTROYED }.flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<MainActivity>()
  }
  return found
}

/** Closes the app's main activities, as ActivityScenario closes the one it starts. */
fun finishMainActivities() {
  val open = mainActivities()
  InstrumentationRegistry.getInstrumentation().runOnMainSync { open.forEach { it.finish() } }
  Device.waitFor("activities closed") { mainActivities().isEmpty() }
}

/** The main activity's records in Android's tasks, as `dumpsys activity activities` lists them: one for each copy. */
fun mainActivityRecords(): List<String> {
  val component = ComponentName(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java).flattenToShortString()
  return Device.shell("dumpsys activity activities").lines().map { it.trim() }.filter { it.startsWith("* Hist") && " $component " in it }
}
