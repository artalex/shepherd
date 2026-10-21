package dev.artalex.herdr.ui.agent

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.artalex.herdr.R
import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.store.Board
import dev.artalex.herdr.ui.agents.paneSubtitle
import dev.artalex.herdr.ui.agents.paneTitle
import dev.artalex.herdr.ui.common.ModKey
import dev.artalex.herdr.ui.common.RowKey
import dev.artalex.herdr.ui.common.StatusChip
import dev.artalex.herdr.ui.files.FilesSheet
import kotlinx.coroutines.launch

/**
 * Label on the button and the herdr logical key it sends; Shift and Ctrl come right after the arrows. Tab is next to
 * Enter: Tab and then Enter takes the prompt Claude Code suggests.
 */
private val QUICK_KEYS =
  listOf("1" to "1", "2" to "2", "3" to "3", "Enter" to "enter", "Tab" to "tab", "Esc" to "esc", "↑" to "up", "↓" to "down", "←" to "left", "→" to "right")

/** The keys after Shift and Ctrl. */
private val MORE_KEYS = listOf("Ctrl+C" to "ctrl+c")

/**
 * Keys the sticky Shift goes with: Shift+Tab switches Claude Code's modes, and Shift with an arrow moves between the
 * parts of a dialog in Codex. herdr sends Shift with a digit as the digit, and Shift+Esc as a sequence few programs know.
 */
private val SHIFT_KEYS = setOf("up", "down", "left", "right", "tab")

/**
 * Keys of the row the sticky Ctrl goes with: with an arrow it moves by words. herdr sends Ctrl with a digit, Enter or
 * Tab as the plain key. A letter for Ctrl comes from the keyboard, typed into the prompt.
 */
