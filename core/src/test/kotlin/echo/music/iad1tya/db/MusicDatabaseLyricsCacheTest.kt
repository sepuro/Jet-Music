package echo.music.iad1tya.db

import androidx.room.Room
import echo.music.iad1tya.db.entities.LyricsEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicDatabaseLyricsCacheTest {
  private lateinit var internalDb: InternalDatabase
  private lateinit var database: MusicDatabase

  @Before
  fun setup() {
    internalDb =
      Room.inMemoryDatabaseBuilder(
          RuntimeEnvironment.getApplication(),
          InternalDatabase::class.java,
        )
        .allowMainThreadQueries()
        .build()
    database = MusicDatabase(internalDb)
  }

  @After
  fun teardown() {
    internalDb.close()
  }

  @Test
  fun hasSyncedLrcTimestamps_detectsStandardLrcAndRejectsPlainText() {
    assertTrue(LyricsEntity.hasSyncedLrcTimestamps("[00:12.34] First line"))
    assertTrue(LyricsEntity.hasSyncedLrcTimestamps("[1:05.00] Second line"))
    assertFalse(LyricsEntity.hasSyncedLrcTimestamps("Just plain static lyrics"))
    assertFalse(LyricsEntity.hasSyncedLrcTimestamps("[Intro]\nNo timestamps"))
    assertFalse(LyricsEntity.hasSyncedLrcTimestamps(LyricsEntity.LYRICS_NOT_FOUND))
  }

  @Test
  fun lyricsFlow_ignoresPreExistingPlainTextUntilRevalidated() = runBlocking {
    // Seed directly into underlying DAO to simulate legacy cached static row from older app version
    internalDb.dao.upsert(
      LyricsEntity(id = "song_1", lyrics = "Unsynced plain text", provider = "YouTube Music")
    )

    // MusicDatabase hides stale unsynced cache so callers re-fetch from synced providers
    assertNull(database.lyrics("song_1").first())

    // Overwrite with synced LRC and verify Flow returns it
    val synced = LyricsEntity(id = "song_1", lyrics = "[00:10.00] Synced line", provider = "LrcLib")
    database.upsert(synced)
    assertEquals(synced, database.lyrics("song_1").first())
  }

  @Test
  fun lyricsFlow_allowsRevalidatedPlainFallbackWithoutInfiniteLoop() = runBlocking {
    val plain = LyricsEntity(id = "song_plain", lyrics = "Plain fallback only", provider = "YouTube Music")
    database.upsert(plain)

    val loaded = database.lyrics("song_plain").first()
    assertNotNull(loaded)
    assertEquals("Plain fallback only", loaded?.lyrics)
  }

  @Test
  fun deleteUnsyncedLyrics_purgesStaticEntriesAndKeepsSyncedLrc() = runBlocking {
    internalDb.dao.upsert(LyricsEntity(id = "static_1", lyrics = "Plain lyrics", provider = "YouTube Music"))
    internalDb.dao.upsert(LyricsEntity(id = "not_found", lyrics = LyricsEntity.LYRICS_NOT_FOUND, provider = "Unknown"))
    internalDb.dao.upsert(LyricsEntity(id = "synced_0", lyrics = "[00:15.20] Line one", provider = "LrcLib"))
    internalDb.dao.upsert(LyricsEntity(id = "synced_1", lyrics = "[10:05.00] Late line", provider = "KuGou"))

    database.deleteUnsyncedLyrics()

    assertNull(database.getLyricsById("static_1"))
    assertNull(database.getLyricsById("not_found"))
    assertNotNull(database.getLyricsById("synced_0"))
    assertNotNull(database.getLyricsById("synced_1"))
  }
}
