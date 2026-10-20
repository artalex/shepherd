package dev.artalex.herdr.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.edit
import dev.artalex.herdr.core.ssh.SshKeyPair
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's SSH key. The private key is encrypted with an AES key that never leaves the Android Keystore and works
 * once the device has been unlocked after it started: the background checks for notifications connect while the
 * screen is locked. Backups are disabled for the whole app.
 *
 * Up to 1.9 the AES key worked only while the device was unlocked ([OLD_ALIAS]). The first load on an unlocked
 * device moves the private key over to the new AES key; the SSH key itself stays the same.
 */
class KeyVault(private val context: Context) {
  private val file = AtomicFile(File(context.noBackupFilesDir, "ssh-key.bin"))
  private val prefs = context.getSharedPreferences("ssh-key", Context.MODE_PRIVATE)

  /** The key, or null if there is none yet. Throws if the Keystore refuses, as with the old key on a locked device. */
  fun load(): SshKeyPair? {
    val publicLine = prefs.getString(PUBLIC_KEY, null) ?: return null
    if (!file.baseFile.exists()) return null
    val data = file.readFully()
    val keyStore = keyStore()
    val old = keyStore.getKey(OLD_ALIAS, null) as SecretKey? ?: return SshKeyPair(decrypt(wrappingKey(keyStore), data), publicLine)
    val fromOld =
      try {
        decrypt(old, data)
      } catch (e: AEADBadTagException) {
        null
      }
    if (fromOld != null) {
      val key = SshKeyPair(fromOld, publicLine)
      store(key)
      Log.i(TAG, "SSH key moved to a Keystore key that works after the first unlock")
      return key
    }
    // The file is under the new key already: the app stopped between writing it and dropping the old key. The old
    // key goes only once the new one has opened the file.
    val key = SshKeyPair(decrypt(wrappingKey(keyStore), data), publicLine)
    keyStore.deleteEntry(OLD_ALIAS)
    return key
  }

  fun generate(): SshKeyPair = SshKeyPair.generate("herdr-android@${Build.MODEL.replace(' ', '-')}").also(::store)

  /** Stores an existing key; the e2e tests use it to bring the localhost-only test key. */
  fun store(key: SshKeyPair) {
    val keyStore = keyStore()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(keyStore))
    val encrypted = cipher.iv + cipher.doFinal(key.privateKey)
    val out = file.startWrite()
    try {
      out.write(encrypted)
      file.finishWrite(out)
    } catch (e: Exception) {
      file.failWrite(out)
      throw e
    }
    prefs.edit { putString(PUBLIC_KEY, key.publicKeyLine) }
    // Only once the new file is in place: until then the old key is the one that opens the stored key.
    if (keyStore.containsAlias(OLD_ALIAS)) keyStore.deleteEntry(OLD_ALIAS)
  }

  fun delete() {
    file.delete()
    prefs.edit { remove(PUBLIC_KEY) }
  }

  private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  private fun decrypt(key: SecretKey, data: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, IV_BYTES))
    return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
  }

  private fun wrappingKey(keyStore: KeyStore): SecretKey {
    (keyStore.getKey(ALIAS, null) as SecretKey?)?.let { return it }
    return try {
      generateWrappingKey(strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
      generateWrappingKey(strongBox = false)
    }
  }

  private fun generateWrappingKey(strongBox: Boolean): SecretKey {
    val spec =
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setIsStrongBoxBacked(strongBox)
        .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
  }

  private companion object {
    const val TAG = "HerdrKeys"
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "herdr-ssh-key-wrap-v2"

    /** The AES key up to 1.9, made with setUnlockedDeviceRequired. */
    const val OLD_ALIAS = "herdr-ssh-key-wrap"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
    const val PUBLIC_KEY = "public_key"
  }
}
