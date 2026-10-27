package dev.artalex.herdr.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Keys in a row of keys are as wide as they look, like on a keyboard: Material's 48dp touch target around a 40dp key
 * would only widen the gaps next to it.
 */
@Composable
fun KeyTarget(content: @Composable () -> Unit) = CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp, content = content)

/** A key of the rows over the keyboard: little room around the label, so more keys fit; one-character keys come out round. */
@Composable
fun RowKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
  Key(label, onClick, modifier, ButtonDefaults.outlinedButtonColors(), enabled)

/**
 * A row of keys over the keyboard. Fn stays at its start while the rest scrolls, and switches the rest between the
 * [usual] keys and the [function] ones, F1–F12; each set keeps where it was scrolled to. [keyModifier] goes to Fn as
 * to the other keys of the row, [tag] to the part that scrolls.
 */
@Composable
fun KeyRow(
  tag: String,
  modifier: Modifier = Modifier,
  keyModifier: Modifier = Modifier,
  usual: LazyListScope.() -> Unit,
  function: LazyListScope.() -> Unit,
) {
  var functionKeys by rememberSaveable { mutableStateOf(false) }
  val usualRow = rememberLazyListState()
  val functionRow = rememberLazyListState()
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(KEY_GAP), verticalAlignment = Alignment.CenterVertically) {
    ModKey("Fn", on = functionKeys, onClick = { functionKeys = !functionKeys }, modifier = keyModifier.testTag("mod-fn"))
    LazyRow(
      state = if (functionKeys) functionRow else usualRow,
      horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f).testTag(tag),
      content = if (functionKeys) function else usual,
    )
  }
}

private val KEY_GAP = 6.dp

/** Shift, Ctrl, Alt or Fn in such a row: as big as the other keys, and filled while it is on. */
@Composable
fun ModKey(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
  Key(
    label,
    onClick,
    modifier.semantics { selected = on },
    if (on) {
      ButtonDefaults.outlinedButtonColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    } else {
      ButtonDefaults.outlinedButtonColors()
    },
  )

@Composable
private fun Key(label: String, onClick: () -> Unit, modifier: Modifier, colors: ButtonColors, enabled: Boolean = true) =
  KeyTarget {
    OutlinedButton(onClick = onClick, enabled = enabled, colors = colors, contentPadding = PaddingValues(horizontal = 8.dp), modifier = modifier.widthIn(min = 40.dp)) {
      Text(label)
    }
  }
