package dev.artalex.herdr.core.files

/**
 * A path on the screen: [shown] as written there (a file link's path decoded), and what it may stand for, most likely
 * first. A path the screen wrapped onto the next rows is tried whole first, then by its pieces.
 */
data class PathMention(val shown: String, val variants: List<String>)

/**
 * Finds file paths in a pane's visible text, as `pane.read` gives it: rows no wider than the pane ([width]
 * columns), trailing spaces trimmed, long lines wrapped onto the next rows by the terminal or by the agent itself,
 * see [wraps]. Without [width] wrapped paths are taken piece by piece.
 *
 * A path has a slash: absolute, from home (`~/…`), or relative (`./…`, `../…`, `dir/name.ext`; a plain relative
 * one needs a dot in its last part, so that `and/or` and `src/main` don't count). A file link, such as Codex's
 * `file:///home/u/a.png` for a picture it made, stands for its path, `%`-escapes decoded. Words with other links or
 * with `…` (paths shortened to fit) are skipped, and so is a path ending in a slash, which is a folder. A line
 * number after a colon, brackets, quotes and the full stop that ends a sentence aren't part of the path.
 */
fun findPathMentions(screen: String, width: Int?): List<PathMention> {
  val rows = screen.split('\n').map { it.trimEnd('\r', ' ') }
  val words = rows.map { row -> WORD.findAll(row).map { it.value }.toList() }
  val found = LinkedHashMap<String, PathMention>()
  // Rows whose first word continues a path from the row above.
  val continued = mutableSetOf<Int>()
  for (i in rows.indices) {
    for ((w, word) in words[i].withIndex()) {
      if (w == 0 && i in continued) continue
      val pieces = mutableListOf(word)
      var row = i
      var last = w == words[i].lastIndex
      while (last && row + 1 < rows.size) {
        val next = words[row + 1].firstOrNull() ?: break
        // A link on the next row, a file link too, is a word of its own; so is a path shortened there.
        if ("://" in next || '…' in next || !wraps(rows[row], pieces.last(), next, width)) break
        pieces += next
        continued += row + 1
        last = words[row + 1].size == 1
        row++
      }
      for (mention in mentionsIn(pieces)) found.putIfAbsent(mention.shown, mention)
    }
  }
  return found.values.toList()
}

/**
 * Whether [row], which ends in the word [end], goes on in [next], the first word of the row below, in a pane [width]
 * columns wide. The terminal and Claude break a long word at the pane's edge, Claude with an indent. Codex breaks it
 * after a slash or a hyphen once the next piece doesn't fit, so its row may end well before the edge, but then [next]
 * wouldn't fit after it either. A line that ends in a folder or a hyphen has room left for a short word below it.
 */
private fun wraps(row: String, end: String, next: String, width: Int?): Boolean {
  if (width == null) return false
  val used = displayWidth(row)
  return used >= width - WRAP_SLACK || (end.endsWith('/') || end.endsWith('-')) && used + displayWidth(next) > width
}

/**
 * The paths in a word that may run on over several rows, given as its [pieces]. A path across a row's end gets the
 * shorter readings as fallbacks: the start of it on fewer rows, then each piece on its own. If the whole doesn't read
 * as a path, the first reading that does stands for it.
 */
private fun mentionsIn(pieces: List<String>): List<PathMention> {
  val word = pieces.joinToString("")
  if (skipped(word)) return emptyList()
  val ends = pieces.runningFold(0) { at, piece -> at + piece.length }.drop(1).dropLast(1)
  // Blanks take the place of a file link's scheme, so its path is a token of its own and the rows end where they did.
  val links = FILE_LINK.findAll(word).map { it.range.last + 1 }.toSet()
  val text = FILE_LINK.replace(word) { " ".repeat(it.value.length) }
  return TOKEN.findAll(text).mapNotNull { token ->
    val first = token.range.first
    val cuts = ends.filter { it > first && it <= token.range.last }
    val starts = cuts.reversed().map { cut -> text.substring(first, cut) }
    val bits = (listOf(first) + cuts + (token.range.last + 1)).zipWithNext { a, b -> text.substring(a, b) }
    val variants = (listOf(token.value) + starts + bits).mapNotNull { cleanPath(if (first in links) unescape(it) else it) }.distinct()
    variants.firstOrNull()?.let { PathMention(it, variants) }
  }.toList()
}

/** A link other than a file link, or a path shortened to fit: nothing to find on the host. */
private fun skipped(word: String) = "://" in FILE_LINK.replace(word, "") || '…' in word

/** [path] from a file link with its `%`-escapes, UTF-8 bytes, decoded: `%20` is a space, `%C3%A9` is `é`. */
private fun unescape(path: String) =
  ESCAPES.replace(path) { run ->
    val bytes = run.value.chunked(3).map { it.drop(1).toInt(16).toByte() }.toByteArray()
    String(bytes, Charsets.UTF_8)
  }

/** A token as a path, or null if it doesn't look like a file: the rules of [findPathMentions]. */
private fun cleanPath(token: String): String? {
  val path = token.removePrefix("@").trimEnd('.')
  if ('/' !in path || path.endsWith('/') || "//" in path || path.none { it.isLetterOrDigit() }) return null
  if (path.startsWith('/') || path.startsWith("~/") || path.startsWith("./") || path.startsWith("../")) return path
  if (path.startsWith('~')) return null
  val name = path.substringAfterLast('/')
  return path.takeIf { name.indexOf('.') in 1 until name.length - 1 }
}

/**
 * The absolute path [path] stands for: `~` is [home], a relative path starts in [cwd], and `.` and `..` are
 * resolved by name. Null for a relative path when [cwd] is unknown.
 */
fun resolvePath(path: String, home: String, cwd: String?): String? {
  val absolute =
    when {
      path == "~" -> home
      path.startsWith("~/") -> home + path.substring(1)
      path.startsWith("/") -> path
      cwd != null -> "$cwd/$path"
      else -> return null
    }
  val parts = ArrayDeque<String>()
  for (part in absolute.split('/')) {
    when (part) {
      "", "." -> Unit
      ".." -> parts.removeLastOrNull()
      else -> parts.addLast(part)
    }
  }
  return parts.joinToString("/", prefix = "/")
}

/** Terminal cells [row] takes: East Asian wide characters and emoji take two. */
internal fun displayWidth(row: String): Int {
  var cells = 0
  var i = 0
  while (i < row.length) {
    val cp = row.codePointAt(i)
    cells += if (WIDE.any { cp in it }) 2 else 1
    i += Character.charCount(cp)
  }
  return cells
}

private val WORD = Regex("\\S+")

/** Characters a path is made of here; a colon, brackets, quotes and commas end it. */
private val TOKEN = Regex("[\\p{L}\\p{M}\\p{N}._~+@%=#/-]+")

/** The scheme of a link to a file on this machine, up to the slash its path starts with. */
private val FILE_LINK = Regex("file://(localhost)?(?=/)", RegexOption.IGNORE_CASE)

private val ESCAPES = Regex("(%[0-9A-Fa-f]{2})+")

/** A row this close to the pane's width may continue on the next row: agents leave a column or two free. */
private const val WRAP_SLACK = 2

private val WIDE =
  listOf(
    0x1100..0x115F, 0x2E80..0xA4CF, 0xAC00..0xD7A3, 0xF900..0xFAFF, 0xFE30..0xFE4F, 0xFF00..0xFF60, 0xFFE0..0xFFE6,
    0x1F300..0x1F64F, 0x1F900..0x1F9FF, 0x20000..0x3FFFD,
  )
