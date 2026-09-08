package com.uacspoofer.mobile.ai

import android.content.Context
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProxyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Picks a second exit for AI domains out of the user's own profile library and learns which
 * exits actually work.
 *
 * AI services reject the tunnel by exit country or exit reputation, so the fix is a different
 * exit rather than a different transport. Every candidate is one of the profiles the user
 * already has, dialled over the Cloudflare edge the main tunnel just proved reachable, so no
 * new server, credential or user action is involved.
 *
 * Each connect carries one candidate and then measures it through the local SOCKS inbound,
 * which forces the request into Xray and through the AI routing rule. A candidate that
 * answers is remembered and reused; one that is refused is written off and the next connect
 * tries the following profile. When nothing is left the plan is null and AI domains ride the
 * main tunnel exactly as they did before this feature existed.
 */
class AiRouteController(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val profileStore = ProfileStore(appContext)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            if (value) forgetVerdicts()
        }

    /**
     * Never touches the network so the connect path pays nothing. A known-good exit wins,
     * otherwise the first untried profile is carried so this connect doubles as its trial.
     */
    fun planFor(activeProfile: ProxyProfile, edge: MciEdge): AiRoutePlan? {
        if (!enabled) return null
        val candidates = candidates(activeProfile)
        if (candidates.isEmpty()) return null
        val chosen = candidates.firstOrNull { verdictOf(it.id) == VERDICT_GOOD }
            ?: candidates.firstOrNull { verdictOf(it.id) == VERDICT_UNTRIED }
            ?: return null
        return AiRoutePlan(chosen, edge).takeIf(AiRoutePlan::isUsable)
    }

    /**
     * The active profile is the one AI services just refused, so it is never a candidate.
     * Order is stable so the rotation makes progress instead of retrying the same profile.
     */
    private fun candidates(activeProfile: ProxyProfile): List<ProxyProfile> =
        runCatching {
            profileStore.snapshot().allProfiles
                .filter { it.id != activeProfile.id && it.id.isNotBlank() }
                .filter { verdictOf(it.id) != VERDICT_BAD }
                .take(MAX_CANDIDATES)
        }.getOrDefault(emptyList())

    /**
     * Confirms the carried exit really opens AI hosts and records the verdict. Probing goes
     * through the local SOCKS inbound so it cannot slip out over IPv6 the way a plain
     * request can while the TUN carries IPv4 only.
     */
    suspend fun verifyAfterConnect(plan: AiRoutePlan?, socksHost: String, socksPort: Int) {
        if (plan == null) {
            AppLogRepository.info(LogSource.ADAPTIVE, "AI exit not applied; AI domains use the main tunnel")
            return
        }
        val reachable = withContext(Dispatchers.IO) {
            withTimeoutOrNull(PROBE_BUDGET_MS) { probeThroughSocks(socksHost, socksPort) }
        }
        when (reachable) {
            true -> {
                recordVerdict(plan.profile.id, VERDICT_GOOD)
                AppLogRepository.success(
                    LogSource.ADAPTIVE,
                    "AI exit confirmed via ${plan.profile.name}; AI domains now use it",
                )
            }
            else -> {
                recordVerdict(plan.profile.id, VERDICT_BAD)
                AppLogRepository.warning(
                    LogSource.ADAPTIVE,
                    "AI exit ${plan.profile.name} refused; the next connect tries another profile",
                )
            }
        }
    }

    /**
     * A single AI host answering over the hop is the whole test. Status is what matters:
     * a country or reputation block answers 403 while a working exit answers 2xx or 3xx.
     */
    private fun probeThroughSocks(socksHost: String, socksPort: Int): Boolean {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(socksHost, socksPort))
        return AiRouteDomains.REACHABILITY_PROBES.any { url ->
            runCatching { statusThrough(url, proxy) in 200..399 }.getOrDefault(false)
        }
    }

    private fun statusThrough(url: String, proxy: Proxy): Int {
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        return try {
            connection.connectTimeout = PROBE_CONNECT_TIMEOUT_MS
            connection.readTimeout = PROBE_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.responseCode
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun verdictOf(profileId: String): Int = prefs.getInt(KEY_VERDICT_PREFIX + profileId, VERDICT_UNTRIED)

    private fun recordVerdict(profileId: String, verdict: Int) {
        prefs.edit().putInt(KEY_VERDICT_PREFIX + profileId, verdict).apply()
    }

    /** Verdicts age out with the network, so a manual re-enable starts the search over. */
    private fun forgetVerdicts() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_VERDICT_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "uac_ai_clean_hop"
        const val KEY_ENABLED = "enabled"
        const val KEY_VERDICT_PREFIX = "verdict:"
        const val VERDICT_UNTRIED = 0
        const val VERDICT_GOOD = 1
        const val VERDICT_BAD = 2
        const val MAX_CANDIDATES = 12
        const val PROBE_BUDGET_MS = 15_000L
        const val PROBE_CONNECT_TIMEOUT_MS = 5_000
        const val PROBE_READ_TIMEOUT_MS = 6_000
    }
}
