package echo.music.iad1tya.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.navigation.NavController
import echo.music.iad1tya.LocalPlayerAwareWindowInsets
import echo.music.iad1tya.R
import echo.music.iad1tya.canvas.YouTubeVideoCanvasProvider
import echo.music.iad1tya.constants.CanvasThumbnailAnimationKey
import echo.music.iad1tya.constants.MusicFlowAnimatedBackgroundKey
import echo.music.iad1tya.constants.MusicFlowAutoNextKey
import echo.music.iad1tya.constants.MusicFlowAutoplayKey
import echo.music.iad1tya.constants.MusicFlowCellularPrefetchKey
import echo.music.iad1tya.constants.MusicFlowEnabledKey
import echo.music.iad1tya.constants.MusicFlowHideRecentKey
import echo.music.iad1tya.constants.MusicFlowLyricsKey
import echo.music.iad1tya.constants.MusicFlowVideoQuality
import echo.music.iad1tya.constants.MusicFlowVideoQualityKey
import echo.music.iad1tya.ui.component.EnumDialog
import echo.music.iad1tya.ui.component.IconButton
import echo.music.iad1tya.ui.component.Material3SettingsGroup
import echo.music.iad1tya.ui.component.Material3SettingsItem
import echo.music.iad1tya.ui.player.CanvasArtworkPlaybackCache
import echo.music.iad1tya.ui.utils.backToMain
import echo.music.iad1tya.utils.rememberEnumPreference
import echo.music.iad1tya.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicFlowSettings(
  navController: NavController,
  scrollBehavior: TopAppBarScrollBehavior,
) {
  val scrollState = rememberScrollState()
  val (inheritedCanvas) = rememberPreference(CanvasThumbnailAnimationKey, defaultValue = true)
  val (videoQuality, onVideoQualityChange) =
    rememberEnumPreference(
      key = MusicFlowVideoQualityKey,
      defaultValue = MusicFlowVideoQuality.HIGH_1080P,
    )
  var showVideoQualityDialog by remember { mutableStateOf(false) }

  @Composable
  fun videoQualityLabel(quality: MusicFlowVideoQuality): String =
    when (quality) {
      MusicFlowVideoQuality.AUTO -> stringResource(R.string.music_flow_video_quality_auto)
      MusicFlowVideoQuality.HIGH_1080P -> stringResource(R.string.music_flow_video_quality_1080p)
      MusicFlowVideoQuality.MEDIUM_720P -> stringResource(R.string.music_flow_video_quality_720p)
      MusicFlowVideoQuality.LOW_480P -> stringResource(R.string.music_flow_video_quality_480p)
    }

  if (showVideoQualityDialog) {
    EnumDialog(
      onDismiss = { showVideoQualityDialog = false },
      onSelect = { selected ->
        onVideoQualityChange(selected)
        YouTubeVideoCanvasProvider.currentVideoQuality = selected
        YouTubeVideoCanvasProvider.clearCache()
        CanvasArtworkPlaybackCache.clear()
        showVideoQualityDialog = false
      },
      title = stringResource(R.string.music_flow_video_quality),
      current = videoQuality,
      values =
        listOf(
          MusicFlowVideoQuality.AUTO,
          MusicFlowVideoQuality.HIGH_1080P,
          MusicFlowVideoQuality.MEDIUM_720P,
          MusicFlowVideoQuality.LOW_480P,
        ),
      valueText = { videoQualityLabel(it) },
    )
  }

  @Composable
  fun toggle(
    key: Preferences.Key<Boolean>,
    default: Boolean,
    icon: Int,
    title: Int,
    description: Int,
  ): Material3SettingsItem {
    val (checked, onCheckedChange) = rememberPreference(key, defaultValue = default)
    return Material3SettingsItem(
      icon = painterResource(icon),
      title = { Text(stringResource(title)) },
      description = { Text(stringResource(description)) },
      trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
      onClick = { onCheckedChange(!checked) },
    )
  }

  val videoQualityItem =
    Material3SettingsItem(
      icon = painterResource(R.drawable.high_quality),
      title = { Text(stringResource(R.string.music_flow_video_quality)) },
      description = { Text(videoQualityLabel(videoQuality)) },
      onClick = { showVideoQualityDialog = true },
    )

  val general =
    listOf(
      toggle(MusicFlowEnabledKey, true, R.drawable.swipe_vertical, R.string.music_flow_enable, R.string.music_flow_enable_description),
      toggle(MusicFlowAutoplayKey, true, R.drawable.play, R.string.music_flow_autoplay, R.string.music_flow_autoplay_description),
      toggle(MusicFlowAutoNextKey, true, R.drawable.skip_next, R.string.music_flow_auto_next, R.string.music_flow_auto_next_description),
      toggle(MusicFlowHideRecentKey, true, R.drawable.history, R.string.music_flow_hide_recent, R.string.music_flow_hide_recent_description),
    )
  val visuals =
    listOf(
      toggle(MusicFlowAnimatedBackgroundKey, inheritedCanvas, R.drawable.graphic_eq, R.string.music_flow_animated_background, R.string.music_flow_animated_background_description),
      videoQualityItem,
      toggle(MusicFlowLyricsKey, true, R.drawable.lyrics, R.string.music_flow_lyrics, R.string.music_flow_lyrics_description),
      toggle(MusicFlowCellularPrefetchKey, true, R.drawable.music_note, R.string.music_flow_cellular_prefetch, R.string.music_flow_cellular_prefetch_description),
    )

  Column(
    Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
      .verticalScroll(scrollState)
      .padding(horizontal = 16.dp)
  ) {
    Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
    Spacer(modifier = Modifier.height(16.dp))
    Material3SettingsGroup(scrollState = scrollState, title = stringResource(R.string.music_flow), items = general)
    Spacer(modifier = Modifier.height(16.dp))
    Material3SettingsGroup(scrollState = scrollState, title = stringResource(R.string.appearance), items = visuals)
    Spacer(modifier = Modifier.height(16.dp))
    Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)))
  }

  TopAppBar(
    title = { Text(stringResource(R.string.music_flow)) },
    navigationIcon = {
      IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
        Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.back_button_desc))
      }
    },
    colors =
      androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
      ),
    scrollBehavior = scrollBehavior,
  )
}
