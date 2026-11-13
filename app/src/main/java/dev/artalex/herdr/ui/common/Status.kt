package dev.artalex.herdr.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.artalex.herdr.R
import dev.artalex.herdr.connection.ConnectionProblem
import dev.artalex.herdr.connection.ConnectionState
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.theme.Amber
import dev.artalex.herdr.theme.OnAmber

/**
 * The agent's status. [stale]: as the session last told it, which may no longer hold: no connection, or no answer to
 * the last poll. Such a chip is dimmed, and TalkBack says it may be out of date.
 */
@Composable
fun StatusChip(status: AgentStatus, modifier: Modifier = Modifier, stale: Boolean = false) {
  val colors = MaterialTheme.colorScheme
  val (container, content) =
    when (status) {
      AgentStatus.BLOCKED -> Amber to OnAmber
      AgentStatus.DONE -> colors.primaryContainer to colors.onPrimaryContainer
      AgentStatus.WORKING -> colors.tertiaryContainer to colors.onTertiaryContainer
      AgentStatus.IDLE, AgentStatus.UNKNOWN -> colors.surfaceVariant to colors.onSurfaceVariant
    }
  val staleText = stringResource(R.string.status_stale)
  // One semantic node, so tests and TalkBack read the chip as its label.
  Surface(
    color = container,
    contentColor = content,
    shape = MaterialTheme.shapes.small,
    modifier = modifier.alpha(if (stale) STALE_ALPHA else 1f).semantics(mergeDescendants = true) { if (stale) stateDescription = staleText },
  ) {
    Text(statusText(status), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
  }
}

/** As Material dims what is disabled. */
private const val STALE_ALPHA = 0.38f

@Composable
fun statusText(status: AgentStatus): String =
  stringResource(
    when (status) {
      AgentStatus.BLOCKED -> R.string.status_blocked
      AgentStatus.DONE -> R.string.status_done
      AgentStatus.WORKING -> R.string.status_working
      AgentStatus.IDLE -> R.string.status_idle
      AgentStatus.UNKNOWN -> R.string.status_unknown
    }
  )

/** How the connection of a server that is on is doing, as the list says under the server's name; null while all is well. */
@Composable
fun connectionText(state: ConnectionState): String? =
  when (state) {
    is ConnectionState.Connected -> if (state.restartNeeded) stringResource(R.string.state_restart_needed) else null
    ConnectionState.Connecting -> stringResource(R.string.state_connecting)
    ConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
    ConnectionState.NotConfigured -> stringResource(R.string.state_no_key)
    is ConnectionState.Failed -> {
      val retry = state.retryInMs?.let { " " + stringResource(R.string.state_retry, (it / 1000).toInt()) } ?: ""
      problemText(state.problem) + retry
    }
  }

@Composable
fun problemText(problem: ConnectionProblem): String =
  when (problem) {
    is ConnectionProblem.UnknownHostKey -> stringResource(R.string.problem_unknown_host_key)
    is ConnectionProblem.HostKeyChanged -> stringResource(R.string.problem_host_key_changed)
    ConnectionProblem.KeyRejected -> stringResource(R.string.problem_key_rejected)
    ConnectionProblem.LocalNetworkDenied -> stringResource(R.string.problem_local_network)
    is ConnectionProblem.Unreachable -> stringResource(R.string.problem_unreachable, problem.detail)
    is ConnectionProblem.HerdrMissing -> stringResource(R.string.problem_herdr_missing, problem.path)
    is ConnectionProblem.BadSessionName -> stringResource(R.string.problem_bad_session, problem.session, problem.reason)
    ConnectionProblem.HerdrNotRunning -> stringResource(R.string.problem_herdr_not_running)
    is ConnectionProblem.HerdrTooOld -> stringResource(R.string.problem_herdr_too_old, problem.version)
    is ConnectionProblem.Other -> stringResource(R.string.problem_other, problem.detail)
  }
