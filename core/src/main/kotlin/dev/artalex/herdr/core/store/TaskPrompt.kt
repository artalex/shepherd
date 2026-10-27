package dev.artalex.herdr.core.store

/** [path] as a prompt takes it: in double quotes if it has whitespace of its own. */
fun promptPath(path: String): String = if (path.any { it.isWhitespace() }) "\"$path\"" else path

/**
 * The prompt of a task the phone gives a new agent: the [task] as the user wrote it, then what came with it from
 * another app, the [shared] text and the [paths] of the files, now on the host, one to a line, and last [note], what to
 * do with the answer. A blank line comes between them, and a part there is nothing of is left out.
 */
fun taskPrompt(task: String, shared: String?, paths: List<String>, note: String? = null): String =
  listOf(task, shared.orEmpty(), paths.joinToString("\n", transform = ::promptPath), note.orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
