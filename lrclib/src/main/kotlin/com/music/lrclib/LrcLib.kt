package com.music.lrclib

import com.music.echo.metadata.cleaner.MetadataCleaner
import com.music.lrclib.models.Track
import com.music.lrclib.models.bestMatchingForRelaxed
import com.music.lrclib.models.bestSyncedMatch
import com.music.lrclib.models.matchesDuration
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

object LrcLib {
  private const val DURATION_TOLERANCE_SECONDS = 8

  private val NOISY_PARENS_REGEX =
    Regex(
      """\s*\((?:[^()]*\b(?:official|video|audio|lyrics?|lyric|visualizer|hd|4k|1080p|720p|remix|live|acoustic|explicit|clean|remaster(?:ed)?|deluxe|radio\s+edit|extended|slowed|sped\s+up|nightcore|version|letra|en\s+vivo)\b[^()]*)\)""",
      RegexOption.IGNORE_CASE,
    )
  private val SQUARE_BRACKETS_REGEX = Regex("""\s*\[.*?\]""")
  private val CJK_BRACKETS_REGEX = Regex("""\s*(?:【.*?】|「.*?」)""")
  private val FEAT_SUFFIX_REGEX =
    Regex("""\s*(?:\(|\[)?\b(?:feat\.?|ft\.?|featuring)\b.*$""", RegexOption.IGNORE_CASE)
  private val ARTIST_SPLIT_REGEX =
    Regex("""\s*(?:,|/|•|;|&|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\band\b|\bx\b)\s*""", RegexOption.IGNORE_CASE)
  private val MULTI_SPACE_REGEX = Regex("""\s+""")

  private val client by lazy {
    HttpClient(CIO) {
      install(ContentNegotiation) {
        json(
          Json {
            isLenient = true
            ignoreUnknownKeys = true
          },
        )
      }

      defaultRequest { url("https://lrclib.net") }

      expectSuccess = true
    }
  }

  internal fun cleanTitleForSearch(title: String, artist: String = ""): String {
    val metadataCleaned = MetadataCleaner.clean(title, artist).toSearchQuery().first
    val stripped =
      metadataCleaned
        .replace(NOISY_PARENS_REGEX, " ")
        .replace(SQUARE_BRACKETS_REGEX, " ")
        .replace(CJK_BRACKETS_REGEX, " ")
        .replace(FEAT_SUFFIX_REGEX, "")
        .replace(MULTI_SPACE_REGEX, " ")
        .trim()
        .trimEnd('-', '–', '—', '|', '/')
        .trim()
    return stripped.ifBlank { metadataCleaned.ifBlank { title.trim() } }
  }

  internal fun extractPrimaryArtist(artist: String, title: String = ""): String {
    val cleaned = MetadataCleaner.clean(title, artist)
    val baseArtist = cleaned.primaryArtist.ifBlank { cleaned.cleanArtist }.ifBlank { artist.trim() }
    val splitPrimary = baseArtist.split(ARTIST_SPLIT_REGEX).firstOrNull()?.trim().orEmpty()
    return splitPrimary.ifBlank { baseArtist }
  }

  private suspend fun getTrackExact(
    trackName: String,
    artistName: String,
    duration: Int,
    albumName: String? = null,
  ): Track? {
    if (trackName.isBlank() || artistName.isBlank()) return null
    return runCatching {
        client
          .get("/api/get") {
            parameter("track_name", trackName)
            parameter("artist_name", artistName)
            if (!albumName.isNullOrBlank()) parameter("album_name", albumName)
            if (duration > 0) parameter("duration", duration)
          }
          .body<Track>()
      }
      .onFailure { if (it is CancellationException) throw it }
      .getOrNull()
      ?.takeIf { it.matchesDuration(duration, DURATION_TOLERANCE_SECONDS) }
  }

  private suspend fun queryLyricsWithParams(
    trackName: String? = null,
    artistName: String? = null,
    albumName: String? = null,
    query: String? = null,
  ): List<Track> =
    runCatching {
        client
          .get("/api/search") {
            if (query != null) parameter("q", query)
            if (trackName != null) parameter("track_name", trackName)
            if (artistName != null) parameter("artist_name", artistName)
            if (albumName != null) parameter("album_name", albumName)
          }
          .body<List<Track>>()
      }
      .onFailure { if (it is CancellationException) throw it }
      .getOrDefault(emptyList())

