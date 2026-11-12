package echo.music.iad1tya.musicflow

import androidx.media3.common.Player
import echo.music.iad1tya.extensions.toMediaItem
import echo.music.iad1tya.playback.MusicService
import echo.music.iad1tya.playback.PlayerConnection
import echo.music.iad1tya.playback.queues.Queue

class PlayerConnectionMusicFlowPlayer(
  private val connection: PlayerConnection,
  private val session: MusicFlowSession,
  private val queueTitle: String,
) : MusicFlowPlayer {
  private val service: MusicService
    get() = connection.service

  override fun currentMediaId(): String? = connection.player.currentMediaItem?.mediaId

  override fun queueMediaIds(): List<String> {
    val player = connection.player
    return List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
  }

  override fun ownsQueue(handle: Any): Boolean = service.activeQueue.value === handle

  override fun isAnyFlowQueueActive(): Boolean = service.activeQueue.value is MusicFlowQueue

  override fun takeoverBlock(): TakeoverBlock? =
    when {
      connection.shouldBlockPlaybackChanges?.invoke() == true -> TakeoverBlock.LISTEN_TOGETHER_GUEST
      service.castConnectionHandler?.isCasting?.value == true -> TakeoverBlock.CASTING
      else -> null
    }

  override fun seekTo(queueIndex: Int, playWhenReady: Boolean) {
    val player = connection.player
    if (queueIndex !in 0 until player.mediaItemCount) return
    if (queueIndex != player.currentMediaItemIndex) {
      player.seekToDefaultPosition(queueIndex)
    }
    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
      player.prepare()
    }
    if (player.playWhenReady != playWhenReady) {
      player.playWhenReady = playWhenReady
    }
  }

  override fun startQueue(items: List<MusicFlowItem>, startIndex: Int, playWhenReady: Boolean): Any {
    val player = connection.player
    if (player.mediaItemCount > 0) {
      val existingIds = queueMediaIds().toSet()
      val newMediaItems = items.filter { it.songId !in existingIds }.map { it.song.toMediaItem() }
      if (newMediaItems.isNotEmpty()) {
        player.addMediaItems(newMediaItems)
      }
      val targetId = items.getOrNull(startIndex)?.songId
      val targetIdx = if (targetId != null) queueMediaIds().indexOf(targetId) else -1
      if (targetIdx >= 0 && targetIdx != player.currentMediaItemIndex) {
        player.seekToDefaultPosition(targetIdx)
      }
      return service.activeQueue.value ?: MusicFlowQueue(queueTitle, items, startIndex, session)
    }
    val queue = MusicFlowQueue(queueTitle, items, startIndex, session)
    service.playQueue(queue, playWhenReady)
    return queue
  }

  override fun attachQueuePreservingCurrent(items: List<MusicFlowItem>): Any? {
    val player = connection.player
    if (player.mediaItemCount == 0 || items.isEmpty()) return null
    val existingIds = queueMediaIds().toSet()
    val newMediaItems =
      items
        .filter { it.songId !in existingIds }
        .map { it.song.toMediaItem() }
    if (newMediaItems.isNotEmpty()) {
      player.addMediaItems(newMediaItems)
    }
    return service.activeQueue.value ?: MusicFlowQueue(queueTitle, items, player.currentMediaItemIndex.coerceAtLeast(0), session)
  }

  override fun append(handle: Any, items: List<MusicFlowItem>) {
    if (items.isEmpty()) return
    val player = connection.player
    val existingIds = queueMediaIds().toSet()
    val newMediaItems = items.filter { it.songId !in existingIds }.map { it.song.toMediaItem() }
    if (newMediaItems.isNotEmpty()) {
      player.addMediaItems(newMediaItems)
    }
  }

  override fun setPauseAtEnd(handle: Any, pause: Boolean) {
    if (handle is Queue) service.setPauseAtEndOfItems(handle, pause)
  }

  override fun snapshot(): Any? = service.snapshotQueue()

  override fun restore(snapshot: Any, playWhenReady: Boolean) {
    if (snapshot is MusicService.QueueSnapshot) service.restoreQueueSnapshot(snapshot, playWhenReady)
  }

  override fun isPlaying(): Boolean = connection.isPlaying.value
}
