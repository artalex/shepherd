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
      Чужой хост file://server/share/a.txt, папка file:///home/u/dir/, сокращённо file:///home/u/…/a.png
      """.trimIndent()
    assertEquals(emptyList(), shown(screen))
  }

  @Test
  fun fileLinksStandForTheirPaths() {
    val screen =
      """
      Сохранил: file:///home/u/.codex/generated_images/t1/exec-1.png, копия <file://localhost/tmp/a.txt>
      FILE:///tmp/B.TXT и file:///home/u/%D0%A4%D0%BE%D1%82%D0%BE%20%E2%84%961.png.
      """.trimIndent()
    assertEquals(
      listOf("/home/u/.codex/generated_images/t1/exec-1.png", "/tmp/a.txt", "/tmp/B.TXT", "/home/u/Фото №1.png"),
      shown(screen),
    )
  }

  @Test
  fun fileLinkCodexBrokeAfterSlashesIsJoined() {
    // Codex 0.164 at 56 columns, where it saved a picture: it breaks the link after a slash, well before the edge. The
    // rows are as long as on a real screen; the user's name and the ids are made up.
    val screen =
      """
      • Generated Image:
        └ A red bicycle by a wall
        └ Saved to: file:///home/someone/.codex/
      generated_images/019a2b3c-4d5e-7f60-8a1b-2c3d4e5f6a7b/
      exec-5f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0.png
      """.trimIndent()
    val path = "/home/someone/.codex/generated_images/019a2b3c-4d5e-7f60-8a1b-2c3d4e5f6a7b/exec-5f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0.png"
    assertEquals(listOf(PathMention(path, listOf(path))), findPathMentions(screen, 56))
  }

  @Test
  fun pathCodexBrokeAfterASlashAndAHyphenIsJoined() {
    // Codex breaks a long word where a line may break, after a slash or a hyphen, as soon as the rest doesn't fit.
    val screen =
      """
      • Картинка: /home/u/devel/photo-book/output/
        imagegen/2026-10-27/retouched-portrait-final-
        version.png готова.
      """.trimIndent()
    val path = "/home/u/devel/photo-book/output/imagegen/2026-10-27/retouched-portrait-final-version.png"
    assertEquals(
      listOf(PathMention(path, listOf(path, "/home/u/devel/photo-book/output/imagegen/2026-10-27/retouched-portrait-final-"))),
      findPathMentions(screen, 50),
    )
  }

  @Test
  fun lineEndingInAFolderOrAHyphenDoesntRunIntoTheNext() {
    val screen =
      """
      - Скриншоты: /home/u/Pictures/links/
      - Готово, что-
      то ещё: /tmp/a.txt
      """.trimIndent()
    assertEquals(listOf("/tmp/a.txt"), shown(screen, 60))
  }

  @Test
  fun fileLinkWithANameWiderThanARowIsJoined() {
    // The name doesn't fit after the slash, nor in a row of its own: its rest goes on, cut in the middle of an escape.
    val screen = "  └ Saved to: file:///tmp/x/\n%D0%BA%D0%B0%D1%80%D1%82%D0%B8%D0%BD%D0%\nBA%D0%B0.png"
    assertEquals(listOf("/tmp/x/картинка.png"), shown(screen, 40))
  }

  @Test
  fun joinedWordThatIsNoPathGivesItsPieces() {
    val mentions = findPathMentions("Папка /home/u/dir/\n/tmp/aaaaaaaaaa.txt", 25)
    assertEquals(listOf(PathMention("/tmp/aaaaaaaaaa.txt", listOf("/tmp/aaaaaaaaaa.txt"))), mentions)
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
