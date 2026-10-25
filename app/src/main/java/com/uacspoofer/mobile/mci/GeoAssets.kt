package com.uacspoofer.mobile.mci

import android.content.Context
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Bundled + updatable Xray geoip.dat / geosite.dat (Iran-v2ray-rules style).
 * Shipped inside libv2ray AAR assets; optional download from Chocolate4U releases.
 */
object GeoAssets {
    const val GEOIP = "geoip.dat"
    const val GEOSITE = "geosite.dat"
    private const val STAMP = "geo-assets.stamp"
    private const val SOURCE = "Chocolate4U/Iran-v2ray-rules"

    /** Latest release assets (follows GitHub "latest" redirect). */
    const val GEOIP_URL =
        "https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download/geoip.dat"
    const val GEOSITE_URL =
        "https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download/geosite.dat"

    data class Status(
        val tag: String,
        val geoipBytes: Long,
        val geositeBytes: Long,
        val updatedAtMs: Long,
        val source: String = SOURCE,
    )

    /** Idempotent; safe to call on every connect. */
    @Synchronized
    fun ensure(context: Context): File {
        val dir = context.applicationContext.filesDir
        val geoip = File(dir, GEOIP)
        val geosite = File(dir, GEOSITE)
        var copied = false
        if (!geoip.isFile || geoip.length() < MIN_BYTES) {
            extract(context, GEOIP, geoip)
            copied = true
        }
        if (!geosite.isFile || geosite.length() < MIN_BYTES) {
            extract(context, GEOSITE, geosite)
            copied = true
        }
        if (copied) {
            writeStamp(dir, "bundled", System.currentTimeMillis())
            AppLogRepository.info(
                LogSource.XRAY,
                "Geo assets ready geoip=${geoip.length()} geosite=${geosite.length()}",
            )
        }
        return dir
    }

    fun status(context: Context): Status {
        ensure(context)
        val dir = context.applicationContext.filesDir
        val stamp = File(dir, STAMP)
        val (tag, at) = if (stamp.isFile) {
            val parts = stamp.readText().trim().split('|')
            (parts.getOrNull(0) ?: "bundled") to (parts.getOrNull(1)?.toLongOrNull() ?: stamp.lastModified())
        } else {
            "bundled" to (File(dir, GEOIP).lastModified())
        }
        return Status(
            tag = tag,
            geoipBytes = File(dir, GEOIP).length(),
            geositeBytes = File(dir, GEOSITE).length(),
            updatedAtMs = at,
        )
    }

    /**
     * Downloads latest geoip.dat + geosite.dat from Chocolate4U/Iran-v2ray-rules.
     * Replaces files in filesDir; callers should [DomesticDirect.reloadFromFiles] after.
     */
    @Synchronized
    fun updateFromNetwork(context: Context): Status {
        val dir = context.applicationContext.filesDir
        ensure(context)
        AppLogRepository.info(LogSource.XRAY, "Geo assets: downloading from $SOURCE …")
        download(GEOIP_URL, File(dir, "$GEOIP.download"))
        download(GEOSITE_URL, File(dir, "$GEOSITE.download"))
        val geoipDl = File(dir, "$GEOIP.download")
        val geositeDl = File(dir, "$GEOSITE.download")
        check(geoipDl.length() > MIN_BYTES) { "Downloaded geoip.dat too small (${geoipDl.length()})" }
        check(geositeDl.length() > MIN_BYTES) { "Downloaded geosite.dat too small (${geositeDl.length()})" }
        val geoip = File(dir, GEOIP)
        val geosite = File(dir, GEOSITE)
        if (geoip.exists()) geoip.delete()
        if (geosite.exists()) geosite.delete()
        check(geoipDl.renameTo(geoip) || (geoip.delete() && geoipDl.renameTo(geoip)))
        check(geositeDl.renameTo(geosite) || (geosite.delete() && geositeDl.renameTo(geosite)))
        val tag = "latest@" + System.currentTimeMillis()
        writeStamp(dir, tag, System.currentTimeMillis())
        AppLogRepository.success(
            LogSource.XRAY,
            "Geo assets updated geoip=${geoip.length()} geosite=${geosite.length()}",
        )
        return status(context)
    }

    private fun writeStamp(dir: File, tag: String, at: Long) {
        File(dir, STAMP).writeText("$tag|$at")
    }

    private fun download(url: String, destination: File) {
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        val temp = File(destination.parentFile, destination.name + ".part")
        if (temp.exists()) temp.delete()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 120_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "UAC-Mobile-GeoAssets")
            }
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code for $url" }
            conn.inputStream.use { input ->
                temp.outputStream().use { out -> input.copyTo(out, BUFFER) }
            }
            check(temp.renameTo(destination) || (destination.delete() && temp.renameTo(destination))) {
                "Could not finalize ${destination.name}"
            }
        } finally {
            conn?.disconnect()
            if (temp.exists() && !destination.exists()) temp.delete()
        }
    }

    private fun extract(context: Context, name: String, destination: File) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "$name.tmp")
        open(context, name).use { input ->
            temp.outputStream().use { out -> input.copyTo(out, BUFFER) }
        }
        check(temp.isFile && temp.length() > MIN_BYTES) {
            "Geo asset $name extracted empty (${temp.length()} bytes)"
        }
        if (destination.exists()) destination.delete()
        check(temp.renameTo(destination) || (destination.delete() && temp.renameTo(destination))) {
            "Could not install ${destination.absolutePath}"
        }
    }

    private fun open(context: Context, name: String): InputStream {
        runCatching { context.assets.open(name) }.getOrNull()?.let { return it }
        val apks = buildList {
            add(File(context.applicationInfo.sourceDir))
            context.applicationInfo.splitSourceDirs.orEmpty().forEach { add(File(it)) }
        }.filter { it.isFile }
        for (apk in apks) {
            val zip = ZipFile(apk)
            val entry = zip.getEntry("assets/$name")
            if (entry != null) {
                return object : InputStream() {
                    private val inner = zip.getInputStream(entry)
                    override fun read(): Int = inner.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
                    override fun close() {
                        inner.close()
                        zip.close()
                    }
                }
            }
            zip.close()
        }
        error("Geo asset $name missing from APK (expected assets/$name from libv2ray)")
    }

    private const val MIN_BYTES = 100_000L
    private const val BUFFER = 64 * 1024
}
