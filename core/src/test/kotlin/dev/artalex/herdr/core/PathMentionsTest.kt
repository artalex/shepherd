package dev.artalex.herdr.core

import dev.artalex.herdr.core.files.PathMention
import dev.artalex.herdr.core.files.displayWidth
import dev.artalex.herdr.core.files.findPathMentions
import dev.artalex.herdr.core.files.resolvePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PathMentionsTest {
  private fun shown(screen: String, width: Int? = null) = findPathMentions(screen, width).map { it.shown }

  @Test
  fun findsAbsoluteHomeAndRelativePaths() {
    val screen =
      """
      ⏺ Скриншот: ~/Pictures/herdr-android/faint/1-подсказка-claude-приглушённая.png.
        Лог в /tmp/build.log, код в app/src/main/X.kt:42 и (core/build.gradle.kts)
        Read(@app/src/test/Y.kt) и ./run.sh, ../other/notes.md
      """.trimIndent()
    assertEquals(
      listOf(
        "~/Pictures/herdr-android/faint/1-подсказка-claude-приглушённая.png",
        "/tmp/build.log",
        "app/src/main/X.kt",
        "core/build.gradle.kts",
        "app/src/test/Y.kt",
        "./run.sh",
        "../other/notes.md",
      ),
      shown(screen),
    )
  }

  @Test
  fun skipsWhatIsNoFile() {
    val screen =
      """
      Ссылка https://example.com/a/b.png, and/or src/main, 1/2, 24/7
      Сокращённо app/src/…/Y.kt и …/Z.kt, папка ~/devel/x/, ~user/a.txt, просто / и //x.txt
      """.trimIndent()
    assertEquals(emptyList(), shown(screen))
  }

  @Test
  fun eachPathOnce() {
    assertEquals(listOf("/tmp/a.txt"), shown("/tmp/a.txt и снова /tmp/a.txt\n/tmp/a.txt"))
  }

  @Test
  fun pathWrappedByTheTerminalIsJoined() {
    // pane.read at 40 columns: the terminal wraps the line with no indent.
    val screen = "~/Pictures/herdr-android/faint/1-подсказ\nка-claude-приглушённая.png\nuser@host:~$"
    assertEquals(
      listOf(
        PathMention(
          "~/Pictures/herdr-android/faint/1-подсказка-claude-приглушённая.png",
          listOf("~/Pictures/herdr-android/faint/1-подсказка-claude-приглушённая.png", "~/Pictures/herdr-android/faint/1-подсказ"),
        )
      ),
      findPathMentions(screen, 40),
    )
  }

  @Test
  fun pathWrappedByTheAgentIsJoinedOverItsIndent() {
    // Claude breaks a long word itself and indents the rest under its text; the sentence goes on after the path.
    val screen = "⏺ Скриншот: ~/Pictures/herdr-android/fa\n  int/1.png. Готово."
    assertEquals(
      listOf(PathMention("~/Pictures/herdr-android/faint/1.png", listOf("~/Pictures/herdr-android/faint/1.png", "~/Pictures/herdr-android/fa", "int/1.png"))),
      findPathMentions(screen, 40),
    )
  }

  @Test
  fun pathOverThreeRowsTriesEveryStart() {
    val mentions = findPathMentions("x /aaaaaaa\nbbbbbbbbbb\ncc.txt", 10)
    assertEquals(listOf(PathMention("/aaaaaaabbbbbbbbbbcc.txt", listOf("/aaaaaaabbbbbbbbbbcc.txt", "/aaaaaaabbbbbbbbbb", "/aaaaaaa"))), mentions)
  }

  @Test
  fun shortRowsAreNotJoined() {
    assertEquals(listOf("~/a.txt", "~/b.txt"), shown("see ~/a.txt\n~/b.txt", 40))
    // Without the width nothing is joined.
    assertEquals(listOf("~/Pictures/herdr-android/fa", "int/1.png"), shown("⏺ Скриншот: ~/Pictures/herdr-android/fa\n  int/1.png.", null))
  }

  @Test
  fun linkOnTheNextRowIsNotGluedOn() {
    assertEquals(listOf("/tmp/aaaaaaaa.txt"), shown("see /tmp/aaaaaaaa.txt\nhttps://example.com/x", 21))
  }

  @Test
  fun resolvesAgainstHomeAndTheAgentsFolder() {
    assertEquals("/home/u/x/a.png", resolvePath("~/x/a.png", "/home/u", "/w"))
    assertEquals("/w/b.txt", resolvePath("a/../b.txt", "/home/u", "/w"))
    assertEquals("/w/x.txt", resolvePath("./x.txt", "/home/u", "/w/"))
    assertEquals("/a/b/c", resolvePath("/a/./b//c", "/home/u", null))
    assertNull(resolvePath("a/b.txt", "/home/u", null))
  }

  @Test
  fun wideCharactersTakeTwoCells() {
    assertEquals(2, displayWidth("ab"))
    assertEquals(4, displayWidth("漢字"))
    assertEquals(11, displayWidth("⏺ Скриншот:"))
  }
}