  private suspend fun queryLyrics(
    artist: String,
    title: String,
    album: String? = null,
  ): List<Track> {
    val rawTitle = title.trim()
    val rawArtist = artist.trim()
    val cleanTitle = cleanTitleForSearch(rawTitle, rawArtist)
    val primaryArtist = extractPrimaryArtist(rawArtist, rawTitle)

    val collected = LinkedHashMap<Int, Track>()

    // Attempt 1: Original title and artist via /api/search
    queryLyricsWithParams(trackName = rawTitle, artistName = rawArtist)
      .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
      .forEach { collected[it.id] = it }

    if (collected.values.any { !it.syncedLyrics.isNullOrBlank() }) {
      return collected.values.toList()
    }

    // Attempt 2: Cleaned title + primary artist via /api/search?q=$cleanTitle $primaryArtist
    val combinedQuery = listOf(cleanTitle, primaryArtist).filter { it.isNotBlank() }.joinToString(" ")
    if (combinedQuery.isNotBlank()) {
      queryLyricsWithParams(query = combinedQuery)
        .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
        .forEach { collected.putIfAbsent(it.id, it) }
    }

    if (collected.values.none { !it.syncedLyrics.isNullOrBlank() } && cleanTitle.isNotBlank()) {
      if (primaryArtist.isNotBlank()) {
        queryLyricsWithParams(trackName = cleanTitle, artistName = primaryArtist)
          .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
          .forEach { collected.putIfAbsent(it.id, it) }
      }

      if (collected.values.none { !it.syncedLyrics.isNullOrBlank() }) {
        queryLyricsWithParams(query = cleanTitle)
          .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
          .forEach { collected.putIfAbsent(it.id, it) }
      }
    }

    if (collected.isEmpty() && !album.isNullOrBlank()) {
      queryLyricsWithParams(trackName = cleanTitle, artistName = primaryArtist, albumName = album)
        .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
        .forEach { collected.putIfAbsent(it.id, it) }
    }

    return collected.values.toList()
  }

  suspend fun getLyrics(
    title: String,
    artist: String,
    duration: Int,
    album: String? = null,
  ) = runCatching {
    val rawTitle = title.trim()
    val rawArtist = artist.trim()
    var plainFallback: String? = null

    // Attempt 1: Original title and artist (/api/get and /api/search with ±8s tolerance)
    val exactOriginal = getTrackExact(rawTitle, rawArtist, duration)
    if (exactOriginal != null) {
      if (!exactOriginal.syncedLyrics.isNullOrBlank()) {
        return@runCatching exactOriginal.syncedLyrics
      }
      if (plainFallback == null && !exactOriginal.plainLyrics.isNullOrBlank()) {
        plainFallback = exactOriginal.plainLyrics
      }
    }

    val originalSearchTracks =
      queryLyricsWithParams(trackName = rawTitle, artistName = rawArtist)
        .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }

    originalSearchTracks.bestSyncedMatch(duration, rawTitle, rawArtist)?.syncedLyrics?.let {
      return@runCatching it
    }
    if (plainFallback == null) {
      plainFallback =
        originalSearchTracks.bestMatchingForRelaxed(duration, rawTitle, rawArtist)?.plainLyrics
    }

    // Attempt 2: Clean noisy YouTube metadata and take primary artist (/api/get + /api/search?q=...)
    val cleanTitle = cleanTitleForSearch(rawTitle, rawArtist)
    val primaryArtist = extractPrimaryArtist(rawArtist, rawTitle)

    if (cleanTitle != rawTitle || primaryArtist != rawArtist) {
      val exactCleaned = getTrackExact(cleanTitle, primaryArtist, duration)
      if (exactCleaned != null) {
        if (!exactCleaned.syncedLyrics.isNullOrBlank()) {
          return@runCatching exactCleaned.syncedLyrics
        }
        if (plainFallback == null && !exactCleaned.plainLyrics.isNullOrBlank()) {
          plainFallback = exactCleaned.plainLyrics
        }
      }
    }

    val combinedQuery = listOf(cleanTitle, primaryArtist).filter { it.isNotBlank() }.joinToString(" ")
    if (combinedQuery.isNotBlank()) {
      val qTracks =
        queryLyricsWithParams(query = combinedQuery)
          .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }

