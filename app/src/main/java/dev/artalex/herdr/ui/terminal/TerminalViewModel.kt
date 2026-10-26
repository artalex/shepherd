package dev.artalex.herdr.ui.terminal

import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.lifecycle.ViewModel
import dev.artalex.herdr.Server
import dev.artalex.herdr.core.terminal.CloseReason
import dev.artalex.herdr.ui.common.StickyModifiers
import dev.artalex.herdr.ui.common.charKey
import dev.artalex.herdr.ui.common.paneKey
import dev.artalex.herdr.ui.common.pressedKey
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

sealed interface TerminalPhase {
  /** The terminal view hasn't measured itself yet; the stream opens with its size. */
  data object Measuring : TerminalPhase

  data object Connecting : TerminalPhase

  data object Live : TerminalPhase

  /** Another client controls the pane; taking over is the user's call. */
  data object NeedsTakeover : TerminalPhase

  data class Closed(val reason: CloseReason) : TerminalPhase

  data class Failed(val message: String) : TerminalPhase
}

/**
 * The terminal screen of one pane: its [stream], shared with the pane's agent screen, and the extra keys row.
 *
 * A key with modifiers, from the row, a keyboard or the soft keyboard, goes to herdr by name ([PaneKey]); keys and text
 * without them termlib types into the stream, as it encodes them. The row's [modifiers] are the screen's own: termlib
 * doesn't see them.
 */
class TerminalViewModel(server: Server, paneId: String) : ViewModel() {
  val stream = server.streams.of(paneId)

  val modifiers = StickyModifiers()

  /** A key of the extra row. The row waits for a live stream, so the emulator is there. */
  fun sendKey(vtermKey: Int) {
    val mods = modifiers.take()
    if (mods.any) stream.sendKey(paneKey(vtermKey, mods)) else stream.emulator.value?.dispatchKey(0, vtermKey)
  }

  /** A character of the extra row; with Shift alone it stays itself, as the row has no other one for it. */
  fun sendChar(char: Char) {
    val mods = modifiers.take()
    if (mods.command) stream.sendKey(charKey(char.code, mods)) else stream.emulator.value?.dispatchCharacter(0, char.code)
  }

  /**
   * A key event before termlib handles it: from a keyboard, or a key of the soft keyboard. With modifiers, its own or
   * set in the row, it goes by name; with the row's Shift alone a key that types gives its character with Shift. termlib
   * doesn't see it then, and the answer is true.
   */
  fun interceptKey(event: ComposeKeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val native = event.nativeKeyEvent
    val held = modifiers.held()
    val key = pressedKey(native, held)
    if (key != null) {
      modifiers.take()
      stream.sendKey(key)
      return true
    }
    val shifted = if (held.shift) native.getUnicodeChar(native.metaState or AndroidKeyEvent.META_SHIFT_ON) else 0
    if (shifted <= 0) return false
    modifiers.take()
    stream.emulator.value?.dispatchCharacter(0, shifted)
    return true
  }

  /**
   * Text termlib is about to send while a modifier of the row is on: a soft keyboard hands some characters over as text
   * rather than as keys, Cyrillic ones always. With Ctrl or Alt one character goes as a key instead, with Shift alone as
   * a capital; true says so.
   */
  fun takeText(text: String): Boolean {
    val mods = modifiers.held()
    if (!mods.any || text.isEmpty()) return false
    val char = text.codePointAt(0)
    if (Character.charCount(char) != text.length) return false
    modifiers.take()
    if (mods.command || Character.isISOControl(char)) stream.sendKey(charKey(char, mods)) else stream.emulator.value?.dispatchCharacter(0, Character.toUpperCase(char))
    return true
  }

  fun paste(text: String) = stream.paste(text)
}
