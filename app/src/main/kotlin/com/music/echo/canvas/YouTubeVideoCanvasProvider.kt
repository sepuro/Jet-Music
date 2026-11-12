package echo.music.iad1tya.canvas

import com.music.innertube.NewPipeExtractor
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.response.PlayerResponse
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object YouTubeVideoCanvasProvider {
  private const val TAG = "YouTubeVideoCanvasProvider"
  private val cache = ConcurrentHashMap<String, CanvasArtwork>()

  private suspend fun findOfficialVideoIds(title: String, artist: String): List<String> {
    val cleanTitle = title
      .replace(Regex("""\s*[\(\[].*?[\)\]]\s*"""), " ")
      .replace(Regex("""\s*[-|/].*"""), " ")
      .trim()
      .ifBlank { title.trim() }
    val cleanArtist = artist
      .split(',', '&', 'x', 'X', '/', ';')
      .firstOrNull()
      ?.replace(Regex("""\s*[\(\[].*?[\)\]]\s*"""), " ")
      ?.trim()
      ?.ifBlank { artist.trim() }
      ?: artist.trim()

    val titleKeywords = cleanTitle.lowercase(Locale.ROOT)
      .split(' ')
      .map { it.trim().filter { c -> c.isLetterOrDigit() } }
      .filter { it.length >= 2 }

    val artistKeywords = cleanArtist.lowercase(Locale.ROOT)
      .split(' ')
      .map { it.trim().filter { c -> c.isLetterOrDigit() } }
      .filter { it.length >= 2 }

    val negativeKeywords = listOf(
      "mashup", "mash up", "mash-up", "slowed", "reverb",
      "speed up", "sped up", "nightcore", "bass boosted", "8d audio", "8d",
      "reaction", "review", "behind the scenes", "making of", "teaser", "trailer",
      "karaoke", "full audio", "audio only", "whatsapp status", "movie scene", "episode"
    )

    val queries = buildList {
      if (cleanTitle.isNotBlank() && cleanArtist.isNotBlank()) {
        add("$cleanTitle $cleanArtist official music video")
        add("$cleanTitle $cleanArtist official video")
        add("$cleanTitle $cleanArtist music video")
        add("$cleanTitle $cleanArtist")
      } else if (cleanTitle.isNotBlank()) {
        add("$cleanTitle official music video")
        add("$cleanTitle music video")
      }
    }.distinct()

    val foundIds = LinkedHashSet<String>()

    for (query in queries) {
      try {
        val result = YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()
        val items = result?.items.orEmpty()

        val scored = items.mapNotNull { item ->
          val itemId = item.id
          if (itemId.isBlank()) return@mapNotNull null

          val itemTitle = item.title.lowercase(Locale.ROOT)
          val itemArtists = (item as? SongItem)?.artists.orEmpty().map { it.name.lowercase(Locale.ROOT) }

          val hasNegative = negativeKeywords.any { neg ->
            itemTitle.contains(neg) && !cleanTitle.lowercase(Locale.ROOT).contains(neg)
          }
          if (hasNegative) return@mapNotNull null

          val lowerCleanTitle = cleanTitle.lowercase(Locale.ROOT)
          val hasExactTitle = lowerCleanTitle.isNotBlank() && itemTitle.contains(lowerCleanTitle)
          val matchedTitleKeywordsCount = titleKeywords.count { kw -> itemTitle.contains(kw) }

          val titleMatches = when {
            hasExactTitle -> true
            titleKeywords.isEmpty() -> true
            titleKeywords.size == 1 -> matchedTitleKeywordsCount >= 1
            else -> matchedTitleKeywordsCount.toFloat() / titleKeywords.size >= 0.5f
          }

          val matchesArtist = artistKeywords.isEmpty() ||
            artistKeywords.any { kw -> itemTitle.contains(kw) } ||
            itemArtists.any { a -> artistKeywords.any { kw -> a.contains(kw) } }

          if (!titleMatches && !matchesArtist) return@mapNotNull null

          var score = 0

          if (hasExactTitle) {
            score += 50
          } else {
            score += matchedTitleKeywordsCount * 18
          }

          if (matchesArtist) {
            score += 35
          }

          if (
            itemTitle.contains("official music video") ||
            itemTitle.contains("official video") ||
            itemTitle.contains("official mv") ||
            itemTitle.contains("video oficial")
          ) {
            score += 45
          } else if (
            itemTitle.contains("music video") ||
            itemTitle.contains("visualizer") ||
            itemTitle.contains("mv") ||
            itemTitle.contains("official")
          ) {
            score += 25
          }

          if (item is SongItem && item.isVideoSong) {
            score += 35
          }

          if (score >= 40) {
            Pair(item, score)
          } else {
            null
          }
        }.sortedByDescending { it.second }

        for ((candidate, score) in scored.take(3)) {
          Timber.tag(TAG).d("Candidate official video: '${candidate.title}' id=${candidate.id} (score=$score)")
          foundIds.add(candidate.id)
        }

        if (foundIds.isEmpty()) {
          items.firstOrNull { it.id.isNotBlank() }?.let { fallbackItem ->
            Timber.tag(TAG).d("Fallback video search result: '${fallbackItem.title}' id=${fallbackItem.id}")
            foundIds.add(fallbackItem.id)
          }
        }

        if (foundIds.size >= 2) break
      } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Error searching for official video query: $query")
      }
    }
    return foundIds.toList()
  }

  private fun codecPriority(mimeType: String): Int {
    val lower = mimeType.lowercase(Locale.ROOT)
    return when {
      lower.contains("av01") -> 3
      lower.contains("vp9") || lower.contains("vp09") -> 2
      lower.contains("avc1") || lower.contains("mp4") -> 1
      else -> 0
    }
  }

  private suspend fun resolveVideoStreamForId(
    targetVideoId: String,
    fallbackDurationSec: Long
  ): Pair<String, Long>? {
    var totalDurationSec = fallbackDurationSec.coerceAtLeast(0L)

    val clients = listOf(
      YouTubeClient.VISIONOS,
      YouTubeClient.ANDROID_VR_1_65_10,
      YouTubeClient.TVHTML5,
      YouTubeClient.ANDROID_VR_1_43_32,
      YouTubeClient.IOS,
      YouTubeClient.WEB_REMIX
    )

    var bestFallbackPair: Pair<PlayerResponse.StreamingData.Format, Long>? = null

    for (client in clients) {
      try {
        val response = YouTube.player(videoId = targetVideoId, client = client).getOrNull()
        val streamingData = response?.streamingData ?: continue

        if (totalDurationSec <= 0L) {
          totalDurationSec = response.videoDetails?.lengthSeconds?.toLongOrNull() ?: 0L
        }

        val adaptiveFormats = streamingData.adaptiveFormats + streamingData.formats.orEmpty()
        val bestVideoFormat =
          adaptiveFormats
            .filter { it.mimeType.startsWith("video/") || it.height != null || it.width != null }
            .mapNotNull { format ->
              val resolvedUrl =
                format.url?.takeIf { it.isNotBlank() }
                  ?: NewPipeExtractor.getStreamUrl(format, targetVideoId)?.takeIf { it.isNotBlank() }
                  ?: return@mapNotNull null
              format.copy(url = resolvedUrl)
            }
            .maxWithOrNull(
              compareBy<PlayerResponse.StreamingData.Format>(
                { it.height ?: 0 },
                { it.fps ?: 0 },
                { codecPriority(it.mimeType) },
                { it.bitrate },
              )
            )

        val url = bestVideoFormat?.url
        if (bestVideoFormat != null && !url.isNullOrBlank()) {
          val height = bestVideoFormat.height ?: 0
          Timber.tag(TAG).d(
            "Found (${height}p @ ${bestVideoFormat.fps ?: 30}fps, ${bestVideoFormat.bitrate}bps) video stream via InnerTube (${client.clientName}) for $targetVideoId"
          )
          if (height >= 1080) {
            return Pair(url, totalDurationSec)
          }
          val currentBestHeight = bestFallbackPair?.first?.height ?: 0
          val currentBestBitrate = bestFallbackPair?.first?.bitrate ?: 0
          if (
            bestFallbackPair == null ||
              height > currentBestHeight ||
              (height == currentBestHeight && bestVideoFormat.bitrate > currentBestBitrate)
          ) {
            bestFallbackPair = Pair(bestVideoFormat, totalDurationSec)
          }
        }
      } catch (e: Exception) {
        Timber.tag(TAG).w(e, "InnerTube client ${client.clientName} failed for $targetVideoId")
      }
    }

    bestFallbackPair?.let { (format, dur) ->
      val url = format.url
      if (!url.isNullOrBlank()) {
        return Pair(url, dur)
      }
    }

    try {
      val newPipeResult = NewPipeExtractor.getVideoUrlAndDuration(targetVideoId)
      if (newPipeResult != null && newPipeResult.first.isNotBlank()) {
        val resolvedDur = if (totalDurationSec > 0L) totalDurationSec else newPipeResult.second
        Timber.tag(TAG).d("Found video stream via NewPipe for $targetVideoId")
        return Pair(newPipeResult.first, resolvedDur)
      }
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "NewPipeExtractor failed for $targetVideoId")
    }

    return null
  }

  suspend fun getCanvasForVideo(
    videoId: String,
    title: String? = null,
    artist: String? = null,
    durationSec: Long? = null
  ): CanvasArtwork? {
    if (videoId.isBlank() || videoId.length < 5) return null
    cache[videoId]?.let { return it }

    return withContext(Dispatchers.IO) {
      val officialVideoIds = if (!title.isNullOrBlank()) {
        findOfficialVideoIds(title, artist.orEmpty())
      } else {
        emptyList()
      }

      // Try official music video candidates first (so ATV audio tracks get upgraded to OMV video),
      // then fall back to the original videoId if needed.
      val candidateIds = (officialVideoIds + listOf(videoId)).distinct()
      Timber.tag(TAG).d("Resolving canvas stream across candidates=$candidateIds (original: $videoId)")

      var resolvedVideoUrl: String? = null
      var resolvedTargetId: String = videoId
      var totalDurationSec: Long = durationSec?.takeIf { it > 0 } ?: 0L

      for (candidateId in candidateIds) {
        cache[candidateId]?.let { cached ->
          cache[videoId] = cached
          return@withContext cached
        }
        val initialDuration = if (candidateId == videoId) totalDurationSec else 0L
        val streamResult = resolveVideoStreamForId(candidateId, initialDuration)
        if (streamResult != null) {
          resolvedVideoUrl = streamResult.first
          totalDurationSec = streamResult.second
          resolvedTargetId = candidateId
          break
        }
      }

      if (resolvedVideoUrl.isNullOrBlank()) {
        Timber.tag(TAG).w("No video stream found for any candidate of videoId: $videoId")
        return@withContext null
      }

      val artwork = CanvasArtwork(
        name = title,
        artist = artist,
        videoUrl = resolvedVideoUrl,
        animated = resolvedVideoUrl,
        clipStartMs = null,
        clipEndMs = null
      )

      cache[videoId] = artwork
      if (resolvedTargetId != videoId) {
        cache[resolvedTargetId] = artwork
      }
      artwork
    }
  }
}
