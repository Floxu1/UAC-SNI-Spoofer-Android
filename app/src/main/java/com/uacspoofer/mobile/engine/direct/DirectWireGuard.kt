package com.uacspoofer.mobile.engine.direct

import android.content.Context
import android.net.VpnService
import android.os.SystemClock
import com.uacspoofer.mobile.engine.pow.PowCoreConfig
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.vpn.NetworkFingerprint
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

internal class DirectWireGuard(private val service: VpnService) {
    @Volatile private var worker: Thread? = null
    private val networkResolver by lazy { NetworkFingerprintResolver(service) }
    private val retryPrefs by lazy { service.getSharedPreferences("direct_h2_startup", Context.MODE_PRIVATE) }
    private var retryNetwork: NetworkFingerprint? = null
    private var pendingVerification: Triple<NetworkFingerprint, String, JSONObject>? = null

    suspend fun start(settings: DirectEngineSettings, excludedCarrierIp: String?): Int {
        retryNetwork = null
        pendingVerification = null
        if (excludedCarrierIp != null) {
            return tryRememberedProfile(settings, excludedCarrierIp) ?: startProfile(settings, excludedCarrierIp)
        }
        val fragment = settings.adaptiveClientHello || settings.fragmentEnabled
        return startDirectH2WithFallback(
            allowRetry = fragment,
            startPrimary = { startProfile(settings, excludedCarrierIp) },
            startWithoutFragments = { startCompatibilityProfile(settings) },
            startRemembered = { tryRememberedProfile(settings, null) },
        )
    }

    private suspend fun tryRememberedProfile(settings: DirectEngineSettings, excludedCarrierIp: String?): Int? {
        val remembered = DirectH2Profiles.find(service, captureNetwork(), excludedCarrierIp)
        if (remembered == null) {
            AppLogRepository.debug(LogSource.XRAY, "Direct fallback decision: no verified H2 profile for this network")
            return null
        }
        currentCoroutineContext().ensureActive()
        DirectStatusStore.update(DirectPhase.BUILDING, 10, "Trying a verified route")
        return try {
            startProfile(settings, excludedCarrierIp, remembered = remembered)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            val reason = when (error.message) {
                "Network changed before trying verified H2 route" -> "network_changed"
                "Verified H2 identity is missing or was changed since the last successful connection" -> "identity_changed_or_missing"
                "other: Verified HTTP/2 identity is missing or invalid" -> "identity_unreadable"
                else -> "native_startup_failed"
            }
            AppLogRepository.warning(
                LogSource.XRAY,
                "Direct fallback decision: remembered ${remembered.source} H2 route failed reason=$reason; returning to route search",
            )
            null
        }
    }

    private suspend fun startCompatibilityProfile(settings: DirectEngineSettings): Int {
        val network = captureNetwork()
        val hint = if (network != null && retryPrefs.getString("network", null) == network.exactStorageKey()) {
            retryPrefs.getString("peer", null)
        } else {
            null
        }
        val profile = File(service.filesDir, "direct-wireguard/h2-startup-fallback")
        check(profile.isDirectory || profile.mkdirs()) { "Could not create Direct fallback directory" }
        // The native core reads this list before the shared PoW hints. Identities stay in the main directory.
        File(profile, PowCoreConfig.cleanH2File(service).name).writeText(
            JSONObject().put("peers", JSONArray(listOfNotNull(hint))).toString(),
        )
        AppLogRepository.warning(
            LogSource.XRAY,
            "Direct fallback decision: searching with H2 fragmentation off (network hint=${hint != null})",
        )
        DirectStatusStore.update(DirectPhase.BUILDING, 10, "Trying another route")
        val port = startProfile(settings, excludedCarrierIp = null, compatibilityProfile = profile)
        retryNetwork = network
        return port
    }

    fun rememberVerifiedCompatibilityRoute() {
        val verified = pendingVerification
        pendingVerification = null
        if (verified != null) {
            val (original, peer, options) = verified
            val current = captureNetwork()
            if (current != null && original.isSameUnderlyingNetwork(current) && original.key == current.key) {
                runCatching { DirectH2Profiles.save(service, current, "direct", peer, options) }
                    .onSuccess {
                        AppLogRepository.debug(LogSource.XRAY, "Direct learned H2 route: peer=$peer payload_verified=true")
                    }
                    .onFailure {
                        AppLogRepository.debug(LogSource.XRAY, "Direct verified H2 route could not be recorded")
                    }
            }
        }
        val original = retryNetwork ?: return
        retryNetwork = null
        val current = captureNetwork() ?: return
        if (!original.isSameUnderlyingNetwork(current) || original.key != current.key) return
        val peer = DirectStatusStore.status.value.ipScan?.checks
            ?.firstOrNull { it.stage == DirectIpStage.SELECTED }?.address ?: return
        retryPrefs.edit().putString("network", current.exactStorageKey()).putString("peer", peer).apply()
    }

