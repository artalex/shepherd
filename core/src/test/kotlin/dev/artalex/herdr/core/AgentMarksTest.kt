package dev.artalex.herdr.core

import dev.artalex.herdr.core.model.AgentInfo
import dev.artalex.herdr.core.model.AgentStatus
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.notify.AgentMark
import dev.artalex.herdr.core.notify.AgentMarks
import dev.artalex.herdr.core.notify.agentMarks
import dev.artalex.herdr.core.notify.notableChanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentMarksTest {
  /** An agent's mark; [finished] is the status change on which it finished work, for one that did. */
  private fun mark(status: AgentStatus, seq: Long = 1, finished: Long? = null) = AgentMark(status, seq, finished)

  @Test
  fun marksComeFromTheSnapshotsAgents() {
    val snapshot =
      SessionSnapshot(
        version = "0.9.5",
        protocol = 22,
        agents =
          listOf(
            AgentInfo("p1", "t1", "w1", "tab1", agent = "claude", agentStatus = AgentStatus.BLOCKED, stateChangeSeq = 7),
            AgentInfo("p2", "t2", "w1", "tab1", agent = "codex", agentStatus = AgentStatus.IDLE, stateChangeSeq = 3, completionSeq = 3),
          ),
      )
    assertEquals(mapOf("p1" to mark(AgentStatus.BLOCKED, 7), "p2" to mark(AgentStatus.IDLE, 3, finished = 3)), snapshot.agentMarks())
  }

  @Test
  fun firstLookIsNoNews() {
    assertEquals(emptyMap(), notableChanges(null, mapOf("p1" to mark(AgentStatus.BLOCKED))))
  }

  @Test
  fun agentThatStartsWaitingOrFinishesIsNews() {
    val before = mapOf("p1" to mark(AgentStatus.WORKING, 4), "p2" to mark(AgentStatus.WORKING, 2))
    val after = mapOf("p1" to mark(AgentStatus.BLOCKED, 5), "p2" to mark(AgentStatus.DONE, 3, finished = 3))
    assertEquals(mapOf("p1" to AgentStatus.BLOCKED, "p2" to AgentStatus.DONE), notableChanges(before, after))
  }

  @Test
  fun agentThatFinishesIdleIsDoneToo() {
    // In the active tab herdr counts the pane as seen, even with no desktop attached: the agent finishes idle.
    val before = mapOf("p1" to mark(AgentStatus.WORKING, 4))
    assertEquals(mapOf("p1" to AgentStatus.DONE), notableChanges(before, mapOf("p1" to mark(AgentStatus.IDLE, 5, finished = 5))))
  }

  @Test
  fun unchangedOrDullStatusIsNoNews() {
    val before =
      mapOf(
        "p1" to mark(AgentStatus.BLOCKED, 5),
        "p2" to mark(AgentStatus.WORKING, 2),
        "p3" to mark(AgentStatus.DONE, 8, finished = 8),
        "p4" to mark(AgentStatus.UNKNOWN, 1),
      )
    // Still waiting on the same question; started working; looked at on the desktop, which turns done into idle and
    // leaves the finish as it was; started up and ready for input, which is no finished work.
    val after =
      mapOf(
        "p1" to mark(AgentStatus.BLOCKED, 5),
        "p2" to mark(AgentStatus.WORKING, 2),
        "p3" to mark(AgentStatus.IDLE, 8, finished = 8),
        "p4" to mark(AgentStatus.IDLE, 2),
      )
    assertEquals(emptyMap(), notableChanges(before, after))
  }

  @Test
  fun statusThatLeftAndCameBackBetweenLooksIsNews() {
    // Answered and asked again between two checks: the status is the same, the count of changes isn't. Worked and
    // finished again: a finish of its own.
    val before = mapOf("p1" to mark(AgentStatus.BLOCKED, 5), "p2" to mark(AgentStatus.IDLE, 6, finished = 6))
    val after = mapOf("p1" to mark(AgentStatus.BLOCKED, 7), "p2" to mark(AgentStatus.IDLE, 8, finished = 8))
    assertEquals(mapOf("p1" to AgentStatus.BLOCKED, "p2" to AgentStatus.DONE), notableChanges(before, after))
  }

  @Test
  fun newAgentInNotableStatusIsNews() {
    val before = mapOf("p1" to mark(AgentStatus.WORKING))
    val after = before + ("p9" to mark(AgentStatus.BLOCKED)) + ("p8" to mark(AgentStatus.WORKING)) + ("p7" to mark(AgentStatus.IDLE, 3, finished = 3))
    assertEquals(mapOf("p9" to AgentStatus.BLOCKED, "p7" to AgentStatus.DONE), notableChanges(before, after))
  }

  @Test
  fun marksRoundTripAndUnreadableTextIsAFirstLook() {
    val marks = mapOf("p1" to mark(AgentStatus.BLOCKED, 5), "p2" to mark(AgentStatus.UNKNOWN, 0), "p3" to mark(AgentStatus.IDLE, 6, finished = 6))
    assertEquals(marks, AgentMarks.decode(AgentMarks.encode(marks)))
    assertEquals(emptyMap(), AgentMarks.decode(AgentMarks.encode(emptyMap())))
    assertNull(AgentMarks.decode(null))
    assertNull(AgentMarks.decode("{broken"))
    assertNull(AgentMarks.decode("""["p1"]"""))
    // Marks of 2.4 and before know no finishes: the look after the update is a first one, not news of old finishes.
    assertNull(AgentMarks.decode("""{"p1":{"status":"idle","seq":6}}"""))
  }
}
