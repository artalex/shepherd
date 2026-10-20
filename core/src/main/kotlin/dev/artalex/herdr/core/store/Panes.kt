package dev.artalex.herdr.core.store

import dev.artalex.herdr.core.model.AgentKind
import dev.artalex.herdr.core.rpc.ApiError
import dev.artalex.herdr.core.rpc.HerdrApi
import kotlinx.coroutines.delay

/** Name attempts before giving up on `agent_name_taken`. */
private const val NAME_ATTEMPTS = 9

/** How long a new pane's shell may take to come up, and how often to ask meanwhile. */
private const val NEW_SHELL_WAIT_MS = 5_000L
private const val NEW_SHELL_POLL_MS = 200L

/**
 * herdr name for a [kind] agent started in [cwd]: the directory's last part in herdr's alphabet (a lowercase
 * letter first, then lowercase letters, digits, '-' or '_'; at most 32), the kind when nothing is left, and
 * `-2`, `-3`… for later [attempt]s.
 */
fun agentName(cwd: String?, kind: AgentKind, attempt: Int = 1): String {
  val word = cwd?.trimEnd('/')?.substringAfterLast('/')?.lowercase()?.replace(Regex("[^a-z0-9_-]+"), "-")?.trim('-', '_').orEmpty()
  val base =
    when {
      word.isEmpty() -> kind.kind
      word.first() in 'a'..'z' -> word
      else -> "${kind.kind}-$word"
    }
  val suffix = if (attempt > 1) "-$attempt" else ""
  return base.take(32 - suffix.length).trimEnd('-', '_') + suffix
}

/** Starts a [kind] agent in [paneId], named after [cwd]; a name another agent holds moves on to the next one. */
suspend fun HerdrApi.startNamedAgent(paneId: String, kind: AgentKind, cwd: String?) {
  for (attempt in 1..NAME_ATTEMPTS) {
    try {
      startAgent(paneId, agentName(cwd, kind, attempt), kind.kind)
      return
    } catch (e: ApiError) {
      if (e.code != "agent_name_taken" || attempt == NAME_ATTEMPTS) throw e
    }
  }
}

/**
 * Starts a [kind] agent in a pane that was just created. herdr counts the pane as a shell only once the shell runs in
 * the foreground of its terminal, and until then answers `agent_pane_busy`.
 */
suspend fun HerdrApi.startNamedAgentInNewPane(paneId: String, kind: AgentKind, cwd: String?) {
  val deadline = System.currentTimeMillis() + NEW_SHELL_WAIT_MS
  while (true) {
    try {
      startNamedAgent(paneId, kind, cwd)
      return
    } catch (e: ApiError) {
      if (e.code != "agent_pane_busy" || System.currentTimeMillis() >= deadline) throw e
    }
    delay(NEW_SHELL_POLL_MS)
  }
}
