package com.uacspoofer.mobile.vpn

import android.content.Context
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.MciConfig
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ExitIpInfo(
    val ipAddress: String,
    val isp: String,
    val city: String,
    val region: String,
    val country: String,
    val countryCode: String,
    val provider: String,
    val fetchedAtMs: Long,
)

data class ExitIpInfoState(
    val profileId: String? = null,
    val info: ExitIpInfo? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)






class ExitIpInfoRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val settingsStore = AdvancedSettingsStore(appContext)
    private val engineModeStore = EngineModeStore.get(appContext)
    private val torEngineStore = TorEngineStore.get(appContext)
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(ExitIpInfoState())

    val state: StateFlow<ExitIpInfoState> = mutableState.asStateFlow()

    suspend fun refresh(profileId: String, force: Boolean = false) {
        refreshMutex.withLock {
            val engine = engineModeStore.snapshot()
            val exitCountry = if (engine.isTor) torEngineStore.snapshot().exitCountryCode else ""
            val lookupId = lookupId(profileId, engine.isTor, exitCountry)
            val now = System.currentTimeMillis()
            val memoryInfo = mutableState.value
                .takeIf { it.profileId == lookupId }
                ?.info
            val cachedInfo = memoryInfo ?: readCache(lookupId)
            if (!force && cachedInfo != null && now - cachedInfo.fetchedAtMs <= CACHE_TTL_MS) {
                mutableState.value = ExitIpInfoState(profileId = lookupId, info = cachedInfo)
                return
            }

            mutableState.value = ExitIpInfoState(
                profileId = lookupId,
                info = cachedInfo,
                isLoading = true,
            )
            val socks = if (engine.isTor) {
                val tor = torEngineStore.snapshot()
                MciConfig.LOCAL_SOCKS_ADDRESS to tor.socksPort
            } else {
                val settings = settingsStore.snapshot().validated()
                settings.socksAddress to settings.socksPort
            }
            val timeoutMs = if (engine.isTor) TOR_LOOKUP_TIMEOUT_MS else LOOKUP_TIMEOUT_MS
            val via = if (engine.isTor) "Tor SOCKS" else "Xray SOCKS"
            try {
                val result = withContext(Dispatchers.IO) {
                    lookupThroughSocks(socks.first, socks.second, timeoutMs)
                }
                val wanted = exitCountry.trim().lowercase()
                val got = result.countryCode.trim().lowercase()
                val mismatch = engine.isTor && wanted.isNotEmpty() && got.isNotEmpty() && got != wanted
                if (!mismatch) {
                    writeCache(lookupId, result)
                }
                mutableState.value = ExitIpInfoState(profileId = lookupId, info = result)
                AppLogRepository.info(
                    LogSource.APP,
                    "Exit IP info refreshed via $via: ip=${result.ipAddress} country=${result.countryCode} " +
                        "provider=${result.provider}" + if (mismatch) " (waiting for ExitNodes {$wanted})" else "",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val detail = error.message.orEmpty().replace(Regex("\\s+"), " ").trim().take(120)
                val message = if (detail.isBlank()) error.javaClass.simpleName else detail
                mutableState.value = ExitIpInfoState(
                    profileId = lookupId,
                    info = cachedInfo,
                    errorMessage = message,
                )
                AppLogRepository.warning(LogSource.APP, "Exit IP lookup through $via failed", error)
            }
        }
    }

    private fun lookupThroughSocks(socksAddress: String, socksPort: Int, timeoutMs: Int): ExitIpInfo {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksAddress, socksPort))
        val primary = runCatchingNonCancellation {
            parseIpWhoIs(fetchJson("https://ipwho.is/", proxy, timeoutMs))
        }
        if (primary.isSuccess) return primary.getOrThrow()
        return runCatchingNonCancellation {
            parseIpApi(fetchJson("https://ipapi.co/json/", proxy, timeoutMs))
        }.getOrElse { fallbackError ->
            primary.exceptionOrNull()?.let { fallbackError.addSuppressed(it) }
            throw fallbackError
        }
    }

    private fun fetchJson(url: String, proxy: Proxy, timeoutMs: Int): String {
        val connection = URL(url).openConnection(proxy) as HttpsURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "UAC-SNI-Spoofer-Android/0.1")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.buffered()?.use { input ->
                val output = ByteArrayOutputStream(2_048)
                val buffer = ByteArray(2_048)
                while (output.size() < MAX_RESPONSE_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - output.size()))
                    if (read < 0) break
                    if (read > 0) output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            check(code in 200..299) { "HTTP $code" }
            check(payload.isNotBlank()) { "Empty response" }
            payload
        } finally {
            connection.disconnect()
        }
    }

    private fun parseIpWhoIs(payload: String): ExitIpInfo {
        val json = JSONObject(payload)
        check(json.optBoolean("success", false)) { json.optString("message", "Lookup failed") }
        val connection = json.optJSONObject("connection")
        return requireUsable(
            ExitIpInfo(
                ipAddress = json.optString("ip"),
                isp = connection?.optString("isp").orEmpty()
                    .ifBlank { connection?.optString("org").orEmpty() },
                city = json.optString("city"),
                region = json.optString("region"),
                country = json.optString("country"),
                countryCode = json.optString("country_code").uppercase(),
                provider = "ipwho.is",
                fetchedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun parseIpApi(payload: String): ExitIpInfo {
        val json = JSONObject(payload)
        check(!json.optBoolean("error", false)) { json.optString("reason", "Lookup failed") }
        return requireUsable(
            ExitIpInfo(
                ipAddress = json.optString("ip"),
                isp = json.optString("org"),
                city = json.optString("city"),
                region = json.optString("region"),
                country = json.optString("country_name"),
                countryCode = json.optString("country_code").uppercase(),
                provider = "ipapi.co",
                fetchedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun requireUsable(info: ExitIpInfo): ExitIpInfo {
        check(info.ipAddress.isNotBlank()) { "Missing public IP" }
        return info
    }

    private fun readCache(profileId: String): ExitIpInfo? {
        val raw = prefs.getString(cacheKey(profileId), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ExitIpInfo(
                ipAddress = json.optString("ip"),
                isp = json.optString("isp"),
                city = json.optString("city"),
                region = json.optString("region"),
                country = json.optString("country"),
                countryCode = json.optString("countryCode"),
                provider = json.optString("provider"),
                fetchedAtMs = json.optLong("fetchedAtMs"),
            ).takeIf { it.ipAddress.isNotBlank() && it.fetchedAtMs > 0L }
        }.getOrNull()
    }

    private fun writeCache(profileId: String, info: ExitIpInfo) {
        prefs.edit().putString(
            cacheKey(profileId),
            JSONObject()
                .put("ip", info.ipAddress)
                .put("isp", info.isp)
                .put("city", info.city)
                .put("region", info.region)
                .put("country", info.country)
                .put("countryCode", info.countryCode)
                .put("provider", info.provider)
                .put("fetchedAtMs", info.fetchedAtMs)
                .toString(),
        ).apply()
    }

    private fun cacheKey(profileId: String): String = "$CACHE_PREFIX${profileId.hashCode().toUInt().toString(16)}"

    companion object {
        private const val PREFS = "exit_ip_info_cache_v1"
        private const val CACHE_PREFIX = "profile:"
        private const val LOOKUP_TIMEOUT_MS = 5_000
        private const val TOR_LOOKUP_TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 128 * 1_024
        private const val CACHE_TTL_MS = 10L * 60L * 1_000L
        const val TOR_LOOKUP_ID = "engine:tor_webtunnel"

        fun lookupId(profileId: String, torEngine: Boolean, exitCountryCode: String = ""): String =
            if (torEngine) {
                val country = exitCountryCode.trim().lowercase().ifBlank { "auto" }
                "$TOR_LOOKUP_ID:$country"
            } else {
                profileId
            }

        @Volatile private var instance: ExitIpInfoRepository? = null

        fun get(context: Context): ExitIpInfoRepository = instance ?: synchronized(this) {
            instance ?: ExitIpInfoRepository(context.applicationContext).also { instance = it }
        }

        private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
