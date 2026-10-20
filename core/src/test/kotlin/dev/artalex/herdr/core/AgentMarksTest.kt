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
  private fun mark(status: AgentStatus, seq: Long = 1) = AgentMark(status, seq)

  @Test
  fun marksComeFromTheSnapshotsAgents() {
    val snapshot =
      SessionSnapshot(
        version = "0.9.5",
        protocol = 22,
        agents =
          listOf(
            AgentInfo("p1", "t1", "w1", "tab1", agent = "claude", agentStatus = AgentStatus.BLOCKED, stateChangeSeq = 7),
            AgentInfo("p2", "t2", "w1", "tab1", agent = "codex", agentStatus = AgentStatus.WORKING, stateChangeSeq = 3),
          ),
      )
    assertEquals(mapOf("p1" to mark(AgentStatus.BLOCKED, 7), "p2" to mark(AgentStatus.WORKING, 3)), snapshot.agentMarks())
  }

  @Test
  fun firstLookIsNoNews() {
    assertEquals(emptyMap(), notableChanges(null, mapOf("p1" to mark(AgentStatus.BLOCKED))))
  }

  @Test
  fun agentThatStartsWaitingOrFinishesIsNews() {
    val before = mapOf("p1" to mark(AgentStatus.WORKING, 4), "p2" to mark(AgentStatus.WORKING, 2))
    val after = mapOf("p1" to mark(AgentStatus.BLOCKED, 5), "p2" to mark(AgentStatus.DONE, 3))
    assertEquals(mapOf("p1" to AgentStatus.BLOCKED, "p2" to AgentStatus.DONE), notableChanges(before, after))
  }

  @Test
  fun unchangedOrDullStatusIsNoNews() {
    val before = mapOf("p1" to mark(AgentStatus.BLOCKED, 5), "p2" to mark(AgentStatus.WORKING, 2), "p3" to mark(AgentStatus.DONE, 8))
    // Still waiting on the same question; started working; looked at on the desktop (done → idle).
    val after = mapOf("p1" to mark(AgentStatus.BLOCKED, 5), "p2" to mark(AgentStatus.WORKING, 2), "p3" to mark(AgentStatus.IDLE, 9))
    assertEquals(emptyMap(), notableChanges(before, after))
  }

  @Test
  fun statusThatLeftAndCameBackBetweenLooksIsNews() {
    // Answered and asked again between two checks: the status is the same, the count of changes isn't.
    val before = mapOf("p1" to mark(AgentStatus.BLOCKED, 5))
    assertEquals(mapOf("p1" to AgentStatus.BLOCKED), notableChanges(before, mapOf("p1" to mark(AgentStatus.BLOCKED, 7))))
  }

  @Test
  fun newAgentInNotableStatusIsNews() {
    val before = mapOf("p1" to mark(AgentStatus.WORKING))
    val after = before + ("p9" to mark(AgentStatus.BLOCKED)) + ("p8" to mark(AgentStatus.WORKING))
    assertEquals(mapOf("p9" to AgentStatus.BLOCKED), notableChanges(before, after))
  }

  @Test
  fun marksRoundTripAndUnreadableTextIsAFirstLook() {
    val marks = mapOf("p1" to mark(AgentStatus.BLOCKED, 5), "p2" to mark(AgentStatus.UNKNOWN, 0))
    assertEquals(marks, AgentMarks.decode(AgentMarks.encode(marks)))
    assertEquals(emptyMap(), AgentMarks.decode(AgentMarks.encode(emptyMap())))
    assertNull(AgentMarks.decode(null))
    assertNull(AgentMarks.decode("{broken"))
    assertNull(AgentMarks.decode("""["p1"]"""))
  }
}