private val CTRL_KEYS = setOf("up", "down", "left", "right")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
  viewModel: AgentViewModel,
  onBack: () -> Unit,
  onOpenTerminal: () -> Unit,
  onSwitchPane: (serverId: String, paneId: String) -> Unit,
  onPaneClosed: () -> Unit,
) {
  val view by viewModel.pane.collectAsStateWithLifecycle()
  val board by viewModel.board.collectAsStateWithLifecycle()
  val switcher by viewModel.switcher.collectAsStateWithLifecycle()
  val serverTitle by viewModel.serverTitle.collectAsStateWithLifecycle()
  val closed by viewModel.paneClosed.collectAsStateWithLifecycle()
  val agents by viewModel.agents.collectAsStateWithLifecycle()
  val snippets by viewModel.snippets.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val resources = LocalResources.current
  LaunchedEffect(viewModel) {
    viewModel.messages.collect { snackbar.showSnackbar(resources.getString(it.text, it.arg)) }
  }
  LaunchedEffect(viewModel) { viewModel.opened.collect { onSwitchPane(viewModel.server.id, it) } }
  LaunchedEffect(closed) { if (closed) onPaneClosed() }
  val pane = view?.pane
  var confirmClose by remember { mutableStateOf(false) }
  var filesOpen by remember { mutableStateOf(false) }
  var editSnippets by remember { mutableStateOf(false) }
  var prompt by remember { mutableStateOf(TextFieldValue()) }
  val promptFocus = remember { FocusRequester() }
  var focusPrompt by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  // A path put into the prompt from the files window: the cursor goes after it once the window is gone.
  LaunchedEffect(focusPrompt, filesOpen) {
    if (focusPrompt && !filesOpen) {
      focusPrompt = false
      runCatching { promptFocus.requestFocus() }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.agent_back)) }
        },
        title = {
          PaneSwitcher(
            current = viewModel.server.id to viewModel.paneId,
            pane = pane,
            serverTitle = serverTitle,
            boards = switcher,
            onPick = { serverId, paneId -> if (serverId to paneId != viewModel.server.id to viewModel.paneId) onSwitchPane(serverId, paneId) },
          )
        },
        actions = {
          // Icons rather than words: the pane's title next to them needs the room.
          IconButton(onClick = onOpenTerminal, enabled = pane != null, modifier = Modifier.testTag("openTerminal")) {
            Icon(painterResource(R.drawable.ic_terminal), contentDescription = stringResource(R.string.terminal_open))
          }
          IconButton(
            onClick = {
              viewModel.scanFiles()
              filesOpen = true
            },
            enabled = pane != null,
            modifier = Modifier.testTag("files"),
          ) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = stringResource(R.string.files_open))
          }
          PaneMenu(
            enabled = pane != null,
            agents = agents,
            canStartHere = pane != null && pane.agent == null,
            onOpen = viewModel::loadAgents,
            onNewSession = viewModel::newSession,
            onStartHere = viewModel::startAgent,
            onClose = { confirmClose = true },
          )
        },
      )
    },
    // The prompt field stays above the keyboard; the screen above it shrinks instead.
    contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      val grid = view?.grid
      Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize().testTag("screen")) {
          // A pane that is gone takes the screen with it; until a new one shows up in a snapshot, wait.
          if (pane == null || grid == null) NoScreen()
          else PaneScreen(viewModel, grid)
        }
        // Over the bottom of the snapshot: above the keyboard it would hide the prompt and the keys it asks to answer
        // with. Dark like the rest of the app, where Material's inverse colours would be a light box.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) {
          Snackbar(it, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)
        }
      }
      // Shift and Ctrl hold for the next key, as in the terminal.
      var shift by remember { mutableStateOf(false) }
      var ctrl by remember { mutableStateOf(false) }
      // The prompt's text with a letter that went to the agent with Ctrl in it. The text field keeps that text until it
      // recomposes, and a keyboard's calls before then, such as finishComposingText, bring it back: it is the same edit
      // again. The recomposition that resets the field ends that.
      val sentWithCtrl = remember { arrayOfNulls<String>(1) }
      SideEffect { sentWithCtrl[0] = null }
      fun send(key: String) {
        val mods = listOfNotNull("ctrl".takeIf { ctrl && key in CTRL_KEYS }, "shift".takeIf { shift && key in SHIFT_KEYS })
        viewModel.sendKeys(listOf((mods + key).joinToString("+")))
        shift = false
        ctrl = false
      }
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("quickKeys"),
      ) {
        items(QUICK_KEYS) { (label, key) -> RowKey(label, onClick = { send(key) }, modifier = Modifier.testTag("key-$key")) }
        item { ModKey("Shift", on = shift, onClick = { shift = !shift }, modifier = Modifier.testTag("mod-shift")) }
        item { ModKey("Ctrl", on = ctrl, onClick = { ctrl = !ctrl }, modifier = Modifier.testTag("mod-ctrl")) }
        items(MORE_KEYS) { (label, key) -> RowKey(label, onClick = { send(key) }, modifier = Modifier.testTag("key-$key")) }
      }
      if (pane?.agent != null) {
        PromptRow(
          value = prompt,
          onValueChange = { next ->
            val typed = if (ctrl) typedChar(prompt, next) else null
            when {
              next.text == sentWithCtrl[0] -> Unit
              typed == null -> prompt = next
              else -> {
                // Ctrl goes with the next character typed: a Latin letter goes to the agent as Ctrl and that letter,
                // anything else into the prompt as usual.
                ctrl = false
                val letter = typed.lowercaseChar()
                if (letter in 'a'..'z') {
                  viewModel.sendKeys(listOf("ctrl+$letter"))
                  sentWithCtrl[0] = next.text
                } else {
                  prompt = next
                }
              }
            }
          },
          // While Ctrl is on, the keyboard is asked for no suggestions: it sends a letter as it is typed, not as a word
          // in the making that it would put back into the prompt after the letter went to the agent.
          keyboard = if (ctrl) KeyboardOptions(keyboardType = KeyboardType.PasswordVisible, autoCorrectEnabled = false) else KeyboardOptions.Default,
          focus = promptFocus,
          starting = view?.starting == true,
          snippets = {
            SnippetsMenu(
              snippets,
              canSave = prompt.text.isNotBlank() && prompt.text.trim() !in snippets,
              onInsert = {
                prompt = prompt.withSnippet(it)
                focusPrompt = true
              },
              onSave = { viewModel.saveSnippet(prompt.text) },
              onEdit = { editSnippets = true },
            )
          },
          onSend = {
            viewModel.sendPrompt(it)
            prompt = TextFieldValue()
          },
        )
      }
    }
  }

  if (filesOpen) {
    FilesSheet(
      viewModel.files,
      cwd = viewModel.cwd,
      onInsertPath = { path ->
        if (pane?.agent != null) {
          prompt = prompt.withPath(path)
          focusPrompt = true
        } else {
          // A shell has no prompt field here; its terminal can paste the path.
          context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(path, path))
          scope.launch { snackbar.showSnackbar(resources.getString(R.string.files_path_copied, path)) }
        }
      },
      onDismiss = { filesOpen = false },
    )
  }

  if (editSnippets) {
    SnippetsDialog(
      snippets,
      onSave = {
        viewModel.setSnippets(it)
        editSnippets = false
      },
      onDismiss = { editSnippets = false },
    )
  }

  if (confirmClose && pane != null) {
    CloseDialog(
      pane,
      board,
      onConfirm = {
        confirmClose = false
        viewModel.closePane()
      },
      onDismiss = { confirmClose = false },
    )
  }
}

