package com.uacspoofer.mobile.engine.direct

import android.content.Context
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.DomesticDirect
import com.uacspoofer.mobile.mci.GeoAssets
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.vpn.TunStats
import go.Seq
import java.io.File
import java.net.ServerSocket
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Runs engine 4 on the same bundled Xray core engine 1 uses (libv2ray for the TUN, libxray.so
 * for proxy mode), with a config that has no server at all.
 */
class DirectConnectionCoordinator(private val context: Context) {
    private var controller: CoreController? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var process: Process? = null
    private var up = 0L
    private var down = 0L
    @Volatile private var port = 0

    @Synchronized
    fun connect(
        advanced: AdvancedSettingsData,
        proxyMode: Boolean,
        establishTun: () -> ParcelFileDescriptor?,
    ) {
        stopLocked()
        val settings = DirectEngineStore.get(context).snapshot()
        val s = advanced.validated()
        port = s.socksPort
        DirectStatusStore.update(DirectPhase.BUILDING, 20, "Building serverless config (variant ${settings.variant})")
        AppLogRepository.info(LogSource.XRAY, "UAC Direct: fronting=${settings.googleFronting} ech=${settings.echEnabled} fragment=${settings.fragmentEnabled} variant=${settings.variant}")
        DirectStatusStore.update(DirectPhase.STARTING, 50, "Starting Xray core (direct only, no server)")
        val attempt = runCatching { start(settings, s, proxyMode, noise = true, establishTun) }
        if (attempt.isFailure && settings.udpNoise) {
            AppLogRepository.warning(LogSource.XRAY, "UAC Direct: start with UDP noise failed, retrying without it", attempt.exceptionOrNull())
            stopLocked()
            start(settings, s, proxyMode, noise = false, establishTun)
        } else {
            attempt.getOrThrow()
        }
        DirectStatusStore.update(DirectPhase.CHECKING, 75, "Checking real reachability through the tunnel")
        val probe = verify(s.socksAddress, s.socksPort, mitm = settings.googleFronting || settings.echEnabled)
        if (!probe.ok) {
            stopLocked()
            error("No site answered through UAC Direct (${probe.detail}). DNS or the ClientHello split is blocked on this network; try variant B or other split settings.")
        }
        AppLogRepository.success(LogSource.XRAY, "UAC Direct probe passed: ${probe.detail}")
    }

    data class ProbeResult(val ok: Boolean, val detail: String)

