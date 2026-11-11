package echo.music.iad1tya.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import echo.music.iad1tya.BuildConfig
import echo.music.iad1tya.LocalPlayerAwareWindowInsets
import echo.music.iad1tya.R
import echo.music.iad1tya.echomusic.component.UpdateInfoDialog
import echo.music.iad1tya.echomusic.updater.autoClearOldApks
import echo.music.iad1tya.echomusic.updater.downloadApkWithProgress
import echo.music.iad1tya.echomusic.updater.extractApkAssetFromRelease
import echo.music.iad1tya.echomusic.updater.getDownloadedApkCount
import echo.music.iad1tya.echomusic.updater.installApk
import echo.music.iad1tya.echomusic.updater.isNewerVersion
import echo.music.iad1tya.echomusic.updater.saveUpdateAvailableState
import echo.music.iad1tya.ui.component.IconButton
import echo.music.iad1tya.ui.component.Material3SettingsGroup
import echo.music.iad1tya.ui.component.Material3SettingsItem
import echo.music.iad1tya.ui.component.PreferenceGroupTitle
import echo.music.iad1tya.ui.utils.backToMain
import echo.music.iad1tya.ui.utils.parseMarkdownToSections
import echo.music.iad1tya.ui.utils.parseSimpleMarkdown
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettings(
  navController: NavController,
  scrollBehavior: TopAppBarScrollBehavior,
  highlightKey: String? = null
) {
  val scrollState = rememberScrollState()
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

  var isChecking by remember { mutableStateOf(false) }
  var isUpdateAvailable by remember { mutableStateOf(false) }
  var latestVersionTag by remember { mutableStateOf(BuildConfig.VERSION_NAME) }
  var apkDownloadUrl by remember { mutableStateOf<String?>(null) }
  var apkSizeMB by remember { mutableStateOf("") }
  var isDownloading by remember { mutableStateOf(false) }
  var downloadProgress by remember { mutableFloatStateOf(0f) }
  var downloadedApkFile by remember { mutableStateOf<File?>(null) }

  var apkCount by remember { mutableStateOf(getDownloadedApkCount(context)) }
  var showInfoDialog by remember { mutableStateOf(false) }
  var releaseNotes by remember { mutableStateOf<String?>(null) }

  fun checkGitHubRelease(showToastIfUpToDate: Boolean = false) {
    if (isChecking || isDownloading) return
    isChecking = true
    coroutineScope.launch {
      withContext(Dispatchers.IO) {
        try {
          val connection =
            (URL("https://api.github.com/repos/sepuro/Jet-Music/releases/latest").openConnection()
                as HttpURLConnection)
              .apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Jet-Music-Updater")
                setRequestProperty("Accept", "application/vnd.github+json")
              }
          val json = connection.inputStream.bufferedReader().use { it.readText() }
          val targetRelease = JSONObject(json)
          val remoteTag = targetRelease.optString("tag_name", BuildConfig.VERSION_NAME)
          val body = targetRelease.optString("body", "")
          val (assetUrl, assetSize) = extractApkAssetFromRelease(targetRelease)
          val newer = isNewerVersion(remoteTag, BuildConfig.VERSION_NAME) && assetUrl.isNotEmpty()

          saveUpdateAvailableState(context, newer)

          withContext(Dispatchers.Main) {
            releaseNotes = body.takeIf { it.isNotBlank() }
            latestVersionTag = remoteTag
            apkDownloadUrl = assetUrl.takeIf { it.isNotEmpty() }
            apkSizeMB = assetSize
            isUpdateAvailable = newer
            isChecking = false
            if (!newer && showToastIfUpToDate) {
              Toast.makeText(
                  context,
                  context.getString(R.string.on_latest_version),
                  Toast.LENGTH_SHORT
                )
                .show()
            }
          }
        } catch (e: Exception) {
          e.printStackTrace()
          withContext(Dispatchers.Main) {
            isChecking = false
            if (showToastIfUpToDate) {
              Toast.makeText(
                  context,
                  context.getString(R.string.check_internet_connection),
                  Toast.LENGTH_SHORT
                )
                .show()
            }
          }
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    autoClearOldApks(context)
    apkCount = getDownloadedApkCount(context)
    checkGitHubRelease(showToastIfUpToDate = false)
  }

  if (showInfoDialog) {
    UpdateInfoDialog(onDismiss = { showInfoDialog = false })
  }

  Column(
    Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)
      )
      .verticalScroll(scrollState)
      .padding(horizontal = 16.dp),
  ) {
    Spacer(
      Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top))
    )

    Spacer(modifier = Modifier.height(16.dp))

    Material3SettingsGroup(
      scrollState = scrollState,
      title = stringResource(R.string.app_updates_title),
      items =
        listOf(
          Material3SettingsItem(
            isHighlighted = (highlightKey == stringResource(R.string.system_update)),
            icon = painterResource(R.drawable.update),
            title = {
              Text(
                when {
                  isDownloading ->
                    stringResource(R.string.downloading_percent, (downloadProgress * 100).toInt())
                  downloadedApkFile?.exists() == true ->
                    stringResource(R.string.tap_to_install_version, latestVersionTag)
                  isUpdateAvailable -> stringResource(R.string.update_to_v, latestVersionTag)
                  else -> stringResource(R.string.system_update)
                }
              )
            },
            description = {
              Column {
                when {
                  isChecking -> Text(stringResource(R.string.checking_for_updates))
                  isDownloading -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                      progress = { downloadProgress },
                      modifier =
                        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                      color = MaterialTheme.colorScheme.primary,
                      trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                  }
                  downloadedApkFile?.exists() == true -> {
                    Text(
                      text = stringResource(R.string.install),
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                  isUpdateAvailable -> {
                    val sizeSuffix = if (apkSizeMB.isNotEmpty()) " ($apkSizeMB MB)" else ""
                    Text(
                      text = "${stringResource(R.string.new_version_available)}$sizeSuffix",
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                  else -> {
                    Text(
                      "${stringResource(R.string.version, BuildConfig.VERSION_NAME)} • ${stringResource(R.string.app_update_uptodate)}"
                    )
                  }
                }
              }
            },
            onClick = {
              when {
                isDownloading -> Unit
                downloadedApkFile?.exists() == true -> {
                  installApk(context, downloadedApkFile!!)
                }
                isUpdateAvailable && !apkDownloadUrl.isNullOrBlank() -> {
                  isDownloading = true
                  downloadProgress = 0f
                  coroutineScope.launch {
                    downloadApkWithProgress(
                      context = context,
                      apkUrl = apkDownloadUrl!!,
                      version = latestVersionTag,
                      onProgress = { progress -> downloadProgress = progress },
                      onSuccess = { file ->
                        isDownloading = false
                        downloadedApkFile = file
                        installApk(context, file)
                      },
                      onError = { errorMsg ->
                        isDownloading = false
                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                      }
                    )
                  }
                }
                else -> checkGitHubRelease(showToastIfUpToDate = true)
              }
            }
          )
        )
    )

    Spacer(modifier = Modifier.height(16.dp))

    releaseNotes?.let { notes ->
      PreferenceGroupTitle(title = stringResource(R.string.whats_new))
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
          ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
          val (effectiveDescription, effectiveSections) =
            remember(notes) { parseMarkdownToSections(notes) }

          if (!effectiveDescription.isNullOrBlank()) {
            Text(
              text = parseSimpleMarkdown(effectiveDescription, MaterialTheme.colorScheme.primary),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = 8.dp)
            )
          }

          if (effectiveSections.isNotEmpty()) {
            effectiveSections.forEachIndexed { sectionIndex, section ->
              if (section.title.isNotBlank()) {
                Text(
                  text = parseSimpleMarkdown(section.title, MaterialTheme.colorScheme.primary),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                  modifier =
                    Modifier.padding(
                      top =
                        if (sectionIndex == 0 && effectiveDescription.isNullOrBlank()) 0.dp
                        else 10.dp,
                      bottom = 4.dp
                    )
                )
              }
              section.items.forEach { item ->
                if (item.isNotBlank()) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    Text(
                      text = "•",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.Bold,
                    )
                    Text(
                      text = parseSimpleMarkdown(item.trim(), MaterialTheme.colorScheme.primary),
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.weight(1f)
                    )
                  }
                }
              }
            }
          } else if (effectiveDescription.isNullOrBlank()) {
            Text(
              text = parseSimpleMarkdown(notes, MaterialTheme.colorScheme.primary),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
    }

    Spacer(modifier = Modifier.height(16.dp))

    Spacer(
      Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
      )
    )
  }

  TopAppBar(
    title = { Text(stringResource(R.string.update_settings_title)) },
    navigationIcon = {
      IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
      }
    }
  )
}
