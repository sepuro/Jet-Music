package echo.music.iad1tya.lyrics

import android.content.Context
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.FetchFasterLyricsKey
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.LyricsEntity
import echo.music.iad1tya.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import echo.music.iad1tya.models.MediaMetadata
import echo.music.iad1tya.playback.LyricsWithProvider
import echo.music.iad1tya.utils.NetworkConnectivityObserver
import echo.music.iad1tya.utils.dataStore
import echo.music.iad1tya.utils.reportException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class LyricsHelper
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val networkConnectivity: NetworkConnectivityObserver,
  private val database: MusicDatabase,
) {

  private suspend fun resolveLyricsProviders(): List<LyricsProvider> {
    val defaultOrder = LyricsProviderRegistry.getDefaultProviderOrder()
    return defaultOrder
      .mapNotNull { LyricsProviderRegistry.getProviderByName(it) }
      .filter { it.isEnabled(context) }
      .sortedBy { provider ->
        when (provider.name) {
          LrcLibLyricsProvider.name -> 0
          KuGouLyricsProvider.name -> 1
          YouTubeSubtitleLyricsProvider.name -> 8
          YouTubeLyricsProvider.name -> 9
          else -> 2
        }
      }
  }

  private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
  private val inFlightRequests = ConcurrentHashMap<String, Deferred<LyricsWithProvider>>()
  private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var currentLyricsJob: Job? = null

  init {
    fetchScope.launch {
      runCatching { database.deleteUnsyncedLyrics() }
    }
  }

  suspend fun getLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
    currentLyricsJob?.cancel()

    val cached = cache.get(mediaMetadata.id)?.firstOrNull()
    if (cached != null) {
      if (LyricsEntity.hasSyncedLrcTimestamps(cached.lyrics)) {
        return LyricsWithProvider(cached.lyrics, cached.providerName)
      }
      // Evict stale plain-text entries so providers can be queried again for synced LRC.
      cache.remove(mediaMetadata.id)
    }

    val existingDbLyrics = runCatching { database.getLyricsById(mediaMetadata.id) }.getOrNull()
    if (existingDbLyrics != null && LyricsEntity.hasSyncedLrcTimestamps(existingDbLyrics.lyrics)) {
      cache.put(
        mediaMetadata.id,
        listOf(LyricsResult(existingDbLyrics.provider, existingDbLyrics.lyrics)),
      )
      return LyricsWithProvider(existingDbLyrics.lyrics, existingDbLyrics.provider)
    }

    val isNetworkAvailable =
      try {
        networkConnectivity.isCurrentlyConnected()
      } catch (e: Exception) {
        true
      }

    if (!isNetworkAvailable) {
      return LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")
    }

    // Shared fetches live in fetchScope, not the first caller's scope, so one caller being
    // cancelled (e.g. collectLatest on a song switch) can't fail the others awaiting it.
    val created =
      fetchScope.async(start = CoroutineStart.LAZY) { fetchFirstSyncedLyrics(mediaMetadata) }
    val existing = inFlightRequests.putIfAbsent(mediaMetadata.id, created)
    val shared =
      existing
        ?: created.also {
          it.invokeOnCompletion { inFlightRequests.remove(mediaMetadata.id, created) }
          it.start()
        }
    if (existing != null) created.cancel()
    val result = shared.await()
    val fetchedLyrics = result.lyrics
    if (
      !fetchedLyrics.isNullOrBlank() &&
        fetchedLyrics != LYRICS_NOT_FOUND &&
        (existingDbLyrics == null ||
          !LyricsEntity.hasSyncedLrcTimestamps(existingDbLyrics.lyrics) ||
          LyricsEntity.hasSyncedLrcTimestamps(fetchedLyrics))
    ) {
      runCatching {
        withContext(Dispatchers.IO) {
          database.upsert(
            LyricsEntity(
              id = mediaMetadata.id,
              lyrics = fetchedLyrics,
              provider = result.providerName,
            )
          )
        }
      }
    }
    return result
  }

  private suspend fun fetchFirstSyncedLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
    val providers = resolveLyricsProviders()
    if (providers.isEmpty()) return LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")

    return coroutineScope {
      val youtubeProviders = setOf(YouTubeLyricsProvider.name, YouTubeSubtitleLyricsProvider.name)
      var nonYouTubeRemaining = providers.count { it.name !in youtubeProviders }
      var syncedYouTubeResult: LyricsWithProvider? = null
      val channel = Channel<Pair<String, LyricsWithProvider?>>(providers.size)
      providers.forEach { provider ->
        launch {
          val lyrics = fetchLyricsFromProvider(provider, mediaMetadata)
          val result =
            lyrics
              ?.takeIf { it != LYRICS_NOT_FOUND && it.isNotBlank() }
              ?.let { LyricsWithProvider(it, provider.name) }
          channel.send(provider.name to result)
        }
      }

      val fallbackPlainLyrics = mutableListOf<LyricsWithProvider>()
      repeat(providers.size) {
        val (providerName, result) = channel.receive()
        if (providerName !in youtubeProviders) {
          nonYouTubeRemaining--
        }

        if (result != null) {
          val text = result.lyrics.orEmpty()
          if (LyricsEntity.hasSyncedLrcTimestamps(text)) {
            if (providerName !in youtubeProviders) {
              coroutineContext.cancelChildren()
              cache.put(mediaMetadata.id, listOf(LyricsResult(result.providerName, text)))
              return@coroutineScope result
            } else {
              syncedYouTubeResult =
                listOfNotNull(syncedYouTubeResult, result).minByOrNull {
                  providerPriorityIndex(providers, it.providerName)
                }
            }
          } else {
            // Never short-circuit on plain text; keep it only as last-resort fallback.
            fallbackPlainLyrics.add(result)
          }
        }

        val readyYouTubeSynced = syncedYouTubeResult
        if (nonYouTubeRemaining <= 0 && readyYouTubeSynced != null) {
          coroutineContext.cancelChildren()
          cache.put(
            mediaMetadata.id,
            listOf(LyricsResult(readyYouTubeSynced.providerName, readyYouTubeSynced.lyrics.orEmpty())),
          )
          return@coroutineScope readyYouTubeSynced
        }
      }

      syncedYouTubeResult?.let { synced ->
        cache.put(mediaMetadata.id, listOf(LyricsResult(synced.providerName, synced.lyrics.orEmpty())))
        return@coroutineScope synced
      }

      fallbackPlainLyrics
        .minWithOrNull(
          compareBy<LyricsWithProvider>(
            {
              when (it.providerName) {
                YouTubeLyricsProvider.name -> 2
                YouTubeSubtitleLyricsProvider.name -> 1
                else -> 0
              }
            },
            { providerPriorityIndex(providers, it.providerName) },
          )
        )
        ?: LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")
    }
  }

  private fun providerPriorityIndex(
    providers: List<LyricsProvider>,
    providerName: String,
  ): Int = providers.indexOfFirst { it.name == providerName }.let { if (it == -1) Int.MAX_VALUE else it }

  private suspend fun fetchLyricsFromProvider(
    provider: LyricsProvider,
    mediaMetadata: MediaMetadata,
  ): String? =
    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
      try {
        provider
          .getLyrics(
            mediaMetadata.id,
            mediaMetadata.title,
            mediaMetadata.artists.joinToString { it.name },
            mediaMetadata.duration,
            mediaMetadata.album?.title,
          )
          .getOrElse {
            if (it is CancellationException) throw it
            reportException(it)
            null
          }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        reportException(e)
        null
      }
    }

  suspend fun getAllLyrics(
    mediaId: String,
    songTitle: String,
    songArtists: String,
    duration: Int,
    album: String? = null,
    callback: (LyricsResult) -> Unit,
  ) {
    currentLyricsJob?.cancel()

    val cacheKey = "$songArtists-$songTitle".replace(" ", "")
    cache.get(cacheKey)?.let { results ->
      results.forEach { callback(it) }
      return
    }

    val isNetworkAvailable =
      try {
        networkConnectivity.isCurrentlyConnected()
      } catch (e: Exception) {
        true
      }

    if (!isNetworkAvailable) {
      return
    }

    val allResult = java.util.concurrent.CopyOnWriteArrayList<LyricsResult>()
    val providers = resolveLyricsProviders()
    currentLyricsJob =
      CoroutineScope(SupervisorJob()).launch {
        val jobs =
          providers.mapNotNull { provider ->
            if (provider.isEnabled(context)) {
              launch {
                try {
                  provider.getAllLyrics(mediaId, songTitle, songArtists, duration, album) { lyrics
                    ->
                    val result = LyricsResult(provider.name, lyrics)
                    allResult += result
                    callback(result)
                  }
                } catch (e: Exception) {
                  reportException(e)
                }
              }
            } else null
          }
        jobs.forEach { it.join() }
        cache.put(cacheKey, allResult.toList())
      }

    currentLyricsJob?.join()
  }

  fun cancelCurrentLyricsJob() {
    currentLyricsJob?.cancel()
    currentLyricsJob = null
  }

  companion object {
    private const val MAX_CACHE_SIZE = 20
    private const val PROVIDER_TIMEOUT_MS = 10_000L
  }
}

data class LyricsResult(
  val providerName: String,
  val lyrics: String,
)
