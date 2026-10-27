package dev.artalex.herdr.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode

/** The test tag of pane [paneId] of server [serverId] on the agent list. */
fun paneTag(paneId: String, serverId: String = HostFixture.SERVER_ID) = "pane-$serverId/$paneId"

/** The test tag of pane [paneId] of server [serverId] in the agent screen's pane switcher. */
fun switchTag(paneId: String, serverId: String = HostFixture.SERVER_ID) = "switch-$serverId/$paneId"

/** Opens the dialog that starts an agent on server [serverId], once it has looked up the machine's agents. */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.openNewAgent(serverId: String = HostFixture.SERVER_ID) {
  waitUntilExactlyOneExists(hasTestTag("newAgent-$serverId"), 30_000)
  onNodeWithTag("newAgent-$serverId").performClick()
  waitUntilExactlyOneExists(hasTestTag("newAgentKind-claude"), 10_000)
}

/**
 * Scrolls the agent list to the item tagged [tag], once it is there: the list shows the servers' names before
 * their connections bring the panes.
 */
fun ComposeTestRule.scrollListTo(tag: String, timeoutMs: Long = 30_000) {
  waitUntil(timeoutMs) { runCatching { onNodeWithTag("board").performScrollToNode(hasTestTag(tag)) }.isSuccess }
}