/**
 * The pane's status and title, and under it [serverTitle] if there is one; tapping it lists every pane to switch to.
 * With more than one server on, their panes come under the servers' names.
 */
@Composable
private fun PaneSwitcher(
  current: Pair<String, String>,
  pane: PaneInfo?,
  serverTitle: String?,
  boards: List<ServerBoard>,
  onPick: (serverId: String, paneId: String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    Column(Modifier.clickable(enabled = boards.isNotEmpty()) { open = true }.testTag("paneSwitcher")) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pane?.agent != null) StatusChip(pane.agentStatus, Modifier.testTag("agentStatus"))
        Text(pane?.let(::paneTitle) ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.agent_switch))
      }
      // Under the status too: next to it, between the icons, the name would be cut down to a few letters.
      serverTitle?.let {
        Text(
          it,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.testTag("serverTitle"),
        )
      }
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("paneSwitcherMenu")) {
      for ((serverId, title, board) in boards) {
        if (boards.size > 1) {
          Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
          )
        }
        for (group in board.groups) {
          Text(
            stringResource(R.string.agents_workspace, group.workspace.label ?: group.workspace.workspaceId, group.workspace.number),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          )
          for (entry in group.agents + group.otherPanes) {
            val item = entry.pane
            DropdownMenuItem(
              text = {
                Column {
                  Text(paneTitle(item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Text(
                    paneSubtitle(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              },
              leadingIcon = if (item.agent != null) ({ StatusChip(item.agentStatus) }) else null,
              trailingIcon =
                if (serverId to item.paneId == current) ({ Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.agent_current_pane)) })
                else null,
              onClick = {
                open = false
                onPick(serverId, item.paneId)
              },
              modifier = Modifier.testTag("switch-$serverId/${item.paneId}"),
            )
          }
        }
      }
    }
  }
}

/**
 * New sessions and starting an agent in this pane (shells only), for every agent herdr can start that the host
 * has; with one agent installed each is a single item. Closing comes last, behind a confirmation.
 */
