package com.uacspoofer.mobile.update

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.uacspoofer.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

internal data class AppRelease(
    val version: String,
    val tagName: String,
    val title: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val releaseUrl: String,
)

internal sealed interface UpdateCheckResult {
    data object Current : UpdateCheckResult
    data class Available(val release: AppRelease) : UpdateCheckResult
}

internal sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val release: AppRelease) : UpdateUiState
    data class Downloading(val release: AppRelease, val progress: Int?) : UpdateUiState
    data class Ready(val release: AppRelease, val message: String) : UpdateUiState
    data class Error(val message: String, val release: AppRelease? = null) : UpdateUiState
}

internal enum class InstallLaunchResult {
    INSTALLER_OPENED,
    PERMISSION_REQUESTED,
}

internal class AppUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "UAC-SNI-Spoofer-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                error("GitHub Releases returned HTTP $status")
            }
            val release = parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
            if (isVersionNewer(release.version, BuildConfig.VERSION_NAME)) {
                UpdateCheckResult.Available(release)
            } else {
                UpdateCheckResult.Current
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndInstall(
        activity: Activity,
        release: AppRelease,
        onProgress: (Int?) -> Unit,
    ): InstallLaunchResult {
        require(Uri.parse(release.apkUrl).scheme.equals("https", ignoreCase = true)) {
            "The release APK does not use HTTPS"
        }
        val downloadManager = appContext.getSystemService(DownloadManager::class.java)
            ?: error("Android Download Manager is unavailable")
        val safeVersion = release.version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val fileName = "UAC-SNI-Spoofer-$safeVersion-${System.currentTimeMillis()}.apk"
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("UAC SNI Spoofer ${release.version}")
            .setDescription("Downloading the verified GitHub release")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadId = downloadManager.enqueue(request)
        preferences.edit().putLong(KEY_PENDING_DOWNLOAD_ID, downloadId).apply()
        withContext(Dispatchers.Main.immediate) { onProgress(0) }

        return try {
            var lastProgress: Int? = -1
            while (true) {
                val snapshot = queryDownload(downloadManager, downloadId)
                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> break
                    DownloadManager.STATUS_FAILED -> error("Download failed (code ${snapshot.reason})")
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_PAUSED,
                    DownloadManager.STATUS_RUNNING -> {
                        if (snapshot.progress != lastProgress) {
                            lastProgress = snapshot.progress
                            withContext(Dispatchers.Main.immediate) { onProgress(snapshot.progress) }
                        }
                    }
                    else -> error("Download stopped unexpectedly")
                }
                delay(DOWNLOAD_POLL_MS)
            }
            withContext(Dispatchers.Main.immediate) {
                openPendingInstaller(activity)
                    ?: error("The downloaded APK could not be opened")
            }
        } catch (error: Throwable) {
            preferences.edit().remove(KEY_PENDING_DOWNLOAD_ID).apply()
            throw error
        }
    }

    private fun parseRelease(jsonText: String): AppRelease {
        val root = JSONObject(jsonText)
        val tagName = root.getString("tag_name")
        val version = extractVersion(tagName)
            ?: extractVersion(root.optString("name"))
            ?: error("The latest release has no readable version")
        val assets = root.getJSONArray("assets")
        var selected: JSONObject? = null
        var selectedScore = Int.MIN_VALUE
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val lowerName = name.lowercase()
            val score = when {
                "unsigned" in lowerName -> 0
                lowerName == "uac-spoofer.apk" -> 100
                "release" in lowerName -> 80
                else -> 60
            }
            if (score > selectedScore) {
                selected = asset
                selectedScore = score
            }
        }
        val apk = selected?.takeIf { selectedScore > 0 }
            ?: error("Release $tagName does not include a signed APK")
        return AppRelease(
            version = version,
            tagName = tagName,
            title = root.optString("name").ifBlank { "UAC SNI Spoofer $version" },
            notes = root.optString("body"),
            apkName = apk.getString("name"),
            apkUrl = apk.getString("browser_download_url"),
            releaseUrl = root.getString("html_url"),
        )
    }

    private data class DownloadSnapshot(val status: Int, val reason: Int, val progress: Int?)

    private fun queryDownload(downloadManager: DownloadManager, downloadId: Long): DownloadSnapshot {
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            check(cursor != null && cursor.moveToFirst()) { "Downloaded file is no longer available" }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val received = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val progress = if (total > 0L) ((received * 100L) / total).toInt().coerceIn(0, 100) else null
            return DownloadSnapshot(status, reason, progress)
        }
    }

    companion object {
        internal const val REPOSITORY_URL = "https://github.com/Floxu1/UAC-SNI-Spoofer-Android"
        internal const val RELEASES_URL = "$REPOSITORY_URL/releases"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/Floxu1/UAC-SNI-Spoofer-Android/releases/latest"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val PREFERENCES_NAME = "app_update_state"
        private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
        private const val NETWORK_TIMEOUT_MS = 12_000
        private const val DOWNLOAD_POLL_MS = 450L

        fun resumePendingInstall(activity: Activity) {
            runCatching { openPendingInstaller(activity) }
        }

        private fun openPendingInstaller(activity: Activity): InstallLaunchResult? {
            val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val downloadId = preferences.getLong(KEY_PENDING_DOWNLOAD_ID, -1L)
            if (downloadId < 0L) return null
            val downloadManager = activity.getSystemService(DownloadManager::class.java) ?: return null
            val status = downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return null
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) return null

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}"),
                    ),
                )
                return InstallLaunchResult.PERMISSION_REQUESTED
            }

            val apkUri = downloadManager.getUriForDownloadedFile(downloadId)
                ?: error("Downloaded APK URI is unavailable")
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            activity.startActivity(installIntent)
            preferences.edit().remove(KEY_PENDING_DOWNLOAD_ID).apply()
            return InstallLaunchResult.INSTALLER_OPENED
        }
    }
}

internal fun isVersionNewer(candidate: String, current: String): Boolean {
    val candidateParts = versionParts(candidate)
    val currentParts = versionParts(current)
    if (candidateParts.isEmpty() || currentParts.isEmpty()) return false
    for (index in 0 until max(candidateParts.size, currentParts.size)) {
        val candidatePart = candidateParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (candidatePart != currentPart) return candidatePart > currentPart
    }
    return false
}

private fun versionParts(raw: String): List<Int> =
    extractVersion(raw)?.split('.')?.mapNotNull(String::toIntOrNull).orEmpty()

private fun extractVersion(raw: String): String? =
    Regex("\\d+(?:\\.\\d+)+").find(raw)?.value
