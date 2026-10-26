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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.artalex.herdr.AgentStartProblem
import dev.artalex.herdr.PromptPart
import dev.artalex.herdr.R
import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.snippets.Snippet
import dev.artalex.herdr.core.store.Board
import dev.artalex.herdr.ui.agents.paneSubtitle
import dev.artalex.herdr.ui.agents.paneTitle
import dev.artalex.herdr.ui.common.ModKey
import dev.artalex.herdr.ui.common.RowKey
import dev.artalex.herdr.ui.common.StatusChip
import dev.artalex.herdr.ui.common.rememberLinkOpener
import dev.artalex.herdr.ui.files.FilesSheet
import dev.artalex.herdr.ui.terminal.TerminalPhase
import kotlinx.coroutines.launch
import org.connectbot.terminal.VTermKey

/**
 * Label on the button and the herdr logical key it sends; Shift and Ctrl come right after the arrows. Tab is next to
 * Enter: Tab and then Enter takes the prompt Claude Code suggests.
 */
private val QUICK_KEYS =
  listOf("1" to "1", "2" to "2", "3" to "3", "Enter" to "enter", "Tab" to "tab", "Esc" to "esc", "↑" to "up", "↓" to "down", "←" to "left", "→" to "right")

/** The keys after Shift and Ctrl. */
private val MORE_KEYS = listOf("Ctrl+C" to "ctrl+c")

/**
 * The last keys of the row, with the libvterm key each types into the live stream: herdr's API has no names for them
 * (0.9.5 answers "unsupported key home"). While another client controls the pane there is no stream, and they are off.
 */
private val STREAM_KEYS = listOf("Home" to VTermKey.HOME, "End" to VTermKey.END, "PgUp" to VTermKey.PAGEUP, "PgDn" to VTermKey.PAGEDOWN)

/**
 * Keys the sticky Shift goes with: Shift+Tab switches Claude Code's modes, and Shift with an arrow moves between the
 * parts of a dialog in Codex. herdr sends Shift with a digit as the digit, and Shift+Esc as a sequence few programs know.
 */
private val SHIFT_KEYS = setOf("up", "down", "left", "right", "tab")

/**
 * Keys of the row the sticky Ctrl goes with: with an arrow it moves by words, and Ctrl+Enter is Claude Code's "send
 * now". To a program that hasn't asked for the kitty keyboard protocol herdr sends Ctrl with a digit, Enter or Tab as the
 * plain key; Claude Code asks for it. A letter for Ctrl comes from the keyboard, typed into the prompt.
 */
