package dev.artalex.herdr

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * The app in Android's share menu. It has no window: it hands the share to [MainActivity] in the app's own task, where
 * the user picks the agent for it on the agent list, and the sharing app stays as it was.
 */
class ShareActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val share = Share.of(intent)
    if (share != null) {
      try {
        startActivity(share.intent(this))
      } catch (e: SecurityException) {
        // A file came without read access to pass on: the text still goes, and the file shows as not sent.
        Log.w(TAG, "cannot pass on access to a shared file: ${e.message}")
        startActivity(share.intent(this, grant = false))
      }
    }
    finish()
  }

  private companion object {
    const val TAG = "HerdrShare"
  }
}
