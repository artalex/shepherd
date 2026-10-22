package dev.artalex.herdr.ui.terminal

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.artalex.herdr.R
import dev.artalex.herdr.ui.common.KeyTarget
import dev.artalex.herdr.ui.common.ModKey
import dev.artalex.herdr.ui.common.RowKey
import dev.artalex.herdr.ui.common.rememberLinkOpener
import dev.artalex.herdr.ui.common.rememberTerminalTypeface
import dev.artalex.herdr.core.terminal.CloseReason
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey

/**
 * Extra keys: the ones the soft keyboard lacks, and Enter for when the keyboard is down, as when answering a dialog.
 * Label and libvterm key, or a character to type.
 */
private sealed interface ExtraKey {
  val label: String

  data class Key(override val label: String, val vterm: Int) : ExtraKey

  data class Char(override val label: String, val char: kotlin.Char) : ExtraKey
}

private val EXTRA_KEYS =
  listOf(
    ExtraKey.Key("Enter", VTermKey.ENTER), ExtraKey.Key("Esc", VTermKey.ESCAPE), ExtraKey.Key("Tab", VTermKey.TAB),
    ExtraKey.Key("↑", VTermKey.UP), ExtraKey.Key("↓", VTermKey.DOWN), ExtraKey.Key("←", VTermKey.LEFT), ExtraKey.Key("→", VTermKey.RIGHT),
    ExtraKey.Key("PgUp", VTermKey.PAGEUP), ExtraKey.Key("PgDn", VTermKey.PAGEDOWN), ExtraKey.Key("Home", VTermKey.HOME), ExtraKey.Key("End", VTermKey.END),
    ExtraKey.Char("|", '|'), ExtraKey.Char("/", '/'), ExtraKey.Char("~", '~'), ExtraKey.Char("-", '-'),
  )

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(viewModel: TerminalViewModel, title: String, onBack: () -> Unit, onPaneClosed: () -> Unit) {
  val stream = viewModel.stream
  // Leaving the screen or the app lets go of the pane, and the desktop gets its size back.
  LifecycleStartEffect(stream) {
    stream.acquire()
    onStopOrDispose { stream.release() }
  }
  val phase by stream.phase.collectAsStateWithLifecycle()
  val closed by stream.paneClosed.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // The button in the extra row, the selection menu and the keyboard's paste key all paste through here.
  val paste: () -> Unit = { clipboardText(context)?.let(viewModel::paste) }
  val snackbar = remember { SnackbarHostState() }
  val openLink = rememberLinkOpener(snackbar)
  LaunchedEffect(closed) { if (closed) onPaneClosed() }
  // Key events (hardware keyboard, the system input path) go to the terminal, also after rotation: it is the
  // only focusable element on this screen, and takes focus again once frames arrive.
  val focus = remember { FocusRequester() }
  // A view squeezed to zero height loses focus (landscape with the keyboard animating in); take it back.
  var terminalHeight by remember { mutableIntStateOf(0) }
  val takeFocus = phase == TerminalPhase.Live && terminalHeight > 0
  LaunchedEffect(takeFocus) { if (takeFocus) runCatching { focus.requestFocus() } }

  // A landscape phone with the keyboard up has almost no height left: give all of it to the terminal.
  // The back gesture still leaves the screen.
  val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
  val compactHeight = windowHeight < COMPACT_HEIGHT
  // There the soft keyboard leaves no room for the extra keys row; it has its own keys anyway.
  val showExtraKeys = !(compactHeight && WindowInsets.isImeVisible)

  Scaffold(
    topBar = {
      if (!compactHeight) TopAppBar(
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.focusProperties { canFocus = false }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.agent_back))
          }
        },
        title = { Text(stringResource(R.string.terminal_title, title)) },
      )
    },
    contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      if (!compactHeight) {
        Text(
          stringResource(R.string.terminal_resize_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }
      Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.Black).wheelScroll(stream).testTag("terminal").onSizeChanged { terminalHeight = it.height }) {
        Terminal(
          stream.emulator,
          modifier = Modifier.fillMaxSize(),
          typeface = rememberTerminalTypeface(),
          keyboardEnabled = true,
          focusRequester = focus,
          modifierManager = viewModel.modifiers,
          onPasteRequest = paste,
          onHyperlinkClick = openLink,
        )
        PhaseOverlay(phase, onReconnect = stream::reconnect, onTakeOver = stream::takeOver)
        // Over the bottom of the terminal, as on the agent screen, rather than over the extra keys.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) {
          Snackbar(it, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)
        }
      }
      if (showExtraKeys) ExtraKeysRow(viewModel, onPaste = paste)
    }
  }

  if (phase == TerminalPhase.NeedsTakeover) {
    AlertDialog(
      onDismissRequest = onBack,
      title = { Text(stringResource(R.string.terminal_takeover_title)) },
      text = { Text(stringResource(R.string.terminal_takeover_text)) },
      confirmButton = { TextButton(onClick = stream::takeOver, modifier = Modifier.testTag("takeOver")) { Text(stringResource(R.string.terminal_takeover)) } },
      dismissButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

@Composable
private fun PhaseOverlay(phase: TerminalPhase, onReconnect: () -> Unit, onTakeOver: () -> Unit) {
  val (text, action) =
    when (phase) {
      TerminalPhase.Measuring, TerminalPhase.Live, TerminalPhase.NeedsTakeover -> return
      TerminalPhase.Connecting -> stringResource(R.string.terminal_connecting) to null
      is TerminalPhase.Failed -> stringResource(R.string.terminal_failed, phase.message) to (R.string.terminal_reconnect to onReconnect)
      is TerminalPhase.Closed ->
        when (phase.reason) {
          CloseReason.TakenOver -> stringResource(R.string.terminal_taken_over) to (R.string.terminal_take_back to onTakeOver)
          CloseReason.NotFound -> stringResource(R.string.terminal_not_found) to null
          CloseReason.ServerUpdating -> stringResource(R.string.terminal_server_updating) to (R.string.terminal_reconnect to onReconnect)
          CloseReason.Detached -> stringResource(R.string.terminal_detached) to (R.string.terminal_reconnect to onReconnect)
          else -> stringResource(R.string.terminal_ended) to (R.string.terminal_reconnect to onReconnect)
        }
    }
  Column(
    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).padding(24.dp).testTag("terminalOverlay"),
    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(text, color = Color.White)
    action?.let { (label, onClick) -> Button(onClick = onClick) { Text(stringResource(label)) } }
  }
}

@Composable
private fun ExtraKeysRow(viewModel: TerminalViewModel, onPaste: () -> Unit) {
  val modifiers = viewModel.modifiers
  LazyRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("extraKeys"),
  ) {
    item {
      KeyTarget {
        OutlinedIconButton(onClick = onPaste, modifier = Modifier.touchOnly().testTag("paste")) {
          Icon(painterResource(R.drawable.ic_paste), contentDescription = stringResource(R.string.terminal_paste))
        }
      }
    }
    item { ModKey("Ctrl", on = modifiers.ctrl, onClick = { modifiers.ctrl = !modifiers.ctrl }, modifier = Modifier.touchOnly().testTag("mod-ctrl")) }
    item { ModKey("Alt", on = modifiers.alt, onClick = { modifiers.alt = !modifiers.alt }, modifier = Modifier.touchOnly().testTag("mod-alt")) }
    item { ModKey("Shift", on = modifiers.shift, onClick = { modifiers.shift = !modifiers.shift }, modifier = Modifier.touchOnly().testTag("mod-shift")) }
    items(EXTRA_KEYS) { key ->
      RowKey(
        key.label,
        onClick = {
          when (key) {
            is ExtraKey.Key -> viewModel.sendKey(key.vterm)
            is ExtraKey.Char -> viewModel.sendChar(key.char)
          }
        },
        modifier = Modifier.touchOnly().testTag("xkey-" + key.label),
      )
    }
  }
}

private val COMPACT_HEIGHT = 480.dp

/** The clipboard's text, if it holds any. */
private fun clipboardText(context: Context): String? =
  context.getSystemService(ClipboardManager::class.java).primaryClip
    ?.takeIf { it.itemCount > 0 }
    ?.getItemAt(0)
    ?.coerceToText(context)
    ?.toString()
    ?.takeIf { it.isNotEmpty() }

/** Buttons around the terminal never take keyboard focus, so Tab, Enter and Space always reach the terminal. */
private fun Modifier.touchOnly(): Modifier = focusProperties { canFocus = false }
