package dev.artalex.herdr.core.ssh

import dev.artalex.herdr.core.files.RemoteFiles
import dev.artalex.herdr.core.host.HerdrHost
import dev.artalex.herdr.core.host.readHostStatus
import dev.artalex.herdr.core.host.remoteCommand
import dev.artalex.herdr.core.transport.ByteStream
import dev.artalex.herdr.core.transport.HerdrTransport
import dev.artalex.herdr.core.transport.ProcessStream

/**
 * herdr over SSH: API requests through a streamlocal channel to [socketPath], CLI streams through exec, files over
 * SFTP. [socketPath] is null only while discovering it with `herdr status`.
 */
class SshHerdrTransport(private val connection: SshConnection, private val socketPath: String?) : HerdrTransport {
  override suspend fun openApi(): ByteStream = connection.streamLocal(checkNotNull(socketPath) { "herdr API socket is not known yet" })

  override suspend fun exec(argv: List<String>): ProcessStream = connection.execute(remoteCommand(argv))

  override suspend fun openTerminal(argv: List<String>): ProcessStream = connection.execute(remoteCommand(argv), terminal = true)

  override suspend fun openFiles(): RemoteFiles = connection.sftp()
}

/** Asks herdr where its API socket is and wraps the session in a [HerdrHost]. [connection] must be connected. */
suspend fun discoverHerdrHost(connection: SshConnection, herdrPath: String, session: String): HerdrHost {
  val status = SshHerdrTransport(connection, socketPath = null).readHostStatus(herdrPath, session)
  return HerdrHost(status, session, SshHerdrTransport(connection, status.server.socket))
}
