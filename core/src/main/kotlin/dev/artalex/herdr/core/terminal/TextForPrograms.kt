package dev.artalex.herdr.core.terminal

/**
 * [text] for a program, as a prompt for an agent or a paste into a terminal: as text alone, with nothing a program
 * would take for keys. Keys come to a program as control characters, and these go:
 * - ESC, which starts the sequences of most keys. One that ends a bracketed paste early would have the rest of the text
 *   come as typed keys, Shift+Tab or Esc;
 * - the rest of C0, such as ETX, which is Ctrl+C, and with them DEL, which is Backspace;
 * - C1, the eight-bit controls: CSI among them stands for ESC [.
 *
 * Line breaks stay, as line feeds. A carriage return is what Enter sends; in text it ends a line, as in text from
 * Windows or an old Mac, and takes along a line feed that follows it. The rarer breaks among the control characters
 * would run the words on both sides of them together if they went: VT, a line break in PowerPoint; FF, a new page from
 * a PDF; FS, GS and RS; NEL of C1. A tab stays too, as text has it. Anything else is text and stays as it is, the marks
 * of bidirectional text too: they are no keys to a program, and text in Arabic or Hebrew needs them.
 */
fun textForPrograms(text: String): String {
  val out = StringBuilder(text.length)
  var i = 0
  while (i < text.length) {
    val c = text[i]
    when {
      c == '\r' -> {
        out.append('\n')
        if (text.getOrNull(i + 1) == '\n') i++
      }
      c in LINE_BREAKS -> out.append('\n')
      c == '\t' -> out.append(c)
      c.isISOControl() -> Unit
      else -> out.append(c)
    }
    i++
  }
  return out.toString()
}

/**
 * Whether [text] leaves a program nothing, once [textForPrograms] has made it text: it is blank then, or empty. herdr
 * refuses an empty prompt, and would paste a blank one with Enter after it, and Enter sends whatever the agent's own
 * field holds. Such text isn't for sending.
 */
fun blankForPrograms(text: String): Boolean = textForPrograms(text).isBlank()

/** The line breaks of C0 and C1 but CR, as Python's str.splitlines has them: LF, VT, FF, FS, GS, RS and NEL. */
private const val LINE_BREAKS = "\n\u000b\u000c\u001c\u001d\u001e\u0085"
