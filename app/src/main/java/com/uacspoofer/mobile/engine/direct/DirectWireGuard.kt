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

    suspend fun start(settings: DirectEngineSettings, excludedCarrierIp: String?): Int {
        retryNetwork = null
        val fragment = settings.adaptiveClientHello || settings.fragmentEnabled
        return startDirectH2WithFallback(
            allowRetry = fragment && excludedCarrierIp == null,
            startPrimary = { startProfile(settings, excludedCarrierIp) },
            startWithoutFragments = { startCompatibilityProfile(settings) },
        )
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
            "UAC Direct MASQUE startup timed out; retrying with H2 fragmentation off (network hint=${hint != null})",
        )
        DirectStatusStore.update(DirectPhase.BUILDING, 10, "Trying another route")
        val port = startProfile(settings, excludedCarrierIp = null, compatibilityProfile = profile)
        retryNetwork = network
        return port
    }

    fun rememberVerifiedCompatibilityRoute() {
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
    ): Int {
        val compatibility = compatibilityProfile != null
        val profile = when {
            compatibility -> "compatibility"
            excludedCarrierIp != null -> "alternate_carrier"
            else -> "primary"
        }
        val scanMode = if (compatibility) DirectScanMode.TURBO else settings.scanMode
        val fragmentation = !compatibility && (settings.adaptiveClientHello || settings.fragmentEnabled)
        val startupBudgetMs = if (compatibility) 25_000L else scanMode.startupTimeoutMs
        val startedAt = SystemClock.elapsedRealtime()
        val network = captureNetwork()
        AppLogRepository.info(
            LogSource.XRAY,
            "Direct native startup: profile=$profile scan=${scanMode.id} h2_fragmentation=$fragmentation " +
                "budget_ms=$startupBudgetMs excluded_ip=${excludedCarrierIp ?: "none"} " +
                "transport=${network?.transport ?: "unknown"} carrier=${network?.carrierClass ?: "unknown"}",
        )
        val loopback = InetAddress.getByName("127.0.0.1")
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val directory = File(service.filesDir, "direct-wireguard")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the WireGuard identity directory" }
        val carrier = File(directory, "carrier.toml")
        val config = JSONObject()
            .put("config_path", File(compatibilityProfile ?: directory, "carrier.toml").absolutePath)
            .put("masque_config_path", if (compatibility) carrier.absolutePath else null)
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
            .toString()
        val (task, previousLog) = synchronized(this) {
            check(worker?.isAlive != true) { "Previous Direct WireGuard session is still stopping" }
            DirectNative.attach(service)
            val baseline = DirectNative.lastLog()
            DirectStatusStore.beginIpScan()
            val newWorker = Thread({ DirectNative.startProxy(config) }, "direct-wireguard")
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
                DirectNative.lastError().ifBlank { "WireGuard over Direct did not become ready" }
            }
            val peer = DirectIpScan.selectedCarrierIp(lastLog) ?: "unknown"
            AppLogRepository.success(
                LogSource.XRAY,
                "Direct native ready: profile=$profile peer=$peer elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
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
    val byAddress = snapshot.lineSequence()
        .filter { it.startsWith("Direct candidate ") }
        .toList().asReversed()
        .distinctBy { it.removePrefix("Direct candidate ").substringBefore(' ') }
        .asReversed()
    val known = byAddress.filter { it.removePrefix("Direct candidate ").substringAfter(' ').startsWith("known ") }
    // Include the first cached candidates even if later ranked attempts fill the remaining log.
    val priority = (known.take(4) + known.takeLast(4)).distinct()
    val recent = byAddress.asReversed().filterNot { it in priority }.take(12 - priority.size).asReversed()
    return listOfNotNull(stage) + priority + recent
}

internal suspend fun startDirectH2WithFallback(
    allowRetry: Boolean,
    startPrimary: suspend () -> Int,
    startWithoutFragments: suspend () -> Int,
): Int = try {
    startPrimary()
} catch (error: IllegalStateException) {
    if (error is CancellationException || !allowRetry || error.message != "masque: candidate startup timed out") throw error
    currentCoroutineContext().ensureActive()
    startWithoutFragments()
}
