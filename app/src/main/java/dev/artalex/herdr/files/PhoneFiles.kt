package dev.artalex.herdr.files

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.artalex.herdr.R
import java.io.IOException
import java.io.OutputStream

/**
 * Downloads go to a folder named after the app in Download through MediaStore: since Android 10 an app needs no
 * permission for its own files there, and the gallery and the file manager see them.
 */
internal object PhoneDownloads {
  /** Download/ and the app's name in the phone's language: Shepherd, or the Russian name on a phone set to Russian. */
  fun folder(context: Context) = "Download/" + context.getString(R.string.app_name)

  /**
   * A downloaded file named [name] in [folder], written by [write]; returns its URI. It goes into a new file, hidden
   * until it is whole, and only then takes the place of the copy at [previous]: a write that fails leaves that copy as
   * it was and nothing else behind. Once the previous copy is deleted, the new file gets the plain name back: MediaStore
   * numbered it while both were there. A previous copy the app can't delete (one made before a reinstall, or one the
   * user deleted already) is left alone, and the new file keeps the name it got.
   */
  fun save(resolver: ContentResolver, folder: String, name: String, previous: Uri?, write: (OutputStream) -> Unit): Uri {
    val created = create(resolver, folder, name)
    try {
      (resolver.openOutputStream(created, "w") ?: throw IOException("cannot write $created")).use(write)
      publish(resolver, created)
    } catch (e: Throwable) {
      delete(resolver, created)
      throw e
    }
    if (previous != null && delete(resolver, previous)) rename(resolver, created, name)
    return created
  }

  /**
   * A new file named [name] in [folder], hidden from other apps until [publish]; MediaStore numbers the name if it is
   * taken.
   */
  private fun create(resolver: ContentResolver, folder: String, name: String): Uri {
    val values =
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, storedType(name))
        put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
    return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore did not create $name")
  }

  private fun publish(resolver: ContentResolver, uri: Uri) {
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
  }

  /** Gives the file at [uri] the name [name], numbered by MediaStore if another file has it; if that fails, it keeps its own. */
  private fun rename(resolver: ContentResolver, uri: Uri, name: String) {
    runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) }
  }

  /** The file is still there: the user may have deleted it in another app. */
  fun exists(resolver: ContentResolver, uri: Uri): Boolean =
    try {
      resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
    } catch (e: Exception) {
      false
    }

  /** Deletes the file at [uri]; false if it isn't there, or isn't the app's to delete. */
  private fun delete(resolver: ContentResolver, uri: Uri): Boolean = runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)

  /**
   * The type MediaStore files [name] under. Only one it knows for the extension: for another type it would add an
   * extension of its own (`X.kt` becoming `X.kt.txt`).
   */
  private fun storedType(name: String): String = knownType(name) ?: "application/octet-stream"
}

/** The type to open a file called [name] as: by its extension, and plain text for sources and logs Android doesn't know. */
internal fun viewType(name: String): String = knownType(name) ?: if (extension(name) in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"

private fun knownType(name: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name))

private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

private val TEXT_EXTENSIONS =
  setOf("kt", "kts", "java", "py", "rs", "go", "ts", "js", "c", "h", "cpp", "sh", "md", "log", "toml", "yaml", "yml", "gradle", "properties", "conf", "ini", "cfg", "diff", "patch")

/** The name and size of a file the user picked or shared; the name falls back to the last part of [uri]. */
internal fun pickedFile(resolver: ContentResolver, uri: Uri): Pair<String, Long?> {
  val row =
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
          if (!c.moveToFirst()) null
          else {
            val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
            val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
            name to size
          }
        }
      }
      .getOrNull()
  return (row?.first ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file") to row?.second
}
