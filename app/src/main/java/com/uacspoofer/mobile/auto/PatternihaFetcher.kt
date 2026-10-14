package com.uacspoofer.mobile.auto

import android.net.Network
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Fetches the Patterniha list (raw GitHub, then jsDelivr, then the GitHub contents API) with ETag,
 * keeps the last good copy on disk, and refreshes at most once a day. Only called during connect.
 */
class PatternihaFetcher(private val store: AutoConfigStore, private val network: Network?) {
    data class Result(val text: String, val source: String)

    suspend fun load(now: Long = System.currentTimeMillis()): Result? = withContext(Dispatchers.IO) {
        val cached = store.cachedList()
        if (cached != null && now - store.listFetchedAt() in 0..REFRESH_MS) return@withContext Result(cached, "cache")
        for (url in PatternihaList.MIRRORS) {
            coroutineContext.ensureActive()
            val fetched = runCatching { fetch(url, cached != null, now) }
                .onFailure { log("Patterniha list fetch failed via ${host(url)}: ${it.message}") }
                .getOrNull()
            if (fetched != null) return@withContext fetched
        }
        cached?.let { Result(it, "stale-cache") }
    }

    private fun fetch(url: String, haveCache: Boolean, now: Long): Result? {
        val connection = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "UAC-SNI-Spoofer-Android/Auto")
            connection.setRequestProperty(
                "Accept",
                if (url == PatternihaList.API_URL) "application/vnd.github.raw" else "text/plain,*/*",
            )
            if (haveCache) store.listEtag(url)?.let { connection.setRequestProperty("If-None-Match", it) }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && haveCache) {
                store.touchList(now)
                log("Patterniha list unchanged (304) via ${host(url)}")
                return store.cachedList()?.let { Result(it, "304 ${host(url)}") }
            }
            check(code in 200..299) { "HTTP $code" }
            val text = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(out.size() + n <= MAX_BYTES) { "list too large" }
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
            check(PatternihaList.parse(text).isNotEmpty()) { "no usable configs in list" }
            store.saveList(text, url, connection.getHeaderField("ETag"), now)
            log("Patterniha list fetched via ${host(url)} (${text.length} chars)")
            return Result(text, host(url))
        } finally {
            connection.disconnect()
        }
    }

    private fun host(url: String) = url.substringAfter("//").substringBefore('/')

    private fun log(message: String) = runCatching { AppLogRepository.info(LogSource.ADAPTIVE, message) }

    companion object {
        const val REFRESH_MS = 24L * 60 * 60 * 1000
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_BYTES = 8 * 1024 * 1024
    }
}