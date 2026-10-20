package dev.artalex.herdr.core

import dev.artalex.herdr.core.terminal.FaintToColor
import kotlin.test.Test
import kotlin.test.assertEquals

class FaintToColorTest {
  private fun FaintToColor.run(vararg chunks: String) = chunks.joinToString("") { String(filter(it.toByteArray()), Charsets.UTF_8) }

  @Test
  fun frameStylesGetADimDefaultColour() {
    // herdr's frames write each style whole: reset, attributes, foreground, background.
    assertEquals(
      "\u001b[0;39;49;38;2;127;127;127mсовет\u001b[0;39;49;39mввод",
      FaintToColor().run("\u001b[0;2;39;49mсовет\u001b[0;39;49mввод"),
    )
  }

  @Test
  fun snapshotStylesAsClaudeWritesTheSuggestion() {
    // pane.read gives the pane's own sequences: Claude's suggestion is SGR 2 alone, ended by a reset.
    assertEquals("❯ \u001b[38;2;127;127;127m! curl\u001b[0;39m", FaintToColor().run("❯ \u001b[2m! curl\u001b[0m"))
  }

  @Test
  fun colouredFaintTextKeepsItsHue() {
    assertEquals("\u001b[31;38;2;112;0;0mx", FaintToColor().run("\u001b[31;2mx"))
    // 196 in libvterm's cube is pure red.
    assertEquals("\u001b[38;5;196;38;2;127;0;0mA\u001b[22;38;5;196mB", FaintToColor().run("\u001b[2;38;5;196mA\u001b[22mB"))
    assertEquals("\u001b[38:2::200:100:50;38;2;100;50;25mX", FaintToColor().run("\u001b[2;38:2::200:100:50mX"))
  }

  @Test
  fun aColourChangeWhileFaintStaysDim() {
    assertEquals("\u001b[38;2;127;127;127ma\u001b[32;38;2;0;112;0mb", FaintToColor().run("\u001b[2ma\u001b[32mb"))
  }

  @Test
  fun twosInsideColoursAreNotFaint() {
    assertEquals("\u001b[48;2;2;2;2mX\u001b[38;5;2mY", FaintToColor().run("\u001b[48;2;2;2;2mX\u001b[38;5;2mY"))
  }

  @Test
  fun sequenceCutBetweenChunksWaitsForTheRest() {
    val filter = FaintToColor()
    assertEquals("ab", filter.run("ab\u001b["))
    assertEquals("\u001b[38;2;127;127;127mc", filter.run("2mc"))
    assertEquals("", filter.run("\u001b"))
    assertEquals("\u001b[0;39md", filter.run("[0md"))
  }

  @Test
  fun everythingElsePassesUnchanged() {
    val other = "\u001b[?25l\u001b[3;3H\u001b]8;;https://example.com\u001b\\ссылка\u001b]8;;\u001b\\\u001b[1;4:3;7mжирный\u001b(B"
    assertEquals(other, FaintToColor().run(other))
  }
}