    /**
     * Real end-to-end check: HTTPS GETs through the core's own SOCKS inbound, with the hostname
     * resolved by the core (so DNS and the ClientHello split are both exercised). Connected is only
     * reported when at least one foreign HTTPS site answers.
     */
    private fun verify(address: String, port: Int, mitm: Boolean): ProbeResult {
        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved(address, port))
        // Fragment-only mode has no MITM: verify against the system store, not just our CA.
        val trustMitm = if (mitm) runCatching { sslContextTrustingDirectCa() }.getOrNull() else null
        val results = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + PROBE_BUDGET_MS
        for (round in 0 until 2) {
            for (url in PROBE_URLS) {
                if (System.currentTimeMillis() > deadline) break
                val started = System.currentTimeMillis()
                val outcome = runCatching {
                    val conn = (java.net.URL(url).openConnection(proxy) as javax.net.ssl.HttpsURLConnection).apply {
                        connectTimeout = PROBE_TIMEOUT_MS
                        readTimeout = PROBE_TIMEOUT_MS
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                        if (trustMitm != null) {
                            sslSocketFactory = trustMitm.socketFactory
                            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                        }
                    }
                    try { conn.responseCode } finally { conn.disconnect() }
                }
                val host = java.net.URL(url).host
                val ms = System.currentTimeMillis() - started
                outcome.onSuccess { code ->
                    results += "$host=$code/${ms}ms"
                    if (code in 100..599) return ProbeResult(true, results.joinToString(" "))
                }.onFailure { results += "$host=${it.javaClass.simpleName}" }
            }
            Thread.sleep(500)
        }
        return ProbeResult(false, results.joinToString(" "))
    }

    /** Trust the per-install MITM CA so the connect probe can speak HTTPS through the decrypt tunnel. */
    private fun sslContextTrustingDirectCa(): javax.net.ssl.SSLContext {
        val material = DirectCa.ensure(context)
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val pem = material.certificatePemLines.joinToString("\n")
        val cert = cf.generateCertificate(pem.byteInputStream()) as java.security.cert.X509Certificate
        val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setCertificateEntry("uac-direct-ca", cert)
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return javax.net.ssl.SSLContext.getInstance("TLS").also { it.init(null, tmf.trustManagers, null) }
    }

    private fun start(
        settings: DirectEngineSettings,
        s: AdvancedSettingsData,
        proxyMode: Boolean,
        noise: Boolean,
        establishTun: () -> ParcelFileDescriptor?,
    ) {
        val ca = if (settings.googleFronting || settings.echEnabled) DirectCa.ensure(context) else null
        val h11 = if (ca != null) freeLoopbackPort() else 0
        val h211 = if (ca != null) freeLoopbackPort() else 0
        AppLogRepository.debug(LogSource.XRAY, "UAC Direct MITM decrypt ports h11=$h11 h211=$h211")
        DomesticDirect.ensure(context)
        DomesticDirect.applyEnabled(NetworkGuardStore.get(context).snapshot().iranDirect)
        // GeoAssets.ensure via DomesticDirect
        val config = DirectXrayConfig.build(
            settings, s.socksAddress, s.socksPort, s.tunMtu,
            nativeTun = !proxyMode, ca = ca, noiseAllowed = noise,
            mitmH11Port = h11, mitmH211Port = h211,
        )
        File(context.filesDir, "xray-direct.json").writeText(config)
        if (proxyMode) {
            val binary = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            check(binary.isFile) { "Xray binary missing" }
            val p = ProcessBuilder(binary.absolutePath, "run", "-config", File(context.filesDir, "xray-direct.json").absolutePath)
                .directory(context.filesDir).redirectErrorStream(true).start()
            process = p
            Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { AppLogRepository.info(LogSource.XRAY, it) } }
            }.apply { isDaemon = true }.start()
            Thread.sleep(600)
            check(isAliveCompat(p)) { "Xray exited during startup" }
            return
        }
        initRuntime(context)
        val tun = checkNotNull(establishTun()) { "VpnService.Builder.establish returned null" }
        descriptor = tun
        val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(code: Long, message: String?): Long {
                if (!message.isNullOrBlank()) AppLogRepository.debug(LogSource.XRAY, "Direct status=$code: $message")
                return 0L
            }
        })
        controller = core
        core.startLoop(config, tun.fd)
        check(core.isRunning) { "Native Xray core did not enter running state" }
        AppLogRepository.info(LogSource.TUN, "UAC Direct TUN started (MTU ${s.tunMtu}, noise=$noise)")
    }

    /**
     * Rebuild Direct routing on the existing TUN fd (Pause/Resume). Does not call establishTun.
     */
    @Synchronized
    fun reloadKeepingTun(advanced: AdvancedSettingsData) {
        val tun = checkNotNull(descriptor) { "Direct TUN is not established" }
        val settings = DirectEngineStore.get(context).snapshot()
        val s = advanced.validated()
        val ca = if (settings.googleFronting || settings.echEnabled) DirectCa.ensure(context) else null
        val h11 = if (ca != null) freeLoopbackPort() else 0
        val h211 = if (ca != null) freeLoopbackPort() else 0
        DomesticDirect.ensure(context)
        DomesticDirect.applyEnabled(NetworkGuardStore.get(context).snapshot().iranDirect)
        val config = DirectXrayConfig.build(
            settings, s.socksAddress, s.socksPort, s.tunMtu,
            nativeTun = true, ca = ca, noiseAllowed = settings.udpNoise,
            mitmH11Port = h11, mitmH211Port = h211,
        )
        File(context.filesDir, "xray-direct.json").writeText(config)
        val paused = com.uacspoofer.mobile.vpn.VpnPauseStore.isPaused()
        val pauseOk = !paused || (config.contains("\"network\":\"tcp,udp\"") && config.contains("direct-plain"))
        AppLogRepository.info(
            LogSource.TUN,
            "Direct reloadKeepingTun pause=$paused pauseRulePresent=$pauseOk",
        )
        val old = controller
        controller = null
        runCatching { old?.stopLoop() }
            .onFailure { AppLogRepository.warning(LogSource.XRAY, "Direct stop before reload failed", it) }
        initRuntime(context)
        val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(code: Long, message: String?): Long {
                if (!message.isNullOrBlank()) AppLogRepository.debug(LogSource.XRAY, "Direct status=$code: $message")
                return 0L
            }
        })
        controller = core
        core.startLoop(config, tun.fd)
        check(core.isRunning) { "Direct core did not enter running state after reload" }
    }

    /** Proxy-mode Pause/Resume: rewrite config and restart the libxray process (no TUN). */
    @Synchronized
    fun reloadForPause(advanced: AdvancedSettingsData, proxyMode: Boolean) {
        if (!proxyMode) {
            reloadKeepingTun(advanced)
            return
        }
        val settings = DirectEngineStore.get(context).snapshot()
        val s = advanced.validated()
        val ca = if (settings.googleFronting || settings.echEnabled) DirectCa.ensure(context) else null
        val h11 = if (ca != null) freeLoopbackPort() else 0
        val h211 = if (ca != null) freeLoopbackPort() else 0
        DomesticDirect.ensure(context)
        DomesticDirect.applyEnabled(NetworkGuardStore.get(context).snapshot().iranDirect)
        val config = DirectXrayConfig.build(
            settings, s.socksAddress, s.socksPort, s.tunMtu,
            nativeTun = false, ca = ca, noiseAllowed = settings.udpNoise,
            mitmH11Port = h11, mitmH211Port = h211,
        )
        File(context.filesDir, "xray-direct.json").writeText(config)
        val proc = process
        process = null
        if (proc != null) {
            runCatching { proc.destroy() }
            runCatching { waitForProcess(proc, 1_500L) }
            if (isAliveCompat(proc)) {
                if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { proc.destroyForcibly() }
                else runCatching { proc.destroy() }
                runCatching { waitForProcess(proc, 1_000L) }
            }
        }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        check(binary.isFile) { "Xray binary missing" }
        val p = ProcessBuilder(binary.absolutePath, "run", "-config", File(context.filesDir, "xray-direct.json").absolutePath)
            .directory(context.filesDir).redirectErrorStream(true).start()
        process = p
        Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { AppLogRepository.info(LogSource.XRAY, it) } }
        }.apply { isDaemon = true }.start()
        Thread.sleep(600)
        check(isAliveCompat(p)) { "Xray exited during pause reload" }
        AppLogRepository.info(
            LogSource.XRAY,
            "Direct proxy reloaded pause=${com.uacspoofer.mobile.vpn.VpnPauseStore.isPaused()}",
        )
    }

    fun markConnected() = DirectStatusStore.update(
        DirectPhase.CONNECTED, 100,
        "Direct · Google fronting + ECH (MITM CA). IP-blocked sites stay unreachable.",
    )

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        runCatching { controller?.stopLoop() }
        controller = null
        runCatching { descriptor?.close() }
        descriptor = null
        val proc = process
        process = null
        if (proc != null) {
            runCatching { proc.destroy() }
            runCatching { waitForProcess(proc, 1_500L) }
            if (isAliveCompat(proc)) {
                if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { proc.destroyForcibly() }
                else runCatching { proc.destroy() }
                runCatching { waitForProcess(proc, 1_000L) }
            }
        }
        up = 0L; down = 0L
    }

    /** Ephemeral loopback port that is free right now (for MITM decrypt inbounds). */
    private fun freeLoopbackPort(): Int = ServerSocket(0).use { it.localPort }

    @Synchronized
    fun isRunning(): Boolean = controller?.isRunning == true || process?.let { isAliveCompat(it) } == true

    fun socksPort(): Int = port

    @Synchronized
    fun tunStats(): TunStats {
        val core = controller ?: return TunStats.ZERO
        for (tag in TAGS) {
            up += runCatching { core.queryStats(tag, "uplink") }.getOrDefault(0L).coerceAtLeast(0L)
            down += runCatching { core.queryStats(tag, "downlink") }.getOrDefault(0L).coerceAtLeast(0L)
        }
        return TunStats(0L, up, 0L, down)
    }


    private fun waitForProcess(proc: Process, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (isAliveCompat(proc) && System.currentTimeMillis() < deadline) {
            Thread.sleep(40)
        }
    }

    private fun isAliveCompat(proc: Process): Boolean = try {
        proc.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 6_000
        private const val PROBE_BUDGET_MS = 25_000L
        private val PROBE_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://www.youtube.com/generate_204",
            "https://www.google.com/generate_204",
            "https://x.com/",
        )
        private val TAGS = listOf(
            DirectXrayConfig.TAG_TLS, DirectXrayConfig.TAG_TCP, DirectXrayConfig.TAG_PLAIN,
            DirectXrayConfig.TAG_UDP, DirectXrayConfig.TAG_NOISE,
        )
        @Volatile private var runtimeReady = false
        private fun initRuntime(context: Context) {
            if (runtimeReady) return
            synchronized(this) {
                if (runtimeReady) return
                Seq.setContext(context.applicationContext)
                DomesticDirect.ensure(context)
        DomesticDirect.applyEnabled(NetworkGuardStore.get(context).snapshot().iranDirect)
        // GeoAssets.ensure via DomesticDirect
                Libv2ray.initCoreEnv(context.filesDir.absolutePath, "dWFjLXNuaS1zcG9vZmVyLW5hdGl2ZS10dW4ta2V5ISE")
                runtimeReady = true
            }
        }
    }
}
