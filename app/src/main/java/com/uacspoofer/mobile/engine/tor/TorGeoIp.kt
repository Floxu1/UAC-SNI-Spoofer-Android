package com.uacspoofer.mobile.engine.tor

import android.content.Context
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

internal data class TorGeoIpFiles(
    val geoip: File,
    val geoip6: File,
)

internal object TorGeoIp {
    const val VERSION = "debian-0.4.9.11-1"

    fun install(context: Context): TorGeoIpFiles {
        val dir = File(context.applicationContext.filesDir, "tor").apply { mkdirs() }
        val geoip = File(dir, "geoip")
        val geoip6 = File(dir, "geoip6")
        val stamp = File(dir, "geoip.version")
        if (
            stamp.takeIf { it.isFile }?.readText()?.trim() == VERSION &&
            geoip.isFile && geoip.length() > MIN_GEOIP_BYTES &&
            geoip6.isFile && geoip6.length() > MIN_GEOIP_BYTES
        ) {
            return TorGeoIpFiles(geoip, geoip6)
        }
        extractNamed(context, "geoip", geoip)
        extractNamed(context, "geoip6", geoip6)
        stamp.writeText(VERSION)
        AppLogRepository.info(
            LogSource.TOR,
            "Installed Tor geoip v$VERSION ipv4=${geoip.length()} ipv6=${geoip6.length()}",
        )
        return TorGeoIpFiles(geoip, geoip6)
    }

    private fun extractNamed(context: Context, logicalName: String, destination: File) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.tmp")
        openSource(context, logicalName).use { raw ->
            decode(raw).use { input ->
                temp.outputStream().use { out -> input.copyTo(out, BUFFER_BYTES) }
            }
        }
        check(temp.isFile && temp.length() > MIN_GEOIP_BYTES) {
            "GeoIP $logicalName extracted empty (${temp.length()} bytes)"
        }
        if (destination.exists()) destination.delete()
        check(temp.renameTo(destination) || (destination.delete() && temp.renameTo(destination))) {
            "Could not install ${destination.absolutePath}"
        }
    }

    private fun openSource(context: Context, logicalName: String): InputStream {
        val assetNames = listOf("tor/$logicalName", "tor/$logicalName.gz")
        for (name in assetNames) {
            val opened = runCatching { context.assets.open(name) }.getOrNull()
            if (opened != null) return opened
        }
        val zipNames = listOf("assets/tor/$logicalName", "assets/tor/$logicalName.gz")
        val fromApk = openFromApk(context, zipNames)
        if (fromApk != null) return fromApk
        error("GeoIP $logicalName is missing from APK assets (${assetNames.joinToString()})")
    }

    private fun openFromApk(context: Context, entryNames: List<String>): InputStream? {
        val apks = buildList {
            add(File(context.applicationInfo.sourceDir))
            context.applicationInfo.splitSourceDirs.orEmpty().forEach { add(File(it)) }
        }.filter { it.isFile }
        for (apk in apks) {
            val zip = ZipFile(apk)
            val entry = entryNames.firstNotNullOfOrNull { name -> zip.getEntry(name) }
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
        return null
    }

    internal fun decode(raw: InputStream): InputStream {
        val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, BUFFER_BYTES)
        buffered.mark(2)
        val magic = ByteArray(2)
        val read = buffered.read(magic)
        buffered.reset()
        return if (read == 2 && magic[0] == GZIP_HEADER_0 && magic[1] == GZIP_HEADER_1) {
            GZIPInputStream(buffered)
        } else {
            buffered
        }
    }

    private const val MIN_GEOIP_BYTES = 1_000_000L
    private const val BUFFER_BYTES = 64 * 1_024
    private const val GZIP_HEADER_0: Byte = 0x1f
    private const val GZIP_HEADER_1: Byte = 0x8b.toByte()
}
