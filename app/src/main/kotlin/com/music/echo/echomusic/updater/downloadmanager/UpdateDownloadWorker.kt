package echo.music.iad1tya.echomusic.updater.downloadmanager

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import echo.music.iad1tya.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateDownloadWorker(private val context: Context, workerParams: WorkerParameters) :
  CoroutineWorker(context, workerParams) {

  override suspend fun doWork(): Result =
    withContext(Dispatchers.IO) {
      val apkUrl = inputData.getString("apk_url") ?: return@withContext Result.failure()
      val version = inputData.getString("version") ?: "unknown"
      val fileSize = inputData.getString("file_size") ?: ""

      try {
        val startingNotification =
          DownloadNotificationManager.getDownloadStartingNotification(version, fileSize)
        val foregroundInfo =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
              DownloadNotificationManager.NOTIFICATION_ID,
              startingNotification,
              ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
          } else {
            ForegroundInfo(DownloadNotificationManager.NOTIFICATION_ID, startingNotification)
          }
        setForeground(foregroundInfo)
      } catch (e: Exception) {}

      try {
        val okHttpClient =
          okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request =
          okhttp3.Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "Jet-Music-Updater")
            .header("Accept", "application/octet-stream, application/vnd.android.package-archive, */*")
            .get()
            .build()

        val downloadDir = File(context.cacheDir, "updates")
        if (!downloadDir.exists()) {
          downloadDir.mkdirs()
        }

        val isZip = apkUrl.contains("nightly.link") || apkUrl.endsWith(".zip")
        val downloadFile =
          if (isZip) File(downloadDir, "echo_temp.zip")
          else File(context.cacheDir, "updates/Jet-Music-update.apk")
        if (downloadFile.exists()) {
          downloadFile.delete()
        }

        okHttpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            DownloadNotificationManager.showDownloadFailed(
              version,
              context.getString(R.string.server_error, response.code)
            )
            if (response.code >= 500) {
              return@withContext Result.retry()
            }
            return@withContext Result.failure()
          }

          val body = response.body ?: return@withContext Result.failure()
          val fileLength = body.contentLength()
          body.byteStream().use { inputStream ->
            FileOutputStream(downloadFile).use { outputStream ->
              val buffer = ByteArray(8192)
              var bytesRead: Int
              var totalBytesRead: Long = 0
              var lastProgress = -1
              var lastNotificationTime = 0L

              while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isStopped) {
                  if (downloadFile.exists()) {
                    downloadFile.delete()
                  }
                  return@withContext Result.retry()
                }

                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                if (fileLength > 0) {
                  val progress = (totalBytesRead.toFloat() / fileLength.toFloat() * 100).toInt()
                  val currentTime = System.currentTimeMillis()

                  if (progress > lastProgress && currentTime - lastNotificationTime >= 1000) {
                    lastProgress = progress
                    lastNotificationTime = currentTime

                    val progressNotification =
                      DownloadNotificationManager.getDownloadProgressNotification(progress, version)
                    val notificationManager =
                      context.getSystemService(Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager
                    notificationManager.notify(
                      DownloadNotificationManager.NOTIFICATION_ID,
                      progressNotification
                    )

                    setProgress(workDataOf("progress" to progress.toFloat() / 100f))
                  }
                }
              }

              outputStream.flush()
              outputStream.fd.sync()
            }
          }
        }

        val finalFile =
          if (isZip) {
            val targetApkFile = File(context.cacheDir, "updates/Jet-Music-update.apk")
            if (targetApkFile.exists()) targetApkFile.delete()
            var extracted = false
            try {
              ZipInputStream(downloadFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                  if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    FileOutputStream(targetApkFile).use { fos ->
                      zis.copyTo(fos)
                      fos.flush()
                      fos.fd.sync()
                    }
                    extracted = true
                    break
                  }
                  entry = zis.nextEntry
                }
              }
            } catch (e: Exception) {
              if (downloadFile.exists()) downloadFile.delete()
              DownloadNotificationManager.showDownloadFailed(
                version,
                e.message ?: "Failed to extract zip file"
              )
              return@withContext Result.failure()
            } finally {
              if (downloadFile.exists()) {
                downloadFile.delete()
              }
            }
            if (!extracted) {
              DownloadNotificationManager.showDownloadFailed(version, "Could not find APK in zip")
              return@withContext Result.failure()
            }
            targetApkFile
          } else {
            downloadFile
          }

        val archiveInfo = context.packageManager.getPackageArchiveInfo(finalFile.absolutePath, 0)
        if (archiveInfo == null) {
          finalFile.delete()
          DownloadNotificationManager.showDownloadFailed(version, "Invalid APK package archive")
          return@withContext Result.failure()
        }

        if (version.startsWith("nightly-r")) {
          val runNumberString = version.removePrefix("nightly-r")
          val runNumber = runNumberString.toIntOrNull()
          if (runNumber != null) {
            val sharedPreferences =
              context.getSharedPreferences("update_settings", Context.MODE_PRIVATE)
            sharedPreferences.edit().putInt("last_installed_nightly_run", runNumber).apply()
          }
        }

        DownloadNotificationManager.showDownloadComplete(version, finalFile.absolutePath)

        Result.success(workDataOf("file_path" to finalFile.absolutePath))
      } catch (e: IOException) {
        DownloadNotificationManager.showDownloadFailed(
          version,
          e.message ?: context.getString(R.string.download_failed)
        )
        return@withContext Result.retry()
      } catch (e: Exception) {
        DownloadNotificationManager.showDownloadFailed(
          version,
          e.message ?: context.getString(R.string.download_failed)
        )
        Result.failure()
      }
    }
}
