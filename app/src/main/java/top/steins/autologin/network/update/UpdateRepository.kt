package top.steins.autologin.network.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.steins.autologin.network.executeCancellable
import top.steins.autologin.R
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val UPDATE_BASE_URL = "https://aloginupdate.steins.top/"

data class UpdateInfo(
    val version: String,
    val fileName: String,
    val downloadUrl: String
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latestVersion: String) : UpdateState
    data class Available(val update: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

sealed interface UpdateDownloadResult {
    data object Enqueued : UpdateDownloadResult
    data object OpenedInBrowser : UpdateDownloadResult
    data object Failed : UpdateDownloadResult
}

class UpdateRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun fetchLatestUpdate(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(UPDATE_BASE_URL)
            .header("User-Agent", "Alogin $currentVersion")
            .get()
            .build()

        client.executeCancellable(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("更新服务器返回 HTTP ${response.code}")
            }
            val html = response.body.string()
            parseLatestUpdate(html)
                ?: throw IOException("更新服务器未提供有效的 APK")
        }
    }

    fun downloadUpdate(context: Context, update: UpdateInfo): UpdateDownloadResult {
        return runCatching {
            val request = DownloadManager.Request(update.downloadUrl.toUri()).apply {
                setTitle(update.fileName)
                setDescription(context.getString(R.string.update_download_description))
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, update.fileName)
                setMimeType("application/vnd.android.package-archive")
            }
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            UpdateDownloadResult.Enqueued
        }.getOrElse {
            openDownloadInBrowser(context, update.downloadUrl)
        }
    }

    private fun openDownloadInBrowser(context: Context, downloadUrl: String): UpdateDownloadResult {
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, downloadUrl.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            UpdateDownloadResult.OpenedInBrowser
        }.getOrDefault(UpdateDownloadResult.Failed)
    }
}

private val apkLinkPattern = Regex(
    """href\s*=\s*["'](alogin-v((?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?)\.apk)["']""",
    RegexOption.IGNORE_CASE
)

internal fun parseLatestUpdate(html: String): UpdateInfo? {
    return apkLinkPattern.findAll(html)
        .mapNotNull { match ->
            val fileName = match.groupValues[1]
            val versionName = match.groupValues[2]
            val semanticVersion = SemanticVersion.parseOrNull(versionName) ?: return@mapNotNull null
            semanticVersion to UpdateInfo(
                version = versionName,
                fileName = fileName,
                downloadUrl = UPDATE_BASE_URL + fileName
            )
        }
        .maxWithOrNull(compareBy { it.first })
        ?.second
}
