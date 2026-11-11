package echo.music.iad1tya.echomusic.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import echo.music.iad1tya.R
import echo.music.iad1tya.echomusic.updater.ChangelogSection
import echo.music.iad1tya.echomusic.updater.downloadApkWithProgress
import echo.music.iad1tya.echomusic.updater.extractApkAssetFromRelease
import echo.music.iad1tya.echomusic.updater.installApk
import echo.music.iad1tya.ui.utils.parseMarkdownToSections
import echo.music.iad1tya.ui.utils.parseSimpleMarkdown
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateAvailableDialog(
  version: String,
  changelog: List<ChangelogSection>,
  description: String?,
  apkUrl: String? = null,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var isDownloading by remember { mutableStateOf(false) }
  var downloadProgress by remember { mutableFloatStateOf(0f) }
  var downloadedFile by remember { mutableStateOf<File?>(null) }
  var errorText by remember { mutableStateOf<String?>(null) }

  val cardShape =
    AbsoluteSmoothCornerShape(
      cornerRadiusTL = 30.dp,
      cornerRadiusTR = 30.dp,
      cornerRadiusBL = 30.dp,
      cornerRadiusBR = 30.dp,
      smoothnessAsPercentTL = 60,
      smoothnessAsPercentTR = 60,
      smoothnessAsPercentBL = 60,
      smoothnessAsPercentBR = 60,
    )
  val blockShape = AbsoluteSmoothCornerShape(22.dp, 60)
  val actionShape = AbsoluteSmoothCornerShape(18.dp, 60)

  BasicAlertDialog(onDismissRequest = { if (!isDownloading) onDismiss() }) {
    Surface(
      modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
      shape = cardShape,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Surface(
          shape = blockShape,
          color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Surface(
                shape = AbsoluteSmoothCornerShape(12.dp, 60),
                color = MaterialTheme.colorScheme.primaryContainer,
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Icon(
                    painter = painterResource(R.drawable.update),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                  )
                  Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                  )
                }
              }
            }

            val cleanVersion =
              remember(version) { version.trim().removePrefix("v").removePrefix("V") }
            Text(
              text = "Nueva actualización disponible (v$cleanVersion)",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
          }
        }

        if (changelog.isNotEmpty() || !description.isNullOrEmpty()) {
          Surface(
            modifier = Modifier.weight(1f, fill = false),
            shape = blockShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
          ) {
            Column(
              modifier =
                Modifier.fillMaxWidth().padding(14.dp).verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Text(
                text = stringResource(R.string.changelog),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
              )

              val (effectiveDescription, effectiveSections) =
                remember(changelog, description) {
                  if (changelog.isNotEmpty()) {
                    Pair(description?.takeIf { it.isNotBlank() }, changelog)
                  } else if (!description.isNullOrBlank()) {
                    parseMarkdownToSections(description)
                  } else {
                    Pair(null, emptyList())
                  }
                }

              if (!effectiveDescription.isNullOrBlank()) {
                Text(
                  text =
                    parseSimpleMarkdown(effectiveDescription, MaterialTheme.colorScheme.primary),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(bottom = 4.dp)
                )
              }

              if (effectiveSections.isNotEmpty()) {
                effectiveSections.forEach { section ->
                  if (section.title.isNotBlank()) {
                    Text(
                      text = section.title,
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.padding(top = 6.dp)
                    )
                  }
                  section.items.forEach { item ->
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
                        modifier = Modifier.weight(1f)
                      )
                    }
                  }
                }
              }
            }
          }
        }

        if (isDownloading) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(
              progress = { downloadProgress },
              modifier = Modifier.fillMaxWidth().height(8.dp),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            )
            Text(
              text = stringResource(R.string.downloading_percent, (downloadProgress * 100).toInt()),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        errorText?.let { err ->
          Text(
            text = err,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            onClick = onDismiss,
            enabled = !isDownloading,
            shape = actionShape,
          ) {
            Text(text = stringResource(R.string.later))
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = {
              val existingFile = downloadedFile
              if (existingFile != null && existingFile.exists()) {
                installApk(context, existingFile)
                return@Button
              }
              isDownloading = true
              errorText = null
              coroutineScope.launch {
                val resolvedUrl =
                  apkUrl?.takeIf { it.isNotBlank() }
                    ?: withContext(Dispatchers.IO) {
                      try {
                        val conn =
                          (URL("https://api.github.com/repos/sepuro/Jet-Music/releases/latest")
                              .openConnection() as HttpURLConnection)
                            .apply {
                              connectTimeout = 15_000
                              readTimeout = 15_000
                              setRequestProperty("User-Agent", "Jet-Music-Updater")
                              setRequestProperty("Accept", "application/vnd.github+json")
                            }
                        val releaseJson =
                          JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        extractApkAssetFromRelease(releaseJson).first
                      } catch (_: Exception) {
                        ""
                      }
                    }

                if (resolvedUrl.isBlank()) {
                  isDownloading = false
                  errorText = context.getString(R.string.download_failed)
                  return@launch
                }

                downloadApkWithProgress(
                  context = context,
                  apkUrl = resolvedUrl,
                  version = version,
                  onProgress = { progress -> downloadProgress = progress },
                  onSuccess = { file ->
                    isDownloading = false
                    downloadedFile = file
                    installApk(context, file)
                  },
                  onError = { err ->
                    isDownloading = false
                    errorText = err
                  }
                )
              }
            },
            enabled = !isDownloading,
            shape = actionShape,
          ) {
            Text(
              text =
                when {
                  isDownloading -> "${(downloadProgress * 100).toInt()}%"
                  downloadedFile?.exists() == true -> stringResource(R.string.install)
                  else -> "Actualizar ahora"
                }
            )
          }
        }
      }
    }
  }
}
