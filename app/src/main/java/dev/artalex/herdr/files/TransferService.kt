package dev.artalex.herdr.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground while files go between the phone and the host: in the background Android would
 * freeze it halfway. The transfers themselves run in [FileTransfers]; the service stops once none is left.
 */
class TransferService : Service() {
  private val scope = MainScope()
  private var watcher: Job? = null
  private var lastStart = 0

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    lastStart = startId
    val notifications = getSystemService(NotificationManager::class.java)
    notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.transfers_channel), NotificationManager.IMPORTANCE_LOW))
    val notification =
      NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_folder)
        .setContentTitle(getString(R.string.transfers_notification))
        .setOngoing(true)
        .setSilent(true)
        .build()
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    // A start that came in after the last transfer ended needs a watcher of its own.
    if (watcher?.isActive != true) {
      val active = (application as HerdrApp).container.transfers
      watcher =
        scope.launch {
          active.first { it == 0 }
          stopSelf(lastStart)
        }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private companion object {
    const val CHANNEL = "transfers"
    const val NOTIFICATION_ID = 1
  }
}
