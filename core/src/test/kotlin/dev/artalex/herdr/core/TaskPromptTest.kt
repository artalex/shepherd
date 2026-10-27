package dev.artalex.herdr.core

import dev.artalex.herdr.core.store.promptPath
import dev.artalex.herdr.core.store.taskPrompt
import kotlin.test.Test
import kotlin.test.assertEquals

/** The prompt of a task given to a new agent with what another app shared. */
class TaskPromptTest {
  @Test
  fun taskThenSharedTextThenPathsThenNote() {
    assertEquals(
      "Find the recipe of this dish\n\nFrom grandma\n\n/home/u/Downloads/herdr-android/dish.jpg\n\"/home/u/Downloads/herdr-android/my photo.jpg\"\n\nSend the answer to Telegram.",
      taskPrompt(
        "  Find the recipe of this dish\n",
        "From grandma",
        listOf("/home/u/Downloads/herdr-android/dish.jpg", "/home/u/Downloads/herdr-android/my photo.jpg"),
        "Send the answer to Telegram.",
      ),
    )
  }

  @Test
  fun partsThereAreNoneOfAreLeftOut() {
    assertEquals("Summarize", taskPrompt("Summarize", null, emptyList()))
    assertEquals("Summarize\n\nhttps://example.com/a", taskPrompt("Summarize", " https://example.com/a ", emptyList(), " "))
    assertEquals("Read it\n\n/srv/a.txt\n\nNote", taskPrompt("Read it", "", listOf("/srv/a.txt"), "Note"))
  }

  @Test
  fun pathWithWhitespaceIsQuoted() {
    assertEquals("/srv/a.txt", promptPath("/srv/a.txt"))
    assertEquals("\"/srv/a b.txt\"", promptPath("/srv/a b.txt"))
    assertEquals("\"/srv/a\tb.txt\"", promptPath("/srv/a\tb.txt"))
  }
}
