package echo.music.iad1tya.musicflow

/** The narrow slice of the shared player Music Flow is allowed to drive. */
interface MusicFlowPlayer {
  fun currentMediaId(): String?

  fun queueMediaIds(): List<String>

  fun ownsQueue(handle: Any): Boolean

  fun isAnyFlowQueueActive(): Boolean

  fun takeoverBlock(): TakeoverBlock?

  fun seekTo(queueIndex: Int, playWhenReady: Boolean)

  fun startQueue(items: List<MusicFlowItem>, startIndex: Int, playWhenReady: Boolean): Any

  fun attachQueuePreservingCurrent(items: List<MusicFlowItem>): Any?

  fun append(handle: Any, items: List<MusicFlowItem>)

  fun setPauseAtEnd(handle: Any, pause: Boolean)

  fun snapshot(): Any?

  fun restore(snapshot: Any, playWhenReady: Boolean)

  fun isPlaying(): Boolean
}

/**
 * Turns settled-page selections into commands on the single shared player. Requests carry a
 * monotonically increasing id; anything older than the newest request is dropped so a burst of
 * swipes can only ever command the final destination.
 */
class MusicFlowCoordinator(
  private val session: MusicFlowSession,
  private val player: MusicFlowPlayer,
) {
  private var latestRequestId = 0L

  fun nextRequestId(): Long = ++latestRequestId

  fun ownsPlayback(): Boolean = session.queueHandle?.let(player::ownsQueue) == true

  /**
   * Appends any upcoming Music Flow feed items to the end of the active ExoPlayer queue
   * without calling `setMediaItems`, removing existing items, or interrupting the active track.
   */
  fun syncUpcomingQueueForCurrentTrack(autoNext: Boolean) {
    if (player.takeoverBlock() != null) return
    val items = session.state.value.items
    if (items.isEmpty() || player.currentMediaId() == null) return

    if (!player.isAnyFlowQueueActive() && session.queueSnapshot == null) {
      session.queueSnapshot = player.snapshot()
    }
    val newHandle = player.attachQueuePreservingCurrent(items) ?: return
    session.queueHandle = newHandle
    player.setPauseAtEnd(newHandle, !autoNext)
  }

  fun select(
    requestId: Long,
    songId: String,
    playWhenReady: Boolean,
    autoNext: Boolean,
  ): MusicFlowPlaybackStatus {
    if (requestId < latestRequestId) return MusicFlowPlaybackStatus.Switching
    if (player.currentMediaId() == songId) return MusicFlowPlaybackStatus.Synced

    player.takeoverBlock()?.let { return MusicFlowPlaybackStatus.Blocked(it) }

    val existingQueueIndex = player.queueMediaIds().indexOf(songId)
    if (existingQueueIndex >= 0) {
      player.seekTo(existingQueueIndex, playWhenReady)
      return MusicFlowPlaybackStatus.Switching
    }

    val items = session.state.value.items
    val startIndex = items.indexOfFirst { it.songId == songId }
    if (startIndex < 0) return MusicFlowPlaybackStatus.Idle

    if (!player.isAnyFlowQueueActive() && session.queueSnapshot == null) {
      session.queueSnapshot = player.snapshot()
    }
    val newHandle = player.startQueue(items, startIndex, playWhenReady)
    session.queueHandle = newHandle
    player.setPauseAtEnd(newHandle, !autoNext)
    return MusicFlowPlaybackStatus.Switching
  }

  fun applyAutoNext(autoNext: Boolean) {
    session.queueHandle?.takeIf(player::ownsQueue)?.let { player.setPauseAtEnd(it, !autoNext) }
  }

  fun appendIfOwned(items: List<MusicFlowItem>) {
    if (items.isEmpty()) return
    session.queueHandle?.takeIf(player::ownsQueue)?.let { player.append(it, items) }
  }

  fun canRestorePreviousQueue(): Boolean = session.queueSnapshot != null && ownsPlayback()

  /** Restores the queue that was playing before Flow took over, only while Flow still owns it. */
  fun restorePreviousQueue(): Boolean {
    val snapshot = session.queueSnapshot ?: return false
    if (!ownsPlayback()) {
      session.queueSnapshot = null
      return false
    }
    player.restore(snapshot, player.isPlaying())
    session.queueSnapshot = null
    session.queueHandle = null
    return true
  }

  /** Drops the snapshot once another source (playlist, radio, cast, Listen Together) replaced the Flow queue. */
  fun invalidateIfSuperseded() {
    val handle = session.queueHandle ?: return
    if (!player.ownsQueue(handle)) {
      session.queueHandle = null
      session.queueSnapshot = null
    }
  }
}