private val CTRL_KEYS = setOf("up", "down", "left", "right", "enter")

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
  val filesWorkspace by viewModel.filesWorkspace.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val resources = LocalResources.current
  val openLink = rememberLinkOpener(snackbar)
  LaunchedEffect(viewModel) {
    viewModel.messages.collect { snackbar.showSnackbar(resources.getString(it.text, it.arg)) }
  }
  // A start the pane's shell couldn't run is explained in a dialog; snippets that didn't go, in the snackbar.
  var missingProgram by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(viewModel) {
    viewModel.startProblems.collect {
      when (it) {
        is AgentStartProblem.ProgramMissing -> missingProgram = it.executable
        is AgentStartProblem.SnippetsNotSent -> {
          val text = if (it.late) R.string.snippets_on_start_late else R.string.snippets_on_start_failed
          snackbar.showSnackbar(resources.getString(text, it.prompts.joinToString(", ")))
        }
      }
    }
  }
  LaunchedEffect(viewModel) { viewModel.opened.collect { onSwitchPane(viewModel.server.id, it) } }
  LaunchedEffect(closed) { if (closed) onPaneClosed() }
  val pane = view?.pane
  var confirmClose by remember { mutableStateOf(false) }
  var filesOpen by remember { mutableStateOf(false) }
  var editSnippets by remember { mutableStateOf(false) }
  // In the view model, which outlives this composition: a refused prompt comes back to it after a rotation too.
  val draft = viewModel.draft
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
  // A share's text and the paths of its files, as they come; only once the pane is known, so that an agent isn't taken
  // for a shell. An agent gets them in the prompt at the cursor. A shell has no prompt field here: they go to the
  // clipboard together, for its terminal to paste.
  var forShell by remember { mutableStateOf(TextFieldValue()) }
  val paneKnown = pane != null
  val hasAgent = pane?.agent != null
  LaunchedEffect(paneKnown, hasAgent) {
    if (!paneKnown) return@LaunchedEffect
    viewModel.inbox.collect { part ->
      when {
        part is PromptPart.Failed -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.share_not_sent, part.name, part.error)) }
        hasAgent -> {
          draft.value = draft.value.with(part)
          focusPrompt = true
        }
        else -> {
          forShell = forShell.with(part)
          val text = forShell.text.trimEnd()
          context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(text, text))
          scope.launch {
            // One note for the whole share, not one after another for each of its parts.
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(R.string.share_copied))
          }
        }
      }
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
          else PaneScreen(viewModel, grid, onLink = openLink)
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
      fun sendToStream(vtermKey: Int) {
        viewModel.sendStreamKey(vtermKey)
        shift = false
        ctrl = false
      }
      // Ctrl+Enter in the prompt, from a keyboard or with the sticky Ctrl; not while a just-started agent isn't ready, as
      // the send button.
      fun sendNow() {
        if (view?.starting != true) viewModel.sendPrompt(now = true)
        shift = false
        ctrl = false
      }
      val phase by viewModel.stream.phase.collectAsStateWithLifecycle()
      // A stream key the stream didn't take, as when it broke a moment ago: one note at a time, as on the terminal screen.
      LaunchedEffect(viewModel.stream) {
        viewModel.stream.undelivered.collect {
          if (snackbar.currentSnackbarData == null) launch { snackbar.showSnackbar(resources.getString(R.string.terminal_input_lost)) }
        }
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
        items(STREAM_KEYS) { (label, key) ->
          RowKey(label, onClick = { sendToStream(key) }, modifier = Modifier.testTag("key-" + label.lowercase()), enabled = phase == TerminalPhase.Live)
        }
      }
      if (pane?.agent != null) {
        PromptRow(
          value = draft.value,
          onValueChange = { next ->
            val typed = if (ctrl) typedChar(draft.value, next) else null
            when {
              next.text == sentWithCtrl[0] -> Unit
              typed == null -> draft.value = next
              else -> {
                // Ctrl goes with the next character typed: a Latin letter goes to the agent as Ctrl and that letter, Enter
                // sends the prompt at once, anything else goes into the prompt as usual.
                ctrl = false
                val letter = typed.lowercaseChar()
                when {
                  letter in 'a'..'z' -> {
                    viewModel.sendKeys(listOf("ctrl+$letter"))
                    sentWithCtrl[0] = next.text
                  }
                  typed == '\n' -> {
                    sendNow()
                    sentWithCtrl[0] = next.text
                  }
                  else -> draft.value = next
                }
              }
            }
          },
          // While Ctrl is on, the keyboard is asked for no suggestions: it sends a letter as it is typed, not as a word
          // in the making that it would put back into the prompt after the letter went to the agent.
          keyboard = if (ctrl) KeyboardOptions(keyboardType = KeyboardType.PasswordVisible, autoCorrectEnabled = false) else KeyboardOptions.Default,
          focus = promptFocus,
          starting = view?.starting == true,
          sending = draft.sending,
          snippets = {
            SnippetsMenu(
              snippets,
              canSave = draft.value.text.isNotBlank() && snippets.none { it.text == draft.value.text.trim() },
              onInsert = {
                draft.value = draft.value.withSnippet(it)
                focusPrompt = true
              },
              onSave = { viewModel.saveSnippet(draft.value.text) },
              onEdit = {
                // The dialog offers the server's agents for the snippets that go to them when they start.
                viewModel.loadAgents()
                editSnippets = true
              },
            )
          },
          onSend = viewModel::sendPrompt,
          onSendNow = { sendNow() },
        )
      }
    }
  }

  if (filesOpen) {
    FilesSheet(
      viewModel.files,
      cwd = viewModel.cwd,
      origin = viewModel.origin,
      workspace = filesWorkspace,
      onWorkspace = viewModel::setFilesWorkspace,
      onInsertPath = { path ->
        if (pane?.agent != null) {
          draft.value = draft.value.withPath(path)
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
      agents,
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

  missingProgram?.let { program ->
    MissingProgramDialog(
      program,
      onCopy = {
        missingProgram = null
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(LOGIN_SHELLS_SETTING, LOGIN_SHELLS_SETTING))
        scope.launch { snackbar.showSnackbar(resources.getString(R.string.agent_missing_copied)) }
      },
      onDismiss = { missingProgram = null },
    )
  }
}

/** herdr's setting for login shells in panes: they read the profile, and see the programs a terminal sees. */
private const val LOGIN_SHELLS_SETTING = "[terminal]\nshell_mode = \"login\""

/** The agent didn't start: the pane's shell has no [program]. Tells why it may be so and what to set on the host. */
@Composable
private fun MissingProgramDialog(program: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.agent_missing_title)) },
    text = { Text(stringResource(R.string.agent_missing_text, program, LOGIN_SHELLS_SETTING)) },
    confirmButton = { TextButton(onClick = onCopy, modifier = Modifier.testTag("copyLoginShells")) { Text(stringResource(R.string.agent_missing_copy)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_missing_ok)) } },
  )
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
 * would refuse it. Nor while the previous prompt is [sending]: one at a time, they reach the agent in order. Ctrl+Enter
 * from a keyboard goes to [onSendNow], where the field would start a new line.
 */
