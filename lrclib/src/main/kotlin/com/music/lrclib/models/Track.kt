package com.music.lrclib.models

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

private const val DURATION_TOLERANCE_SECONDS = 15

@Serializable
data class Track(
  val id: Int,
  val trackName: String,
  val artistName: String,
  val duration: Double,
  val plainLyrics: String?,
  val syncedLyrics: String?,
)

internal fun Track.matchesDuration(duration: Int, toleranceSeconds: Int = DURATION_TOLERANCE_SECONDS): Boolean {
  if (duration <= 0) return true
  return abs(this.duration.roundToInt() - duration) <= toleranceSeconds
}

internal fun List<Track>.bestMatchingFor(duration: Int): Track? {
  if (isEmpty()) return null

  if (duration <= 0) {
    return firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: firstOrNull()
  }

  return minByOrNull { abs(it.duration.roundToInt() - duration) }
    ?.takeIf { abs(it.duration.roundToInt() - duration) <= 2 }
}

// Relaxed matching with ±15 seconds tolerance
internal fun List<Track>.bestMatchingForRelaxed(
  duration: Int,
  trackName: String? = null,
  artistName: String? = null,
): Track? {
  if (isEmpty()) return null

  bestSyncedMatch(duration, trackName, artistName)?.let { return it }

  if (duration <= 0) {
    return if (trackName != null && artistName != null) {
      findBestMatch(trackName, artistName)
    } else {
      firstOrNull { !it.plainLyrics.isNullOrBlank() } ?: firstOrNull()
    }
  }

  val withinTolerance = filter { !it.plainLyrics.isNullOrBlank() && it.matchesDuration(duration) }
  if (withinTolerance.isEmpty()) return null

  return if (trackName != null && artistName != null) {
    withinTolerance.findBestMatch(trackName, artistName, duration)
  } else {
    withinTolerance.minByOrNull { abs(it.duration.roundToInt() - duration) }
  }
}

internal fun List<Track>.bestSyncedMatch(
  duration: Int,
  trackName: String? = null,
  artistName: String? = null,
  toleranceSeconds: Int = DURATION_TOLERANCE_SECONDS,
): Track? {
  val syncedTracks =
    filter { !it.syncedLyrics.isNullOrBlank() && it.matchesDuration(duration, toleranceSeconds) }
  if (syncedTracks.isEmpty()) return null

  if (trackName != null && artistName != null) {
    return syncedTracks.findBestMatch(trackName, artistName, duration, toleranceSeconds)
  }

  return if (duration <= 0) {
    syncedTracks.firstOrNull()
  } else {
    syncedTracks.minByOrNull { abs(it.duration.roundToInt() - duration) }
  }
}

internal fun List<Track>.bestMatchingFor(
  duration: Int,
  trackName: String? = null,
  artistName: String? = null
): Track? {
  if (isEmpty()) return null

  if (duration <= 0) {
    if (trackName != null && artistName != null) {
      return findBestMatch(trackName, artistName)
    }
    return firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: firstOrNull()
  }

  return bestMatchingForRelaxed(duration, trackName, artistName)
}

private fun List<Track>.findBestMatch(
  trackName: String,
  artistName: String,
  duration: Int = -1,
  toleranceSeconds: Int = DURATION_TOLERANCE_SECONDS,
): Track? {
  val normalizedTrackName = trackName.trim().lowercase()
  val normalizedArtistName = artistName.trim().lowercase()

  val scored =
    mapNotNull { track ->
      val trackNameSimilarity =
        calculateSimilarity(normalizedTrackName, track.trackName.trim().lowercase())
      val artistNameSimilarity =
        if (normalizedArtistName.isBlank()) {
          1.0
        } else {
          calculateSimilarity(normalizedArtistName, track.artistName.trim().lowercase())
        }

      val combinedSimilarity =
        if (normalizedArtistName.isBlank()) {
          trackNameSimilarity
        } else {
          (trackNameSimilarity * 0.6) + (artistNameSimilarity * 0.4)
        }

      // Reject unrelated tracks when searching by free-text query
      val validMatch =
        trackNameSimilarity >= 0.45 &&
          (normalizedArtistName.isBlank() || artistNameSimilarity >= 0.35 || combinedSimilarity >= 0.55)
      if (!validMatch) return@mapNotNull null

      var score = combinedSimilarity
      if (!track.syncedLyrics.isNullOrBlank()) score += 0.25
      if (duration > 0) {
        val diff = abs(track.duration.roundToInt() - duration)
        score += (1.0 - (diff.toDouble() / toleranceSeconds.coerceAtLeast(1).toDouble())) * 0.15
      }
      track to score
    }

  return scored.maxByOrNull { it.second }?.first
}

private fun calculateSimilarity(str1: String, str2: String): Double {
  if (str1 == str2) return 1.0
  if (str1.isEmpty() || str2.isEmpty()) return 0.0

  val containsScore =
    when {
      str1.contains(str2) || str2.contains(str1) -> 0.8
      else -> 0.0
    }

  val maxLength = maxOf(str1.length, str2.length)
  val distance = levenshteinDistance(str1, str2)
  val distanceScore = 1.0 - (distance.toDouble() / maxLength)

  return maxOf(containsScore, distanceScore)
}

private fun levenshteinDistance(str1: String, str2: String): Int {
  val len1 = str1.length
  val len2 = str2.length
  val matrix = Array(len1 + 1) { IntArray(len2 + 1) }

  for (i in 0..len1) matrix[i][0] = i
  for (j in 0..len2) matrix[0][j] = j

  for (i in 1..len1) {
    for (j in 1..len2) {
      val cost = if (str1[i - 1] == str2[j - 1]) 0 else 1
      matrix[i][j] =
        minOf(
          matrix[i - 1][j] + 1, // deletion
          matrix[i][j - 1] + 1, // insertion
          matrix[i - 1][j - 1] + cost // substitution
        )
    }
  }

  return matrix[len1][len2]
}
