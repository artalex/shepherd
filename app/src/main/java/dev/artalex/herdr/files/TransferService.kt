package dev.artalex.herdr.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.artalex.herdr.HerdrApp
import dev.artalex.herdr.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground while files go between the phone and the host, while a task goes to an agent the
 * phone started ([dev.artalex.herdr.AgentTasks]), and while the settings wait for the user's message to a Telegram bot:
 * in the background Android would freeze the app halfway. The work itself runs elsewhere, [FileTransfers] for files;
 * the service stops once none of it is left, and its notification tells which goes on.
 */
class TransferService : Service() {
  private val scope = MainScope()
  private var watcher: Job? = null
  private var titles: Job? = null
  private var lastStart = 0

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    lastStart = startId
    val container = (application as HerdrApp).container
    val work = combine(container.transfers, container.tasks, container.telegramWaits, ::titleOf)
    val notifications = getSystemService(NotificationManager::class.java)
    notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.transfers_channel), NotificationManager.IMPORTANCE_LOW))
    val title = titleOf(container.transfers.value, container.tasks.value, container.telegramWaits.value) ?: R.string.transfers_notification
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(title), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    if (titles == null) {
      // Only while work goes on: a notification posted as the service stops would stay behind it.
      titles = scope.launch { work.filterNotNull().distinctUntilChanged().collect { notifications.notify(NOTIFICATION_ID, notification(it)) } }
    }
    // A start that came in after the last transfer ended needs a watcher of its own.
    if (watcher?.isActive != true) {
      watcher =
        scope.launch {
          work.first { it == null }
          stopSelf(lastStart)
        }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  /** What the notification says while [files], [tasks] and [waits] for a bot's message go on; null once none does. */
  private fun titleOf(files: Int, tasks: Int, waits: Int): Int? =
    when {
      tasks > 0 -> R.string.tasks_notification
      waits > 0 -> R.string.telegram_waiting_notification
      files > 0 -> R.string.transfers_notification
      else -> null
    }

  private fun notification(title: Int): Notification =
    NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_folder).setContentTitle(getString(title)).setOngoing(true).setSilent(true).build()

  companion object {
    private const val TAG = "HerdrFiles"
    private const val CHANNEL = "transfers"
    private const val NOTIFICATION_ID = 1

    /** Starts the service for work that just began; it stops by itself once the work is done. */
    fun start(context: Context) {
      try {
        ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
      } catch (e: Exception) {
        // Android refuses a foreground service started from the background; the work goes on without it.
        Log.w(TAG, "cannot start the transfer service: ${e.message}")
      }
    }
  }
}