@Composable
private fun PaneMenu(
  enabled: Boolean,
  agents: List<AgentKind>?,
  canStartHere: Boolean,
  onOpen: () -> Unit,
  onNewSession: (AgentKind) -> Unit,
  onStartHere: (AgentKind) -> Unit,
  onClose: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  LaunchedEffect(open) { if (open) onOpen() }
  Box {
    IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("paneMenu")) {
      Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.pane_menu))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      @Composable
      fun item(text: String, tag: String, color: Color = Color.Unspecified, action: () -> Unit) =
        DropdownMenuItem(
          text = { Text(text, color = color) },
          onClick = {
            open = false
            action()
          },
          modifier = Modifier.testTag(tag),
        )

      @Composable
      fun header(text: String) =
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))

      when {
        agents == null -> DropdownMenuItem(text = { Text(stringResource(R.string.pane_agents_loading)) }, onClick = {}, enabled = false)
        agents.isEmpty() -> DropdownMenuItem(text = { Text(stringResource(R.string.pane_agents_none)) }, onClick = {}, enabled = false)
        agents.size == 1 -> {
          val kind = agents.single()
          item(stringResource(R.string.pane_new_session, kind.title), "newSession-${kind.kind}") { onNewSession(kind) }
          if (canStartHere) item(stringResource(R.string.pane_start_agent, kind.title), "startAgent-${kind.kind}") { onStartHere(kind) }
        }
        else -> {
          header(stringResource(R.string.pane_new_session_header))
          for (kind in agents) item(kind.title, "newSession-${kind.kind}") { onNewSession(kind) }
          if (canStartHere) {
            header(stringResource(R.string.pane_start_header))
            for (kind in agents) item(kind.title, "startAgent-${kind.kind}") { onStartHere(kind) }
          }
        }
      }
      HorizontalDivider()
      item(stringResource(R.string.pane_close), "closePane", MaterialTheme.colorScheme.error, onClose)
    }
  }
}

/** Asks before closing: what runs in the pane dies with it, and the last pane takes its workspace along. */
@Composable
private fun CloseDialog(pane: PaneInfo, board: Board?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  val group = board?.groups?.firstOrNull { g -> (g.agents + g.otherPanes).any { it.pane.paneId == pane.paneId } }
  val last = group != null && group.agents.size + group.otherPanes.size == 1
  val parts =
    listOfNotNull(
      stringResource(R.string.pane_close_text, paneTitle(pane)),
      if (pane.agent != null && pane.agentStatus == AgentStatus.WORKING) stringResource(R.string.pane_close_working) else null,
      if (last) stringResource(R.string.pane_close_last, group.workspace.label ?: group.workspace.workspaceId) else null,
    )
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.pane_close_title)) },
    text = { Text(parts.joinToString("\n\n")) },
    confirmButton = {
      TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirmClose")) {
        Text(stringResource(R.string.pane_close_confirm), color = MaterialTheme.colorScheme.error)
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

/**
 * The prompt, with [snippets] before it. While a just-started agent isn't ready, text can be typed but not sent: herdr
 * would refuse it.
 */
@Composable
private fun PromptRow(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  keyboard: KeyboardOptions,
  focus: FocusRequester,
  starting: Boolean,
  snippets: @Composable () -> Unit,
  onSend: (String) -> Unit,
) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    snippets()
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      keyboardOptions = keyboard,
      placeholder = { Text(stringResource(if (starting) R.string.agent_starting else R.string.agent_prompt_hint)) },
      maxLines = 4,
      modifier = Modifier.weight(1f).focusRequester(focus).testTag("prompt"),
    )
    IconButton(onClick = { onSend(value.text) }, enabled = value.text.isNotBlank() && !starting, modifier = Modifier.testTag("sendPrompt")) {
      Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.agent_send))
    }
  }
}

/**
 * The snippets button by the prompt. A snippet goes into the prompt at the cursor, to be sent as it is or with more
 * to it; the prompt's text can become a snippet, and the list is edited in [SnippetsDialog].
 */
