package echo.music.iad1tya.lyrics

import echo.music.iad1tya.db.entities.LyricsEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimestampCheckTest {
  @Test
  fun plainTextAndMetadataHeadersAreNotTreatedAsSyncedLrc() {
    assertFalse(LyricsEntity.hasSyncedLrcTimestamps("Verse 1\nStatic YouTube Music lyrics"))
    assertFalse(LyricsEntity.hasSyncedLrcTimestamps("[Verse 1]\nNo timestamps"))
    assertFalse(LyricsEntity.hasSyncedLrcTimestamps(LyricsEntity.LYRICS_NOT_FOUND))
    assertFalse(LyricsUtils.hasSyncedLyrics("Verse 1\nStatic YouTube Music lyrics"))
  }

  @Test
  fun syncedLrcTimestampsAreRecognized() {
    val lrc = "[00:05.50] Line one\n[01:12.34] Line two"
    assertTrue(LyricsEntity.hasSyncedLrcTimestamps(lrc))
    assertTrue(LyricsUtils.hasSyncedLyrics(lrc))
  }
}
