package echo.music.iad1tya.lyrics

import android.content.Context
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint

object YouTubeLyricsProvider : LyricsProvider {
  override val name = "YouTube Music"

  override fun isEnabled(context: Context) = true

  override suspend fun getLyrics(
    id: String,
    title: String,
    artist: String,
    duration: Int,
    album: String?,
  ): Result<String> = runCatching {
    val nextResult = YouTube.next(WatchEndpoint(videoId = id)).getOrNull()
    val lyricsEndpoint = nextResult?.lyricsEndpoint
    val lyrics =
      if (lyricsEndpoint != null) {
        YouTube.lyrics(endpoint = lyricsEndpoint, videoId = id).getOrNull()
      } else {
        YouTube.captionLyrics(id).getOrNull()
      }
    lyrics?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Lyrics unavailable")
  }
}
