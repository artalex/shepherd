package dev.artalex.herdr.core.terminal

import java.io.ByteArrayOutputStream

/**
 * Terminal output as termlib can take it. Its libvterm lacks two things:
 * - faint text (SGR 2), which it ignores: such text is drawn as a dimmer foreground colour instead. Claude Code shows
 *   its prompt suggestion only as faint text, which there looked as if typed;
 * - a bound on CSI parameters: a 17th one lands on the parser's callback pointer, and the app dies in native code at
 *   the next sequence. herdr writes 17 for a cell with every attribute and two RGB colours, and the dim colour adds
 *   more. A longer SGR is split between whole attributes; any other CSI that long is dropped. (Vim bounds its libvterm
 *   since patch 9.2.0279; termlib 0.3.14 doesn't.)
 *
 * One per byte stream: it keeps the pen between calls, and an escape sequence cut at the end of a chunk waits for the
 * next one. Colours follow libvterm's palette, so a dimmed colour is the drawn one at half brightness; text in the
 * default colour is drawn white, as termlib does by default.
 */
class TermlibInput {
  private var faint = false
  private var fg: Fg = Fg.Default
  private var carry = ByteArray(0)

  fun filter(chunk: ByteArray): ByteArray {
    val input = if (carry.isEmpty()) chunk else carry + chunk
    carry = ByteArray(0)
    val out = ByteArrayOutputStream(input.size + 32)
    var i = 0
    while (i < input.size) {
      if (input[i] != ESC) {
        var text = i
        while (text < input.size && input[text] != ESC) text++
        out.write(input, i, text - i)
        i = text
        continue
      }
      if (i + 1 == input.size) {
        carry = input.copyOfRange(i, input.size)
        break
      }
      if (input[i + 1] != '['.code.toByte()) {
        out.write(input[i].toInt())
        i++
        continue
      }
      // CSI: parameter bytes 0x30–0x3F, intermediate bytes 0x20–0x2F, a final byte 0x40–0x7E.
      var j = i + 2
      while (j < input.size && input[j] in 0x30..0x3F) j++
      val paramsEnd = j
      while (j < input.size && input[j] in 0x20..0x2F) j++
      if (j == input.size) {
        // Unfinished. A runaway one is dropped rather than piling up: none that long fits libvterm.
        if (input.size - i <= MAX_CARRY) carry = input.copyOfRange(i, input.size)
        break
      }
      val params = String(input, i + 2, paramsEnd - i - 2, Charsets.US_ASCII)
      when {
        // Cut short by some other byte, which libvterm may read the parameters on past: the sequence goes, the byte stays.
        input[j] !in 0x40..0x7E -> {
          i = j
          continue
        }
        input[j] == 'm'.code.toByte() && paramsEnd == j && params.all { it.isDigit() || it == ';' || it == ':' } -> out.write(sgr(params))
        fields(params) > MAX_FIELDS -> Unit
        else -> out.write(input, i, j + 1 - i)
      }
      i = j + 1
    }
    return out.toByteArray()
  }

  private fun sgr(params: String): ByteArray {
    val wasFaint = faint
    var fgSet = false
    val parts = params.split(';')
    // Whole attributes, a colour with its arguments: the sequence may be split between them.
    val attributes = mutableListOf<String>()
    var k = 0
    while (k < parts.size) {
      val part = parts[k]
      val code = part.substringBefore(':').toIntOrNull() ?: 0
      when {
        code == 0 -> {
          faint = false
          fg = Fg.Default
          fgSet = true
          attributes += part
        }
        code == 2 && part == "2" -> faint = true
        code == 22 -> {
          faint = false
          attributes += part
        }
        code in 30..37 -> {
          fg = Fg.Palette(code - 30)
          fgSet = true
          attributes += part
        }
        code in 90..97 -> {
          fg = Fg.Palette(code - 90 + 8)
          fgSet = true
          attributes += part
        }
        code == 39 -> {
          fg = Fg.Default
          fgSet = true
          attributes += part
        }
        // Extended colours: their arguments follow as more parameters, unless written with colons.
        code == 38 || code == 48 || code == 58 -> {
          val colons = ':' in part
          val args = if (colons) part.split(':').drop(1).filter { it.isNotEmpty() } else extendedArgs(parts, k)
          if (code == 38) {
            extendedColour(args)?.let {
              fg = it
              fgSet = true
            }
          }
          attributes += if (colons) part else parts.subList(k, k + 1 + args.size).joinToString(";")
          if (!colons) k += args.size
        }
        else -> attributes += part
      }
      k++
    }
    // Faint text ending takes its colour back, unless the sequence sets one itself.
    if (faint) attributes += dimmed(fg) else if (wasFaint && !fgSet) attributes += fg.sgr()
    return sequences(attributes)
  }

