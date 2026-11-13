package dev.artalex.herdr.core

import dev.artalex.herdr.core.terminal.blankForPrograms
import dev.artalex.herdr.core.terminal.textForPrograms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextForProgramsTest {
  @Test
  fun theEndOfAPasteAndTheKeysAfterItStayText() {
    // A share that would end herdr's paste early, then switch Claude Code's mode with Shift+Tab and press Esc.
    assertEquals("look at this[201~[Zx", textForPrograms("look at this\u001b[201~\u001b[Z\u001bx"))
  }

  @Test
  fun controlCharactersGo() {
    val lineBreaks = listOf(0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x85)
    val controls = (0x00..0x1f) + 0x7f + (0x80..0x9f) - lineBreaks - 0x09
    for (code in controls) assertEquals("ab", textForPrograms("a${code.toChar()}b"), "U+%04X".format(code))
  }

  @Test
  fun tabsAndLineFeedsStay() {
    assertEquals("a\tb\nc\n\n", textForPrograms("a\tb\nc\n\n"))
  }

  @Test
  fun carriageReturnsEndLinesAsLineFeeds() {
    // Windows ends a line with CR LF, an old Mac with CR alone.
    assertEquals("a\nb\nc\n\nd\n", textForPrograms("a\r\nb\rc\r\r\nd\r"))
  }

  @Test
  fun otherLineBreaksBecomeLineFeedsToo() {
    // A line break in PowerPoint is VT, a new page from a PDF is FF; FS, GS, RS and NEL end lines too.
    assertEquals("first line\nsecond line", textForPrograms("first line\u000bsecond line"))
    assertEquals("end of page\nstart of page", textForPrograms("end of page\u000cstart of page"))
    assertEquals("a\nb\nc\nd", textForPrograms("a\u001cb\u001dc\u001ed"))
    assertEquals("one\ntwo", textForPrograms("one\u0085two"))
  }

  @Test
  fun textOfEveryScriptStaysAsItIs() {
    // Cyrillic, an emoji of three joined, Hebrew with a right-to-left mark, an isolate and an override of direction, a
    // line separator, and ~ and the no-break space, the characters next to DEL and C1.
    val text = "привет 👩\u200d💻 שלום\u200f \u2067abc\u2069 \u202eabc\u202c a\u2028b ~\u00a0"
    assertEquals(text, textForPrograms(text))
  }

  @Test
  fun textOfControlCharactersAloneIsBlank() {
    // As a share may bring them, with the space the prompt puts after shared text, or a line end.
    assertTrue(blankForPrograms("\u001b\u0003\u0007"))
    assertTrue(blankForPrograms("\u001b\u0003 "))
    assertTrue(blankForPrograms("\u009b\r\n\t"))
    assertFalse(blankForPrograms("\u001bx"))
  }
}
