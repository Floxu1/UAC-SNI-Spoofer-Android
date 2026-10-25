package com.uacspoofer.mobile.mci

import android.content.Context
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.uacspoofer.mobile.settings.DirectDomainWhitelist

/**
 * Shared Iran-direct matcher for engines that cannot use Xray geosite routing
 * (PoW SOCKS split, Tor SOCKS split). Engine 1/4 still use geosite/geoip tags
 * in generated Xray JSON; this object covers hostname decisions at the SOCKS layer.
 */
object DomesticDirect {
    @Volatile
    var enabled: Boolean = true

    private val loaded = AtomicBoolean(false)
    private val suffixes = HashSet<String>(4096)
    private val lock = Any()

    /** Popular Iranian hosts that are not under .ir (used before geosite loads). */
    private val FALLBACK = setOf(
        "aparat.com", "digikala.com", "dkstatics-public.digikala.com",
        "snapp.taxi", "filimo.com", "sheypoor.com", "torob.com",
        "eitaa.com", "bale.ai", "tasnimnews.com", "mehrnews.com",
        "asriran.com", "digistyle.com", "okala.com", "basalam.com",
    )

    fun applyEnabled(value: Boolean) {
        enabled = value
    }

    fun isDomesticHost(host: String): Boolean {
        val name = host.trim().trimEnd('.').lowercase()
        if (name.length <= 3) return false
        // Manual whitelist always bypasses the tunnel (independent of Iran-direct toggle).
        if (isWhitelisted(name)) return true
        if (!enabled) return false
        if (name.endsWith(".ir")) return true
        if (name in FALLBACK) return true
        if (!loaded.get()) return FALLBACK.any { name == it || name.endsWith(".$it") }
        if (name in suffixes) return true
        var idx = name.indexOf('.')
        while (idx >= 0 && idx + 1 < name.length) {
            val suffix = name.substring(idx + 1)
            if (suffix in FALLBACK || suffix in suffixes) return true
            idx = name.indexOf('.', idx + 1)
        }
        return false
    }

    /** Exact host or parent suffix match against the user whitelist. */
    private fun isWhitelisted(name: String): Boolean {
        val list = runCatching {
            // Lazy: no Context here — snapshot from process singleton if initialized.
            DirectDomainWhitelist.snapshotOrEmpty()
        }.getOrDefault(emptyList())
        if (list.isEmpty()) return false
        if (name in list) return true
        var idx = name.indexOf('.')
        while (idx >= 0 && idx + 1 < name.length) {
            val suffix = name.substring(idx + 1)
            if (suffix in list) return true
            idx = name.indexOf('.', idx + 1)
        }
        return false
    }

    fun ensure(context: Context) {
        DirectDomainWhitelist.get(context)
        GeoAssets.ensure(context)
        reloadFromFiles(context)
    }

    fun reloadFromFiles(context: Context) {
        val geosite = File(context.applicationContext.filesDir, GeoAssets.GEOSITE)
        if (!geosite.isFile) return
        val parsed = parseCategoryIr(geosite)
        synchronized(lock) {
            suffixes.clear()
            suffixes.addAll(parsed)
            loaded.set(true)
        }
        AppLogRepository.info(LogSource.XRAY, "DomesticDirect loaded ${parsed.size} category-ir suffixes")
    }

    /** Minimal protobuf walk: GeoSiteList entry name CATEGORY-IR / IR domain values. */
    internal fun parseCategoryIr(file: File): Set<String> {
        val data = file.readBytes()
        val out = HashSet<String>(8192)
        var i = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (i < data.size) {
                val b = data[i].toInt() and 0xff
                i++
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) error("varint")
            }
            error("eof")
        }
        while (i < data.size) {
            val key = readVarint().toInt()
            val fn = key ushr 3
            val wt = key and 7
            if (wt != 2) {
                when (wt) {
                    0 -> readVarint()
                    1 -> { i += 8 }
                    5 -> { i += 4 }
                    else -> break
                }
                continue
            }
            val len = readVarint().toInt()
            if (len < 0 || i + len > data.size) break
            val entry = data.copyOfRange(i, i + len)
            i += len
            if (fn != 1) continue
            var j = 0
            fun rv(): Long {
                var result = 0L
                var shift = 0
                while (j < entry.size) {
                    val b = entry[j].toInt() and 0xff
                    j++
                    result = result or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
                error("eof")
            }
            var name = ""
            val domains = ArrayList<String>()
            while (j < entry.size) {
                val k = rv().toInt()
                val f = k ushr 3
                val w = k and 7
                if (w != 2) {
                    when (w) {
                        0 -> rv()
                        1 -> { j += 8 }
                        5 -> { j += 4 }
                        else -> break
                    }
                    continue
                }
                val ln = rv().toInt()
                if (ln < 0 || j + ln > entry.size) break
                val chunk = entry.copyOfRange(j, j + ln)
                j += ln
                when (f) {
                    1 -> name = chunk.toString(Charsets.UTF_8)
                    2 -> {
                        var d = 0
                        fun rdv(): Long {
                            var result = 0L
                            var shift = 0
                            while (d < chunk.size) {
                                val b = chunk[d].toInt() and 0xff
                                d++
                                result = result or ((b and 0x7f).toLong() shl shift)
                                if (b and 0x80 == 0) return result
                                shift += 7
                            }
                            error("eof")
                        }
                        while (d < chunk.size) {
                            val dk = rdv().toInt()
                            val df = dk ushr 3
                            val dw = dk and 7
                            if (dw == 0) { rdv(); continue }
                            if (dw == 1) { d += 8; continue }
                            if (dw == 5) { d += 4; continue }
                            if (dw != 2) break
                            val dl = rdv().toInt()
                            if (dl < 0 || d + dl > chunk.size) break
                            val part = chunk.copyOfRange(d, d + dl)
                            d += dl
                            if (df == 2) {
                                val v = part.toString(Charsets.UTF_8).trim().trimStart('.').lowercase()
                                if (v.isNotEmpty() && v.length < 256) domains.add(v)
                            }
                        }
                    }
                }
            }
            if (name.equals("CATEGORY-IR", ignoreCase = true) || name.equals("IR", ignoreCase = true)
                || name.equals("CATEGORY-BANK-IR", ignoreCase = true)) {
                out.addAll(domains)
            }
        }
        return out
    }
}
