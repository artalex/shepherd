package dev.artalex.herdr.core

import dev.artalex.herdr.core.snippets.SnippetList
import dev.artalex.herdr.core.snippets.tidy
import dev.artalex.herdr.core.snippets.withSnippet
import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetsTest {
  @Test
  fun listRoundTripsAndUnreadableTextIsNoSnippets() {
    val snippets = listOf("/effort max", "продолжай", "посмотри\nлоги")
    assertEquals(snippets, SnippetList.decode(SnippetList.encode(snippets)))
    assertEquals(emptyList(), SnippetList.decode(null))
    assertEquals(emptyList(), SnippetList.decode("{broken"))
    assertEquals(emptyList(), SnippetList.decode("""{"snippets":[]}"""))
  }

  @Test
  fun savedTextGoesLastWithoutItsSpaces() {
    assertEquals(listOf("/compact", "/effort max"), listOf("/compact").withSnippet("  /effort max\n"))
  }

  @Test
  fun blankTextOrOneAlreadyThereChangesNothing() {
    val snippets = listOf("/effort max")
    assertEquals(snippets, snippets.withSnippet("   "))
    assertEquals(snippets, snippets.withSnippet(" /effort max"))
  }

  @Test
  fun editedListLosesBlanksAndRepeatsButKeepsItsOrder() {
    assertEquals(listOf("b", "a", "c"), listOf(" b", "", "a", "b ", "  ", "c").tidy())
  }
}
