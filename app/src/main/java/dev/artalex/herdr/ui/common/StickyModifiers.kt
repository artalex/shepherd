package dev.artalex.herdr.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Ctrl, Alt and Shift buttons that stay on until the next key, like on a hardware keyboard row. The screens apply them
 * themselves ([PaneKey]): termlib's libvterm would drop Shift from a character, and doesn't know whether the program
 * asked for the kitty keyboard protocol.
 */
class StickyModifiers {
  var ctrl by mutableStateOf(false)
  var alt by mutableStateOf(false)
  var shift by mutableStateOf(false)

  /** The modifiers that are on. */
  fun held(): KeyModifiers = KeyModifiers(ctrl, alt, shift)

  /** The modifiers that are on, for the key pressed now: after it they are off. */
  fun take(): KeyModifiers =
    held().also {
      ctrl = false
      alt = false
      shift = false
    }
}
