package dev.artalex.herdr.e2e

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.artalex.herdr.core.ssh.SshKeyPair
import org.junit.Assume.assumeTrue

/**
 * Where the e2e tests find herdr: passed by scripts/e2e.sh as instrumentation arguments.
 * Without them (a plain `connectedAndroidTest`) the e2e tests are skipped.
 */
class E2eArgs private constructor(private val args: android.os.Bundle) {
  val host: String get() = args.getString("sshHost")!!
  val port: String get() = args.getString("sshPort", "22")
  val user: String get() = args.getString("sshUser")!!
  val session: String get() = args.getString("herdrSession")!!

  /** Another session on the same host, standing for a second server. */
  val session2: String get() = args.getString("herdrSession2")!!
  val hostKeyFingerprint: String get() = args.getString("hostKeyFingerprint")!!

  val key: SshKeyPair
    get() = SshKeyPair(decode("sshKey"), String(decode("sshPub")).trim())

  private fun decode(name: String) = Base64.decode(args.getString(name), Base64.DEFAULT)

  companion object {
    fun require(): E2eArgs {
      val args = InstrumentationRegistry.getArguments()
      assumeTrue("e2e arguments missing: run scripts/e2e.sh", args.getString("sshKey") != null)
      return E2eArgs(args)
    }
  }
}
