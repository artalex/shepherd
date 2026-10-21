package dev.artalex.herdr.e2e

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import dev.artalex.herdr.MainActivity

/** Whether the soft keyboard is up over the app. */
fun keyboardShown(scenario: ActivityScenario<MainActivity>): Boolean {
  var shown = false
  scenario.onActivity { shown = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
  return shown
}

/**
 * Types into the focused text editor the way a soft keyboard does, through its input connection: `input text` cannot
 * type Cyrillic, and keyboards send text with commitText, or compose words with setComposingText where the editor
 * allows suggestions. The editor is the view with focus: termlib's input view in the terminal, the Compose view while
 * a text field has focus. [check] looks at what the editor asked the keyboard for.
 */
fun keyboard(scenario: ActivityScenario<MainActivity>, check: (EditorInfo) -> Unit = {}, action: InputConnection.() -> Unit) {
  scenario.onActivity { activity ->
    val view = checkNotNull(activity.window.decorView.findFocus()?.takeIf { it.onCheckIsTextEditor() }) { "no text editor has focus" }
    val info = EditorInfo()
    val connection = checkNotNull(view.onCreateInputConnection(info)) { "no input connection" }
    check(info)
    connection.action()
  }
}
