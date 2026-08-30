package com.uacspoofer.mobile.ui

import java.io.File

internal data class MemoryBucket(
    val id: String,
    val bytes: Long,
)

internal object ProcMemoryMaps {
    fun readPssBuckets(smapsFile: File = File("/proc/self/smaps")): List<MemoryBucket> {
        if (!smapsFile.isFile) return emptyList()
        val totals = LinkedHashMap<String, Long>()
        var current = "other"
        runCatching {
            smapsFile.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("Pss:") -> {
                            val kb = parseKb(line)
                            totals[current] = (totals[current] ?: 0L) + kb * 1024L
                        }
                        isMappingHeader(line) -> current = classifyMapping(mappingPath(line))
                    }
                }
            }
        }
        return totals
            .map { MemoryBucket(it.key, it.value) }
            .filter { it.bytes > 0L }
            .sortedByDescending { it.bytes }
    }

    fun classifyMapping(path: String): String {
        val lower = path.lowercase()
        return when {
            "libtor.so" in lower -> "tor"
            "libwebtunnel.so" in lower -> "webtunnel"
            "libhev-socks5-tunnel.so" in lower -> "tun2socks"
            "libgojni" in lower || "libv2ray" in lower || "libxray" in lower -> "xray"
            "dalvik" in lower || lower == "[heap]" || "anon:dalvik" in lower -> "art"
            "kgsl" in lower || "mali" in lower || "renderengine" in lower ||
                "libhwui" in lower || "libegl" in lower || "libgles" in lower ||
                "libskia" in lower || "gralloc" in lower -> "graphics"
            isSystemRuntime(lower) -> "android"
            lower.endsWith(".so") -> "so:${lower.substringAfterLast('/')}"
            else -> "other"
        }
    }

    fun rankSuspects(
        latest: List<MemoryBucket>,
        slopes: Map<String, Float>,
        minSamplesReady: Boolean,
        leakSlope: Float,
    ): List<RankedMemorySuspect> {
        return latest
            .map { bucket ->
                val slope = slopes[bucket.id] ?: 0f
                RankedMemorySuspect(
                    id = bucket.id,
                    bytes = bucket.bytes,
                    slopeMbPerMin = slope,
                    growing = minSamplesReady && slope > leakSlope,
                )
            }
            .sortedWith(
                compareByDescending<RankedMemorySuspect> { if (it.growing) it.slopeMbPerMin else -1f }
                    .thenByDescending { it.slopeMbPerMin }
                    .thenByDescending { it.bytes },
            )
    }

    private fun isMappingHeader(line: String): Boolean {
        val dash = line.indexOf('-')
        if (dash <= 0) return false
        val first = line[0]
        return first in '0'..'9' || first in 'a'..'f' || first in 'A'..'F'
    }

    private fun mappingPath(header: String): String {
        val pathStart = header.indexOf('/')
        val bracket = header.indexOf('[')
        return when {
            pathStart >= 0 -> header.substring(pathStart).trim()
            bracket >= 0 -> header.substring(bracket).trim()
            else -> ""
        }
    }

    private fun parseKb(line: String): Long {
        var total = 0L
        var sawDigit = false
        for (char in line) {
            if (char in '0'..'9') {
                sawDigit = true
                total = total * 10 + (char - '0')
            } else if (sawDigit) {
                break
            }
        }
        return total
    }

    private fun isSystemRuntime(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.startsWith("libc.") ||
            name.startsWith("libm.") ||
            name.startsWith("libdl.") ||
            name.startsWith("libart.") ||
            name.startsWith("libandroid") ||
            name.startsWith("libjavacore") ||
            name.startsWith("libopenjdk") ||
            name.startsWith("libutils.") ||
            name.startsWith("libc++") ||
            "apex/com.android" in path
    }
}

internal data class RankedMemorySuspect(
    val id: String,
    val bytes: Long,
    val slopeMbPerMin: Float,
    val growing: Boolean,
)