  /** SGR sequences of [attributes] in order, as few as libvterm's [MAX_FIELDS] parameters each allow. */
  private fun sequences(attributes: List<String>): ByteArray {
    val out = ByteArrayOutputStream()
    val sequence = mutableListOf<String>()
    var used = 0
    fun flush() {
      if (sequence.isEmpty()) return
      out.write("\u001b[${sequence.joinToString(";")}m".toByteArray(Charsets.US_ASCII))
      sequence.clear()
      used = 0
    }
    for (attribute in attributes) {
      val n = fields(attribute)
      // No sequence can carry it.
      if (n > MAX_FIELDS) continue
      if (used + n > MAX_FIELDS) flush()
      sequence += attribute
      used += n
    }
    flush()
    return out.toByteArray()
  }

  private fun extendedArgs(parts: List<String>, k: Int): List<String> =
    when (parts.getOrNull(k + 1)) {
      "5" -> parts.subList(k + 1, minOf(k + 3, parts.size))
      "2" -> parts.subList(k + 1, minOf(k + 5, parts.size))
      else -> emptyList()
    }

  private fun extendedColour(args: List<String>): Fg? {
    val values = args.map { it.toIntOrNull() ?: return null }
    return when {
      values.size == 2 && values[0] == 5 -> Fg.Palette(values[1].coerceIn(0, 255))
      values.size == 4 && values[0] == 2 -> Fg.Rgb(values[1], values[2], values[3])
      else -> null
    }
  }

  private fun dimmed(colour: Fg): String {
    val rgb = colour.rgb()
    fun half(shift: Int) = (rgb shr shift and 0xFF) / 2
    return "38;2;${half(16)};${half(8)};${half(0)}"
  }

  private sealed interface Fg {
    fun sgr(): String

    fun rgb(): Int

    data object Default : Fg {
      override fun sgr() = "39"

      override fun rgb() = 0xFFFFFF
    }

    data class Palette(val index: Int) : Fg {
      override fun sgr() = "38;5;$index"

      override fun rgb() = paletteRgb(index)
    }

    data class Rgb(val r: Int, val g: Int, val b: Int) : Fg {
      override fun sgr() = "38;2;$r;$g;$b"

      override fun rgb() = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
  }

  private companion object {
    const val ESC = 0x1b.toByte()
    const val MAX_CARRY = 256

    /** Parameters libvterm keeps for a CSI (CSI_ARGS_MAX); a subparameter after a colon takes one too. */
    const val MAX_FIELDS = 16

    fun fields(params: String) = params.count { it == ';' || it == ':' } + 1

    /** libvterm's 16 ANSI colours (pen.c), which termlib keeps. */
    val ANSI =
      intArrayOf(
        0x000000, 0xE00000, 0x00E000, 0xE0E000, 0x0000E0, 0xE000E0, 0x00E0E0, 0xE0E0E0,
        0x808080, 0xFF4040, 0x40FF40, 0xFFFF40, 0x4040FF, 0xFF40FF, 0x40FFFF, 0xFFFFFF,
      )
    val RAMP6 = intArrayOf(0x00, 0x33, 0x66, 0x99, 0xCC, 0xFF)
    val RAMP24 =
      intArrayOf(
        0x00, 0x0B, 0x16, 0x21, 0x2C, 0x37, 0x42, 0x4D, 0x58, 0x63, 0x6E, 0x79,
        0x85, 0x90, 0x9B, 0xA6, 0xB1, 0xBC, 0xC7, 0xD2, 0xDD, 0xE8, 0xF3, 0xFF,
      )

    fun paletteRgb(index: Int): Int =
      when (index) {
        in 0..15 -> ANSI[index]
        in 16..231 -> (index - 16).let { (RAMP6[it / 36] shl 16) or (RAMP6[it / 6 % 6] shl 8) or RAMP6[it % 6] }
        else -> RAMP24[index - 232].let { (it shl 16) or (it shl 8) or it }
      }
  }
}
