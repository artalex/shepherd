package dev.artalex.herdr

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app makes of the intents of Android's share menu, and of the one that passes a share on to the main activity. */
class ShareTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val photo = Uri.parse("content://media/external/images/media/1")
  private val log = Uri.parse("content://com.example.files/root/build%20log.txt")

  @Test
  fun textFilesAndBoth() {
    assertEquals(Share("Ошибка: нет файла", emptyList()), Share.of(send().putExtra(Intent.EXTRA_TEXT, "  Ошибка: нет файла\n")))
    assertEquals(Share(null, listOf(photo)), Share.of(send().putExtra(Intent.EXTRA_STREAM, photo)))
    assertEquals(
      Share("посмотри", listOf(photo, log)),
      Share.of(Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_TEXT, "посмотри").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, log))),
    )
  }

  @Test
  fun filesOnlyInTheClipDataCountAndRepeatsDont() {
    val clip = ClipData.newRawUri(null, photo).apply { addItem(ClipData.Item(log)) }
    assertEquals(Share(null, listOf(photo, log)), Share.of(send().putExtra(Intent.EXTRA_STREAM, photo).also { it.clipData = clip }))
  }

  @Test
  fun aListOfTextsWithSeveralFiles() {
    val intent =
      Intent(Intent.ACTION_SEND_MULTIPLE)
        .putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf<CharSequence>("первый", "второй"))
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, log))
    assertEquals(Share("первый\nвторой", listOf(photo, log)), Share.of(intent))
  }

  @Test
  fun nothingToShare() {
    assertNull(Share.of(null))
    assertNull(Share.of(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "текст")))
    assertNull(Share.of(send()))
    assertNull(Share.of(send().putExtra(Intent.EXTRA_TEXT, " \n ")))
  }

  @Test
  fun passedOnToTheMainActivityWithAccessToTheFiles() {
    for (share in listOf(Share("текст", emptyList()), Share(null, listOf(photo)), Share("посмотри", listOf(photo, log)))) {
      val intent = share.intent(context)
      assertEquals(MainActivity::class.java.name, intent.component?.className)
      assertEquals(share, Share.of(intent))
      val task = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
      assertEquals(task, intent.flags and task)
      assertEquals(share.files.isNotEmpty(), intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
      assertEquals(share.files, intent.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }.orEmpty())
    }
    assertTrue(Share(null, listOf(photo)).intent(context, grant = false).flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0)
  }

  private fun send() = Intent(Intent.ACTION_SEND)
}
