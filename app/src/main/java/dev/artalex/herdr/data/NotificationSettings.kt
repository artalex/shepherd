package dev.artalex.herdr.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The notification settings: on or off, and how long to keep watching the agents after the app leaves the screen.
 * Also the last background check, which the settings show, so it's visible whether Android lets the checks run.
 */
class NotificationSettings(context: Context) {
  private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)

  private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED, true))
  val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

  private val _watchMinutes = MutableStateFlow(prefs.getInt(WATCH_MINUTES, DEFAULT_WATCH_MINUTES))

  /** How long the watch goes on after the last change; 0 goes straight to the periodic checks. */
  val watchMinutes: StateFlow<Int> = _watchMinutes.asStateFlow()

  private val _lastCheck = MutableStateFlow(readCheck())
  val lastCheck: StateFlow<BackgroundCheck?> = _lastCheck.asStateFlow()

  /** The agent list asked for the notification permission already: it asks once, after that it's in the settings. */
  var asked: Boolean
    get() = prefs.getBoolean(ASKED, false)
    set(value) = prefs.edit { putBoolean(ASKED, value) }

  fun setEnabled(on: Boolean) {
    prefs.edit { putBoolean(ENABLED, on) }
    _enabled.value = on
  }

  fun setWatchMinutes(minutes: Int) {
    prefs.edit { putInt(WATCH_MINUTES, minutes) }
    _watchMinutes.value = minutes
  }

  fun checked(check: BackgroundCheck) {
    prefs.edit {
      putLong(CHECK_AT, check.atMs)
      putInt(CHECK_REACHED, check.reached)
      putInt(CHECK_TOTAL, check.total)
    }
    _lastCheck.value = check
  }

  private fun readCheck(): BackgroundCheck? {
    val at = prefs.getLong(CHECK_AT, 0)
    return if (at == 0L) null else BackgroundCheck(at, prefs.getInt(CHECK_REACHED, 0), prefs.getInt(CHECK_TOTAL, 0))
  }

  companion object {
    const val DEFAULT_WATCH_MINUTES = 5

    /** What the settings offer for the watch, in minutes. */
    val WATCH_CHOICES = listOf(0, 5, 15, 30)

    private const val ENABLED = "enabled"
    private const val WATCH_MINUTES = "watch_minutes"
    private const val ASKED = "asked"
    private const val CHECK_AT = "check_at"
    private const val CHECK_REACHED = "check_reached"
    private const val CHECK_TOTAL = "check_total"
  }
}

/** A background check: when it ran and how many of the servers it looked at answered. */
data class BackgroundCheck(val atMs: Long, val reached: Int, val total: Int)
