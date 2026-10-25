package dev.artalex.herdr.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.bundleOf
import dev.artalex.herdr.Agent
import dev.artalex.herdr.MainActivity
import dev.artalex.herdr.R
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.notify.AgentMark
import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.ui.agents.paneTitle

/**
 * The agent notifications: one per agent, tagged "server/pane", in the channel of its status. Waiting for an answer
 * comes with sound, finished is quiet; the user changes either in the system settings. A tap opens the agent.
 */
class Alerts(private val context: Context) {
  private val manager = context.getSystemService(NotificationManager::class.java)

  init {
    manager.createNotificationChannels(
      listOf(
        NotificationChannel(BLOCKED_CHANNEL, context.getString(R.string.alerts_channel_blocked), NotificationManager.IMPORTANCE_HIGH),
        NotificationChannel(DONE_CHANNEL, context.getString(R.string.alerts_channel_done), NotificationManager.IMPORTANCE_LOW),
      )
    )
  }

  /** Notifications can show: the system allows them and, from Android 13 on, the user gave the permission. */
  fun allowed(): Boolean = manager.areNotificationsEnabled()

  /**
   * The agent in [paneId] of [server] came to [status], [AgentStatus.DONE] for any finish: titled as on the agent
   * list, with its workspace next to the status. [withServer] names the server too, as with several on. The
   * notification keeps the status the agent has in [snapshot], which [dropStale] holds it to.
   */
  fun show(server: ServerConfig, withServer: Boolean, snapshot: SessionSnapshot, paneId: String, status: AgentStatus, atMs: Long) {
    val pane = snapshot.panes.firstOrNull { it.paneId == paneId }
    val workspace = pane?.let { p -> snapshot.workspaces.firstOrNull { it.workspaceId == p.workspaceId } }?.label?.takeIf { it.isNotBlank() }
    val agentStatus = snapshot.agents.firstOrNull { it.paneId == paneId }?.agentStatus ?: status
    val text = context.getString(if (status == AgentStatus.BLOCKED) R.string.alerts_blocked else R.string.alerts_done)
    val notification =
      NotificationCompat.Builder(context, if (status == AgentStatus.BLOCKED) BLOCKED_CHANNEL else DONE_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(pane?.let(::paneTitle) ?: paneId)
        .setContentText(listOfNotNull(text, workspace).joinToString(" · "))
        .setSubText(if (withServer) server.title else null)
        .setWhen(atMs)
        .setShowWhen(true)
        .setAutoCancel(true)
        .setContentIntent(openAgent(server.id, paneId))
        .addExtras(bundleOf(EXTRA_STATUS to agentStatus.wire))
        .build()
    if (allowed()) manager.notify(tag(server.id, paneId), ALERT_ID, notification)
  }

  /**
   * Takes down the notifications of [serverId] whose agent left the status it had then, or is gone: a question
   * answered, work started again, a `done` seen on the desktop, which herdr turns into `idle`. A finish that came as
   * `idle` stays until the agent works again.
   */
  fun dropStale(serverId: String, marks: Map<String, AgentMark>) {
    for (shown in shown(serverId)) {
      // Notifications of 2.4 and before have no status of their own: their channel's.
      val status =
        shown.notification.extras.getString(EXTRA_STATUS)?.let(AgentStatus::fromWire)
          ?: if (shown.notification.channelId == BLOCKED_CHANNEL) AgentStatus.BLOCKED else AgentStatus.DONE
      if (marks[shown.tag?.substringAfter('/')]?.status != status) manager.cancel(shown.tag, ALERT_ID)
    }
  }

  /** The user opened the agent: its notification has done its job. */
  fun dismiss(serverId: String, paneId: String) = manager.cancel(tag(serverId, paneId), ALERT_ID)

  /** The server is gone, off or moved: its notifications no longer hold. */
  fun dismissServer(serverId: String) {
    for (shown in shown(serverId)) manager.cancel(shown.tag, ALERT_ID)
  }

  private fun shown(serverId: String): List<StatusBarNotification> =
    manager.activeNotifications.filter { it.id == ALERT_ID && it.tag?.substringBefore('/') == serverId }

  private fun openAgent(serverId: String, paneId: String): PendingIntent =
    PendingIntent.getActivity(context, 0, intentFor(context, serverId, paneId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  companion object {
    const val BLOCKED_CHANNEL = "agent-blocked"
    const val DONE_CHANNEL = "agent-done"

    /** The id of every agent notification; the tag tells them apart. */
    const val ALERT_ID = 2
    private const val EXTRA_SERVER = "dev.artalex.herdr.extra.SERVER"
    private const val EXTRA_PANE = "dev.artalex.herdr.extra.PANE"
    private const val EXTRA_STATUS = "dev.artalex.herdr.extra.STATUS"

    fun tag(serverId: String, paneId: String) = "$serverId/$paneId"

    /** The intent a notification about pane [paneId] of server [serverId] opens the app with; see [agentOf]. */
    fun intentFor(context: Context, serverId: String, paneId: String): Intent =
      MainActivity.intent(context).setIdentifier(tag(serverId, paneId)).putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId)

    /** The agent a tapped notification opens; null for any other intent. */
    fun agentOf(intent: Intent?): Agent? {
      val serverId = intent?.getStringExtra(EXTRA_SERVER) ?: return null
      val paneId = intent.getStringExtra(EXTRA_PANE) ?: return null
      return Agent(serverId, paneId)
    }
  }
}
