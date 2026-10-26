package dev.artalex.herdr.core

import dev.artalex.herdr.core.model.PaneInfo
import dev.artalex.herdr.core.model.SessionSnapshot
import dev.artalex.herdr.core.servers.FolderChoice
import dev.artalex.herdr.core.servers.FolderSightings
import dev.artalex.herdr.core.servers.folderChoices
import dev.artalex.herdr.core.servers.folderOrder
import dev.artalex.herdr.core.servers.shortFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class FoldersTest {
  @Test
  fun foldersInTheHomeFolderStartWithTilde() {
    assertEquals("~", shortFolder("/home/dev", "/home/dev"))
    assertEquals("~/proj/app", shortFolder("/home/dev/proj/app", "/home/dev"))
    // Whole names only: a folder whose name merely begins like the home folder's stays as it is.
    assertEquals("/home/developer", shortFolder("/home/developer", "/home/dev"))
    assertEquals("/srv/notes", shortFolder("/srv/notes", "/home/dev"))
    assertEquals("/home/dev/proj", shortFolder("/home/dev/proj", null))
    // A home folder at the root: only itself.
    assertEquals("~", shortFolder("/", "/"))
    assertEquals("/srv", shortFolder("/srv", "/"))
  }

  @Test
  fun menuOffersTheHomeFolderAndEveryOtherOnceInOrder() {
    val folders =
      listOf("/home/dev/smart-home", "/srv/notes", "/home/dev/herdr-android", "/home/dev", "/home/dev/herdr-android-new", "/home/dev/herdr-android/app", "/home/dev/Docs", "/opt/x")
    val choices = folderChoices("/home/dev", folders)
    assertEquals(listOf("~", "~/Docs", "~/herdr-android", "~/herdr-android/app", "~/herdr-android-new", "~/smart-home", "/opt/x", "/srv/notes"), choices.map { it.shown })
    // The home folder can't be taken off, though the server keeps it as a folder seen too.
    assertEquals(FolderChoice("~", null), choices.first())
    assertEquals(FolderChoice("~/Docs", "/home/dev/Docs"), choices[1])
  }

  @Test
  fun menuOffersTheHomeFolderWhateverIsKnown() {
    assertEquals(listOf(FolderChoice("~", null)), folderChoices("/home/dev", emptyList()))
    // Before the host tells its home folder, the folders read as they are.
    assertEquals(listOf("~", "/home/dev/proj", "/srv/notes"), folderChoices(null, listOf("/srv/notes", "/home/dev/proj")).map { it.shown })
  }

  @Test
  fun orderIsAlphabeticalNameByNameWhateverTheCase() {
    assertEquals(listOf("~/a", "~/B", "~/b", "~/c"), listOf("~/c", "~/b", "~/B", "~/a").sortedWith(folderOrder))
    assertEquals(listOf("~/devel/x", "~/devel/x/y", "~/devel/x-y", "~/devel/x.y", "~/документы"), listOf("~/документы", "~/devel/x.y", "~/devel/x-y", "~/devel/x/y", "~/devel/x").sortedWith(folderOrder))
    assertEquals(listOf("~", "~/z", "/a"), listOf("/a", "~/z", "~").sortedWith(folderOrder))
  }

  private fun snapshot(vararg panes: Pair<String?, String?>) =
    SessionSnapshot(version = "0.9.5", protocol = 1, panes = panes.mapIndexed { i, (cwd, front) -> PaneInfo("p$i", "t$i", "w", "tab", cwd = cwd, foregroundCwd = front) })

  @Test
  fun foldersOfPanesAreNewOnceTheyShowUp() {
    val sightings = FolderSightings()
    // The shell's folder and that of what runs in front; no path is no folder.
    assertEquals(setOf("/home/dev/a", "/home/dev/a/sub", "/srv"), sightings.newIn(snapshot("/home/dev/a" to "/home/dev/a/sub", "/srv" to null, null to "relative")))
    // Still there: nothing new, so a folder the user took off the menu meanwhile stays off.
    assertEquals(emptySet(), sightings.newIn(snapshot("/home/dev/a" to "/home/dev/a", "/srv" to null)))
    // Gone, then back: new again.
    assertEquals(setOf("/opt"), sightings.newIn(snapshot("/opt" to null)))
    assertEquals(setOf("/srv"), sightings.newIn(snapshot("/opt" to null, "/srv" to null)))
    // For another host or session, everything.
    sightings.reset()
    assertEquals(setOf("/opt", "/srv"), sightings.newIn(snapshot("/opt" to null, "/srv" to null)))
  }
}