@Composable
private fun PromptRow(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  keyboard: KeyboardOptions,
  focus: FocusRequester,
  starting: Boolean,
  sending: Boolean,
  snippets: @Composable () -> Unit,
  onSend: () -> Unit,
  onSendNow: () -> Unit,
) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    snippets()
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      keyboardOptions = keyboard,
      placeholder = { Text(stringResource(if (starting) R.string.agent_starting else R.string.agent_prompt_hint)) },
      maxLines = 4,
      modifier =
        Modifier.weight(1f).focusRequester(focus).testTag("prompt").onPreviewKeyEvent {
          val ctrlEnter = (it.key == Key.Enter || it.key == Key.NumPadEnter) && it.isCtrlPressed && !it.isShiftPressed && !it.isAltPressed && !it.isMetaPressed
          // Once for a press: held down, the key repeats.
          if (ctrlEnter && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) onSendNow()
          ctrlEnter
        },
    )
    IconButton(onClick = onSend, enabled = value.text.isNotBlank() && !starting && !sending, modifier = Modifier.testTag("sendPrompt")) {
      Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.agent_send))
    }
  }
}

/**
 * The snippets button by the prompt. A snippet goes into the prompt at the cursor, to be sent as it is or with more
 * to it; the prompt's text can become a snippet, and the list is edited in [SnippetsDialog].
 */
