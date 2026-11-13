package com.music.innertube.utils

import com.music.innertube.models.response.PlayerResponse
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import org.xml.sax.InputSource

object YouTubeLyricsParser {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
  }

  private val FMT_PARAM_REGEX = Regex("([?&])fmt=[^&]*")
  private val WHITESPACE_REGEX = Regex("\\s+")
  private val HTML_TAG_REGEX = Regex("<[^>]+>")

  @Serializable
  private data class Json3Response(
    val events: List<Event>? = null,
  ) {
    @Serializable
    data class Event(
      val tStartMs: Long? = null,
      val dDurationMs: Long? = null,
      val segs: List<Seg>? = null,
    ) {
      @Serializable
      data class Seg(
        val utf8: String? = null,
      )
    }
  }

  fun formatLrcTimestamp(timeMs: Long): String {
    val safeMs = timeMs.coerceAtLeast(0L)
    val minutes = safeMs / 60_000L
    val seconds = (safeMs % 60_000L) / 1_000L
    val centiseconds = (safeMs % 1_000L) / 10L
    return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centiseconds)
  }

  fun formatLrcLine(timeMs: Long, rawText: String): String? {
    val cleaned = cleanCueText(rawText)
    if (cleaned.isBlank()) return null
    return "${formatLrcTimestamp(timeMs)}$cleaned"
  }

  fun extractTimedLyricsFromBrowseJson(root: JsonElement): String? {
    val timedLyricsArray = findTimedLyricsDataArray(root) ?: return null
    if (timedLyricsArray.isEmpty()) return null

    val lines =
      timedLyricsArray.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val lyricLine = obj["lyricLine"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val cueRange = obj["cueRange"] as? JsonObject ?: return@mapNotNull null
        val startMs =
          cueRange["startTimeMilliseconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: return@mapNotNull null
        formatLrcLine(startMs, lyricLine)
      }

    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
  }

  fun extractPlainDescriptionLyricsFromBrowseJson(root: JsonElement): String? {
    val descriptionShelf = findObjectByKey(root, "musicDescriptionShelfRenderer") ?: return null
    val description = descriptionShelf["description"] as? JsonObject ?: return null
    val runs = description["runs"] as? JsonArray ?: return null
    val text =
      runs.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        .joinToString(separator = "")
        .trim()
    return text.takeIf { it.isNotBlank() }
  }

  private fun findTimedLyricsDataArray(element: JsonElement): JsonArray? {
    // Fast-path for standard elementRenderer -> newElement -> type -> componentType -> model ->
    // timedLyricsModel -> lyricsData -> timedLyricsData
    val sectionContents =
      (element as? JsonObject)
        ?.get("contents")
        ?.let { it as? JsonObject }
        ?.get("sectionListRenderer")
        ?.let { it as? JsonObject }
        ?.get("contents") as? JsonArray

    if (sectionContents != null) {
      for (content in sectionContents) {
        val timedArray =
          (content as? JsonObject)
            ?.get("elementRenderer")
            ?.let { it as? JsonObject }
            ?.get("newElement")
            ?.let { it as? JsonObject }
            ?.get("type")
            ?.let { it as? JsonObject }
            ?.get("componentType")
            ?.let { it as? JsonObject }
            ?.get("model")
            ?.let { it as? JsonObject }
            ?.get("timedLyricsModel")
            ?.let { it as? JsonObject }
            ?.get("lyricsData")
            ?.let { it as? JsonObject }
            ?.get("timedLyricsData") as? JsonArray
        if (timedArray != null && timedArray.isNotEmpty()) {
          return timedArray
        }
      }
    }

    // Recursive fallback if nested inside another container
    val timedModel = findObjectByKey(element, "timedLyricsModel")
    val fromModel =
      (timedModel?.get("lyricsData") as? JsonObject)?.get("timedLyricsData") as? JsonArray
    if (fromModel != null && fromModel.isNotEmpty()) return fromModel

    return findArrayByKey(element, "timedLyricsData")
  }

  private fun findObjectByKey(element: JsonElement, targetKey: String): JsonObject? {
    when (element) {
      is JsonObject -> {
        val direct = element[targetKey] as? JsonObject
        if (direct != null) return direct
        for (value in element.values) {
          val found = findObjectByKey(value, targetKey)
          if (found != null) return found
        }
      }
      is JsonArray -> {
        for (item in element) {
          val found = findObjectByKey(item, targetKey)
          if (found != null) return found
        }
      }
      is JsonPrimitive -> Unit
    }
    return null
  }

  private fun findArrayByKey(element: JsonElement, targetKey: String): JsonArray? {
    when (element) {
      is JsonObject -> {
        val direct = element[targetKey] as? JsonArray
        if (direct != null) return direct
        for (value in element.values) {
          val found = findArrayByKey(value, targetKey)
          if (found != null) return found
        }
      }
      is JsonArray -> {
        for (item in element) {
          val found = findArrayByKey(item, targetKey)
          if (found != null) return found
        }
      }
      is JsonPrimitive -> Unit
    }
    return null
  }

  fun selectManualCaptionTracks(
    captionTracks: List<PlayerResponse.Captions.PlayerCaptionsTracklistRenderer.CaptionTrack>?,
    preferredLanguage: String?,
  ): List<PlayerResponse.Captions.PlayerCaptionsTracklistRenderer.CaptionTrack> {
    if (captionTracks.isNullOrEmpty()) return emptyList()

    val manualTracks =
      captionTracks.filter { track ->
        !track.baseUrl.isNullOrBlank() &&
          !track.kind.equals("asr", ignoreCase = true) &&
          track.vssId?.startsWith("a.", ignoreCase = true) != true
      }

    if (manualTracks.isEmpty()) return emptyList()

    val primaryLang = preferredLanguage?.substringBefore("-")?.lowercase()
    return manualTracks.sortedWith(
      compareByDescending<PlayerResponse.Captions.PlayerCaptionsTracklistRenderer.CaptionTrack> {
          it.name?.simpleText?.contains("lyric", ignoreCase = true) == true
        }
        .thenByDescending {
          primaryLang != null &&
            it.languageCode?.substringBefore("-")?.equals(primaryLang, ignoreCase = true) == true
        }
        .thenByDescending {
          it.languageCode?.substringBefore("-")?.equals("en", ignoreCase = true) == true
        }
    )
  }

  fun withTimedTextFormat(baseUrl: String, format: String): String {
    return if (FMT_PARAM_REGEX.containsMatchIn(baseUrl)) {
      FMT_PARAM_REGEX.replaceFirst(baseUrl, "$1fmt=$format")
    } else {
      val separator = if ('?' in baseUrl) "&" else "?"
      "$baseUrl${separator}fmt=$format"
    }
  }

  fun parseJson3ToLrc(jsonText: String): String? {
    val trimmed = jsonText.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null

    val parsed = runCatching { json.decodeFromString<Json3Response>(trimmed) }.getOrNull()
      ?: return null
    val events = parsed.events ?: return null

    val lines =
      events.mapNotNull { event ->
        val startMs = event.tStartMs ?: return@mapNotNull null
        val text = event.segs?.joinToString(separator = "") { it.utf8.orEmpty() } ?: return@mapNotNull null
        formatLrcLine(startMs, text)
      }

    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
  }

  fun parseSrv3ToLrc(xmlText: String): String? {
    val trimmed = xmlText.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("<")) return null

    val lines = mutableListOf<String>()
    val parsed =
      runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(trimmed)))
        val paragraphs = doc.getElementsByTagName("p")
        for (i in 0 until paragraphs.length) {
          val node = paragraphs.item(i) as? Element ?: continue
          val startMs = node.getAttribute("t").toLongOrNull() ?: continue
          val rawText = node.textContent.orEmpty()
          formatLrcLine(startMs, rawText)?.let(lines::add)
        }
        if (lines.isEmpty()) {
          val textNodes = doc.getElementsByTagName("text")
          for (i in 0 until textNodes.length) {
            val node = textNodes.item(i) as? Element ?: continue
            val startSec = node.getAttribute("start").toDoubleOrNull() ?: continue
            val startMs = (startSec * 1000.0).toLong()
            val rawText = node.textContent.orEmpty()
            formatLrcLine(startMs, rawText)?.let(lines::add)
          }
        }
      }

    if (parsed.isSuccess && lines.isNotEmpty()) {
      return lines.joinToString("\n")
    }

    // Lightweight regex fallback for malformed XML fragments
    val pRegex = Regex("<p\\b[^>]*\\bt=\"(\\d+)\"[^>]*>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL))
    val pLines =
      pRegex
        .findAll(trimmed)
        .mapNotNull { match ->
          val startMs = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
          val text = HTML_TAG_REGEX.replace(match.groupValues[2], "")
          formatLrcLine(startMs, text)
        }
        .toList()
    if (pLines.isNotEmpty()) {
      return pLines.joinToString("\n")
    }

    val textRegex = Regex("<text\\b[^>]*\\bstart=\"([\\d.]+)\"[^>]*>(.*?)</text>", setOf(RegexOption.DOT_MATCHES_ALL))
    val textLines =
      textRegex
        .findAll(trimmed)
        .mapNotNull { match ->
          val startSec = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
          val text = HTML_TAG_REGEX.replace(match.groupValues[2], "")
          formatLrcLine((startSec * 1000.0).toLong(), text)
        }
        .toList()

    return textLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
  }

  private fun cleanCueText(rawText: String): String {
    val decoded =
      rawText
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("\r", " ")
        .replace("\n", " ")
    return WHITESPACE_REGEX.replace(decoded, " ").trim()
  }
}
