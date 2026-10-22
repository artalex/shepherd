package dev.artalex.herdr.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import dev.artalex.herdr.R
import dev.artalex.herdr.core.terminal.webLink
import kotlinx.coroutines.launch

/**
 * What a tap on a link in a terminal does: a web link opens in the phone's browser, see [webLink]. A link of another
 * kind, or one nothing on the phone can open, gets a note in [snackbar] instead.
 */
@Composable
fun rememberLinkOpener(snackbar: SnackbarHostState): (String) -> Unit {
  val context = LocalContext.current
  val resources = LocalResources.current
  val scope = rememberCoroutineScope()
  return remember(context, resources, snackbar, scope) {
    { link ->
      val problem = open(context, link)
      val shown = if (link.length > SHOWN_LINK) link.take(SHOWN_LINK) + "…" else link
      if (problem != null) scope.launch { snackbar.showSnackbar(resources.getString(problem, shown)) }
    }
  }
}

/** Opens [link] in a browser; the text saying why not if it can't. */
private fun open(context: Context, link: String): Int? {
  val url = webLink(link) ?: return R.string.link_not_web
  return try {
    // Browsable activities only: the link comes from whatever runs in the pane.
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
    null
  } catch (e: ActivityNotFoundException) {
    R.string.files_no_viewer
  }
}

/** Characters of a link a note shows: a link set with OSC 8 may be of any length. */
private const val SHOWN_LINK = 120
