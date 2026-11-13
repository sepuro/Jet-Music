package com.music.kugou

import com.music.kugou.models.DownloadLyricsResponse
import com.music.kugou.models.Keyword
import com.music.kugou.models.SearchLyricsResponse
import com.music.kugou.models.SearchSongResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import java.lang.Integer.min
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class, ExperimentalEncodingApi::class)
private val client = HttpClient {
  expectSuccess = true

  install(ContentNegotiation) {
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
      encodeDefaults = true
    }
    json(json)
    json(json, ContentType.Text.Html)
    json(json, ContentType.Text.Plain)
  }

  install(ContentEncoding) {
    gzip()
    deflate()
  }
}

private const val PAGE_SIZE = 8
private const val HEAD_CUT_LIMIT = 30

/** KuGou Lyrics Library Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic) */
object KuGou {
  var useTraditionalChinese: Boolean = false

  suspend fun getLyrics(
    title: String,
    artist: String,
    duration: Int,
    album: String? = null
  ): Result<String> = runCatching {
    val candidates = getLyricsCandidates(title, artist, album, duration)
    for (candidate in candidates.take(4)) {
      val downloaded =
        runCatching {
            Base64.Default.decode(downloadLyrics(candidate.id, candidate.accesskey).content)
              .decodeToString()
              .normalize()
          }
          .onFailure { if (it is CancellationException) throw it }
          .getOrNull()
      if (!downloaded.isNullOrBlank()) {
        return@runCatching downloaded
      }
    }
    throw IllegalStateException("No lyrics candidate")
  }

  suspend fun getAllPossibleLyricsOptions(
    title: String,
    artist: String,
    duration: Int,
    album: String? = null,
    callback: (String) -> Unit
  ) {
    val keyword = generateKeyword(title, artist, album)
    searchSongs(keyword).data.info.forEach {
      if (duration == -1 || abs(it.duration - duration) <= DURATION_TOLERANCE) {
        searchLyricsByHash(it.hash).candidates.firstOrNull()?.let { candidate ->
          Base64.Default.decode(downloadLyrics(candidate.id, candidate.accesskey).content)
            .decodeToString()
            .normalize()
            .let(callback)
        }
      }
    }
    searchLyricsByKeyword(keyword, duration).candidates.forEach { candidate ->
      Base64.Default.decode(downloadLyrics(candidate.id, candidate.accesskey).content)
        .decodeToString()
        .normalize()
        .let(callback)
    }
  }

