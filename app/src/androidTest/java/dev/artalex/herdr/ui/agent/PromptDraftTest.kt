package dev.artalex.herdr.ui.agent

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prompt keeps sent text until herdr takes it, and gives refused text back without losing what was typed since. */
class PromptDraftTest {
  private val draft = PromptDraft()

  @Test
  fun sentTextLeavesTheFieldAndWaitsForHerdr() {
    draft.value = TextFieldValue("сделай тесты", TextRange(12))
    assertEquals("сделай тесты", draft.take())
    assertEquals(TextFieldValue(), draft.value)
    assertTrue(draft.sending)
    draft.sent()
    assertFalse(draft.sending)
    assertEquals(TextFieldValue(), draft.value)
  }

  @Test
  fun refusedTextComesBackWithTheCursorAtItsEnd() {
    draft.value = TextFieldValue("не дойдёт")
    draft.take()
    draft.failed()
    assertEquals(TextFieldValue("не дойдёт", TextRange(9)), draft.value)
    assertFalse(draft.sending)
  }

  @Test
  fun refusedTextGoesBeforeWhatWasTypedSince() {
    draft.value = TextFieldValue("первый")
    draft.take()
    draft.value = TextFieldValue("второй", TextRange(3))
    draft.failed()
    // "первый\n" goes in front: the cursor stays where it was in the new text.
    assertEquals(TextFieldValue("первый\nвторой", TextRange(10)), draft.value)
  }

  @Test
  fun lateSuccessLeavesWhatWasTypedSince() {
    draft.value = TextFieldValue("первый")
    draft.take()
    draft.value = TextFieldValue("второй", TextRange(6))
    draft.sent()
    assertEquals(TextFieldValue("второй", TextRange(6)), draft.value)
  }

  @Test
  fun nothingGoesWhileTextIsOnItsWayOrBlank() {
    draft.value = TextFieldValue("  ")
    assertNull(draft.take())
    draft.value = TextFieldValue("первый")
    draft.take()
    draft.value = TextFieldValue("второй")
    assertNull(draft.take())
    assertEquals(TextFieldValue("второй"), draft.value)
  }
}
