package dev.artalex.herdr.ui.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.artalex.herdr.R
import dev.artalex.herdr.core.files.AddResult
import dev.artalex.herdr.core.files.FileOrigin
import dev.artalex.herdr.core.files.FileScope
import dev.artalex.herdr.core.files.FileShelf
import dev.artalex.herdr.core.files.FoundFile
import dev.artalex.herdr.core.files.ScanEntry
import dev.artalex.herdr.core.files.ScanState
import dev.artalex.herdr.core.files.Transfer
import dev.artalex.herdr.core.files.Upload
import dev.artalex.herdr.files.FileTransfers
import dev.artalex.herdr.files.Opening
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The files window of the agent screen. "Incoming": files whose paths are on the screen and those found before;
 * a tap downloads one and opens it. "Outgoing": files sent from the phone; a tap gives [onInsertPath] the path on
 * the host. Paths relative to the agent's folder start in [cwd]. Both list the files of the pane of [origin], or with
 * [workspace] those of its whole workspace; [onWorkspace] switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesSheet(
  files: FileTransfers,
  cwd: String?,
  origin: FileOrigin?,
  workspace: Boolean,
  onWorkspace: (Boolean) -> Unit,
  onInsertPath: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val shelf = files.shelf
  val book by shelf.book.collectAsStateWithLifecycle()
  val scan by shelf.scan.collectAsStateWithLifecycle()
  val downloads by files.downloads.collectAsStateWithLifecycle()
  val uploads by files.uploads.collectAsStateWithLifecycle()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  val resources = LocalResources.current
  var tab by rememberSaveable { mutableIntStateOf(0) }
  var confirmClear by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(files) {
    launch { files.messages.collect { snackbar.showSnackbar(resources.getString(it.text, it.arg)) } }
    files.openings.collect { if (!view(context, it)) snackbar.showSnackbar(resources.getString(R.string.files_no_viewer, it.name)) }
  }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> files.upload(uris, origin) }
  // Until the pane is known, everything shows.
  val fileScope = origin?.let { FileScope(it, workspace) }
  val listed = book.found.filter { fileScope?.has(it) ?: true }
  val sentListed = book.sent.filter { fileScope?.has(it) ?: true }
  val uploadsListed = uploads.filter { fileScope?.has(listOfNotNull(it.origin)) ?: true }
  val dismiss = {
    files.closeScan()
    onDismiss()
  }

  ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState, modifier = Modifier.testTag("filesSheet")) {
    Box(Modifier.imePadding()) {
      Column(Modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = tab) {
          Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.files_incoming)) }, modifier = Modifier.testTag("tab-incoming"))
          Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.files_outgoing)) }, modifier = Modifier.testTag("tab-outgoing"))
        }
        if (origin != null) ScopeSwitch(workspace, onWorkspace)
        if (tab == 0) {
          LazyColumn(Modifier.fillMaxWidth().testTag("incoming")) {
            item { PathField { files.add(it, cwd, origin) } }
            incoming(shelf, scan, book.found, listed, downloads, book.home, files, onClear = { confirmClear = 0 })
          }
        } else {
          LazyColumn(Modifier.fillMaxWidth().testTag("outgoing")) {
            item {
              OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("sendFiles")) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.files_send), Modifier.padding(start = 8.dp))
              }
            }
            items(uploadsListed, key = { "upload-${it.id}" }) { upload ->
              UploadRow(upload, onCancel = { files.cancel(upload) }, onRetry = { files.retry(upload) }, onDismiss = { files.dismiss(upload) })
            }
            items(sentListed, key = { "sent-${it.path}" }) { sent ->
              Swipeable(onForget = { shelf.forgetSent(sent.path) }) {
                FileRow(
                  name = sent.path.substringAfterLast('/'),
                  details = listOf(shortDir(sent.path, book.home), sizeText(sent.size)).joinToString(" · "),
                  tag = "sent-${sent.path}",
                  onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                      dismiss()
                      onInsertPath(sent.path)
                    }
                  },
                )
              }
            }
            if (sentListed.isNotEmpty()) item { ClearButton { confirmClear = 1 } }
            if (uploadsListed.isEmpty() && sentListed.isEmpty()) item { Note(stringResource(R.string.files_outgoing_empty)) }
          }
        }
      }
      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) {
        Snackbar(it, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)
      }
    }
  }

  confirmClear?.let { which ->
    AlertDialog(
      onDismissRequest = { confirmClear = null },
      title = { Text(stringResource(R.string.files_clear_title)) },
      text = { Text(stringResource(if (which == 0) R.string.files_clear_incoming else R.string.files_clear_outgoing)) },
      confirmButton = {
        TextButton(
          onClick = {
            // Only the files the window shows: those of other panes stay.
            if (which == 0) shelf.forget(listed.map { it.path }) else shelf.forgetSent(sentListed.map { it.path })
            confirmClear = null
          },
          modifier = Modifier.testTag("confirmClear"),
        ) {
          Text(stringResource(R.string.files_clear_confirm), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmClear = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

/**
 * The paths on the screen at this opening of the window, then the files found before: those [listed] for the pane or
 * its workspace, of all the server's [found] files. In both, what the host doesn't have goes after the rest, faded:
 * there is nothing to download.
 */
private fun LazyListScope.incoming(
  shelf: FileShelf,
  scan: List<ScanEntry>?,
  found: List<FoundFile>,
  listed: List<FoundFile>,
  downloads: Map<String, Transfer>,
  home: String?,
  files: FileTransfers,
  onClear: () -> Unit,
) {
  val byPath = found.associateBy { it.path }
  @Composable
  fun found(file: FoundFile) =
    Swipeable(onForget = { shelf.forget(file.path) }) {
      FoundRow(file, downloads[file.path], home, onOpen = { files.open(file) }, onCancel = { files.cancelDownload(file.path) })
    }

  if (scan != null) {
    item { Header(R.string.files_on_screen) }
    if (scan.isEmpty()) item { Note(stringResource(R.string.files_none_on_screen)) }
    // Paths not found and files gone since they were found go last, in the screen's order like the rest.
    val shown = scan.sortedBy { it.state == ScanState.MISSING || byPath[it.path]?.gone == true }
    items(shown, key = { "scan-" + (it.path ?: it.shown) }) { entry ->
      when (entry.state) {
        ScanState.FOUND -> byPath[entry.path]?.let { found(it) }
        ScanState.CHECKING -> PathRow(entry.shown, home, stringResource(R.string.files_checking), checking = true)
        ScanState.MISSING -> PathRow(entry.shown, home, stringResource(R.string.files_missing), checking = false)
      }
    }
  }
  val onScreen = scan.orEmpty().mapNotNullTo(HashSet()) { it.path }
  val earlier = listed.filter { it.path !in onScreen }.sortedBy { it.gone }
  if (earlier.isNotEmpty()) {
    if (scan != null) item { Header(R.string.files_earlier) }
    items(earlier, key = { "found-${it.path}" }) { found(it) }
  }
  if (listed.isNotEmpty()) item { ClearButton(onClear) }
  if (scan.isNullOrEmpty() && listed.isEmpty()) item { Note(stringResource(R.string.files_incoming_empty)) }
}

/** The files of the window's pane, or of the pane's whole workspace. */
@Composable
private fun ScopeSwitch(workspace: Boolean, onChange: (Boolean) -> Unit) {
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    val choices = listOf(false to R.string.files_scope_pane, true to R.string.files_scope_workspace)
    choices.forEachIndexed { i, (whole, label) ->
      SegmentedButton(
        selected = whole == workspace,
        onClick = { onChange(whole) },
        shape = SegmentedButtonDefaults.itemShape(i, choices.size),
        modifier = Modifier.testTag(if (whole) "scope-workspace" else "scope-pane"),
      ) {
        Text(stringResource(label))
      }
    }
  }
}

/** A path typed by hand, for what the screen doesn't show as a path: one with spaces, say. */
@Composable
private fun PathField(add: suspend (String) -> AddResult?) {
  var text by rememberSaveable { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  // A lookup under way: another one for the same path would only race it.
  var busy by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val focus = LocalFocusManager.current
  val resources = LocalResources.current
  val submit: () -> Unit = submit@{
    if (busy) return@submit
    val typed = text
    busy = true
    // Back on the main thread after the lookup: clearing focus touches the views.
    scope.launch(Dispatchers.Main.immediate) {
      error =
        try {
          when (add(typed)) {
            AddResult.ADDED -> {
              // Added: the keyboard goes, the list with the new file shows.
              text = ""
              focus.clearFocus()
              null
            }
            AddResult.MISSING -> resources.getString(R.string.files_path_missing)
            AddResult.NOT_A_FILE -> resources.getString(R.string.files_path_not_file)
            null -> resources.getString(R.string.files_no_connection)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // The host was reached, or not for long: say what went wrong rather than that there is no connection.
          Log.w("HerdrFiles", "adding $typed failed: ${e.message}")
          resources.getString(R.string.files_path_failed, e.message ?: e.javaClass.simpleName)
        } finally {
          busy = false
        }
    }
  }
  OutlinedTextField(
    value = text,
    onValueChange = {
      text = it
      error = null
    },
    singleLine = true,
    label = { Text(stringResource(R.string.files_path_hint)) },
    isError = error != null,
    supportingText = error?.let { { Text(it) } },
    trailingIcon = {
      IconButton(onClick = submit, enabled = text.isNotBlank() && !busy, modifier = Modifier.testTag("addPath")) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.files_path_add))
      }
    },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) submit() }),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("pathField"),
  )
}

