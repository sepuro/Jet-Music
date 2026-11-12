package echo.music.iad1tya.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.R
import echo.music.iad1tya.canvas.YouTubeVideoCanvasProvider
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.LyricsEntity
import echo.music.iad1tya.db.entities.RecommendationExclusionEntity
import echo.music.iad1tya.extensions.currentMetadata
import echo.music.iad1tya.extensions.metadata
import echo.music.iad1tya.extensions.toMediaItem
import echo.music.iad1tya.lyrics.LyricsHelper
import echo.music.iad1tya.models.MediaMetadata
import echo.music.iad1tya.models.toMediaMetadata
import echo.music.iad1tya.musicflow.MusicFlowCoordinator
import echo.music.iad1tya.musicflow.MusicFlowItem
import echo.music.iad1tya.musicflow.MusicFlowPlaybackStatus
import echo.music.iad1tya.musicflow.MusicFlowPreferences
import echo.music.iad1tya.musicflow.MusicFlowSession
import echo.music.iad1tya.musicflow.MusicFlowSource
import echo.music.iad1tya.musicflow.MusicFlowUiState
import echo.music.iad1tya.musicflow.PlayerConnectionMusicFlowPlayer
import echo.music.iad1tya.musicflow.toMusicFlowSongItem
import echo.music.iad1tya.musicflow.trackKey
import echo.music.iad1tya.playback.PlayerConnection
import echo.music.iad1tya.ui.player.CanvasArtworkPlaybackCache
import echo.music.iad1tya.utils.dataStore
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PlaybackRequest(val requestId: Long, val songId: String, val playWhenReady: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MusicFlowViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val session: MusicFlowSession,
  private val database: MusicDatabase,
  private val lyricsHelper: LyricsHelper,
) : ViewModel() {

  val preferences: StateFlow<MusicFlowPreferences> =
    context.dataStore.data
      .map(MusicFlowPreferences::from)
      .stateIn(viewModelScope, SharingStarted.Eagerly, MusicFlowPreferences())

  private val playbackStatus = MutableStateFlow<MusicFlowPlaybackStatus>(MusicFlowPlaybackStatus.Idle)
  private val canRestore = MutableStateFlow(false)
  private val requests = MutableStateFlow<PlaybackRequest?>(null)

  val uiState: StateFlow<MusicFlowUiState> =
    combine(session.state, playbackStatus, canRestore) { feed, status, restorable ->
        MusicFlowUiState(
          items = feed.items,
          selectedIndex = feed.selectedIndex,
          isInitialLoading = feed.isInitialLoading,
          isLoadingMore = feed.isLoadingMore,
          error = feed.error,
          hasMore = feed.hasMore,
          playbackStatus = status,
          canRestorePreviousQueue = restorable,
          isOfflineFeed = feed.isOffline,
        )
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, MusicFlowUiState())

  private var connection: PlayerConnection? = null
  private var coordinator: MusicFlowCoordinator? = null
  private val attachedJobs = mutableListOf<Job>()
  private var consecutiveFailures = 0

  fun prepareEntry(playerConnection: PlayerConnection) {
    val activeQueue = resolveActiveQueueItems(playerConnection)
    if (activeQueue.isNotEmpty()) {
      val currentIndex = playerConnection.player.currentMediaItemIndex.coerceIn(0, activeQueue.lastIndex)
      session.syncWithActiveQueue(activeQueue, currentIndex)
    } else {
      val nowPlaying = resolveNowPlaying(playerConnection)
      if (nowPlaying != null) {
        session.prepareEntryWithNowPlaying(nowPlaying)
      }
    }
  }

  private fun resolveActiveQueueItems(playerConnection: PlayerConnection): List<MusicFlowItem> {
    val player = playerConnection.player
    val count = player.mediaItemCount
    if (count == 0) return emptyList()
    val currentMeta = playerConnection.mediaMetadata.value
    val existingById = session.state.value.items.associateBy { it.songId }
    return buildList(count) {
      for (i in 0 until count) {
        val mediaItem = player.getMediaItemAt(i)
        val mediaId = mediaItem.mediaId.takeIf { it.isNotBlank() } ?: continue
        val metadata =
          mediaItem.metadata
            ?: currentMeta?.takeIf { it.id == mediaId }
        val item =
          when {
            metadata != null ->
              MusicFlowItem(
                song = metadata.toMusicFlowSongItem(),
                source = if (i == player.currentMediaItemIndex) MusicFlowSource.NOW_PLAYING else MusicFlowSource.CATALOG,
              )
            existingById[mediaId] != null -> existingById.getValue(mediaId)
            else -> {
              val itemMeta = mediaItem.mediaMetadata
              MusicFlowItem(
                song =
                  com.music.innertube.models.SongItem(
                    id = mediaId,
                    title = itemMeta.title?.toString().orEmpty(),
                    artists =
                      listOfNotNull(
                        itemMeta.artist?.toString()?.takeIf { it.isNotBlank() }?.let {
                          com.music.innertube.models.Artist(name = it, id = null)
                        }
                      ),
                    album = null,
                    duration = null,
                    thumbnail = itemMeta.artworkUri?.toString().orEmpty(),
                    explicit = false,
                  ),
                source = if (i == player.currentMediaItemIndex) MusicFlowSource.NOW_PLAYING else MusicFlowSource.CATALOG,
              )
            }
          }
        add(item)
      }
    }
  }

  private fun resolveNowPlaying(playerConnection: PlayerConnection): MusicFlowItem? {
    val player = playerConnection.player
    if (player.mediaItemCount == 0) return null
    val currentMediaItem = player.currentMediaItem
    val metadata =
      playerConnection.mediaMetadata.value?.takeIf {
        currentMediaItem == null || it.id == currentMediaItem.mediaId
      }
        ?: currentMediaItem?.metadata
        ?: player.currentMetadata
    if (metadata != null) {
      return MusicFlowItem(metadata.toMusicFlowSongItem(), MusicFlowSource.NOW_PLAYING)
    }
    val mediaId = currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return null
    val itemMeta = currentMediaItem.mediaMetadata
    val fallbackSong =
      com.music.innertube.models.SongItem(
        id = mediaId,
        title = itemMeta.title?.toString().orEmpty(),
        artists =
          listOfNotNull(
            itemMeta.artist?.toString()?.takeIf { it.isNotBlank() }?.let {
              com.music.innertube.models.Artist(name = it, id = null)
            }
          ),
        album = null,
        duration = null,
        thumbnail = itemMeta.artworkUri?.toString().orEmpty(),
        explicit = false,
      )
    return MusicFlowItem(fallbackSong, MusicFlowSource.NOW_PLAYING)
  }

  fun attach(playerConnection: PlayerConnection) {
    prepareEntry(playerConnection)
    if (connection === playerConnection) return
    detach()
    connection = playerConnection
    val flowCoordinator =
      MusicFlowCoordinator(
        session,
        PlayerConnectionMusicFlowPlayer(playerConnection, session, context.getString(R.string.music_flow)),
      )
    coordinator = flowCoordinator

    attachedJobs += viewModelScope.launch { enter(playerConnection, flowCoordinator) }

    attachedJobs +=
      viewModelScope.launch {
        requests.filterNotNull().collectLatest { request ->
          delay(SETTLE_DEBOUNCE_MS)
          playbackStatus.value =
            flowCoordinator.select(
              requestId = request.requestId,
              songId = request.songId,
              playWhenReady = request.playWhenReady,
              autoNext = preferences.value.autoNext,
            )
          refreshStatus(playerConnection.player.currentMediaItem?.mediaId, flowCoordinator)
          canRestore.value = flowCoordinator.canRestorePreviousQueue()
        }
      }

    attachedJobs +=
      viewModelScope.launch {
        combine(
            playerConnection.queueWindows,
            playerConnection.currentMediaItemIndex,
          ) { _, currentIndex -> currentIndex }
          .collect { currentIndex ->
            val activeQueue = resolveActiveQueueItems(playerConnection)
            if (activeQueue.isNotEmpty()) {
              val safeIndex = currentIndex.coerceIn(0, activeQueue.lastIndex)
              session.syncWithActiveQueue(activeQueue, safeIndex)
            }
          }
      }

    attachedJobs +=
      viewModelScope.launch {
        playerConnection.mediaMetadata
          .map { it?.id }
          .distinctUntilChanged()
          .collect { mediaId -> onActiveMediaChanged(mediaId, flowCoordinator) }
      }

    attachedJobs +=
      viewModelScope.launch {
        playerConnection.service.activeQueue.collect {
          flowCoordinator.invalidateIfSuperseded()
          canRestore.value = flowCoordinator.canRestorePreviousQueue()
          refreshStatus(playerConnection.player.currentMediaItem?.mediaId, flowCoordinator)
        }
      }

    attachedJobs +=
      viewModelScope.launch {
        preferences.map { it.autoNext }.distinctUntilChanged().collect(flowCoordinator::applyAutoNext)
      }

    attachedJobs +=
      viewModelScope.launch {
        combine(
            playerConnection.mediaMetadata.distinctUntilChangedBy { it?.id },
            preferences.map { it.lyrics && !it.dataSaver }.distinctUntilChanged(),
          ) { metadata, enabled -> metadata.takeIf { enabled } }
          .collectLatest { metadata -> metadata?.let { fetchLyricsIfMissing(it) } }
      }

    attachedJobs +=
      viewModelScope.launch {
        playerConnection.isPlaying.collect { isPlaying ->
          if (isPlaying) consecutiveFailures = 0
        }
      }

    attachedJobs +=
      viewModelScope.launch {
        playerConnection.error.collectLatest { error ->
          if (error == null) return@collectLatest
          val failedId = playerConnection.player.currentMediaItem?.mediaId ?: return@collectLatest
          if (!flowCoordinator.ownsPlayback() || failedId != uiState.value.selectedSongId) return@collectLatest
          delay(ERROR_GRACE_MS)
          val player = playerConnection.player
          val gaveUp =
            player.playbackState == Player.STATE_IDLE &&
              player.playerError != null &&
              player.currentMediaItem?.mediaId == failedId
          if (!gaveUp || consecutiveFailures >= MAX_AUTO_ADVANCES) return@collectLatest
          consecutiveFailures++
          session.hide(failedId)
          advanceFrom(session.indexOf(failedId))
        }
      }
  }

  fun detach() {
    attachedJobs.forEach(Job::cancel)
    attachedJobs.clear()
    requests.value = null
    coordinator = null
    connection = null
  }

  private suspend fun enter(playerConnection: PlayerConnection, flowCoordinator: MusicFlowCoordinator) {
    val activeQueue = resolveActiveQueueItems(playerConnection)
    val nowPlaying = resolveNowPlaying(playerConnection)
    val currentId = playerConnection.player.currentMediaItem?.mediaId ?: nowPlaying?.songId

    if (activeQueue.isNotEmpty() || nowPlaying != null) {
      val currentIndex =
        playerConnection.player.currentMediaItemIndex
          .takeIf { it >= 0 }
          ?.coerceIn(0, (activeQueue.size - 1).coerceAtLeast(0))
          ?: 0

      if (activeQueue.isNotEmpty()) {
        session.syncWithActiveQueue(activeQueue, currentIndex)
      } else {
        session.prepareEntryWithNowPlaying(nowPlaying)
      }

      prefetchUpcomingVideos(session.state.value.items, currentIndex)

      val remainingAhead = (session.state.value.items.size - 1 - currentIndex).coerceAtLeast(0)
      if (remainingAhead <= MusicFlowSession.PREFETCH_THRESHOLD) {
        val appended = withContext(Dispatchers.IO) { session.ensureUpcomingForActiveQueue(nowPlaying) }
        if (appended.isNotEmpty()) {
          val existingIds =
            List(playerConnection.player.mediaItemCount) {
              playerConnection.player.getMediaItemAt(it).mediaId
            }.toSet()
          val newMediaItems = appended.filter { it.songId !in existingIds }.map { it.song.toMediaItem() }
          if (newMediaItems.isNotEmpty()) {
            playerConnection.player.addMediaItems(newMediaItems)
          }
        }
      }

      val prefs = preferences.first()
      if (prefs.autoplay && playerConnection.player.mediaItemCount > 0 && !playerConnection.player.isPlaying) {
        if (
          playerConnection.player.playbackState == Player.STATE_IDLE ||
            playerConnection.player.playbackState == Player.STATE_ENDED
        ) {
          playerConnection.player.prepare()
        }
        playerConnection.player.playWhenReady = true
        playerConnection.player.play()
      }
      flowCoordinator.syncUpcomingQueueForCurrentTrack(prefs.autoNext)
      canRestore.value = flowCoordinator.canRestorePreviousQueue()
      refreshStatus(currentId, flowCoordinator)
      prefetchIfNeeded()
      prefetchUpcomingLyrics(session.state.value.items, currentIndex + 1)
      prefetchUpcomingVideos(session.state.value.items, currentIndex)
      return
    }

    if (!session.isStarted) {
      session.start(null)
    }
    canRestore.value = flowCoordinator.canRestorePreviousQueue()
    refreshStatus(currentId, flowCoordinator)

    val selected = uiState.value.selectedSongId ?: return
    val prefs = preferences.first()
    if (prefs.autoplay && !playerConnection.isPlaying.value && currentId == null) {
      request(selected, playWhenReady = true)
    }
    prefetchIfNeeded()
    prefetchUpcomingLyrics(session.state.value.items, session.state.value.selectedIndex + 1)
    prefetchUpcomingVideos(session.state.value.items, session.state.value.selectedIndex)
  }

  fun onPageSettled(index: Int) {
    val items = session.state.value.items
    val item = items.getOrNull(index)
    if (item == null) {
      if (items.isNotEmpty()) prefetchIfNeeded()
      return
    }
    val player = connection?.player
    val currentId = player?.currentMediaItem?.mediaId
    val liveIndex = player?.currentMediaItemIndex ?: -1
    val shouldAutoPlay = preferences.value.autoplay
    if ((index == liveIndex || index == session.state.value.selectedIndex) && (currentId == null || item.songId == currentId)) {
      session.select(index)
      prefetchIfNeeded()
      prefetchUpcomingLyrics(items, index + 1)
      prefetchUpcomingVideos(items, index)
      return
    }
    session.select(index)
    if (item.songId != currentId) {
      if (player != null && index in 0 until player.mediaItemCount && index != player.currentMediaItemIndex) {
        player.seekToDefaultPosition(index)
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
          player.prepare()
        }
        if (shouldAutoPlay) {
          player.playWhenReady = true
          player.play()
        } else {
          player.playWhenReady = false
          player.pause()
        }
      } else {
        request(item.songId, playWhenReady = shouldAutoPlay)
      }
    }
    prefetchIfNeeded()
    prefetchUpcomingLyrics(items, index + 1)
    prefetchUpcomingVideos(items, index)
  }

  fun togglePlayPause() {
    val playerConnection = connection ?: return
    val selected = uiState.value.selectedSongId ?: return
    if (playerConnection.player.currentMediaItem?.mediaId == selected) {
      playerConnection.togglePlayPause()
    } else {
      request(selected, playWhenReady = true)
    }
  }

  fun next() = moveBy(1)

  fun previous() = moveBy(-1)

  private fun moveBy(delta: Int) {
    val target = session.state.value.selectedIndex + delta
    if (target in session.state.value.items.indices) onPageSettled(target)
  }

  fun retryPlayback() {
    consecutiveFailures = 0
    val selected = uiState.value.selectedSongId ?: return
    connection?.play()
    request(selected, playWhenReady = true)
  }

  fun retryFeed() {
    viewModelScope.launch {
      val playerConnection = connection
      val nowPlaying =
        playerConnection?.mediaMetadata?.value
          ?.takeIf { playerConnection.player.mediaItemCount > 0 }
          ?.let { MusicFlowItem(it.toMusicFlowSongItem(), MusicFlowSource.NOW_PLAYING) }
      session.reset(nowPlaying)
    }
  }

  fun loadMoreIfNeeded() = prefetchIfNeeded()

  fun loadMoreVideos() = prefetchIfNeeded()

  fun notInterested(item: MusicFlowItem, persist: Boolean) {
    session.hide(item.songId)
    if (persist) {
      viewModelScope.launch {
        database.recommendationExclusionDao.upsert(
          RecommendationExclusionEntity(
            trackKey = item.trackKey,
            excludedAtMillis = System.currentTimeMillis(),
            trackName = item.song.title,
            artistName = item.song.artists.joinToString(", ") { it.name },
          )
        )
      }
    }
    if (item.songId == uiState.value.selectedSongId) advanceFrom(uiState.value.selectedIndex)
  }

  fun lessLikeThis(item: MusicFlowItem) = session.lessLikeThis(item)

  fun restorePreviousQueue(): Boolean {
    val restored = coordinator?.restorePreviousQueue() == true
    canRestore.value = coordinator?.canRestorePreviousQueue() == true
    if (restored) playbackStatus.value = MusicFlowPlaybackStatus.ExternalOverride
    return restored
  }

  private fun advanceFrom(index: Int) {
    val items = session.state.value.items
    val next = (index + 1 until items.size).firstOrNull { !session.isHidden(items[it].songId) }
    if (next != null) {
      session.select(next)
      request(items[next].songId, playWhenReady = true)
      prefetchIfNeeded()
    } else {
      prefetchIfNeeded()
    }
  }

  private fun request(songId: String, playWhenReady: Boolean) {
    val flowCoordinator = coordinator ?: return
    playbackStatus.value = MusicFlowPlaybackStatus.Switching
    requests.value = PlaybackRequest(flowCoordinator.nextRequestId(), songId, playWhenReady)
  }

  private fun onActiveMediaChanged(mediaId: String?, flowCoordinator: MusicFlowCoordinator) {
    if (mediaId != null) {
      val index = session.indexOf(mediaId)
      val pending = requests.value
      val awaitingOtherSong = pending != null && pending.songId != mediaId && playbackStatus.value == MusicFlowPlaybackStatus.Switching
      if (index >= 0 && index != session.state.value.selectedIndex && !awaitingOtherSong) {
        session.select(index)
        prefetchIfNeeded()
      }
    }
    refreshStatus(mediaId, flowCoordinator)
  }

  private fun refreshStatus(mediaId: String?, flowCoordinator: MusicFlowCoordinator) {
    val selected = uiState.value.selectedSongId
    val current = playbackStatus.value
    playbackStatus.value =
      when {
        selected != null && mediaId == selected -> {
          MusicFlowPlaybackStatus.Synced
        }
        current is MusicFlowPlaybackStatus.Blocked -> current
        current == MusicFlowPlaybackStatus.Switching && flowCoordinator.ownsPlayback() -> current
        mediaId == null -> MusicFlowPlaybackStatus.Idle
        else -> MusicFlowPlaybackStatus.ExternalOverride
      }
  }

  private fun prefetchIfNeeded() {
    val state = session.state.value
    if (!state.hasMore || state.isLoadingMore || state.isInitialLoading) return
    if (state.items.size - state.selectedIndex > MusicFlowSession.PREFETCH_THRESHOLD) return
    val generation = session.generation
    val seed = connection?.let(::resolveNowPlaying) ?: state.items.getOrNull(state.selectedIndex)
    viewModelScope.launch(Dispatchers.IO) {
      val appended = session.ensureUpcomingForActiveQueue(seed)
      if (session.generation == generation && appended.isNotEmpty()) {
        withContext(Dispatchers.Main) {
          val player = connection?.player
          if (player != null && player.mediaItemCount > 0) {
            val existingIds =
              List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }.toSet()
            val newMediaItems = appended.filter { it.songId !in existingIds }.map { it.song.toMediaItem() }
            if (newMediaItems.isNotEmpty()) {
              player.addMediaItems(newMediaItems)
            }
          } else {
            coordinator?.appendIfOwned(appended)
          }
        }
        prefetchUpcomingVideos(session.state.value.items, session.state.value.selectedIndex)
      }
    }
  }

  private fun prefetchUpcomingVideos(items: List<MusicFlowItem>, startIndex: Int) {
    val prefs = preferences.value
    YouTubeVideoCanvasProvider.currentVideoQuality = prefs.videoQuality
    if (!prefs.animatedBackground || prefs.dataSaver) return
    val candidates = items.drop(startIndex.coerceAtLeast(0)).take(VIDEO_PREFETCH_COUNT)
    candidates.forEach { item ->
      if (CanvasArtworkPlaybackCache.get(item.songId) != null) return@forEach
      viewModelScope.launch(Dispatchers.IO) {
        try {
          val artwork =
            YouTubeVideoCanvasProvider.getCanvasForVideo(
              videoId = item.songId,
              title = item.song.title,
              artist = item.song.artists.joinToString(", ") { it.name },
              durationSec = item.song.duration?.toLong(),
              videoQuality = prefs.videoQuality,
            )
          if (artwork != null) {
            CanvasArtworkPlaybackCache.put(item.songId, artwork)
          }
        } catch (e: Exception) {
          if (e is CancellationException) throw e
        }
      }
    }
  }

  private suspend fun fetchLyricsIfMissing(metadata: MediaMetadata) =
    withContext(Dispatchers.IO) {
      if (database.lyrics(metadata.id).first() != null) return@withContext
      val result =
        try {
          lyricsHelper.getLyrics(metadata)
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          return@withContext
        }
      database.query { upsert(LyricsEntity(metadata.id, result.lyrics.orEmpty(), result.providerName)) }
    }

  private fun prefetchUpcomingLyrics(items: List<MusicFlowItem>, nextIndex: Int) {
    val prefs = preferences.value
    if (!prefs.lyrics || prefs.dataSaver) return
    val nextItem = items.getOrNull(nextIndex) ?: return
    viewModelScope.launch(Dispatchers.IO) {
      if (database.lyrics(nextItem.songId).first() == null) {
        try {
          val metadata = nextItem.song.toMediaMetadata()
          val result = lyricsHelper.getLyrics(metadata)
          database.query {
            upsert(LyricsEntity(metadata.id, result.lyrics.orEmpty(), result.providerName))
          }
        } catch (e: Exception) {
          if (e is CancellationException) throw e
        }
      }
    }
  }

  override fun onCleared() {
    detach()
    super.onCleared()
  }

  private companion object {
    const val SETTLE_DEBOUNCE_MS = 180L
    const val ERROR_GRACE_MS = 8_000L
    const val MAX_AUTO_ADVANCES = 3
    const val VIDEO_PREFETCH_COUNT = 5
  }
}
