package dev.artalex.herdr.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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

/** Shift or Ctrl in such a row: as big as the other keys, and filled while it is on. */
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
