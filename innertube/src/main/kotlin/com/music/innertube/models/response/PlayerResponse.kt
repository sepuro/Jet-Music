package com.music.innertube.models.response

import com.music.innertube.models.ResponseContext
import com.music.innertube.models.Thumbnails
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PlayerResponse with [com.music.innertube.models.YouTubeClient.WEB_REMIX] client */
@Serializable
data class PlayerResponse(
  val responseContext: ResponseContext,
  val playabilityStatus: PlayabilityStatus,
  val playerConfig: PlayerConfig?,
  val streamingData: StreamingData?,
  val videoDetails: VideoDetails?,
  @SerialName("playbackTracking") val playbackTracking: PlaybackTracking?,
  val captions: Captions? = null,
) {
  @Serializable
  data class PlayabilityStatus(
    val status: String,
    val reason: String?,
  )

  @Serializable
  data class PlayerConfig(
    val audioConfig: AudioConfig,
  ) {
    @Serializable
    data class AudioConfig(
      val loudnessDb: Double?,
      val perceptualLoudnessDb: Double?,
    )
  }

  @Serializable
  data class StreamingData(
    val formats: List<Format>?,
    val adaptiveFormats: List<Format>,
    val expiresInSeconds: Int,
  ) {
    @Serializable
    data class Format(
      val itag: Int,
      val url: String?,
      val mimeType: String,
      val bitrate: Int,
      val width: Int?,
      val height: Int?,
      val contentLength: Long?,
      val quality: String,
      val fps: Int?,
      val qualityLabel: String?,
      val averageBitrate: Int?,
      val audioQuality: String?,
      val approxDurationMs: String?,
      val audioSampleRate: Int?,
      val audioChannels: Int?,
      val loudnessDb: Double?,
      val lastModified: Long?,
      val signatureCipher: String?,
      val cipher: String?,
      val audioTrack: AudioTrack?
    ) {
      val isAudio: Boolean
        get() = width == null

      val isOriginal: Boolean
        get() = audioTrack?.isAutoDubbed == null

      @Serializable
      data class AudioTrack(
        val displayName: String?,
        val id: String?,
        val isAutoDubbed: Boolean?,
      )
    }
  }

  @Serializable
  data class VideoDetails(
    val videoId: String,
    val title: String?,
    val author: String?,
    val channelId: String,
    val lengthSeconds: String,
    val musicVideoType: String?,
    val viewCount: String?,
    val thumbnail: Thumbnails,
  )

  @Serializable
  data class PlaybackTracking(
    @SerialName("videostatsPlaybackUrl") val videostatsPlaybackUrl: VideostatsPlaybackUrl?,
    @SerialName("videostatsWatchtimeUrl") val videostatsWatchtimeUrl: VideostatsWatchtimeUrl?,
    @SerialName("atrUrl") val atrUrl: AtrUrl?,
  ) {
    @Serializable
    data class VideostatsPlaybackUrl(
      @SerialName("baseUrl") val baseUrl: String?,
    )

    @Serializable
    data class VideostatsWatchtimeUrl(
      @SerialName("baseUrl") val baseUrl: String?,
    )

    @Serializable
    data class AtrUrl(
      @SerialName("baseUrl") val baseUrl: String?,
    )
  }

  @Serializable
  data class Captions(
    val playerCaptionsTracklistRenderer: PlayerCaptionsTracklistRenderer? = null,
  ) {
    @Serializable
    data class PlayerCaptionsTracklistRenderer(
      val captionTracks: List<CaptionTrack>? = null,
    ) {
      @Serializable
      data class CaptionTrack(
        val baseUrl: String? = null,
        val name: Name? = null,
        val vssId: String? = null,
        val languageCode: String? = null,
        val kind: String? = null,
        val isTranslatable: Boolean? = null,
      ) {
        @Serializable
        data class Name(
          val simpleText: String? = null,
        )
      }
    }
  }
}
