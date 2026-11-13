package com.music.lrclib

import com.music.echo.metadata.cleaner.MetadataCleaner
import com.music.lrclib.models.Track
import com.music.lrclib.models.bestSyncedMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LrcLibSanitizationTest {

  @Test
  fun testSearchQuerySanitization() {
    val query = MetadataCleaner.clean("Coldplay - Yellow (Official Video) [4K]", "Coldplay - Topic").toSearchQuery()
    assertEquals("Yellow", query.first)
    assertEquals("Coldplay", query.second)
  }

  @Test
  fun testRemixSearchQueryStripsRemixForSearch() {
    val query = MetadataCleaner.clean("Song (The Remix)", "Artist").toSearchQuery()
    assertEquals("Song", query.first)
    assertEquals("Artist", query.second)
  }

  @Test
  fun testLrcLibTitleAndPrimaryArtistCleaning() {
    assertEquals(
      "La Cancion",
      LrcLib.cleanTitleForSearch("La Cancion (Dimelo Flow Remix) [Visualizer] feat. Bad Bunny", "J Balvin, Bad Bunny"),
    )
    assertEquals(
      "Relación (Dimelo Flow Remix)",
      LrcLib.cleanTitleKeepingVersionTags(
        "Relación (Dimelo Flow Remix) [Official Video] feat. Daddy Yankee",
        "Sech, Daddy Yankee & J Balvin",
      ),
    )
    assertEquals(
      "Relación",
      LrcLib.cleanTitleForSearch("Relación (Dimelo Flow Remix)", "Sech"),
    )
    assertEquals("Sech", LrcLib.extractPrimaryArtist("Sech, Daddy Yankee & J Balvin"))
    assertEquals(
      "Song Name",
      LrcLib.cleanTitleForSearch("Song Name (Official Video) [4K] ft. Guest", "Main Artist, Guest"),
    )
    assertEquals(
      "J Balvin",
      LrcLib.extractPrimaryArtist("J Balvin, Bad Bunny & Tainy"),
    )
  }

  @Test
  fun testSyncedMatchRespectsFifteenSecondTolerance() {
    val tracks =
      listOf(
        Track(1, "Yellow", "Coldplay", 274.0, "plain", "[00:10.00]Look at the stars"),
        Track(2, "Yellow", "Coldplay", 310.0, "plain", "[00:10.00]Too far"),
      )

    val withinFifteen = tracks.bestSyncedMatch(duration = 260, trackName = "Yellow", artistName = "Coldplay")
    assertNotNull(withinFifteen)
    assertEquals(1, withinFifteen?.id)

    val outsideFifteen = tracks.bestSyncedMatch(duration = 255, trackName = "Yellow", artistName = "Coldplay")
    assertNull(outsideFifteen)
  }
}
