package dev.artalex.herdr.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.MainActivity
import dev.artalex.herdr.R
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app going while [AgentWatch] watches the agents after the app left the screen: in the background Android
 * would freeze it within seconds, and with the screen off the CPU would sleep through the polls. Its notification
 * tells until when the watch goes on and has a button to end it now; the service stops once the watch is over.
 */
class WatchService : Service() {
  private val scope = MainScope()
  private var follower: Job? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var lastStart = 0

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val watch = (application as HerdrApp).container.watch
    // The stop button's start too: stopping with an earlier id after it would be ignored, and the service would stay.
    lastStart = startId
    if (intent?.action == ACTION_STOP) {
      watch.stopWatch()
      return START_NOT_STICKY
    }
    getSystemService(NotificationManager::class.java)
      .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.watch_channel), NotificationManager.IMPORTANCE_MIN))
    val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(watch.until.value), type)
    val lock =
      wakeLock ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "herdr:watch").also {
        it.setReferenceCounted(false)
        wakeLock = it
      }
    if (follower?.isActive != true) {
      follower =
        scope.launch {
          watch.until.collect { until ->
            if (until == null) {
              // A watch that started meanwhile came with a later start, which this doesn't stop.
              stopSelf(lastStart)
            } else {
              // Each change moves the end, and the wake lock with it; the timeout only guards against a leak.
              lock.acquire(until - System.currentTimeMillis() + WAKE_MARGIN_MS)
              getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(until))
            }
          }
        }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    wakeLock?.let { if (it.isHeld) it.release() }
    super.onDestroy()
  }

  private fun notification(until: Long?): Notification {
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
    val stop = PendingIntent.getService(this, 0, Intent(this, WatchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(getString(R.string.watch_notification))
      .setContentText(until?.let { getString(R.string.watch_until, DateFormat.getTimeFormat(this).format(Date(it))) })
      .setContentIntent(open)
      .addAction(0, getString(R.string.watch_stop), stop)
      .setOngoing(true)
      .setSilent(true)
      .build()
  }

  private companion object {
    const val CHANNEL = "watch"
    const val NOTIFICATION_ID = 3
    const val ACTION_STOP = "dev.artalex.herdr.action.STOP_WATCH"
    const val WAKE_MARGIN_MS = 60_000L
  }
}
