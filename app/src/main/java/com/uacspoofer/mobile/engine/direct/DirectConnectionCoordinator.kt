package com.uacspoofer.mobile.engine.direct

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.uacspoofer.mobile.engine.pow.PowRegions
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.DomesticDirect
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.vpn.SocksDnsProbe
import com.uacspoofer.mobile.vpn.TunPacketFilter
import com.uacspoofer.mobile.vpn.TunStats
import com.uacspoofer.mobile.vpn.VpnPauseStore
import go.Seq
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Runs engine 4 on the same bundled Xray core engine 1 uses (libv2ray for the TUN, libxray.so
 * for proxy mode), with a config that has no server at all.
 */
class DirectConnectionCoordinator(private val context: VpnService) {
    private var controller: CoreController? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var process: Process? = null
    private val maskProcesses = mutableListOf<Process>()
    private val dnsProbe = SocksDnsProbe()
    private val dnsSelector = DirectDnsSelector(dnsProbe)
    private var selectedDns: DirectDnsSelection? = null
    private var up = 0L
    private var down = 0L
    @Volatile private var port = 0
    private val wireGuard = DirectWireGuard(context)
    private val countryExit = DirectCountryExit(context)
    private var exitSocksPort: Int? = null
    private var exitCountryCode = ""

    suspend fun connect(
        advanced: AdvancedSettingsData,
        proxyMode: Boolean,
        establishTun: () -> ParcelFileDescriptor?,
    ) {
        val settings = DirectEngineStore.get(context).snapshot()
        val s = advanced.validated()
        stop()
        AppLogRepository.info(
            LogSource.XRAY,
            "Direct session: scan=${settings.scanMode.id} auto_dns=${settings.autoSelectDns} " +
                "mode=${if (proxyMode) "proxy" else "vpn"} mtu=${s.tunMtu} cloud_transport=${settings.wireGuardOverDirect}",
        )
        verifyDirectStartup(
            start = { skipIp -> startAndVerify(settings, s, proxyMode, establishTun, skipIp) },
            selectedCarrier = { if (settings.wireGuardOverDirect) wireGuard.selectedCarrierIp() else null },
            prepareRetry = { failedCarrier, detail ->
                AppLogRepository.warning(
                    LogSource.XRAY,
                    "UAC Direct startup verification failed via $failedCarrier; trying another route: $detail",
                )
                // Keep capturing VPN traffic while the replacement carrier starts.
                stopConnection(keepTun = true)
            },
        )
    }

