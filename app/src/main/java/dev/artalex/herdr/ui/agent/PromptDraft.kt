package dev.artalex.herdr.ui.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The prompt of an agent screen. Sent text leaves the field at once, for the next prompt, and is kept here until herdr
 * takes it: if herdr doesn't, the text comes back to the field, ahead of anything typed meanwhile.
 */
class PromptDraft {
  /** The field's text and selection. */
  var value by mutableStateOf(TextFieldValue())

  // The text on its way to the agent.
  private var pending by mutableStateOf<String?>(null)

  /** Text is on its way to the agent: nothing else goes until herdr answers about it. */
  val sending: Boolean
    get() = pending != null

  /** The field's text to send, which empties the field; null if it is blank or another text is on its way. */
  fun take(): String? {
    if (sending || value.text.isBlank()) return null
    val text = value.text
    pending = text
    value = TextFieldValue()
    return text
  }

  /** herdr took the text; the field keeps whatever was typed since. */
  fun sent() {
    pending = null
  }

  /** herdr didn't take the text: it goes back to the field, on a line of its own before anything typed since. */
  fun failed() {
    val text = pending ?: return
    pending = null
    putBack(text)
  }

  /** [text] that didn't reach the agent goes into the field, on a line of its own before anything typed since. */
  fun putBack(text: String) {
    val typed = value
    value =
      if (typed.text.isEmpty()) {
        TextFieldValue(text, TextRange(text.length))
      } else {
        val head = text + "\n"
        TextFieldValue(head + typed.text, TextRange(typed.selection.start + head.length, typed.selection.end + head.length))
      }
  }
}