@Composable
private fun SnippetsMenu(snippets: List<String>, canSave: Boolean, onInsert: (String) -> Unit, onSave: () -> Unit, onEdit: () -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("snippets")) {
      Icon(painterResource(R.drawable.ic_snippets), contentDescription = stringResource(R.string.snippets_open))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("snippetsMenu")) {
      if (snippets.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.snippets_none)) }, onClick = {}, enabled = false)
      snippets.forEachIndexed { i, snippet ->
        DropdownMenuItem(
          text = { Text(snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) },
          onClick = {
            open = false
            onInsert(snippet)
          },
          modifier = Modifier.testTag("snippet-$i"),
        )
      }
      HorizontalDivider()
      DropdownMenuItem(
        text = { Text(stringResource(R.string.snippets_save)) },
        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
        onClick = {
          open = false
          onSave()
        },
        enabled = canSave,
        modifier = Modifier.testTag("saveSnippet"),
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.snippets_edit)) },
        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        onClick = {
          open = false
          onEdit()
        },
        modifier = Modifier.testTag("editSnippets"),
      )
    }
  }
}

/** The snippets to change, delete or add to; blank ones and repeats go when the list is saved. */
@Composable
private fun SnippetsDialog(snippets: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
  // Rows carry ids of their own: a deleted row takes its field along, not the one of the row after it.
  var rows by remember { mutableStateOf(snippets.ifEmpty { listOf("") }.withIndex().map { it.index to it.value }) }
  var nextId by remember { mutableIntStateOf(rows.size) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.snippets_title)) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, (id, text) ->
          key(id) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              OutlinedTextField(
                value = text,
                onValueChange = { changed -> rows = rows.map { if (it.first == id) id to changed else it } },
                maxLines = 4,
                modifier = Modifier.weight(1f).testTag("snippetField-$i"),
              )
              IconButton(onClick = { rows = rows.filter { it.first != id } }, modifier = Modifier.testTag("deleteSnippet-$i")) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.snippets_delete))
              }
            }
          }
        }
        TextButton(
          onClick = {
            rows = rows + (nextId to "")
            nextId++
          },
          modifier = Modifier.testTag("addSnippet"),
        ) {
          Icon(Icons.Filled.Add, contentDescription = null)
          Text(stringResource(R.string.snippets_add), Modifier.padding(start = 8.dp))
        }
      }
    },
    confirmButton = { TextButton(onClick = { onSave(rows.map { it.second }) }, modifier = Modifier.testTag("saveSnippets")) { Text(stringResource(R.string.snippets_done)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

/** The one character typed at the cursor that turns [before] into [after], or null for any other edit. */
private fun typedChar(before: TextFieldValue, after: TextFieldValue): Char? {
  val at = before.selection.min
  val inserted =
    before.selection.collapsed &&
      after.text.length == before.text.length + 1 &&
      after.text.startsWith(before.text.substring(0, at)) &&
      after.text.endsWith(before.text.substring(at))
  return if (inserted) after.text[at] else null
}

/** [path] put in at the cursor, with spaces from the words around it; in quotes if it has spaces of its own. */
private fun TextFieldValue.withPath(path: String): TextFieldValue = withWord(if (path.any { it.isWhitespace() }) "\"$path\"" else path, spaceAtEnd = true)

/** [snippet] put in at the cursor, with spaces from the words around it; at the end of the text nothing follows it. */
private fun TextFieldValue.withSnippet(snippet: String): TextFieldValue = withWord(snippet, spaceAtEnd = false)

/** [word] in place of the selection, apart from the words around it; [spaceAtEnd]: a space after it at the end too. */
private fun TextFieldValue.withWord(word: String, spaceAtEnd: Boolean): TextFieldValue {
  val before = text.substring(0, selection.min)
  val after = text.substring(selection.max)
  val spaceBefore = before.isNotEmpty() && !before.last().isWhitespace()
  val spaceAfter = if (after.isEmpty()) spaceAtEnd else !after.first().isWhitespace()
  val inserted = (if (spaceBefore) " " else "") + word + (if (spaceAfter) " " else "")
  return TextFieldValue(before + inserted + after, TextRange(before.length + inserted.length))
}
