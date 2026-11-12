package echo.music.iad1tya.ui.player

import android.graphics.Matrix
import android.view.TextureView
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import com.music.innertube.YouTube
import echo.music.iad1tya.LocalPlayerConnection
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient

private fun updateTextureTransform(
  textureView: TextureView,
  viewWidth: Int,
  viewHeight: Int,
  videoWidth: Int,
  videoHeight: Int,
) {
  if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return

  val viewRatio = viewWidth.toFloat() / viewHeight
  val videoRatio = videoWidth.toFloat() / videoHeight

  var scaleX = 1f
  var scaleY = 1f

  if (viewRatio > videoRatio) {
    scaleY = viewRatio / videoRatio
  } else {
    scaleX = videoRatio / viewRatio
  }

  val matrix = Matrix()
  matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
  textureView.setTransform(matrix)
}

@Composable
fun CanvasArtworkPlayer(
  primaryUrl: String?,
  fallbackUrl: String?,
  isPlaying: Boolean,
  clipStartMs: Long? = null,
  clipEndMs: Long? = null,
  onVideoReadyChanged: (Boolean) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val playerConnection = LocalPlayerConnection.current
  val primary = primaryUrl?.takeIf { it.isNotBlank() }
  val fallback = fallbackUrl?.takeIf { it.isNotBlank() }
  val initial = primary ?: fallback ?: return
  var currentUrl by remember(initial) { mutableStateOf(initial) }
  var isVideoReady by remember(initial) { mutableStateOf(false) }
  var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
  var currentVideoWidth by remember { mutableIntStateOf(0) }
  var currentVideoHeight by remember { mutableIntStateOf(0) }

  LaunchedEffect(isVideoReady) {
    onVideoReadyChanged(isVideoReady)
  }

  val okHttpClient = remember {
    OkHttpClient.Builder()
      .proxy(YouTube.proxy)
      .addInterceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val isYouTubeMediaHost =
          host.endsWith("googlevideo.com") ||
            host.endsWith("googleusercontent.com") ||
            host.endsWith("youtube.com") ||
            host.endsWith("youtube-nocookie.com") ||
            host.endsWith("ytimg.com")

        if (!isYouTubeMediaHost) return@addInterceptor chain.proceed(request)

        val url = request.url.toString()
        val extraHeaders =
          echo.music.iad1tya.utils.InnerTubeXResolver.headersFor(url)
            ?: echo.music.iad1tya.utils.PlayerClient.forStreamUrl(url).mediaHeaders()

        val builder = request.newBuilder()
        for ((k, v) in extraHeaders) {
          builder.header(k, v)
        }
        YouTube.cookie?.let { cookie -> builder.header("Cookie", cookie) }

        chain.proceed(builder.build())
      }
      .build()
  }

  val downloadCache = playerConnection?.service?.downloadCache
  val mediaSourceFactory =
    remember(okHttpClient, downloadCache) {
      val upstreamFactory =
        DefaultDataSource.Factory(
          context,
          OkHttpDataSource.Factory(okHttpClient),
        )
      val dataSourceFactory =
        if (downloadCache != null) {
          CacheDataSource.Factory()
            .setCache(downloadCache as androidx.media3.datasource.cache.Cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
          upstreamFactory
        }
      DefaultMediaSourceFactory(dataSourceFactory)
    }

  val trackSelector = remember {
    DefaultTrackSelector(context).apply {
      parameters =
        buildUponParameters()
          .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
          .setMaxVideoBitrate(Int.MAX_VALUE)
          .setForceHighestSupportedBitrate(true)
          .clearVideoSizeConstraints()
          .build()
    }
  }

  val exoPlayer = remember {
    ExoPlayer.Builder(context)
      .setTrackSelector(trackSelector)
      .setMediaSourceFactory(mediaSourceFactory)
      .setLoadControl(DefaultLoadControl.Builder().setTargetBufferBytes(32 * 1024 * 1024).build())
      .build()
      .apply {
        trackSelectionParameters =
          trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            .setMaxVideoBitrate(Int.MAX_VALUE)
            .setForceHighestSupportedBitrate(true)
            .clearVideoSizeConstraints()
            .build()
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
          false,
        )
        videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        volume = 0f
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = isPlaying
      }
  }

  val aspectRatioFrameLayout = remember {
    AspectRatioFrameLayout(context).apply {
      layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
      resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
      clipChildren = true
      isVerticalFadingEdgeEnabled = true
      setFadingEdgeLength((resources.displayMetrics.density * 140).toInt())
      setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
  }

  LaunchedEffect(isPlaying) {
    if (exoPlayer.playWhenReady != isPlaying) {
      exoPlayer.playWhenReady = isPlaying
    }
  }

  // Synchronize OMV / full-length video position with mainPlayer.currentPosition & mainPlayer.isPlaying
  LaunchedEffect(exoPlayer, playerConnection, isPlaying, clipStartMs, clipEndMs) {
    if (clipStartMs != null && clipEndMs != null) return@LaunchedEffect
    val mainPlayer = playerConnection?.player ?: return@LaunchedEffect
    while (isActive) {
      val videoDuration = exoPlayer.duration
      if (exoPlayer.playbackState == Player.STATE_READY && videoDuration >= 30_000L) {
        val mainPos = mainPlayer.currentPosition.coerceAtLeast(0L)
        val targetPos = if (videoDuration > 0L) mainPos % videoDuration else mainPos
        val drift = abs(exoPlayer.currentPosition - targetPos)
        if (drift > 2_000L) {
          exoPlayer.seekTo(targetPos)
        }
      }
      delay(1_000L)
    }
  }

  DisposableEffect(exoPlayer, primary, fallback) {
    val listener =
      object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
          val next =
            when (currentUrl) {
              primary -> fallback
              else -> null
            }
          if (!next.isNullOrBlank()) {
            currentUrl = next
            isVideoReady = false
          }
        }

        override fun onRenderedFirstFrame() {
          isVideoReady = true
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
          if (videoSize.width > 0 && videoSize.height > 0) {
            currentVideoWidth = videoSize.width
            currentVideoHeight = videoSize.height
            aspectRatioFrameLayout.setAspectRatio(videoSize.width.toFloat() / videoSize.height)
            textureViewRef?.let { tv ->
              updateTextureTransform(tv, tv.width, tv.height, videoSize.width, videoSize.height)
            }
          }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          if (playbackState == Player.STATE_READY && exoPlayer.videoSize.width > 0 && exoPlayer.videoSize.height > 0) {
            isVideoReady = true
          } else if (playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0)
            exoPlayer.play()
          }
        }
      }
    exoPlayer.addListener(listener)
    onDispose { exoPlayer.removeListener(listener) }
  }

  LaunchedEffect(currentUrl, exoPlayer, clipStartMs, clipEndMs) {
    val normalized = currentUrl.trim()
    val mimeType =
      when {
        normalized.contains("mime=video%2Fwebm", ignoreCase = true) ||
          normalized.contains("mime=video/webm", ignoreCase = true) ||
          normalized.contains(".webm", ignoreCase = true) -> MimeTypes.VIDEO_WEBM
        normalized.contains(".m3u8", ignoreCase = true) ||
          normalized.contains("m3u8", ignoreCase = true) ||
          normalized.contains("apple.com", ignoreCase = true) ||
          normalized.contains("music.apple", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
        else -> MimeTypes.VIDEO_MP4
      }

    val mediaItemBuilder = MediaItem.Builder().setUri(normalized).setMimeType(mimeType)
    if (clipStartMs != null && clipEndMs != null && clipEndMs > clipStartMs) {
      mediaItemBuilder.setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(clipStartMs)
          .setEndPositionMs(clipEndMs)
          .setStartsAtKeyFrame(true)
          .build()
      )
    }
    val mediaItem = mediaItemBuilder.build()

    exoPlayer.stop()
    isVideoReady = false
    val startPositionMs =
      if (clipStartMs == null && clipEndMs == null) {
        playerConnection?.player?.currentPosition?.takeIf { it > 0L } ?: 0L
      } else {
        0L
      }
    exoPlayer.setMediaItem(mediaItem, startPositionMs)
    exoPlayer.prepare()
    exoPlayer.playWhenReady = isPlaying
  }

  DisposableEffect(exoPlayer) {
    onDispose {
      onVideoReadyChanged(false)
      textureViewRef?.let { exoPlayer.clearVideoTextureView(it) }
      textureViewRef = null
      exoPlayer.release()
    }
  }

  val alpha by
    animateFloatAsState(
      targetValue = if (isVideoReady) 1f else 0f,
      animationSpec = tween(durationMillis = 300),
      label = "canvasAlpha"
    )

  AndroidView(
    factory = { viewContext ->
      aspectRatioFrameLayout.apply {
        isEnabled = false
        isClickable = false
        isFocusable = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((resources.displayMetrics.density * 140).toInt())
        if (childCount == 0) {
          val textureView =
            TextureView(viewContext).apply {
              layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
              isOpaque = false
              isVerticalFadingEdgeEnabled = true
              setFadingEdgeLength((resources.displayMetrics.density * 140).toInt())
              addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val w = right - left
                val h = bottom - top
                updateTextureTransform(this, w, h, currentVideoWidth, currentVideoHeight)
              }
            }
          addView(textureView)
          textureViewRef = textureView
          exoPlayer.setVideoTextureView(textureView)
        }
      }
    },
    update = { frameLayout ->
      frameLayout.isVerticalFadingEdgeEnabled = true
      frameLayout.setFadingEdgeLength((frameLayout.resources.displayMetrics.density * 140).toInt())
      val tv = (frameLayout.getChildAt(0) as? TextureView) ?: textureViewRef
      if (tv != null) {
        tv.isOpaque = false
        tv.isVerticalFadingEdgeEnabled = true
        tv.setFadingEdgeLength((tv.resources.displayMetrics.density * 140).toInt())
        if (textureViewRef !== tv) {
          textureViewRef = tv
        }
        exoPlayer.setVideoTextureView(tv)
        updateTextureTransform(tv, tv.width, tv.height, currentVideoWidth, currentVideoHeight)
      }
    },
    modifier = modifier.graphicsLayer { this.alpha = alpha },
  )
}
