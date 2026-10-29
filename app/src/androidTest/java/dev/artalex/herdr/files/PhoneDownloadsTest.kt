package dev.artalex.herdr.files

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Downloads saved into the emulator's MediaStore as the files window saves them: a refresh that breaks off leaves the
 * previous copy whole, one that comes through takes its place and its name. The files go into a folder of the test's
 * own, under names of this run, and the folder is emptied before and after each test.
 */
class PhoneDownloadsTest {
  private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
  private val name = "report-${System.currentTimeMillis().toString().takeLast(6)}.txt"

  /**
   * Files of the app the folder still has before a test are left from a run cut short: an emulator killed before
   * Android saved its last writes brings back rows of files deleted at the end of the run, files gone or not.
   */
  @Before
  @After
  fun emptyFolder() {
    files().forEach { (_, uri) -> resolver.delete(uri, null, null) }
  }

  @Test
  fun refreshThatBreaksOffLeavesThePreviousCopyWhole() {
    val previous = save(null, "old")
    val error =
      runCatching {
          PhoneDownloads.save(resolver, FOLDER, name, previous) { out ->
            out.write("new, half of it".toByteArray())
            throw IOException("connection lost")
          }
        }
        .exceptionOrNull()
    assertEquals("connection lost", error?.message)
    assertEquals("old", read(previous))
    assertEquals(listOf(name to "old"), contents())
  }

  @Test
  fun refreshTakesThePlaceAndTheNameOfThePreviousCopy() {
    val previous = save(null, "old")
    val refreshed = save(previous, "new")
    assertEquals("new", read(refreshed))
    assertFalse(PhoneDownloads.exists(resolver, previous))
    assertEquals(listOf(name to "new"), contents())
  }

  @Test
  fun copyTheUserDeletedGivesWayToANewOne() {
    val previous = save(null, "old")
    resolver.delete(previous, null, null)
    val saved = save(previous, "new")
    assertEquals("new", read(saved))
    assertEquals(listOf(name to "new"), contents())
  }

  @Test
  fun refreshBesideAnotherCopyOfTheNameKeepsItsNumber() {
    // Two files of one name on the host: the second one's copy came out numbered.
    save(null, "other")
    val previous = save(null, "old")
    val numbered = contents().single { it.second == "old" }.first
    save(previous, "new")
    assertEquals(setOf(name to "other", numbered to "new"), contents().toSet())
  }

  private fun save(previous: Uri?, text: String): Uri = PhoneDownloads.save(resolver, FOLDER, name, previous) { it.write(text.toByteArray()) }

  private fun read(uri: Uri): String = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes().decodeToString() }

  /** The files in the test's folder, hidden and trashed ones too: their names and URIs. */
  private fun files(): List<Pair<String, Uri>> {
    val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val query =
      Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$FOLDER/"))
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
      }
    return checkNotNull(resolver.query(downloads, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME), query, null)).use { c ->
      buildList { while (c.moveToNext()) add(c.getString(1) to ContentUris.withAppendedId(downloads, c.getLong(0))) }
    }
  }

  /** The files in the test's folder by name, with what they hold. */
  private fun contents(): List<Pair<String, String>> = files().map { (name, uri) -> name to read(uri) }

  private companion object {
    /** Not the app's own folder, where the e2e tests download. */
    const val FOLDER = "Download/PhoneDownloadsTest"
  }
}
