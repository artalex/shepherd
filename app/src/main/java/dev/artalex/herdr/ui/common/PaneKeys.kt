package dev.artalex.herdr.ui.common

import android.view.KeyEvent
import org.connectbot.terminal.VTermKey

/** Ctrl, Alt and Shift with a key: held on a keyboard, or set ahead with the buttons of a row. */
data class KeyModifiers(val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) {
  /** Ctrl or Alt: the key is for the program, never text. */
  val command: Boolean
    get() = ctrl || alt

  val any: Boolean
    get() = ctrl || alt || shift

  /** libvterm's mask of them, as termlib's dispatchKey takes it. */
  val mask: Int
    get() = (if (shift) MOD_SHIFT else 0) or (if (alt) MOD_ALT else 0) or (if (ctrl) MOD_CTRL else 0)

  operator fun plus(other: KeyModifiers) = KeyModifiers(ctrl || other.ctrl, alt || other.alt, shift || other.shift)

  companion object {
    val NONE = KeyModifiers()

    private const val MOD_SHIFT = 1
    private const val MOD_ALT = 2
    private const val MOD_CTRL = 4
  }
}

/**
 * A key for the program in a pane. A key with modifiers goes to herdr by name: herdr alone knows whether the program
 * asked for the kitty keyboard protocol, and encodes the key for it as herdr's own client does. Ctrl+Shift+C reaches
 * Claude Code as such, and a shell as Ctrl+C. termlib can't tell: its libvterm drops Shift from a letter, and has
 * escape sequences for keys a shell takes for plain ones.
 */
sealed interface PaneKey {
  /** herdr's name for the key, modifiers first: `ctrl+shift+c`, `alt+enter`. */
  data class Named(val name: String) : PaneKey

  /**
   * Home, End, PgUp, PgDn, Insert and Delete, which herdr's API has no names for: typed into the live stream as libvterm
   * encodes them, with [modifiers] as its mask. With modifiers or without, they come out the same for a program with
   * the kitty keyboard protocol and one without.
   */
  data class Typed(val vtermKey: Int, val modifiers: Int) : PaneKey
}

/** herdr's name for [key] (`enter`, `c`, `f5`) with [mods] in front of it: `ctrl+alt+shift+c`. */
fun keyName(key: String, mods: KeyModifiers): String =
  (listOfNotNull("ctrl".takeIf { mods.ctrl }, "alt".takeIf { mods.alt }, "shift".takeIf { mods.shift }) + key).joinToString("+")

/** libvterm key [vtermKey] with [mods]: by name, or typed into the stream when herdr's API has no name for it. */
fun paneKey(vtermKey: Int, mods: KeyModifiers): PaneKey =
  VTERM_NAMES[vtermKey]?.let { PaneKey.Named(keyName(it, mods)) } ?: PaneKey.Typed(vtermKey, mods.mask)

/**
 * The character [char] typed with [mods], as a key. A capital letter is the small one with Shift. A Cyrillic letter
 * stands for the Latin one on its key of the Russian layout, as on a desktop terminal: Ctrl with С is Ctrl+C. herdr
 * would send any other character with Ctrl as a kitty sequence even to a shell.
 */
fun charKey(char: Int, mods: KeyModifiers): PaneKey.Named {
  val small = Character.toLowerCase(char)
  val key = CYRILLIC_KEYS[small]?.code ?: small
  val name =
    when (key) {
      '\n'.code, '\r'.code -> "enter"
      '\t'.code -> "tab"
      ' '.code -> "space"
      // herdr splits names at the plus.
      '+'.code -> "plus"
      else -> String(Character.toChars(key))
    }
  return PaneKey.Named(keyName(name, mods.copy(shift = mods.shift || small != char)))
}

/**
 * What pressing [keyCode] with [mods] is to the program when it is a key rather than text: with Ctrl or Alt, or Shift
 * with a key that types nothing (Shift+Tab, Shift+Space). [char] is what the key types without modifiers: 0 if nothing,
 * negative for a dead key. Null otherwise, also without modifiers: the screen types those as before.
 */
fun pressedKey(keyCode: Int, char: Int, mods: KeyModifiers): PaneKey? {
  if (!mods.any) return null
  KEYCODE_VTERM[keyCode]?.let { return paneKey(it, mods) }
  if (keyCode == KeyEvent.KEYCODE_SPACE) return charKey(' '.code, mods)
  if (!mods.command || char <= 0) return null
  // A key of a layout without Latin letters stands for its Latin letter, as the key's code tells it.
  val latin = if (char >= 0x80 && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) 'a'.code + keyCode - KeyEvent.KEYCODE_A else char
  return charKey(latin, mods)
}

/** The modifiers held with [event]. Right Alt types the characters of a layout (AltGr), as termlib takes it. */
fun KeyEvent.heldModifiers(): KeyModifiers {
  val leftAlt = metaState and KeyEvent.META_ALT_LEFT_ON != 0 || (metaState and KeyEvent.META_ALT_ON != 0 && metaState and KeyEvent.META_ALT_RIGHT_ON == 0)
  return KeyModifiers(ctrl = metaState and KeyEvent.META_CTRL_ON != 0, alt = leftAlt, shift = metaState and KeyEvent.META_SHIFT_ON != 0)
}

/** [event] with the modifiers set ahead in [held], when it is a key for the program; see [pressedKey]. */
fun pressedKey(event: KeyEvent, held: KeyModifiers): PaneKey? = pressedKey(event.keyCode, event.getUnicodeChar(0), event.heldModifiers() + held)

private val VTERM_NAMES =
  mapOf(
    VTermKey.ENTER to "enter", VTermKey.TAB to "tab", VTermKey.BACKSPACE to "backspace", VTermKey.ESCAPE to "esc",
    VTermKey.UP to "up", VTermKey.DOWN to "down", VTermKey.LEFT to "left", VTermKey.RIGHT to "right",
  ) + (1..12).associate { VTermKey.FUNCTION_0 + it to "f$it" }

private val KEYCODE_VTERM =
  mapOf(
    KeyEvent.KEYCODE_ENTER to VTermKey.ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER to VTermKey.ENTER, KeyEvent.KEYCODE_TAB to VTermKey.TAB,
    KeyEvent.KEYCODE_DEL to VTermKey.BACKSPACE, KeyEvent.KEYCODE_ESCAPE to VTermKey.ESCAPE,
    KeyEvent.KEYCODE_DPAD_UP to VTermKey.UP, KeyEvent.KEYCODE_DPAD_DOWN to VTermKey.DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT to VTermKey.LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to VTermKey.RIGHT,
    KeyEvent.KEYCODE_MOVE_HOME to VTermKey.HOME, KeyEvent.KEYCODE_MOVE_END to VTermKey.END,
    KeyEvent.KEYCODE_PAGE_UP to VTermKey.PAGEUP, KeyEvent.KEYCODE_PAGE_DOWN to VTermKey.PAGEDOWN,
    KeyEvent.KEYCODE_INSERT to VTermKey.INS, KeyEvent.KEYCODE_FORWARD_DEL to VTermKey.DEL,
  ) + (1..12).associate { KeyEvent.KEYCODE_F1 + it - 1 to VTermKey.FUNCTION_0 + it }

/** Cyrillic letters and the Latin keys they share in the Russian ЙЦУКЕН layout. */
private val CYRILLIC_KEYS: Map<Int, Char> =
  ("йq цw уe кr еt нy гu шi щo зp х[ ъ] фa ыs вd аf пg рh оj лk дl ж; э' яz чx сc мv иb тn ьm б, ю. ё`")
    .split(' ')
    .associate { it[0].code to it[1] }
