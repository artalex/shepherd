package dev.artalex.herdr.core

import dev.artalex.herdr.core.terminal.TermlibInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TermlibInputTest {
  private fun TermlibInput.run(vararg chunks: String) = chunks.joinToString("") { String(filter(it.toByteArray()), Charsets.UTF_8) }

  /** Parameters of every CSI in [text], as libvterm counts them: a subparameter after a colon is one too. */
  private fun csiFields(text: String) = Regex("\u001b\\[([0-?]*)[ -/]*[@-~]").findAll(text).map { m -> m.groupValues[1].count { it == ';' || it == ':' } + 1 }.toList()

  @Test
  fun frameStylesGetADimDefaultColour() {
    // herdr's frames write each style whole: reset, attributes, foreground, background.
    assertEquals(
      "\u001b[0;39;49;38;2;127;127;127mсовет\u001b[0;39;49mввод",
      TermlibInput().run("\u001b[0;2;39;49mсовет\u001b[0;39;49mввод"),
    )
  }

  @Test
  fun snapshotStylesAsClaudeWritesTheSuggestion() {
    // pane.read gives the pane's own sequences: Claude's suggestion is SGR 2 alone, ended by a reset.
    assertEquals("❯ \u001b[38;2;127;127;127m! curl\u001b[0m", TermlibInput().run("❯ \u001b[2m! curl\u001b[0m"))
  }

  @Test
  fun colouredFaintTextKeepsItsHue() {
    assertEquals("\u001b[31;38;2;112;0;0mx", TermlibInput().run("\u001b[31;2mx"))
    // 196 in libvterm's cube is pure red.
    assertEquals("\u001b[38;5;196;38;2;127;0;0mA\u001b[22;38;5;196mB", TermlibInput().run("\u001b[2;38;5;196mA\u001b[22mB"))
    assertEquals("\u001b[38:2::200:100:50;38;2;100;50;25mX", TermlibInput().run("\u001b[2;38:2::200:100:50mX"))
  }

  @Test
  fun aColourChangeWhileFaintStaysDim() {
    assertEquals("\u001b[38;2;127;127;127ma\u001b[32;38;2;0;112;0mb", TermlibInput().run("\u001b[2ma\u001b[32mb"))
  }

  @Test
  fun twosInsideColoursAreNotFaint() {
    assertEquals("\u001b[48;2;2;2;2mX\u001b[38;5;2mY", TermlibInput().run("\u001b[48;2;2;2;2mX\u001b[38;5;2mY"))
  }

  @Test
  fun codexKeyHintAfterFaintTextStaysWithinLibvterm() {
    // Codex's update dialog as herdr streams it: a faint word, then a bold key in two RGB colours. Its colour went in
    // twice once the faint text ended, 17 parameters in all, and the 17th, 255, broke libvterm.
    val out = TermlibInput().run("\u001b[0;2;39;48;2;72;39;62m continue ·\u001b[0;1;38;2;255;255;255;48;2;72;39;62mesc")
    assertEquals("\u001b[0;39;48;2;72;39;62;38;2;127;127;127m continue ·\u001b[0;1;38;2;255;255;255;48;2;72;39;62mesc", out)
    assertTrue(csiFields(out).all { it <= 16 })
  }

  @Test
  fun longStylesAreSplitBetweenWholeAttributes() {
    // herdr writes 17 parameters for every attribute with two RGB colours (reverse video swaps them), and the dim
    // colour comes on top.
    assertEquals(
      "\u001b[0;1;3;4;5;9;38;2;72;39;62;48;2;255;160;0m\u001b[38;2;36;19;31mSTYLED",
      TermlibInput().run("\u001b[0;1;2;3;4;5;9;38;2;72;39;62;48;2;255;160;0mSTYLED"),
    )
    assertEquals(
      "\u001b[0;1;3;4;5;7;9;38;2;1;2;3m\u001b[48;2;4;5;6;58;2;7;8;9mX",
      TermlibInput().run("\u001b[0;1;3;4;5;7;9;38;2;1;2;3;48;2;4;5;6;58;2;7;8;9mX"),
    )
    // Each subparameter after a colon counts.
    assertEquals(
      "\u001b[1;3;4;38:2::1:2:3;48:2::4:5:6m\u001b[58:2::7:8:9mX",
      TermlibInput().run("\u001b[1;3;4;38:2::1:2:3;48:2::4:5:6;58:2::7:8:9mX"),
    )
  }

  @Test
  fun otherSequencesTooLongForLibvtermAreDropped() {
    val sixteen = (1..16).joinToString(";")
    assertEquals("\u001b[${sixteen}Hx", TermlibInput().run("\u001b[${sixteen}Hx"))
    assertEquals("x", TermlibInput().run("\u001b[$sixteen;17Hx"))
    assertEquals("x", TermlibInput().run("\u001b[>${(1..17).joinToString(":")}ux"))
  }

  @Test
  fun sequenceCutShortByAnotherByteIsDropped() {
    // libvterm would run a control inside a CSI and go on reading parameters after it.
    assertEquals("\nx", TermlibInput().run("\u001b[1;2\nx"))
    assertEquals("\u001b[0mx", TermlibInput().run("\u001b[1;2\u001b[0mx"))
    assertEquals("тx", TermlibInput().run("\u001b[1;2тx"))
  }

  @Test
  fun sequenceCutBetweenChunksWaitsForTheRest() {
    val filter = TermlibInput()
    assertEquals("ab", filter.run("ab\u001b["))
    assertEquals("\u001b[38;2;127;127;127mc", filter.run("2mc"))
    assertEquals("", filter.run("\u001b"))
    assertEquals("\u001b[0md", filter.run("[0md"))
  }

  @Test
  fun runawaySequenceIsDropped() {
    val filter = TermlibInput()
    assertEquals("a", filter.run("a\u001b[" + "1;".repeat(200)))
    // What is left of it is text by now.
    assertEquals("1mb", filter.run("1mb"))
  }

  @Test
  fun everythingElsePassesUnchanged() {
    val other = "\u001b[?25l\u001b[3;3H\u001b]8;;https://example.com\u001b\\ссылка\u001b]8;;\u001b\\\u001b[1;4:3;7mжирный\u001b(B"
    assertEquals(other, TermlibInput().run(other))
  }
}