  private suspend fun getLyricsCandidates(
    title: String,
    artist: String,
    album: String?,
    duration: Int,
  ): List<SearchLyricsResponse.Candidate> {
    val results = LinkedHashMap<Long, SearchLyricsResponse.Candidate>()
    val cleanArtist = normalizeArtist(artist)
    val primaryArtist =
      cleanArtist
        .split(Regex("""\s*(?:、|,|/|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE))
        .firstOrNull()
        ?.trim()
        .orEmpty()
        .ifBlank { cleanArtist }

    val versionedTitle = normalizeTitleKeepingVersion(title)
    val baseTitle = normalizeTitle(title)

    val queries =
      buildList {
        add(Keyword(versionedTitle, cleanArtist, album))
        if (primaryArtist != cleanArtist || album != null) {
          add(Keyword(versionedTitle, primaryArtist, null))
        }
        if (baseTitle != versionedTitle) {
          add(Keyword(baseTitle, cleanArtist, null))
          if (primaryArtist != cleanArtist) {
            add(Keyword(baseTitle, primaryArtist, null))
          }
        }
      }.distinct()

    for (kw in queries) {
      findCandidatesForKeyword(kw, duration).forEach { results.putIfAbsent(it.id, it) }
      if (results.isNotEmpty()) break
    }
    return results.values.toList()
  }

  suspend fun getLyricsCandidate(keyword: Keyword, duration: Int): SearchLyricsResponse.Candidate? {
    findCandidatesForKeyword(keyword, duration).firstOrNull()?.let { return it }

    // Fallback with primary artist only when multi-artist keyword fails
    val primaryArtist =
      keyword.artist
        .split(Regex("""\s*(?:、|,|/|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE))
        .firstOrNull()
        ?.trim()
        .orEmpty()
    if (primaryArtist.isNotBlank() && primaryArtist != keyword.artist) {
      return findCandidatesForKeyword(keyword.copy(artist = primaryArtist, album = null), duration)
        .firstOrNull()
    }

    return null
  }

  private suspend fun findCandidatesForKeyword(
    keyword: Keyword,
    duration: Int,
  ): List<SearchLyricsResponse.Candidate> {
    val collected = mutableListOf<SearchLyricsResponse.Candidate>()
    runCatching { searchSongs(keyword) }
      .onFailure { if (it is CancellationException) throw it }
      .getOrNull()
      ?.data
      ?.info
      ?.forEach { song ->
        if (
          duration <= 0 || abs(song.duration - duration) <= DURATION_TOLERANCE
        ) { // if duration <= 0, we don't care duration
          runCatching { searchLyricsByHash(song.hash) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?.candidates
            ?.let(collected::addAll)
        }
      }
    runCatching { searchLyricsByKeyword(keyword, duration) }
      .onFailure { if (it is CancellationException) throw it }
      .getOrNull()
      ?.candidates
      ?.let(collected::addAll)
    return collected.distinctBy { it.id }
  }

  suspend fun searchSongs(keyword: Keyword) =
    client
      .get("https://mobileservice.kugou.com/api/v3/search/song") {
        parameter("version", 9108)
        parameter("plat", 0)
        parameter("pagesize", PAGE_SIZE)
        parameter("showtype", 0)
        val searchQuery = buildString {
          append(keyword.title)
          append(" - ")
          append(keyword.artist)
          if (!keyword.album.isNullOrBlank()) {
            append(" ")
            append(keyword.album)
          }
        }
        url.encodedParameters.append("keyword", searchQuery.encodeURLParameter(spaceToPlus = false))
      }
      .body<SearchSongResponse>()

  private suspend fun searchLyricsByKeyword(keyword: Keyword, duration: Int) =
    client
      .get("https://lyrics.kugou.com/search") {
        parameter("ver", 1)
        parameter("man", "yes")
        parameter("client", "pc")
        parameter(
          "duration",
          duration.takeIf { it != -1 }?.times(1000)
        ) // if duration == -1, we don't care duration
        val searchQuery = buildString {
          append(keyword.title)
          append(" - ")
          append(keyword.artist)
          if (!keyword.album.isNullOrBlank()) {
            append(" ")
            append(keyword.album)
          }
        }
        url.encodedParameters.append("keyword", searchQuery.encodeURLParameter(spaceToPlus = false))
      }
      .body<SearchLyricsResponse>()

  private suspend fun searchLyricsByHash(hash: String) =
    client
      .get("https://lyrics.kugou.com/search") {
        parameter("ver", 1)
        parameter("man", "yes")
        parameter("client", "pc")
        parameter("hash", hash)
      }
      .body<SearchLyricsResponse>()

  private suspend fun downloadLyrics(id: Long, accessKey: String) =
    client
      .get("https://lyrics.kugou.com/download") {
        parameter("fmt", "lrc")
        parameter("charset", "utf8")
        parameter("client", "pc")
        parameter("ver", 1)
        parameter("id", id)
        parameter("accesskey", accessKey)
      }
      .body<DownloadLyricsResponse>()

  private fun normalizeTitleKeepingVersion(title: String) =
    title
      .replace(
        """(?i)\s*\((?:[^()]*\b(?:official\s*(?:music\s*)?video|official\s*audio|video\s*oficial|official|video|audio|lyrics?|visualizer|hd|4k|1080p|720p|letra)\b[^()]*)\)""".toRegex(),
        "",
      )
      .replace("\\[.*?\\]".toRegex(), "")
      .replace("(?i)\\s*\\b(?:feat\\.?|ft\\.?|featuring)\\b.*$".toRegex(), "")
      .replace("\\s+".toRegex(), " ")
      .trim()

  private fun normalizeTitle(title: String) =
    title
      .replace("\\(.*?\\)".toRegex(), "")
      .replace("\\[.*?\\]".toRegex(), "")
      .replace("（.*?）".toRegex(), "")
      .replace("「.*?」".toRegex(), "")
      .replace("『.*?』".toRegex(), "")
      .replace("【.*?】".toRegex(), "")
      .replace("<.*?>".toRegex(), "")
      .replace("《.*?》".toRegex(), "")
      .replace("〈.*?〉".toRegex(), "")
      .replace("＜.*?＞".toRegex(), "")
      .replace("(?i)\\s*\\b(?:feat\\.?|ft\\.?|featuring)\\b.*$".toRegex(), "")
      .replace("\\s+".toRegex(), " ")
      .trim()

  private fun normalizeArtist(artist: String) =
    artist
      .replace("(?i)\\s*-\\s*Topic$".toRegex(), "")
      .replace("(?i)VEVO$".toRegex(), "")
      .replace(", ", "、")
      .replace(",", "、")
      .replace(" & ", "、")
      .replace(".", "")
      .replace("和", "、")
      .replace("\\(.*?\\)".toRegex(), "")
      .replace("（.*?）".toRegex(), "")
      .trim()

  fun generateKeyword(title: String, artist: String, album: String? = null) =
    Keyword(normalizeTitle(title), normalizeArtist(artist), album)

  private fun String.normalize(): String =
    lines()
      .filter { line -> line.matches(ACCEPTED_REGEX) }
      .let { lines ->
        // Remove useless information such as singer, writer, composer, guitar, etc.
        var headCutLine = 0
        for (i in min(HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
          if (lines[i].matches(BANNED_REGEX)) {
            headCutLine = i + 1
            break
          }
        }
        val filteredLines = lines.drop(headCutLine)

        var tailCutLine = 0
        for (i in min(lines.size - HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
          if (lines[lines.lastIndex - i].matches(BANNED_REGEX)) {
            tailCutLine = i + 1
            break
          }
        }
        val finalLines = filteredLines.dropLast(tailCutLine)

        return@let finalLines.joinToString("\n")
      }

  @Suppress("RegExpRedundantEscape")
  private val ACCEPTED_REGEX = "\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\].*".toRegex()
  private val BANNED_REGEX = ".+].+[:：].+".toRegex()

  private const val DURATION_TOLERANCE = 15
}
