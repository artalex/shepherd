package dev.artalex.herdr.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import dev.artalex.herdr.core.ssh.SshKeyPair
import dev.artalex.herdr.e2e.Device
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** The phone's key of 1.9 moves to an AES key that works on a locked device, and stays the same SSH key. */
class KeyVaultTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun keyOfVersionOneNineMovesOverAndOpensWhileLocked() {
    val key = SshKeyPair.generate("e2e-key-of-1.9")
    storeAsVersionOneNine(key)
    val vault = KeyVault(context)
    // Why it moves: the old key doesn't open on a locked device. This also shows the lock is real for the Keystore,
    // which the notification test with the screen locked relies on.
    Device.locked { assertThrows(InvalidKeyException::class.java) { vault.load() } }

    val moved = checkNotNull(vault.load())
    assertEquals(key.publicKeyLine, moved.publicKeyLine)
    assertArrayEquals(key.privateKey, moved.privateKey)
    assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(OLD_ALIAS))
    assertArrayEquals(key.privateKey, checkNotNull(vault.load()).privateKey)
    Device.locked { assertArrayEquals(key.privateKey, checkNotNull(vault.load()).privateKey) }
  }

  /** What the key vault of 1.9 wrote: the same file and preferences, under an AES key that needs an unlocked device. */
  private fun storeAsVersionOneNine(key: SshKeyPair) {
    val spec =
      KeyGenParameterSpec.Builder(OLD_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUnlockedDeviceRequired(true)
        .build()
    val aes = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes) }
    File(context.noBackupFilesDir, "ssh-key.bin").writeBytes(cipher.iv + cipher.doFinal(key.privateKey))
    context.getSharedPreferences("ssh-key", Context.MODE_PRIVATE).edit().putString("public_key", key.publicKeyLine).commit()
  }

  private companion object {
    const val OLD_ALIAS = "herdr-ssh-key-wrap"
  }
}
