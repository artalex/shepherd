package dev.artalex.herdr.core

import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.snippets.Snippet
import dev.artalex.herdr.core.snippets.SnippetList
import dev.artalex.herdr.core.snippets.onStart
import dev.artalex.herdr.core.snippets.tidy
import dev.artalex.herdr.core.snippets.withSnippet
import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetsTest {
  @Test
  fun listRoundTripsAndUnreadableTextIsNoSnippets() {
    val snippets = listOf(Snippet("/effort max", onStart = "claude"), Snippet("продолжай"), Snippet("посмотри\nлоги"))
    assertEquals(snippets, SnippetList.decode(SnippetList.encode(snippets)))
    assertEquals(emptyList(), SnippetList.decode(null))
    assertEquals(emptyList(), SnippetList.decode("{broken"))
    assertEquals(emptyList(), SnippetList.decode("""{"snippets":[]}"""))
    // Fields of a later version don't keep the rest from loading.
    assertEquals(listOf(Snippet("/compact")), SnippetList.decode("""[{"text":"/compact","color":"red"}]"""))
  }

  @Test
  fun plainTextsOfEarlierVersionsStillRead() {
    assertEquals(listOf(Snippet("/effort max"), Snippet("продолжай")), SnippetList.decode("""["/effort max","продолжай"]"""))
  }

  @Test
  fun savedTextGoesLastWithoutItsSpaces() {
    assertEquals(listOf(Snippet("/compact"), Snippet("/effort max")), listOf(Snippet("/compact")).withSnippet("  /effort max\n"))
  }

  @Test
  fun blankTextOrOneAlreadyThereChangesNothing() {
    val snippets = listOf(Snippet("/effort max", onStart = "claude"))
    assertEquals(snippets, snippets.withSnippet("   "))
    assertEquals(snippets, snippets.withSnippet(" /effort max"))
  }

  @Test
  fun editedListLosesBlanksAndRepeatsButKeepsItsOrder() {
    val edited = listOf(Snippet(" b", onStart = "claude"), Snippet(""), Snippet("a"), Snippet("b ", onStart = "codex"), Snippet("  "), Snippet("c"))
    assertEquals(listOf(Snippet("b", onStart = "claude"), Snippet("a"), Snippet("c")), edited.tidy())
  }

  @Test
  fun anAgentStartsWithItsOwnSnippetsInTheirOrder() {
    val snippets = listOf(Snippet("/effort max", onStart = "claude"), Snippet("продолжай"), Snippet("/model o3", onStart = "codex"), Snippet("/compact", onStart = "claude"))
    assertEquals(listOf("/effort max", "/compact"), snippets.onStart(AgentKind.CLAUDE))
    assertEquals(listOf("/model o3"), snippets.onStart(AgentKind.CODEX))
    assertEquals(emptyList(), snippets.onStart(AgentKind.GEMINI))
  }
}