      qTracks.bestSyncedMatch(duration, cleanTitle, primaryArtist)?.syncedLyrics?.let {
        return@runCatching it
      }
      if (plainFallback == null) {
        plainFallback = qTracks.bestMatchingForRelaxed(duration, cleanTitle, primaryArtist)?.plainLyrics
      }
    }

    if (cleanTitle.isNotBlank() && primaryArtist.isNotBlank()) {
      val cleanFieldTracks =
        queryLyricsWithParams(trackName = cleanTitle, artistName = primaryArtist)
          .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }

      cleanFieldTracks.bestSyncedMatch(duration, cleanTitle, primaryArtist)?.syncedLyrics?.let {
        return@runCatching it
      }
      if (plainFallback == null) {
        plainFallback =
          cleanFieldTracks.bestMatchingForRelaxed(duration, cleanTitle, primaryArtist)?.plainLyrics
      }
    }

    if (cleanTitle.isNotBlank()) {
      val titleOnlyTracks =
        queryLyricsWithParams(query = cleanTitle)
          .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }

      titleOnlyTracks.bestSyncedMatch(duration, cleanTitle, primaryArtist)?.syncedLyrics?.let {
        return@runCatching it
      }
      if (plainFallback == null) {
        plainFallback =
          titleOnlyTracks.bestMatchingForRelaxed(duration, cleanTitle, primaryArtist)?.plainLyrics
      }
    }

    plainFallback?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Lyrics unavailable")
  }

  suspend fun getAllLyrics(
    title: String,
    artist: String,
    duration: Int,
    album: String? = null,
    callback: (String) -> Unit,
  ) {
    val cleanTitle = cleanTitleForSearch(title, artist)
    val primaryArtist = extractPrimaryArtist(artist, title)
    val tracks = queryLyrics(artist, title, album)
    var count = 0
    var plain = 0

    val sortedTracks =
      when {
        duration <= 0 -> {
          tracks.sortedByDescending { track ->
            var score = 0.0

            if (!track.syncedLyrics.isNullOrBlank()) score += 1.0

            val titleSimilarity = calculateStringSimilarity(cleanTitle, track.trackName)
            val artistSimilarity = calculateStringSimilarity(primaryArtist, track.artistName)
            score += (titleSimilarity + artistSimilarity) / 2.0

            score
          }
        }
        else -> {
          tracks.sortedWith(
            compareByDescending<Track> { !it.syncedLyrics.isNullOrBlank() }
              .thenBy { abs(it.duration.roundToInt() - duration) }
          )
        }
      }

    sortedTracks.forEach { track ->
      currentCoroutineContext().ensureActive()
      if (count <= 4) {
        if (!track.syncedLyrics.isNullOrBlank() && duration <= 0) {
          count++
          track.syncedLyrics.let(callback)
        } else {
          // Relaxed duration matching (±8 seconds)
          if (!track.syncedLyrics.isNullOrBlank() && track.matchesDuration(duration, DURATION_TOLERANCE_SECONDS)) {
            count++
            track.syncedLyrics.let(callback)
          }
          if (
            !track.plainLyrics.isNullOrBlank() &&
              track.matchesDuration(duration, DURATION_TOLERANCE_SECONDS) &&
              plain == 0
          ) {
            count++
            plain++
            track.plainLyrics.let(callback)
          }
        }
      }
    }
  }

  private fun calculateStringSimilarity(str1: String, str2: String): Double {
    val s1 = str1.trim().lowercase()
    val s2 = str2.trim().lowercase()

    if (s1 == s2) return 1.0
    if (s1.isEmpty() || s2.isEmpty()) return 0.0

    return when {
      s1.contains(s2) || s2.contains(s1) -> 0.8
      else -> {
        val maxLength = maxOf(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        1.0 - (distance.toDouble() / maxLength)
      }
    }
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

  suspend fun lyrics(
    artist: String,
    title: String,
  ) = runCatching { queryLyrics(artist = artist, title = title, album = null) }

  @JvmInline
  value class Lyrics(
    val text: String,
  ) {
    val sentences
      get() =
        runCatching {
            buildMap {
              put(0L, "")
              text
                .trim()
                .lines()
                .filter { it.length >= 10 }
                .forEach {
                  put(
                    it[8].digitToInt() * 10L +
                      it[7].digitToInt() * 100 +
                      it[5].digitToInt() * 1000 +
                      it[4].digitToInt() * 10000 +
                      it[2].digitToInt() * 60 * 1000 +
                      it[1].digitToInt() * 600 * 1000,
                    it.substring(10),
                  )
                }
            }
          }
          .getOrNull()
  }
}