@Composable
private fun SnippetsMenu(snippets: List<Snippet>, canSave: Boolean, onInsert: (String) -> Unit, onSave: () -> Unit, onEdit: () -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("snippets")) {
      Icon(painterResource(R.drawable.ic_snippets), contentDescription = stringResource(R.string.snippets_open))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("snippetsMenu")) {
      if (snippets.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.snippets_none)) }, onClick = {}, enabled = false)
      snippets.forEachIndexed { i, snippet ->
        DropdownMenuItem(
          text = {
            Column {
              Text(snippet.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
              snippet.onStart?.let {
                Text(
                  stringResource(R.string.snippets_on_start_hint, agentTitle(it)),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          },
          onClick = {
            open = false
            onInsert(snippet.text)
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

/** A row of [SnippetsDialog]. Rows carry ids of their own: a deleted row takes its fields along, not the next row's. */
private data class SnippetRow(val id: Int, val text: String, val onStart: String?)

/**
 * The snippets to change, reorder, delete or add to; blank ones and repeats go when the list is saved. Their order is
 * the menu's, and the one they go in to an agent at its start. A snippet can also go to an agent by itself when the
 * phone starts one: [agents] are those the server has, once known.
 */
@Composable
private fun SnippetsDialog(snippets: List<Snippet>, agents: List<AgentKind>?, onSave: (List<Snippet>) -> Unit, onDismiss: () -> Unit) {
  var rows by remember { mutableStateOf(snippets.ifEmpty { listOf(Snippet("")) }.mapIndexed { i, it -> SnippetRow(i, it.text, it.onStart) }) }
  var nextId by remember { mutableIntStateOf(rows.size) }
  fun change(id: Int, edit: (SnippetRow) -> SnippetRow) {
    rows = rows.map { if (it.id == id) edit(it) else it }
  }
  fun move(id: Int, step: Int) {
    val from = rows.indexOfFirst { it.id == id }
    val to = from + step
    if (from < 0 || to !in rows.indices) return
    rows = rows.toMutableList().apply { add(to, removeAt(from)) }
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.snippets_title)) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, row ->
          key(row.id) {
            Column {
              Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                  value = row.text,
                  onValueChange = { changed -> change(row.id) { it.copy(text = changed) } },
                  maxLines = 4,
                  modifier = Modifier.weight(1f).testTag("snippetField-$i"),
                )
                IconButton(onClick = { rows = rows.filter { it.id != row.id } }, modifier = Modifier.testTag("deleteSnippet-$i")) {
                  Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.snippets_delete))
                }
              }
              // The arrows go by the start choice, so that the text field keeps its width.
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                  StartChoice(row.onStart, agents, Modifier.testTag("snippetStart-$i")) { kind -> change(row.id) { it.copy(onStart = kind) } }
                }
                IconButton(onClick = { move(row.id, -1) }, enabled = i > 0, modifier = Modifier.testTag("moveSnippetUp-$i")) {
                  Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.snippets_move_up))
                }
                IconButton(onClick = { move(row.id, 1) }, enabled = i < rows.lastIndex, modifier = Modifier.testTag("moveSnippetDown-$i")) {
                  Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.snippets_move_down))
                }
              }
            }
          }
        }
        TextButton(
          onClick = {
            rows = rows + SnippetRow(nextId, "", null)
            nextId++
          },
          modifier = Modifier.testTag("addSnippet"),
        ) {
          Icon(Icons.Filled.Add, contentDescription = null)
          Text(stringResource(R.string.snippets_add), Modifier.padding(start = 8.dp))
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(rows.map { Snippet(it.text, it.onStart) }) }, modifier = Modifier.testTag("saveSnippets")) {
        Text(stringResource(R.string.snippets_done))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

/** Which agent a snippet goes to when the phone starts it: none, one of [agents], or the one it goes to already. */
@Composable
private fun StartChoice(onStart: String?, agents: List<AgentKind>?, modifier: Modifier, onChange: (String?) -> Unit) {
  var open by remember { mutableStateOf(false) }
  val kinds = (agents.orEmpty().map { it.kind } + listOfNotNull(onStart)).distinct()
  Box {
    TextButton(onClick = { open = true }, modifier = modifier) {
      Text(stringResource(R.string.snippets_on_start, onStart?.let(::agentTitle) ?: stringResource(R.string.snippets_on_start_none)))
      Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      @Composable
      fun option(kind: String?, title: String) =
        DropdownMenuItem(
          text = { Text(title) },
          onClick = {
            open = false
            onChange(kind)
          },
          modifier = Modifier.testTag("startOption-${kind ?: "none"}"),
        )

      option(null, stringResource(R.string.snippets_on_start_none))
      for (kind in kinds) option(kind, agentTitle(kind))
    }
  }
}

/** The name of agent kind [kind], or [kind] itself for one this version doesn't know. */
private fun agentTitle(kind: String): String = AgentKind.entries.firstOrNull { it.kind == kind }?.title ?: kind

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

/** Shared [text] put in at the cursor, with spaces from the words around it: the paths of shared files may follow. */
private fun TextFieldValue.withText(text: String): TextFieldValue = withWord(text, spaceAtEnd = true)

/** A share's text or a shared file's path put in at the cursor; see [withText] and [withPath]. */
private fun TextFieldValue.with(part: PromptPart): TextFieldValue =
  when (part) {
    is PromptPart.Text -> withText(part.text)
    is PromptPart.Path -> withPath(part.path)
    is PromptPart.Failed -> this
  }

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
