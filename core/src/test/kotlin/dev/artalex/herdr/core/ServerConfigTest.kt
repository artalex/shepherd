package dev.artalex.herdr.core

import dev.artalex.herdr.core.servers.ServerConfig
import dev.artalex.herdr.core.servers.ServerList
import dev.artalex.herdr.core.servers.withActive
import dev.artalex.herdr.core.servers.withHostKey
import dev.artalex.herdr.core.servers.withServer
import dev.artalex.herdr.core.servers.without
import dev.artalex.herdr.core.ssh.HostKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerConfigTest {
  private val key = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })
  private val node = ServerConfig(id = "a", host = "workstation", user = "dev", hostKey = key.encode())

  @Test
  fun titleIsTheNameOrTheHostWithAnOtherThanDefaultSession() {
    assertEquals("workstation", node.title)
    assertEquals("workstation · lab", node.copy(session = "lab").title)
    assertEquals("ноутбук", node.copy(name = " ноутбук ", session = "lab").title)
    assertEquals("workstation", node.copy(name = "  ").title)
  }

  @Test
  fun sessionNameFollowsHerdrsRules() {
    assertNull(sessionNameProblem(DEFAULT_SESSION))
    assertNull(sessionNameProblem("Lab-1.test_2"))
    assertNull(sessionNameProblem("a".repeat(MAX_SESSION_NAME_LENGTH)))
    assertNull(sessionNameProblem("..."))
    assertEquals(SessionNameProblem.EMPTY, sessionNameProblem(""))
    assertEquals(SessionNameProblem.CHARACTERS, sessionNameProblem("my session"))
    assertEquals(SessionNameProblem.CHARACTERS, sessionNameProblem("../prod"))
    assertEquals(SessionNameProblem.CHARACTERS, sessionNameProblem("лаборатория"))
    // Too long as well: the characters are what to fix first.
    assertEquals(SessionNameProblem.CHARACTERS, sessionNameProblem("я".repeat(MAX_SESSION_NAME_LENGTH)))
    assertEquals(SessionNameProblem.TOO_LONG, sessionNameProblem("a".repeat(MAX_SESSION_NAME_LENGTH + 1)))
    assertEquals(SessionNameProblem.DOTS, sessionNameProblem("."))
    assertEquals(SessionNameProblem.DOTS, sessionNameProblem(".."))
    // The form saves the name trimmed.
    assertNull(node.copy(session = " lab ").sessionProblem)
    assertEquals(SessionNameProblem.EMPTY, node.copy(session = "  ").sessionProblem)
  }

  @Test
  fun listRoundTripsAndUnreadableTextIsNoServers() {
    val servers = listOf(node, ServerConfig(id = "b", name = "nas", host = "nas", port = 2222, user = "root", session = "x", active = false))
    assertEquals(servers, ServerList.decode(ServerList.encode(servers)))
    assertEquals(emptyList(), ServerList.decode(null))
    assertEquals(emptyList(), ServerList.decode("{broken"))
    assertEquals(emptyList(), ServerList.decode("""{"servers":[]}"""))
    // Fields of a later version don't keep the rest from loading.
    assertEquals(listOf(ServerConfig(id = "c", host = "h")), ServerList.decode("""[{"id":"c","host":"h","color":"red"}]"""))
  }

  @Test
  fun hostKeyThatCannotBeReadIsAskedForAgain() {
    assertEquals(key, node.pinnedHostKey)
    assertNull(node.copy(hostKey = "garbage").pinnedHostKey)
    assertNull(node.copy(hostKey = null).pinnedHostKey)
  }

  @Test
  fun newServerIsAddedTrimmedAtTheEnd() {
    val added = listOf(node).withServer(ServerConfig(id = "b", host = " nas ", user = "root ", session = " lab"))
    assertEquals(listOf("a", "b"), added.map { it.id })
    assertEquals(ServerConfig(id = "b", host = "nas", user = "root", session = "lab"), added[1])
  }

  @Test
  fun editKeepsHostKeyAndSwitchUnlessTheServerMoved() {
    val servers = listOf(node.copy(active = false))
    // The form doesn't carry them: what the list has stays.
    val renamed = servers.withServer(node.copy(name = "узел", hostKey = null, active = true)).single()
    assertEquals("узел", renamed.name)
    assertEquals(key.encode(), renamed.hostKey)
    assertFalse(renamed.active)
    assertNull(servers.withServer(node.copy(host = "other")).single().hostKey)
    assertNull(servers.withServer(node.copy(port = 2222)).single().hostKey)
    assertEquals(key.encode(), servers.withServer(node.copy(user = "root", session = "lab")).single().hostKey)
  }

  @Test
  fun switchKeyAndDeleteTouchOnlyTheirServer() {
    val other = ServerConfig(id = "b", host = "nas", user = "root")
    val servers = listOf(node, other)
    assertEquals(listOf(false, true), servers.withActive("a", false).map { it.active })
    assertEquals(listOf(null, key.encode()), servers.withHostKey("a", null).withHostKey("b", key).map { it.hostKey })
    assertEquals(listOf(other), servers.without("a"))
  }

  @Test
  fun sameConnectionIgnoresNameAndSwitch() {
    assertTrue(node.sameConnection(node.copy(name = "x", active = false, hostKey = null)))
    assertFalse(node.sameConnection(node.copy(session = "lab")))
    assertFalse(node.sameConnection(node.copy(herdrPath = "/usr/bin/herdr")))
    assertFalse(node.sameConnection(node.copy(user = "root")))
    assertNotEquals(ServerConfig.new().id, ServerConfig.new().id)
  }
}