@Composable
private fun FoundRow(file: FoundFile, transfer: Transfer?, home: String?, onOpen: () -> Unit, onCancel: () -> Unit) {
  val error = transfer?.error
  val status =
    when {
      error != null -> stringResource(R.string.files_download_failed, error)
      transfer != null -> stringResource(R.string.files_downloading, transfer.percent)
      file.gone -> stringResource(R.string.files_gone)
      file.fresh -> stringResource(R.string.files_downloaded)
      file.copy != null -> stringResource(R.string.files_changed)
      else -> null
    }
  FileRow(
    name = file.path.substringAfterLast('/'),
    details = listOfNotNull(shortDir(file.path, home), sizeText(file.size), timeText(file.mtime), status).joinToString(" · "),
    tag = "found-${file.path}",
    onClick = onOpen,
    faded = file.gone,
  ) {
    when {
      transfer != null && transfer.error == null ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(progress = { transfer.percent / 100f }, modifier = Modifier.size(24.dp))
          IconButton(onClick = onCancel, modifier = Modifier.testTag("cancel-${file.path}")) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.files_cancel))
          }
        }
      file.fresh -> Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.files_downloaded), tint = MaterialTheme.colorScheme.primary)
    }
  }
}

/** A path from the screen that isn't a known file: being checked, or not found. */
@Composable
private fun PathRow(path: String, home: String?, status: String, checking: Boolean) =
  FileRow(name = path.substringAfterLast('/'), details = listOfNotNull(shortDir(path, home), status).joinToString(" · "), tag = "path-$path", onClick = null, faded = !checking) {
    if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
  }