    private suspend fun startAndVerify(
        settings: DirectEngineSettings,
        s: AdvancedSettingsData,
        proxyMode: Boolean,
        establishTun: () -> ParcelFileDescriptor?,
        excludedCarrierIp: String?,
    ): ProbeResult = coroutineScope {
        DirectStatusStore.setDnsSelection(null)
        val tunAlreadyUp = descriptor != null
        val chainedPort = if (settings.wireGuardOverDirect) {
            val detail = if (excludedCarrierIp == null) "Preparing WireGuard over Direct" else "Trying another route"
            DirectStatusStore.update(DirectPhase.BUILDING, 10, detail)
            wireGuard.start(settings, excludedCarrierIp)
        } else null
        val nativeBefore = chainedPort?.takeIf { !proxyMode && !tunAlreadyUp }?.let { nativePort ->
            async(Dispatchers.IO) { probeLayer("native_before_vpn", nativePort) }
        }
        val region = if (chainedPort != null) PowRegions.egressRegion(settings.exitCountryCode) else null
        val selectedExitPort = if (region != null) countryExit.allocatePort() else chainedPort
        val dns = if (settings.autoSelectDns) {
            if (chainedPort != null && region != null && selectedExitPort != null) {
                DirectStatusStore.update(DirectPhase.STARTING, 35, "Connecting selected exit")
                countryExit.start(chainedPort, region, selectedExitPort)
            }
            DirectStatusStore.update(DirectPhase.STARTING, 45, "Testing secure DNS")
            val resolverProxy = selectedExitPort?.let { Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", it)) }
                ?: Proxy.NO_PROXY
            dnsSelector.select(resolverProxy).also {
                DirectStatusStore.setDnsSelection(it)
                AppLogRepository.info(
                    LogSource.XRAY,
                    it?.let { "UAC Direct DNS selected: ${it.resolver.id} ${it.latencyMs}ms" }
                        ?: "UAC Direct DNS selection: no resolver passed, using configured DNS",
                )
            }
        } else null
        val beforeVpn = nativeBefore?.await()
        synchronized(this@DirectConnectionCoordinator) {
            selectedDns = dns
            exitSocksPort = selectedExitPort
            exitCountryCode = region.orEmpty()
            port = s.socksPort
            if (chainedPort == null) DirectStatusStore.update(DirectPhase.BUILDING, 20, "Building direct TLS routes")
            AppLogRepository.info(
                LogSource.XRAY,
                "UAC Direct: adaptive=${settings.adaptiveClientHello} manual=${settings.fragmentEnabled}",
            )
            val detail = when {
                chainedPort != null -> "WireGuard handshake and data verified via Direct"
                else -> "Starting Xray with direct outbounds"
            }
            DirectStatusStore.update(DirectPhase.STARTING, 50, detail)
            val attempt = runCatching { start(settings, s, proxyMode, noise = true, establishTun) }
            if (attempt.isFailure && settings.udpNoise) {
                AppLogRepository.warning(LogSource.XRAY, "UAC Direct: retrying startup without UDP noise", attempt.exceptionOrNull())
                stopLocked(keepTun = excludedCarrierIp != null)
                start(settings, s, proxyMode, noise = false, establishTun)
            } else {
                attempt.getOrThrow()
            }
        }

        if (!settings.autoSelectDns && chainedPort != null && region != null && selectedExitPort != null) {
            DirectStatusStore.update(DirectPhase.STARTING, 60, "Connecting Psiphon exit $region through WireGuard")
            countryExit.start(chainedPort, region, selectedExitPort)
        }
        DirectStatusStore.update(DirectPhase.CHECKING, 75, "Checking DNS and HTTPS payload")
        val rounds = when {
            region != null -> 8
            settings.adaptiveClientHello -> 3
            else -> 2
        }
        suspend fun verifyRound(round: Int): ProbeResult {
            val started = SystemClock.elapsedRealtime()
            val result = verify(s)
            if (!result.ok) {
                AppLogRepository.debug(
                    LogSource.XRAY,
                    "Direct DNS/HTTPS startup check $round/$rounds failed after " +
                        "${SystemClock.elapsedRealtime() - started} ms: ${result.detail}",
                )
            }
            return result
        }
        var result = verifyRound(1)
        for (round in 1 until rounds) {
            if (result.ok) break
            delay(if (region != null) 1_000 else 300)
            result = verifyRound(round + 1)
        }
        if (result.ok) {
            if (chainedPort != null) wireGuard.rememberVerifiedCompatibilityRoute()
            AppLogRepository.success(LogSource.XRAY, "UAC Direct DNS and HTTPS verified: ${result.detail}")
        } else if (chainedPort != null) {
            diagnosePayloadFailure(chainedPort, selectedExitPort, s, beforeVpn)
        }
        result
    }

    data class ProbeResult(val ok: Boolean, val detail: String)

    private fun probeLayer(label: String, port: Int, host: String = "127.0.0.1"): ProbeResult {
        val result = probe(
            PAYLOAD_DIAGNOSTIC_URL,
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)),
            timeoutMs = DIAGNOSTIC_TIMEOUT_MS,
        )
        AppLogRepository.debug(LogSource.XRAY, "Direct payload layer=$label ok=${result.ok} ${result.detail}")
        return result
    }

    private suspend fun diagnosePayloadFailure(
        nativePort: Int,
        exitPort: Int?,
        settings: AdvancedSettingsData,
        beforeVpn: ProbeResult?,
    ) = coroutineScope {
        val native = async(Dispatchers.IO) { probeLayer("native_after_xray", nativePort) }
        val xray = async(Dispatchers.IO) { probeLayer("xray", settings.socksPort, settings.socksAddress) }
        val exit = exitPort?.takeIf { it != nativePort }?.let {
            async(Dispatchers.IO) { probeLayer("country_exit", it) }
        }
        val nativeOk = native.await().ok
        val xrayOk = xray.await().ok
        val exitOk = exit?.await()?.ok
        val stage = when {
            nativeOk && xrayOk -> "dns_or_domain_https"
            nativeOk && exitOk == false -> "country_exit"
            nativeOk -> "xray_routing"
            beforeVpn?.ok == true -> "native_payload_lost_after_vpn"
            else -> "native_payload_unconfirmed"
        }
        AppLogRepository.warning(
            LogSource.XRAY,
            "Direct payload failure: stage=$stage native_running=${wireGuard.isRunning()} " +
                "before_vpn=${beforeVpn?.ok ?: "unmeasured"} native=$nativeOk xray=$xrayOk " +
                "country_exit=${exitOk ?: "unused"}",
        )
    }

    suspend fun verify(settings: AdvancedSettingsData, tryLightCheck: Boolean = false): ProbeResult = coroutineScope {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(settings.socksAddress, settings.socksPort))
        if (tryLightCheck) {
            val light = withContext(Dispatchers.IO) { probe(PROBE_URLS.last(), proxy, timeoutMs = 2_000) }
            if (light.ok) return@coroutineScope light
        }
        val dns = async { dnsProbe.verify(settings, socketTimeoutMs = PROBE_TIMEOUT_MS) }
        val https = PROBE_URLS.map { url -> async(Dispatchers.IO) { probe(url, proxy) } }.awaitAll()
        val dnsResult = dns.await()
        ProbeResult(
            dnsResult.success && https.any { it.ok },
            "DNS=${if (dnsResult.success) "OK" else dnsResult.detail} " +
                "dns_elapsed_ms=${dnsResult.latencyMs ?: "unknown"} dns_rcode=${dnsResult.rcode} " +
                "dns_answers=${dnsResult.answerCount}; ${https.joinToString(" ") { it.detail }}",
        )
    }

    private fun probe(url: String, proxy: Proxy, timeoutMs: Int = PROBE_TIMEOUT_MS): ProbeResult {
        val target = URL(url)
        val started = android.os.SystemClock.elapsedRealtime()
        var stage = "connect_or_response"
        return runCatching {
            val conn = target.openConnection(proxy) as HttpsURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("Connection", "close")
                val status = conn.responseCode
                stage = "payload_read"
                val payload = if (status in 200..299) conn.inputStream.use { it.read(ByteArray(256)) } else 0
                val ms = android.os.SystemClock.elapsedRealtime() - started
                ProbeResult(payload > 0, "${target.host}=$status/${payload.coerceAtLeast(0)}B/${ms}ms")
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            val failure = it.cause?.message ?: it.message
            val elapsed = SystemClock.elapsedRealtime() - started
            ProbeResult(
                false,
                "${target.host}=${it.javaClass.simpleName} stage=$stage elapsed_ms=$elapsed " +
                    failure.orEmpty().lineSequence().firstOrNull().orEmpty().take(120),
            )
        }
    }

    private fun start(
        settings: DirectEngineSettings,
        s: AdvancedSettingsData,
        proxyMode: Boolean,
        noise: Boolean,
        establishTun: () -> ParcelFileDescriptor?,
    ) {
        val config = writeConfig(settings, s, proxyMode, noise)
        if (proxyMode) {
            startProcess()
            return
        }
        val tun = descriptor ?: checkNotNull(establishTun()) { "VpnService.Builder.establish returned null" }
        descriptor = tun
        startController(config, tun)
        AppLogRepository.info(LogSource.TUN, "UAC Direct TUN started (MTU ${s.tunMtu}, noise=$noise)")
    }

    private fun writeConfig(
        settings: DirectEngineSettings,
        s: AdvancedSettingsData,
        proxyMode: Boolean,
        noise: Boolean,
    ): String {
        DomesticDirect.ensure(context)
        DomesticDirect.applyEnabled(NetworkGuardStore.get(context).snapshot().iranDirect)
        stopMasks()
        val masks = if (exitSocksPort == null && settings.adaptiveClientHello && !VpnPauseStore.isPaused()) {
            startMasks(settings.ipv6)
        } else emptyMap()
        return DirectXrayConfig.build(
            settings, s.socksAddress, s.socksPort, s.tunMtu,
            nativeTun = !proxyMode, noiseAllowed = noise, maskPorts = masks,
            wireGuardSocksPort = exitSocksPort,
            countryExit = exitCountryCode.isNotEmpty(),
            sessionDns = selectedDns?.resolver,
        ).also { File(context.filesDir, "xray-direct.json").writeText(it) }
    }

    private fun startMasks(ipv6: Boolean): Map<String, Int> {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libbyedpi.so")
        if (!binary.isFile) return emptyMap()
        val loopback = InetAddress.getByName("127.0.0.1")
        val ports = mutableMapOf<String, Int>()
        for ((name, options) in DirectXrayConfig.localMasks) {
            runCatching {
                val port = ServerSocket(0, 1, loopback).use { it.localPort }
                val command = listOf(
                    binary.absolutePath, "--ip", loopback.hostAddress, "--port", port.toString(),
                    "--no-udp", "--no-domain",
                ) + (if (ipv6) emptyList() else listOf("--no-ipv6")) + options
                val child = ProcessBuilder(command).redirectErrorStream(true).start()
                Thread {
                    runCatching { child.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { AppLogRepository.debug(LogSource.XRAY, "Direct $name: $it") }
                    } }
                }.apply { isDaemon = true }.start()
                if (awaitMaskReady(child, loopback, port)) {
                    maskProcesses += child
                    ports[name] = port
                } else {
                    stopProcess(child)
                    AppLogRepository.warning(LogSource.XRAY, "Direct $name skipped: helper did not answer SOCKS5")
                }
            }.onFailure { AppLogRepository.warning(LogSource.XRAY, "Direct could not start $name", it) }
        }
        return ports
    }

    private fun awaitMaskReady(child: Process, address: InetAddress, port: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 400L
        while (isAliveCompat(child) && SystemClock.elapsedRealtime() < deadline) {
            val ready = runCatching {
                Socket().use { socket ->
                    socket.soTimeout = 80
                    socket.connect(InetSocketAddress(address, port), 80)
                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                    val input = socket.getInputStream()
                    input.read() == 5 && input.read() == 0
                }
            }.getOrDefault(false)
            if (ready) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun stopMasks() {
        for (child in maskProcesses) stopProcess(child)
        maskProcesses.clear()
    }

    private fun startController(config: String, tun: ParcelFileDescriptor) {
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
        check(core.isRunning) { "Native Xray core did not enter running state" }
    }

    private fun startProcess() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        check(binary.isFile) { "Xray binary missing" }
        val config = File(context.filesDir, "xray-direct.json")
        val p = ProcessBuilder(binary.absolutePath, "run", "-config", config.absolutePath)
            .directory(context.filesDir).redirectErrorStream(true).start()
        process = p
        Thread {
            runCatching { p.inputStream.bufferedReader().useLines { it.forEach { line -> AppLogRepository.info(LogSource.XRAY, line) } } }
        }.apply { isDaemon = true }.start()
        Thread.sleep(350)
        check(isAliveCompat(p)) { "Xray exited during startup" }
    }

    /**
     * Rebuild Direct routing on the existing TUN fd (Pause/Resume). Does not call establishTun.
     */
    @Synchronized
    fun reloadKeepingTun(advanced: AdvancedSettingsData) {
        val tun = checkNotNull(descriptor) { "Direct TUN is not established" }
        val settings = DirectEngineStore.get(context).snapshot()
        val s = advanced.validated()
        val config = writeConfig(settings, s, proxyMode = false, noise = settings.udpNoise)
        val paused = VpnPauseStore.isPaused()
        val pauseOk = !paused || (config.contains("\"network\":\"tcp,udp\"") && config.contains("direct-plain"))
        AppLogRepository.info(
            LogSource.TUN,
            "Direct reloadKeepingTun pause=$paused pauseRulePresent=$pauseOk",
        )
        val old = controller
        controller = null
        runCatching { old?.stopLoop() }
            .onFailure { AppLogRepository.warning(LogSource.XRAY, "Direct stop before reload failed", it) }
        startController(config, tun)
        TunPacketFilter.setEngineQuicRejection(settings.blockQuic && !paused)
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
        writeConfig(settings, s, proxyMode = true, noise = settings.udpNoise)
        stopProcess()
        startProcess()
        AppLogRepository.info(
            LogSource.XRAY,
            "Direct proxy reloaded pause=${VpnPauseStore.isPaused()}",
        )
    }

    fun markConnected() = DirectStatusStore.update(
        DirectPhase.CONNECTED, 100,
        when {
            exitCountryCode.isNotEmpty() -> "WireGuard over Direct · verified exit $exitCountryCode"
            exitSocksPort != null -> "WireGuard over Direct · data verified"
            else -> "Direct · HTTPS reachability verified"
        },
        usingWireGuard = exitSocksPort != null,
        exitCountryCode = exitCountryCode,
    )

    suspend fun stop() = stopConnection(keepTun = false)

    private suspend fun stopConnection(keepTun: Boolean) {
        synchronized(this) {
            stopLocked(keepTun)
            exitSocksPort = null
            exitCountryCode = ""
            selectedDns = null
        }
        try {
            countryExit.stop()
        } finally {
            wireGuard.stop()
        }
    }

    private fun stopLocked(keepTun: Boolean) {
        runCatching { controller?.stopLoop() }
        controller = null
        if (!keepTun) {
            runCatching { descriptor?.close() }
            descriptor = null
        }
        stopProcess()
        stopMasks()
        up = 0L
        down = 0L
    }

    private fun stopProcess() {
        val proc = process ?: return
        process = null
        stopProcess(proc)
    }

    private fun stopProcess(proc: Process) {
        runCatching { proc.destroy() }
        runCatching { waitForProcess(proc, 1_500L) }
        if (isAliveCompat(proc)) {
            if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { proc.destroyForcibly() }
            else runCatching { proc.destroy() }
            runCatching { waitForProcess(proc, 1_000L) }
        }
    }

    @Synchronized
    fun isRunning(): Boolean = (exitSocksPort == null || wireGuard.isRunning()) &&
        (exitCountryCode.isEmpty() || countryExit.isRunning()) &&
        (controller?.isRunning == true || process?.let { isAliveCompat(it) } == true)

    fun socksPort(): Int = port

    @Synchronized
    fun tunStats(): TunStats {
        val core = controller ?: return TunStats.ZERO
        for (tag in DirectXrayConfig.trafficTags) {
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
        private const val PROBE_TIMEOUT_MS = 4_000
        private const val DIAGNOSTIC_TIMEOUT_MS = 1_500
        private const val PAYLOAD_DIAGNOSTIC_URL = "https://1.1.1.1/cdn-cgi/trace"
        private val PROBE_URLS = listOf(
            "https://m.youtube.com/robots.txt",
            "https://x.com/robots.txt",
            "https://www.google.com/robots.txt",
            "https://www.cloudflare.com/cdn-cgi/trace",
        )
        @Volatile private var runtimeReady = false
        private fun initRuntime(context: Context) {
            if (runtimeReady) return
            synchronized(this) {
                if (runtimeReady) return
                Seq.setContext(context.applicationContext)
                Libv2ray.initCoreEnv(context.filesDir.absolutePath, "dWFjLXNuaS1zcG9vZmVyLW5hdGl2ZS10dW4ta2V5ISE")
                runtimeReady = true
            }
        }
    }
}

internal suspend fun verifyDirectStartup(
    start: suspend (excludedCarrierIp: String?) -> DirectConnectionCoordinator.ProbeResult,
    selectedCarrier: () -> String?,
    prepareRetry: suspend (failedCarrierIp: String, failureDetail: String) -> Unit,
) {
    val first = start(null)
    if (first.ok) return

    val failedCarrier = selectedCarrier()
        ?: error("UAC Direct DNS or HTTPS check failed (${first.detail})")
    prepareRetry(failedCarrier, first.detail)
    currentCoroutineContext().ensureActive()
    val alternate = start(failedCarrier)
    check(alternate.ok) { "UAC Direct DNS or HTTPS check failed on the alternate route (${alternate.detail})" }
}
