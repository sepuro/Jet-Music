package echo.music.iad1tya.ui.screens.ambient

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import echo.music.iad1tya.LocalPlayerConnection
import echo.music.iad1tya.applecanvas.AppleMusicCanvasProvider
import echo.music.iad1tya.canvas.CanvasArtwork
import echo.music.iad1tya.canvas.TidalCanvasProvider
import echo.music.iad1tya.canvas.YouTubeVideoCanvasProvider
import echo.music.iad1tya.echomusiccanvas.echomusicCanvasProvider
import echo.music.iad1tya.ui.player.CanvasArtworkPlaybackCache
import echo.music.iad1tya.ui.player.CanvasArtworkPlayer
import echo.music.iad1tya.ui.player.normalizeCanvasArtistName
import echo.music.iad1tya.ui.player.normalizeCanvasSongTitle
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Composable
fun AmbientCanvasBackground(
  modifier: Modifier = Modifier,
  songId: String? = null,
  songTitle: String? = null,
  artistName: String? = null,
  albumTitle: String? = null,
  durationSec: Long? = null,
  isPlayingOverride: Boolean? = null,
  onVideoReadyChanged: (Boolean) -> Unit = {},
) {
  val context = LocalContext.current
  val playerConnection = LocalPlayerConnection.current ?: return
  val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
  val isPlayingState by playerConnection.isEffectivelyPlaying.collectAsState()

  val targetId = songId ?: mediaMetadata?.id ?: return
  val isPlaying = isPlayingOverride ?: isPlayingState

  var canvasArtwork by remember(targetId) { mutableStateOf<CanvasArtwork?>(null) }

  val storefront = remember {
    val country = Locale.getDefault().country
    if (country.length == 2) country.lowercase(Locale.ROOT) else "us"
  }

  LaunchedEffect(targetId, songTitle, artistName, albumTitle) {
    CanvasArtworkPlaybackCache.get(targetId)?.let { cached ->
      canvasArtwork = cached
      return@LaunchedEffect
    }

    try {
      val file = File(context.filesDir, "canvas_${targetId}.json")
      if (file.exists()) {
        val cached = Json.decodeFromString<CanvasArtwork>(file.readText())
        CanvasArtworkPlaybackCache.put(targetId, cached)
        canvasArtwork = cached
        return@LaunchedEffect
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }

    val currentMeta = mediaMetadata?.takeIf { it.id == targetId }
    val itemMetadata =
      playerConnection.player.currentMediaItem
        ?.takeIf { it.mediaId == targetId }
        ?.mediaMetadata

    val albumName =
      albumTitle
        ?: currentMeta?.album?.title
        ?: itemMetadata?.albumTitle?.toString()
        ?: ""
    val songTitleRaw =
      songTitle
        ?: currentMeta?.title
        ?: itemMetadata?.title?.toString()
        ?: ""
    val artistNameRaw =
      artistName
        ?: currentMeta?.artists?.joinToString { it.name }
        ?: itemMetadata?.artist?.toString()
        ?: ""

    val playerDurationSec =
      try {
        playerConnection.player.duration.takeIf { it > 0 }?.div(1000L)
      } catch (e: Exception) {
        null
      }
    val resolvedDurationSec =
      durationSec?.takeIf { it > 0 }
        ?: currentMeta?.duration?.toLong()?.takeIf { it > 0 }
        ?: playerDurationSec

    val fetched =
      withContext(Dispatchers.IO) {
        val normalizedSong = normalizeCanvasSongTitle(songTitleRaw)
        val normalizedArtist = normalizeCanvasArtistName(artistNameRaw)

        linkedSetOf(
            normalizedSong to normalizedArtist,
            songTitleRaw to normalizedArtist,
            normalizedSong to artistNameRaw,
            songTitleRaw to artistNameRaw,
          )
          .filter { (s, a) -> s.isNotBlank() && a.isNotBlank() }
          .firstNotNullOfOrNull { (s, a) ->
            if (albumName.isNotBlank()) {
              AppleMusicCanvasProvider.getByAlbumArtist(
                  album = albumName,
                  artist = a,
                  storefront = storefront,
                )
                ?.takeIf { !it.preferredAnimationUrl.isNullOrBlank() }
                ?.let { return@firstNotNullOfOrNull it }
            }

            echomusicCanvasProvider.getBySongArtist(song = s, artist = a)
              ?.takeIf { !it.preferredAnimationUrl.isNullOrBlank() }
              ?: TidalCanvasProvider.getBySongArtist(
                  song = s,
                  artist = a,
                  album = albumName,
                )
                ?.takeIf { !it.preferredAnimationUrl.isNullOrBlank() }
              ?: AppleMusicCanvasProvider.getBySongArtist(
                  song = s,
                  artist = a,
                  album = albumName,
                  storefront = storefront,
                )
                ?.takeIf { !it.preferredAnimationUrl.isNullOrBlank() }
          }
          ?: YouTubeVideoCanvasProvider.getCanvasForVideo(
            videoId = targetId,
            title = songTitleRaw,
            artist = artistNameRaw,
            durationSec = resolvedDurationSec,
          )
      }

    val validated =
      if (fetched?.clipStartMs != null) {
        fetched
      } else {
        fetched?.takeIf { artwork ->
          val resultArtist = artwork.artist
          val artistMatches =
            if (resultArtist != null && artistNameRaw.isNotBlank()) {
              val normalizedResult = normalizeCanvasArtistName(resultArtist)
              val normalizedRequested = normalizeCanvasArtistName(artistNameRaw)
              resultArtist.contains(artistNameRaw, ignoreCase = true) ||
                artistNameRaw.contains(resultArtist, ignoreCase = true) ||
                normalizedResult.contains(normalizedRequested, ignoreCase = true) ||
                normalizedRequested.contains(normalizedResult, ignoreCase = true)
            } else {
              true
            }

          val canvasAlbumName = artwork.albumName
          val canvasSongName = artwork.name
          val titleMatches =
            when {
              canvasAlbumName != null && albumName.isNotBlank() -> {
                val normalizedCanvasAlbum = normalizeCanvasSongTitle(canvasAlbumName)
                val normalizedRequestedAlbum = normalizeCanvasSongTitle(albumName)
                canvasAlbumName.contains(albumName, ignoreCase = true) ||
                  albumName.contains(canvasAlbumName, ignoreCase = true) ||
                  normalizedCanvasAlbum.contains(normalizedRequestedAlbum, ignoreCase = true) ||
                  normalizedRequestedAlbum.contains(normalizedCanvasAlbum, ignoreCase = true)
              }
              canvasSongName != null && songTitleRaw.isNotBlank() -> {
                val normalizedCanvasSong = normalizeCanvasSongTitle(canvasSongName)
                val normalizedRequestedTitle = normalizeCanvasSongTitle(songTitleRaw)
                canvasSongName.contains(songTitleRaw, ignoreCase = true) ||
                  songTitleRaw.contains(canvasSongName, ignoreCase = true) ||
                  normalizedCanvasSong.contains(normalizedRequestedTitle, ignoreCase = true) ||
                  normalizedRequestedTitle.contains(normalizedCanvasSong, ignoreCase = true)
              }
              else -> true
            }

          artistMatches && titleMatches
        }
      }

    canvasArtwork = validated
    validated?.let { CanvasArtworkPlaybackCache.put(targetId, it) }
  }

  val activeArtwork = canvasArtwork
  if (activeArtwork != null) {
    CanvasArtworkPlayer(
      primaryUrl = activeArtwork.animated,
      fallbackUrl = activeArtwork.videoUrl,
      isPlaying = isPlaying,
      clipStartMs = activeArtwork.clipStartMs,
      clipEndMs = activeArtwork.clipEndMs,
      onVideoReadyChanged = onVideoReadyChanged,
      modifier = modifier,
    )
  } else {
    LaunchedEffect(Unit) {
      onVideoReadyChanged(false)
    }
  }
}
