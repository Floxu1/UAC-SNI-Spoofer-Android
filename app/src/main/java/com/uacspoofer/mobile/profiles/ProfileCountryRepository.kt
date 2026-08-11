package com.uacspoofer.mobile.profiles

import android.content.Context
import android.util.Log
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ProfileEndpoint(val host: String, val port: Int)

enum class CountryResolutionSource(val label: String) {
    PROFILE_METADATA("profile-metadata"),
    CACHE("geoip-cache"),
    GEOIP("geoip"),
    UNKNOWN("unknown"),
}

data class ProfileCountryResolution(
    val profileId: String,
    val endpoint: ProfileEndpoint,
    val resolvedIp: String?,
    val country: CountryMetadata,
    val source: CountryResolutionSource,
    val reason: String? = null,
    val resolvedAtMs: Long = System.currentTimeMillis(),
)







class ProfileCountryRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val endpointLocks = ConcurrentHashMap<String, Mutex>()
    private val stateLock = Any()
    private val mutableResolutions = MutableStateFlow<Map<String, ProfileCountryResolution>>(emptyMap())

    val resolutions: StateFlow<Map<String, ProfileCountryResolution>> = mutableResolutions.asStateFlow()

    suspend fun resolve(profile: ProxyProfile, endpoint: ProfileEndpoint): ProfileCountryResolution =
        withContext(Dispatchers.IO) {
            val cleanEndpoint = endpoint.copy(host = endpoint.host.trim(), port = endpoint.port.coerceIn(1, 65_535))
            val stateKey = stateKey(profile, cleanEndpoint)
            currentResolution(stateKey)?.let { return@withContext it }

            endpointLocks.computeIfAbsent(endpointKey(cleanEndpoint)) { Mutex() }.withLock {
                currentResolution(stateKey)?.let { return@withLock it }

                if (profile.country.isKnown) {
                    val resolvedIp = runCatchingNonCancellation { resolvePublicIp(cleanEndpoint.host) }.getOrNull()
                    currentCoroutineContext().ensureActive()
                    return@withLock publish(
                        stateKey,
                        ProfileCountryResolution(
                            profileId = profile.id,
                            endpoint = cleanEndpoint,
                            resolvedIp = resolvedIp,
                            country = profile.country,
                            source = CountryResolutionSource.PROFILE_METADATA,
                        ),
                        profile,
                    )
                }

                if (cleanEndpoint.host.isBlank()) {
                    return@withLock unknown(stateKey, profile, cleanEndpoint, null, "no-host")
                }

                val resolvedIp = runCatchingNonCancellation { resolvePublicIp(cleanEndpoint.host) }
                    .getOrElse { error ->
                        currentCoroutineContext().ensureActive()
                        return@withLock unknown(
                            stateKey,
                            profile,
                            cleanEndpoint,
                            null,
                            failureReason("dns-failed", error),
                        )
                    }
                if (resolvedIp == null) {
                    currentCoroutineContext().ensureActive()
                    return@withLock unknown(stateKey, profile, cleanEndpoint, null, "no-public-ip")
                }

                currentCoroutineContext().ensureActive()
                readCache(resolvedIp)?.let { cached ->
                    currentCoroutineContext().ensureActive()
                    return@withLock publish(
                        stateKey,
                        ProfileCountryResolution(
                            profileId = profile.id,
                            endpoint = cleanEndpoint,
                            resolvedIp = resolvedIp,
                            country = cached,
                            source = CountryResolutionSource.CACHE,
                        ),
                        profile,
                    )
                }

                val lookup = runCatchingNonCancellation { lookupCountry(resolvedIp) }
                    .getOrElse { error ->
                        currentCoroutineContext().ensureActive()
                        return@withLock unknown(
                            stateKey,
                            profile,
                            cleanEndpoint,
                            resolvedIp,
                            failureReason("geo-failed", error),
                        )
                    }

                currentCoroutineContext().ensureActive()
                if (!lookup.isKnown) {
                    return@withLock unknown(stateKey, profile, cleanEndpoint, resolvedIp, "no-geo-result")
                }

                writeCache(resolvedIp, lookup)
                publish(
                    stateKey,
                    ProfileCountryResolution(
                        profileId = profile.id,
                        endpoint = cleanEndpoint,
                        resolvedIp = resolvedIp,
                        country = lookup,
                        source = CountryResolutionSource.GEOIP,
                    ),
                    profile,
                )
            }
        }

    fun resolutionFor(profile: ProxyProfile, endpoint: ProfileEndpoint): ProfileCountryResolution? =
        currentResolution(stateKey(profile, endpoint))

    private fun currentResolution(stateKey: String): ProfileCountryResolution? {
        val resolution = mutableResolutions.value[stateKey] ?: return null
        if (resolution.country.isKnown) return resolution
        return resolution.takeIf { System.currentTimeMillis() - it.resolvedAtMs < NEGATIVE_CACHE_TTL_MS }
    }

    private fun resolvePublicIp(host: String): String? {
        val addresses = InetAddress.getAllByName(host)
        val address = addresses.firstOrNull { it is Inet4Address && it.isPublicAddress() }
            ?: addresses.firstOrNull { it.isPublicAddress() }
        return address?.hostAddress
    }

    private fun InetAddress.isPublicAddress(): Boolean =
        !isAnyLocalAddress && !isLoopbackAddress && !isLinkLocalAddress && !isSiteLocalAddress && !isMulticastAddress

    private fun lookupCountry(ip: String): CountryMetadata {
        val connection = (URL("https://ipwho.is/$ip?fields=success,country_code,country,message").openConnection() as HttpsURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = LOOKUP_TIMEOUT_MS
            connection.readTimeout = LOOKUP_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "UAC-SNI-Spoofer-Android/0.1")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            parseCountryLookup(payload)
        } finally {
            connection.disconnect()
        }
    }

    private fun readCache(ip: String): CountryMetadata? {
        val raw = prefs.getString("$CACHE_PREFIX$ip", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val timestamp = json.optLong("timestamp", 0L)
            if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) return null
            CountryMetadata.resolve(json.optString("code"), json.optString("name")).takeIf { it.isKnown }
        }.getOrNull()
    }

    private fun writeCache(ip: String, country: CountryMetadata) {
        prefs.edit().putString(
            "$CACHE_PREFIX$ip",
            JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("code", country.countryCode)
                .put("name", country.countryName)
                .toString(),
        ).apply()
    }

    private fun unknown(
        stateKey: String,
        profile: ProxyProfile,
        endpoint: ProfileEndpoint,
        resolvedIp: String?,
        reason: String,
    ): ProfileCountryResolution = publish(
        stateKey,
        ProfileCountryResolution(
            profileId = profile.id,
            endpoint = endpoint,
            resolvedIp = resolvedIp,
            country = CountryMetadata.UNKNOWN,
            source = CountryResolutionSource.UNKNOWN,
            reason = reason,
        ),
        profile,
    )

    private fun publish(
        stateKey: String,
        resolution: ProfileCountryResolution,
        profile: ProxyProfile,
    ): ProfileCountryResolution {
        synchronized(stateLock) {
            mutableResolutions.value = mutableResolutions.value + (stateKey to resolution)
        }
        val message = buildString {
            append("Country config=${profile.name}")
            append(" host=${resolution.endpoint.host}")
            append(" resolvedIp=${resolution.resolvedIp ?: "-"}")
            append(" countrySource=${resolution.source.label}")
            if (resolution.country.isKnown) {
                append(" countryCode=${resolution.country.countryCode}")
                append(" countryName=${resolution.country.countryName}")
            } else {
                append(" reason=${resolution.reason ?: "unknown"}")
            }
        }
        if (resolution.country.isKnown) {
            AppLogRepository.info(LogSource.APP, message)
            Log.d(TAG, message)
        } else {
            AppLogRepository.warning(LogSource.APP, message)
            Log.w(TAG, message)
        }
        return resolution
    }

    companion object {
        private const val TAG = "ProfileCountry"
        private const val PREFS = "profile_country_cache_v1"
        private const val CACHE_PREFIX = "geo:"
        private const val LOOKUP_TIMEOUT_MS = 4_500
        private const val CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val NEGATIVE_CACHE_TTL_MS = 60_000L

        @Volatile private var instance: ProfileCountryRepository? = null

        fun get(context: Context): ProfileCountryRepository = instance ?: synchronized(this) {
            instance ?: ProfileCountryRepository(context.applicationContext).also { instance = it }
        }

        internal fun parseCountryLookup(payload: String): CountryMetadata {
            val json = JSONObject(payload)
            if (!json.optBoolean("success", false)) return CountryMetadata.UNKNOWN
            return CountryMetadata.resolve(json.optString("country_code"), json.optString("country"))
        }

        private fun stateKey(profile: ProxyProfile, endpoint: ProfileEndpoint): String =
            "${profile.id}:${profile.country.countryCode.orEmpty()}@${endpointKey(endpoint)}"

        private fun endpointKey(endpoint: ProfileEndpoint): String =
            "${endpoint.host.trim().lowercase()}:${endpoint.port}"

        private fun failureReason(prefix: String, error: Throwable): String {
            val detail = error.message.orEmpty().replace(Regex("\\s+"), " ").trim().take(96)
            return if (detail.isBlank()) "$prefix:${error.javaClass.simpleName}" else "$prefix:${error.javaClass.simpleName}:$detail"
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
