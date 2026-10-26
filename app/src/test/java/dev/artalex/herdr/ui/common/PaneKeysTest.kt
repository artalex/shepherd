package dev.artalex.herdr.ui.common

import android.view.KeyEvent
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneKeysTest {
  private val ctrl = KeyModifiers(ctrl = true)
  private val alt = KeyModifiers(alt = true)
  private val shift = KeyModifiers(shift = true)
  private val ctrlShift = KeyModifiers(ctrl = true, shift = true)

  private fun named(name: String) = PaneKey.Named(name)

  @Test
  fun modifiersComeBeforeTheKeyInHerdrsOrder() {
    assertEquals("ctrl+alt+shift+c", keyName("c", KeyModifiers(ctrl = true, alt = true, shift = true)))
    assertEquals("enter", keyName("enter", KeyModifiers.NONE))
  }

  @Test
  fun capitalIsTheSmallLetterWithShift() {
    assertEquals(named("ctrl+shift+c"), charKey('C'.code, ctrl))
    assertEquals(named("ctrl+shift+c"), charKey('c'.code, ctrlShift))
    assertEquals(named("alt+x"), charKey('x'.code, alt))
  }

  @Test
  fun cyrillicLettersAreTheLatinKeysTheyShare() {
    // Ctrl with С on the Russian layout is Ctrl+C, as on a desktop terminal.
    assertEquals(named("ctrl+c"), charKey('с'.code, ctrl))
    assertEquals(named("alt+shift+f"), charKey('А'.code, alt))
    assertEquals(named("ctrl+["), charKey('х'.code, ctrl))
    assertEquals(named("ctrl+`"), charKey('ё'.code, ctrl))
  }

  @Test
  fun charactersHerdrNamesOtherwise() {
    assertEquals(named("ctrl+space"), charKey(' '.code, ctrl))
    assertEquals(named("alt+plus"), charKey('+'.code, alt))
    assertEquals(named("alt+enter"), charKey('\n'.code, alt))
    assertEquals(named("ctrl+enter"), charKey('\r'.code, ctrl))
    assertEquals(named("ctrl+tab"), charKey('\t'.code, ctrl))
    assertEquals(named("ctrl+/"), charKey('/'.code, ctrl))
    assertEquals(named("ctrl+1"), charKey('1'.code, ctrl))
  }

  @Test
  fun keysWithoutHerdrNamesAreTypedWithTheirMask() {
    assertEquals(PaneKey.Typed(VTermKey.END, 4), paneKey(VTermKey.END, ctrl))
    assertEquals(PaneKey.Typed(VTermKey.HOME, 5), paneKey(VTermKey.HOME, ctrlShift))
    assertEquals(PaneKey.Typed(VTermKey.PAGEUP, 2), paneKey(VTermKey.PAGEUP, alt))
    assertEquals(PaneKey.Typed(VTermKey.PAGEDOWN, 0), paneKey(VTermKey.PAGEDOWN, KeyModifiers.NONE))
  }

  @Test
  fun keysWithHerdrNamesGoByName() {
    assertEquals(named("alt+enter"), paneKey(VTermKey.ENTER, alt))
    assertEquals(named("shift+tab"), paneKey(VTermKey.TAB, shift))
    assertEquals(named("ctrl+shift+up"), paneKey(VTermKey.UP, ctrlShift))
    assertEquals(named("shift+f5"), paneKey(VTermKey.FUNCTION_0 + 5, shift))
    assertEquals(named("ctrl+esc"), paneKey(VTermKey.ESCAPE, ctrl))
    assertEquals(named("ctrl+backspace"), paneKey(VTermKey.BACKSPACE, ctrl))
  }

  @Test
  fun pressedKeysWithCtrlOrAltAreKeys() {
    assertEquals(named("ctrl+shift+c"), pressedKey(KeyEvent.KEYCODE_C, 'c'.code, ctrlShift))
    assertEquals(named("ctrl+o"), pressedKey(KeyEvent.KEYCODE_O, 'o'.code, ctrl))
    assertEquals(named("ctrl+shift+1"), pressedKey(KeyEvent.KEYCODE_1, '1'.code, ctrlShift))
    assertEquals(PaneKey.Typed(VTermKey.END, 4), pressedKey(KeyEvent.KEYCODE_MOVE_END, 0, ctrl))
    assertEquals(named("ctrl+right"), pressedKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0, ctrl))
  }

  @Test
  fun aKeyOfAKeyboardWithoutLatinLettersIsItsLatinLetter() {
    // A Greek layout: the key of C types ψ.
    assertEquals(named("ctrl+c"), pressedKey(KeyEvent.KEYCODE_C, 'ψ'.code, ctrl))
    assertEquals(named("alt+c"), pressedKey(KeyEvent.KEYCODE_C, 'с'.code, alt))
  }

  @Test
  fun shiftAloneIsAKeyOnlyWhereItTypesNothing() {
    assertEquals(named("shift+tab"), pressedKey(KeyEvent.KEYCODE_TAB, '\t'.code, shift))
    assertEquals(named("shift+space"), pressedKey(KeyEvent.KEYCODE_SPACE, ' '.code, shift))
    assertEquals(named("shift+enter"), pressedKey(KeyEvent.KEYCODE_ENTER, '\n'.code, shift))
    // A capital or a symbol: text, typed as before.
    assertNull(pressedKey(KeyEvent.KEYCODE_C, 'c'.code, shift))
    assertNull(pressedKey(KeyEvent.KEYCODE_1, '1'.code, shift))
  }

  @Test
  fun keysWithoutModifiersAndKeysOfNothingStayWithTheScreen() {
    assertNull(pressedKey(KeyEvent.KEYCODE_C, 'c'.code, KeyModifiers.NONE))
    assertNull(pressedKey(KeyEvent.KEYCODE_ENTER, '\n'.code, KeyModifiers.NONE))
    assertNull(pressedKey(KeyEvent.KEYCODE_CTRL_LEFT, 0, ctrl))
    // A dead key, waiting for the letter of its accent.
    assertNull(pressedKey(KeyEvent.KEYCODE_GRAVE, 0x80000060.toInt(), ctrl))
  }

  @Test
  fun modifiersAddUp() {
    assertEquals(ctrlShift, ctrl + shift)
    assertEquals(4 or 1, ctrlShift.mask)
  }
}