@Composable
private fun UploadRow(upload: Upload, onCancel: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
  val error = upload.transfer.error
  FileRow(
    name = upload.name,
    details = listOfNotNull(upload.size?.let { sizeText(it) }, if (error != null) stringResource(R.string.files_upload_failed, error) else stringResource(R.string.files_uploading, upload.transfer.percent))
      .joinToString(" · "),
    tag = "upload-${upload.name}",
    onClick = null,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (error == null) {
        CircularProgressIndicator(progress = { upload.transfer.percent / 100f }, modifier = Modifier.size(24.dp))
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.files_cancel)) }
      } else {
        IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.files_retry)) }
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.files_dismiss)) }
      }
    }
  }
}

/** A file's row; [faded] for a file the host doesn't have. */
@Composable
private fun FileRow(name: String, details: String, tag: String, onClick: (() -> Unit)?, faded: Boolean = false, trailing: @Composable () -> Unit = {}) =
  ListItem(
    headlineContent = { Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
    supportingContent = { Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    trailingContent = trailing,
    // The text only: the row stays opaque over the red of a swipe.
    colors =
      if (faded) {
        ListItemDefaults.colors(
          headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = FADED),
          supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FADED),
        )
      } else {
        ListItemDefaults.colors()
      },
    // One node for the whole row, clickable or not: TalkBack reads it at once, tests find its text by its tag.
    modifier = (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier.semantics(mergeDescendants = true) {}).testTag(tag),
  )

/** A row that a swipe either way takes off its list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Swipeable(onForget: () -> Unit, content: @Composable () -> Unit) {
  val state = rememberSwipeToDismissBoxState()
  SwipeToDismissBox(
    state = state,
    backgroundContent = {
      Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
        contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
      ) {
        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.files_forget), tint = MaterialTheme.colorScheme.onErrorContainer)
      }
    },
    onDismiss = { onForget() },
  ) {
    content()
  }
}

@Composable
private fun Header(text: Int) =
  Text(
    stringResource(text),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
  )

@Composable
private fun Note(text: String) =
  Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

@Composable
private fun ClearButton(onClick: () -> Unit) =
  Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
    TextButton(onClick = onClick, modifier = Modifier.testTag("clearFiles")) { Text(stringResource(R.string.files_clear)) }
  }

private fun view(context: Context, opening: Opening): Boolean =
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(opening.uri, opening.type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    true
  } catch (e: ActivityNotFoundException) {
    false
  }

/** The folder of [path], with the remote [home] as `~`. */
internal fun shortDir(path: String, home: String?): String {
  val dir = path.substringBeforeLast('/', "").ifEmpty { "/" }
  return if (home != null && (dir == home || dir.startsWith("$home/"))) "~" + dir.removePrefix(home) else dir
}

/** A size in the interface's language: 512 B, 3.4 MB, 16 MB; Russian has its own units and a decimal comma. */
@Composable
internal fun sizeText(bytes: Long): String {
  val units = stringArrayResource(R.array.file_size_units)
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  return if (unit == 0 || value >= 10) "${value.roundToLong()} ${units[unit]}"
  else String.format(LocalConfiguration.current.locales[0], "%.1f %s", value, units[unit])
}

/** A file's time as the phone writes it: just the time for today, the date too for other days. */
@Composable
internal fun timeText(mtime: Long): String {
  val time = Instant.ofEpochSecond(mtime).atZone(ZoneId.systemDefault())
  val today = LocalDate.now()
  val flags =
    when {
      time.toLocalDate() == today -> DateUtils.FORMAT_SHOW_TIME
      time.year == today.year -> DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME
      else -> DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_SHOW_YEAR
    }
  return DateUtils.formatDateTime(LocalContext.current, mtime * 1000, flags)
}

/** The alpha Material gives content that can't be used. */
private const val FADED = 0.38f
