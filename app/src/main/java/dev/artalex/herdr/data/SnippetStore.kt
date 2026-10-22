package dev.artalex.herdr.data

import android.content.Context
import androidx.core.content.edit
import dev.artalex.herdr.core.snippets.Snippet
import dev.artalex.herdr.core.snippets.SnippetList
import dev.artalex.herdr.core.snippets.tidy
import dev.artalex.herdr.core.snippets.withSnippet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The prompt snippets, one list for every server and agent, kept as one value like the servers. */
class SnippetStore(context: Context) {
  private val prefs = context.getSharedPreferences("snippets", Context.MODE_PRIVATE)
  private val _snippets = MutableStateFlow(SnippetList.decode(prefs.getString(LIST, null)))

  val snippets: StateFlow<List<Snippet>> = _snippets.asStateFlow()

  /** Adds [text] at the end; see [withSnippet]. */
  fun add(text: String) = change { it.withSnippet(text) }

  /** The list as the user edited it; see [tidy]. */
  fun set(snippets: List<Snippet>) = change { snippets.tidy() }

  @Synchronized
  private fun change(edit: (List<Snippet>) -> List<Snippet>) {
    val snippets = edit(_snippets.value)
    prefs.edit { putString(LIST, SnippetList.encode(snippets)) }
    _snippets.value = snippets
  }

  private companion object {
    const val LIST = "list"
  }
}