    private fun captureNetwork(): NetworkFingerprint? =
        runCatching { networkResolver.capture() }.getOrNull()?.takeIf { it.networkHandle >= 0L }

    private suspend fun startProfile(
        settings: DirectEngineSettings,
        excludedCarrierIp: String?,
        compatibilityProfile: File? = null,
        remembered: DirectH2Profiles.Profile? = null,
    ): Int {
        val compatibility = compatibilityProfile != null
        val profile = when {
            remembered != null -> "verified_${remembered.source}_h2"
            compatibility -> "compatibility"
            excludedCarrierIp != null -> "alternate_carrier"
            else -> "primary"
        }
        val scanMode = if (compatibility || remembered != null) DirectScanMode.TURBO else settings.scanMode
        val fragmentation = remembered?.settings?.optBoolean("h2_fragmentation")
            ?: (!compatibility && (settings.adaptiveClientHello || settings.fragmentEnabled))
        val startupBudgetMs = if (compatibility || remembered != null) 25_000L else scanMode.startupTimeoutMs
        val startedAt = SystemClock.elapsedRealtime()
        val network = captureNetwork()
        if (remembered != null) {
            check(remembered.networkKey == network?.exactStorageKey()) { "Network changed before trying verified H2 route" }
        }
        val powIdentity = if (remembered?.source == "pow") {
            PowCoreConfig.identityPath(service).also { file ->
                check(file.isFile && file.lastModified() in 1L..remembered.verifiedAt) {
                    "Verified H2 identity is missing or was changed since the last successful connection"
                }
            }
        } else null
        AppLogRepository.info(
            LogSource.XRAY,
            "Direct native startup: profile=$profile scan=${scanMode.id} h2_fragmentation=$fragmentation " +
                "budget_ms=$startupBudgetMs excluded_ip=${excludedCarrierIp ?: "none"} " +
                "transport=${network?.transport ?: "unknown"} carrier=${network?.carrierClass ?: "unknown"} " +
                "peer=${remembered?.peer ?: "auto"} h2_fingerprint=${remembered?.settings?.optString("h2_fingerprint")?.ifBlank { "default" } ?: "default"} " +
                "h2_identity=${if (powIdentity != null) "verified_pow" else "direct"}",
        )
        if (network != null) {
            AppLogRepository.debug(
                LogSource.XRAY,
                "Direct network: mtu=${network.mtu} ipv4=${network.hasIpv4} ipv6=${network.hasIpv6} " +
                    "validated=${network.validated} captive_portal=${network.captivePortal} dns_count=${network.dnsCount}",
            )
        }
        val loopback = InetAddress.getByName("127.0.0.1")
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val directory = File(service.filesDir, "direct-wireguard")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the WireGuard identity directory" }
        val carrier = File(directory, "carrier.toml")
        val config = JSONObject()
            .put("config_path", File(compatibilityProfile ?: directory, "carrier.toml").absolutePath)
            .put("masque_config_path", powIdentity?.absolutePath ?: carrier.absolutePath.takeIf { compatibility })
            .put("wireguard_config_path", File(directory, "wireguard.toml").absolutePath)
            .put("protocol", "masque")
            .put("masque_transport", "h2")
            .put("scan_mode", scanMode.id)
            .put("direct_retry_skip_ip", excludedCarrierIp)
            .put("listen", "127.0.0.1:$port")
            .put("endpoint_cache_path", PowCoreConfig.identityPath(service).absolutePath)
            .put("h2_fragmentation", fragmentation)
            .put("api_camo", "auto")
            .put("log_level", "info")
            .apply { remembered?.applyTo(this) }
        val (task, previousLog) = synchronized(this) {
            check(worker?.isAlive != true) { "Previous Direct WireGuard session is still stopping" }
            DirectNative.attach(service)
            val baseline = DirectNative.lastLog()
            DirectStatusStore.beginIpScan()
            val newWorker = Thread({ DirectNative.startProxy(config.toString()) }, "direct-wireguard")
            worker = newWorker
            try {
                newWorker.start()
            } catch (error: Throwable) {
                worker = null
                DirectNative.detach()
                throw error
            }
            newWorker to baseline
        }
        var lastLog = previousLog
        fun collectProgress() {
            val log = DirectNative.lastLog()
            if (log == lastLog) return
            lastLog = log
            DirectIpScan.fromNativeLog(log)?.let(DirectStatusStore::updateIpScan)
        }
        var budgetExpired = false
        try {
            val ready = withTimeoutOrNull(startupBudgetMs) {
                while (task.isAlive) {
                    collectProgress()
                    if (DirectNative.isReady() && runCatching {
                            Socket().use { it.connect(InetSocketAddress(loopback, port), 250) }
                        }.isSuccess
                    ) return@withTimeoutOrNull true
                    delay(200)
                }
                false
            }
            budgetExpired = ready == null
            collectProgress()
            check(ready == true) {
                if (budgetExpired) "Direct carrier startup budget expired"
                else DirectNative.lastError().ifBlank { "WireGuard over Direct did not become ready" }
            }
            val peer = DirectIpScan.selectedCarrierIp(lastLog)
            if (peer != null && network != null && powIdentity == null) {
                pendingVerification = Triple(network, peer, config)
            }
            AppLogRepository.success(
                LogSource.XRAY,
                "Direct native ready: profile=$profile peer=${peer ?: "unknown"} h2=ok wireguard=ok socks=ready " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            lastLog.lineSequence().lastOrNull { it.startsWith("Direct range summary:") }?.let {
                AppLogRepository.debug(LogSource.XRAY, it)
            }
            return port
        } catch (error: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            stopWorker(task)
            if (error !is CancellationException) {
                AppLogRepository.warning(
                    LogSource.XRAY,
                    "Direct native startup failed: profile=$profile elapsed_ms=$elapsed budget_expired=$budgetExpired",
                )
                val snapshot = runCatching { DirectNative.lastLog() }.getOrDefault("")
                    .takeUnless { it == previousLog }.orEmpty()
                directStartupReport(snapshot).forEach { AppLogRepository.debug(LogSource.XRAY, it) }
            }
            throw error
        }
    }

    fun isRunning() = worker?.isAlive == true && DirectNative.isRunning()

    fun selectedCarrierIp(): String? = DirectIpScan.selectedCarrierIp(DirectNative.lastLog())

    fun stop() {
        worker?.let(::stopWorker)
    }

    @Synchronized
    private fun stopWorker(task: Thread) {
        if (worker !== task) return
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (task.isAlive) {
            DirectNative.stop()
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            task.join(minOf(remaining, 100L))
        }
        if (!task.isAlive) {
            DirectNative.detach()
            worker = null
        }
    }
}

internal fun directStartupReport(snapshot: String): List<String> {
    val stage = snapshot.lineSequence().lastOrNull { it.startsWith("Direct startup stage=") }
    val rangeSummary = snapshot.lineSequence().lastOrNull { it.startsWith("Direct range summary:") }
    val identity = snapshot.lineSequence().lastOrNull { it.startsWith("Direct H2 identity source=") }
    val byAddress = snapshot.lineSequence()
        .filter { it.startsWith("Direct candidate ") }
        .toList().asReversed()
        .distinctBy { it.removePrefix("Direct candidate ").substringBefore(' ') }
        .asReversed()
    val known = byAddress.filter { it.removePrefix("Direct candidate ").substringAfter(' ').startsWith("known ") }
    // Include the first cached candidates even if later ranked attempts fill the remaining log.
    val priority = (known.take(4) + known.takeLast(4)).distinct()
    val recent = byAddress.asReversed().filterNot { it in priority }.take(12 - priority.size).asReversed()
    return listOfNotNull(identity, stage, rangeSummary) + priority + recent
}

internal suspend fun startDirectH2WithFallback(
    allowRetry: Boolean,
    startPrimary: suspend () -> Int,
    startWithoutFragments: suspend () -> Int,
    startRemembered: suspend () -> Int? = { null },
): Int = try {
    startPrimary()
} catch (error: IllegalStateException) {
    val reason = error.message.orEmpty()
    val carrierFailed = reason.startsWith("masque:") || reason.startsWith("tls:") ||
        reason == "other: No usable Direct WireGuard path" || reason == "Direct carrier startup budget expired" ||
        reason.startsWith("other: WireGuard dataplane verify timed out", ignoreCase = true)
    if (error is CancellationException || !carrierFailed) throw error
    currentCoroutineContext().ensureActive()
    startRemembered() ?: if (allowRetry) startWithoutFragments() else throw error
}
