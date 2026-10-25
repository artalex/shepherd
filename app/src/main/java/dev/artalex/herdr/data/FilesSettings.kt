package dev.artalex.herdr.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the files window lists files: those of its pane or those of the pane's whole workspace, the same everywhere. */
class FilesSettings(context: Context) {
  private val prefs = context.getSharedPreferences("files", Context.MODE_PRIVATE)

  private val _workspace = MutableStateFlow(prefs.getBoolean(WORKSPACE, false))

  /** The window lists the files of the whole workspace; at first only those of the pane. */
  val workspace: StateFlow<Boolean> = _workspace.asStateFlow()

  fun setWorkspace(on: Boolean) {
    prefs.edit { putBoolean(WORKSPACE, on) }
    _workspace.value = on
  }

  private companion object {
    const val WORKSPACE = "workspace"
  }
}
