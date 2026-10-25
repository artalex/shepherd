package dev.artalex.herdr

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * What another app shared through Android's share menu: [text] for an agent's prompt, [files] for the host, or both.
 * The files are URIs the app may read while the activity that got them lives.
 */
data class Share(val text: String?, val files: List<Uri>) {
  /**
   * The intent that brings this share to [MainActivity] in the app's own task (see [MainActivity.intent]). [grant]
   * passes on the read access to the files, which then lasts as long as that activity.
   */
  fun intent(context: Context, grant: Boolean = true): Intent =
    MainActivity.intent(context).apply {
      action = if (files.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
      text?.let { putExtra(Intent.EXTRA_TEXT, it) }
      when (files.size) {
        0 -> Unit
        1 -> putExtra(Intent.EXTRA_STREAM, files.single())
        else -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
      }
      // Android passes read access on for the URIs of the clip data.
      if (files.isNotEmpty()) clipData = ClipData.newRawUri(null, files.first()).apply { files.drop(1).forEach { addItem(ClipData.Item(it)) } }
      if (grant && files.isNotEmpty()) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

  /** Gives this share to pane [paneId] of [server]: the text goes into its prompt, the files to the host and their paths after it. */
  fun sendTo(server: Server, paneId: String) {
    text?.let { server.inbox.put(paneId, PromptPart.Text(it)) }
    if (files.isNotEmpty()) server.files.upload(files, server.originOf(paneId), forPane = paneId)
  }

  companion object {
    /** The share in an ACTION_SEND or ACTION_SEND_MULTIPLE intent; null for any other intent and for one with nothing in it. */
    fun of(intent: Intent?): Share? {
      val action = intent?.action
      if (intent == null || (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE)) return null
      // Some apps send a list of texts with several files.
      val text = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.joinToString("\n"))?.toString()?.trim()?.ifEmpty { null }
      val streams =
        if (action == Intent.ACTION_SEND) listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        else IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
      // Some put their files only into the clip data.
      val clip = intent.clipData
      val clipped = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
      val files = (streams + clipped).distinct()
      return if (text == null && files.isEmpty()) null else Share(text, files)
    }
  }
}
