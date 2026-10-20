package dev.artalex.herdr.core

import dev.artalex.herdr.core.host.remoteCommand
import dev.artalex.herdr.core.ssh.HostKey
import dev.artalex.herdr.core.ssh.SshConnection
import dev.artalex.herdr.core.ssh.SshKeyPair
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SshKeysTest {
  /** The localhost-only test key; its fingerprint as printed by `ssh-keygen -lf`. */
  private val testPub = fixture("test-key.pub").trim()

  @Test
  fun fingerprintMatchesSshKeygen() {
    assertEquals("SHA256:qob/8CVsQicDHCd4mTN5/oEOabX0PweHC4EsQjC9A7c", SshKeyPair(ByteArray(0), testPub).fingerprint)
  }

  @Test
  fun hostKeyRoundTripsAndReadsTypeFromBlob() {
    val blob = Base64.getDecoder().decode(testPub.split(' ')[1])
    val key = HostKey.fromBlob(blob)
    assertEquals("ssh-ed25519", key.type)
    assertEquals(key, HostKey.decode(key.encode()))
    assertEquals("SHA256:qob/8CVsQicDHCd4mTN5/oEOabX0PweHC4EsQjC9A7c", key.fingerprint)
  }

  @Test
  fun generatesEd25519KeyPair() {
    val pair = SshKeyPair.generate("herdr-android test")
    assertTrue(pair.publicKeyLine.startsWith("ssh-ed25519 "))
    assertTrue(pair.publicKeyLine.endsWith(" herdr-android test"))
    assertTrue(pair.fingerprint.startsWith("SHA256:"))
    assertTrue(String(pair.privateKey).contains("PRIVATE KEY"))
  }

  @Test
  fun ed25519HostKeyIsPreferred() {
    assertEquals("ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512", SshConnection.preferEd25519("ecdsa-sha2-nistp256,ssh-ed25519,rsa-sha2-512"))
  }

  @Test
  fun remoteCommandExpandsHomeOnlyInProgramPath() {
    assertEquals(
      "\"\$HOME\"/.local/bin/herdr --session default status --json",
      remoteCommand(listOf("~/.local/bin/herdr", "--session", "default", "status", "--json")),
    )
    assertEquals("/opt/herdr '~/x'", remoteCommand(listOf("/opt/herdr", "~/x")))
  }
}
