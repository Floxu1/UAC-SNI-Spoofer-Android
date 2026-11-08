package com.uacspoofer.mobile.logging

import android.content.Context
import android.net.Uri
import android.os.Build
import com.uacspoofer.mobile.BuildConfig
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns the in-memory live log into a plain .txt file the user can save. */
object LogExport {
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
}
