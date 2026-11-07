package com.uacspoofer.mobile.logging

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.uacspoofer.mobile.BuildConfig
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns the in-memory live log into a plain .txt file the user can save
 * or hand to the developer on Telegram.
 */
object LogExport {
    const val DEVELOPER_TELEGRAM = "Beh50roocentzuac"
    private const val CACHE_DIR = "logs"

    /** Telegram and its common forks, official app first. */
    private val TELEGRAM_PACKAGES = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.thunderdog.challegram",
        "org.telegram.plus",
        "tw.nekomimi.nekogram",
        "xyz.nextalone.nagram",
        "org.forkgram.messenger",
        "it.belloworld.mercurygram",
        "com.exteragram.messenger",
        "app.nicegram",
        "org.telegram.mdgram",
        "ir.ilmili.telegraph",
        "org.telegram.bgram",
    )

    data class Snapshot(val text: String, val lineCount: Int, val bytes: Long, val fileName: String)

    fun snapshot(context: Context): Snapshot {
        val entries = AppLogRepository.entries.value
        val body = AppLogRepository.snapshotText()
        val text = buildString {
            append(header(context, entries.size))
            append('\n')
            append(body)
            append('\n')
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return Snapshot(
            text = text,
            lineCount = entries.size,
            bytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            fileName = "UAC-log-$stamp.txt",
        )
    }

    private fun header(context: Context, count: Int): String {
        val engine = runCatching { engineLabel(EngineModeStore.get(context).mode.value) }.getOrDefault("unknown")
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("===== UAC SNI Spoofer log =====")
            appendLine("App:      ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android:  ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device:   ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI:      ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
            appendLine("Engine:   $engine")
            appendLine("Exported: $now")
            appendLine("Entries:  $count")
            appendLine("================================")
        }
    }

    private fun engineLabel(mode: EngineMode): String = when (mode) {
        EngineMode.XRAY_CF -> "Engine 1 - Xray"
        EngineMode.TOR_WEBTUNNEL -> "Engine 2 - Tor WebTunnel"
        EngineMode.UAC_POW -> "Engine 3 - UAC PoW"
        EngineMode.UAC_DIRECT -> "Engine 4 - UAC Direct"
    }

    /** Writes the text to the place the user picked in the system file dialog. */
    fun writeTo(context: Context, uri: Uri, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: error("no stream")
        true
    }.getOrElse {
        AppLogRepository.warning(LogSource.APP, "Log export failed", it)
        false
    }

    private fun writeCache(context: Context, snapshot: Snapshot): File {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, snapshot.fileName).apply { writeText(snapshot.text, Charsets.UTF_8) }
    }

    enum class TelegramResult { TELEGRAM, CHOOSER, LINK, FAILED }

    /**
     * Android cannot pre-pick a Telegram contact for a file, so the file goes to
     * Telegram's own chat picker and the developer id is put on the clipboard.
     */
    fun sendToTelegram(context: Context, snapshot: Snapshot): TelegramResult {
        copyDeveloperId(context)
        val uri = runCatching {
            val file = writeCache(context, snapshot)
            FileProvider.getUriForFile(context, "${context.packageName}.logfiles", file)
        }.getOrElse {
            AppLogRepository.warning(LogSource.APP, "Log file for Telegram failed", it)
            return if (openDeveloperChat(context)) TelegramResult.LINK else TelegramResult.FAILED
        }
        fun sendIntent(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, snapshot.fileName)
            clipData = ClipData.newRawUri(snapshot.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) setPackage(pkg)
        }
        for (pkg in TELEGRAM_PACKAGES) {
            if (!isInstalled(context, pkg)) continue
            try {
                context.startActivity(sendIntent(pkg))
                return TelegramResult.TELEGRAM
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return try {
            val chooser = Intent.createChooser(sendIntent(null), "UAC log").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            TelegramResult.CHOOSER
        } catch (_: Exception) {
            if (openDeveloperChat(context)) TelegramResult.LINK else TelegramResult.FAILED
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private fun copyDeveloperId(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Telegram", "@$DEVELOPER_TELEGRAM"))
        }
    }

    private fun openDeveloperChat(context: Context): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$DEVELOPER_TELEGRAM"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: Exception) {
        false
    }
}
