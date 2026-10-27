package dev.artalex.herdr.ui.terminal

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import dev.artalex.herdr.R
import org.connectbot.terminal.R as TerminalR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** termlib's selection menu speaks English on a phone set to Russian too, while the app's own words are Russian. */
class TermlibWordsTest {
  @Test
  fun termlibWordsStayEnglishInRussian() {
    val english = resources("en-US")
    val russian = resources("ru-RU")
    assertNotEquals(english.getString(R.string.settings_title), russian.getString(R.string.settings_title))
    for (id in WORDS) assertEquals(english.getResourceEntryName(id), english.getString(id), russian.getString(id))
  }

  private fun resources(languageTag: String): Resources {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val configuration = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(languageTag)) }
    return context.createConfigurationContext(configuration).resources
  }

  private companion object {
    /** Every string termlib 0.3.14 translates; values-ru/termlib.xml keeps them English. */
    val WORDS =
      listOf(
        TerminalR.string.terminal_selection_copy,
        TerminalR.string.terminal_selection_mode_character,
        TerminalR.string.terminal_selection_mode_line,
        TerminalR.string.terminal_selection_mode_word,
        TerminalR.string.terminal_selection_more_options_description,
        TerminalR.string.terminal_selection_paste,
        TerminalR.string.terminal_selection_select_all,
        TerminalR.string.terminal_selection_select_all_visible,
        TerminalR.string.terminal_selection_share,
      )
  }
}
