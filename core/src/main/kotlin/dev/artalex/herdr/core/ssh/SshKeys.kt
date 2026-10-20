package dev.artalex.herdr.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/** An Ed25519 client key: [privateKey] in OpenSSH format, [publicKeyLine] ready for authorized_keys. */
class SshKeyPair(val privateKey: ByteArray, val publicKeyLine: String) {
  /** `SHA256:…` fingerprint, as `ssh-keygen -lf` prints it. */
  val fingerprint: String
    get() = sha256Fingerprint(Base64.getDecoder().decode(publicKeyLine.split(' ')[1]))

  companion object {
    fun generate(comment: String): SshKeyPair {
      val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
      try {
        // Ed25519 keys only exist in the OpenSSH v1 format (what ssh-keygen writes); no passphrase: Keystore encrypts it.
        val prv = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, null) }.toByteArray()
        val pub = ByteArrayOutputStream().also { pair.writePublicKey(it, comment) }.toString(Charsets.UTF_8).trim()
        return SshKeyPair(prv, pub)
      } finally {
        pair.dispose()
      }
    }
  }
}

/** A server host key as presented during the SSH handshake. */
data class HostKey(val type: String, val blob: ByteArray) {
  val fingerprint: String get() = sha256Fingerprint(blob)

  /** `type base64` — the form stored for pinning. */
  fun encode(): String = "$type ${Base64.getEncoder().encodeToString(blob)}"

  override fun equals(other: Any?) = other is HostKey && type == other.type && blob.contentEquals(other.blob)

  override fun hashCode() = 31 * type.hashCode() + blob.contentHashCode()

  companion object {
    fun decode(encoded: String): HostKey {
      val (type, b64) = encoded.trim().split(' ', limit = 2)
      return HostKey(type, Base64.getDecoder().decode(b64))
    }

    /** Reads the key type from the blob itself (an SSH string at the start). */
    fun fromBlob(blob: ByteArray): HostKey {
      val len = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
        ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
      return HostKey(String(blob, 4, len, Charsets.US_ASCII), blob)
    }
  }
}

internal fun sha256Fingerprint(blob: ByteArray): String =
  "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
