package com.uacspoofer.mobile.vpn

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.DomesticDirect
import com.uacspoofer.mobile.mci.GeoAssets
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.ai.AiRoutePlan
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciNativeXrayConfig
import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.TunBackend
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.InetAddress
import java.net.ServerSocket


class XrayNativeTunEngine(private val context: VpnService) {
    private var descriptor: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    private val socksTun = SocksTunRelay()
    private var socksTunPort: Int? = null
    private var totalUplink = 0L
    private var totalDownlink = 0L
    private var totalProbeUplink = 0L
    private var totalProbeDownlink = 0L

    @Synchronized
    fun start(
        edge: MciEdge,
        settings: AdvancedSettingsData,
        profile: ProxyProfile = ProxyProfile.ADVANCED_SETTINGS_TEMPLATE,
        runtimeOptions: MciXrayRuntimeOptions = MciXrayRuntimeOptions.DEFAULT,
        aiRoute: AiRoutePlan? = null,
        backend: TunBackend = TunBackend.HEV,
        establishTun: () -> ParcelFileDescriptor?,
    ) {
        stopLocked()
        NativeXrayRuntime.initialize(context)
        val tun = checkNotNull(establishTun()) { "VpnService.Builder.establish returned null" }
        descriptor = tun
        val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(code: Long, message: String?): Long {
                if (!message.isNullOrBlank()) {
                    AppLogRepository.debug(LogSource.XRAY, "Native status=$code: $message")
                }
                return 0L
            }
        })
        controller = core
        totalUplink = 0L
        totalDownlink = 0L
        totalProbeUplink = 0L
        totalProbeDownlink = 0L
        try {
            val port = if (backend == TunBackend.ZEPTUN) {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            } else null
            socksTunPort = port
            core.startLoop(
                MciNativeXrayConfig.build(edge, settings, profile, runtimeOptions, aiRoute, port),
                if (port == null) tun.fd else 0,
            )
            check(core.isRunning) { "Native Xray core did not enter running state" }
            if (port != null) {
                socksTun.start(context, backend, tun, SocksTunRelay.Config(port, settings.tunMtu, udp = true)) {
                    error("Xray HEV selection uses Xray's built-in TUN")
                }
            } else {
                AppLogRepository.info(LogSource.TUN, "[TUN] Active backend: Xray native (stable selection)")
            }
            AppLogRepository.info(
                LogSource.TUN,
                "Xray Native TUN started for ${edge.role} (MTU ${settings.tunMtu})" +
                    if (aiRoute != null) " with the AI exit via ${aiRoute.profile.name}" else "",
            )
        } catch (error: Throwable) {
            stopLocked()
            AppLogRepository.error(LogSource.TUN, "Xray Native TUN startup failed", error)
            throw error
        }
    }

    @Synchronized
    fun stats(): TunStats {
        if (socksTunPort != null) return socksTun.stats()
        val core = controller ?: return TunStats.ZERO
        totalUplink += query(core, "proxy", "uplink")
        totalDownlink += query(core, "proxy", "downlink")
        return TunStats(0L, totalUplink, 0L, totalDownlink)
    }

    @Synchronized
    fun probeStats(): TunStats {
        val core = controller ?: return TunStats.ZERO
        totalProbeUplink += query(core, "probe-proxy", "uplink")
        totalProbeDownlink += query(core, "probe-proxy", "downlink")
        return TunStats(0L, totalProbeUplink, 0L, totalProbeDownlink)
    }


    /**
     * Rebuild Xray routing on the existing TUN fd (no new VpnService interface).
     * Used for Pause/Resume so the session stays up.
     */
    @Synchronized
    fun reloadKeepingTun(
        edge: MciEdge,
        settings: AdvancedSettingsData,
        profile: ProxyProfile = ProxyProfile.ADVANCED_SETTINGS_TEMPLATE,
        runtimeOptions: MciXrayRuntimeOptions = MciXrayRuntimeOptions.DEFAULT,
        aiRoute: AiRoutePlan? = null,
    ) {
        val tun = checkNotNull(descriptor) { "Native TUN is not established" }
        NativeXrayRuntime.initialize(context)
        val old = controller
        controller = null
        runCatching { old?.stopLoop() }
            .onFailure { AppLogRepository.warning(LogSource.XRAY, "Native core stop before reload failed", it) }
        val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(code: Long, message: String?): Long {
                if (!message.isNullOrBlank()) {
                    AppLogRepository.debug(LogSource.XRAY, "Native status=$code: $message")
                }
                return 0L
            }
        })
        controller = core
        val port = socksTunPort
        val configJson = MciNativeXrayConfig.build(edge, settings, profile, runtimeOptions, aiRoute, port)
        val paused = com.uacspoofer.mobile.vpn.VpnPauseStore.isPaused()
        val dnsQueryDirect = configJson.contains("\"inboundTag\":[\"dns-query\"],\"outboundTag\":\"direct\"")
        val allDirect = configJson.contains("\"outboundTag\":\"direct\"")
        val pauseOk = !paused || (dnsQueryDirect && allDirect)
        AppLogRepository.info(
            LogSource.TUN,
            "Xray reloadKeepingTun pause=$paused pauseRulePresent=$pauseOk dnsQueryDirect=$dnsQueryDirect",
        )
        if (paused && !pauseOk) {
            error("Pause config missing all-direct / dns-query-direct rules")
        }
        core.startLoop(configJson, if (port == null) tun.fd else 0)
        check(core.isRunning) { "Native Xray core did not enter running state after reload" }
        AppLogRepository.info(
            LogSource.TUN,
            "Xray Native TUN reloaded (pause=$paused)",
        )
    }

    @Synchronized
    fun isRunning(): Boolean = controller?.isRunning == true &&
        (socksTunPort == null || socksTun.isRunning())

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        socksTun.stop()
        socksTunPort = null
        val hadCore = controller != null
        val core = controller
        controller = null
        runCatching { core?.stopLoop() }
            .onFailure { AppLogRepository.warning(LogSource.XRAY, "Native core stop failed", it) }
        runCatching { descriptor?.close() }
        descriptor = null
        totalUplink = 0L
        totalDownlink = 0L
        totalProbeUplink = 0L
        totalProbeDownlink = 0L
        if (hadCore) AppLogRepository.info(LogSource.TUN, "Xray Native TUN stopped")
    }

    private fun query(core: CoreController, tag: String, direction: String): Long =
        runCatching { core.queryStats(tag, direction) }.getOrDefault(0L).coerceAtLeast(0L)
}

private object NativeXrayRuntime {
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            Seq.setContext(context.applicationContext)
            DomesticDirect.ensure(context)
            DomesticDirect.applyEnabled(NetworkGuardStore.get(context).snapshot().iranDirect)
            Libv2ray.initCoreEnv(context.filesDir.absolutePath, "dWFjLXNuaS1zcG9vZmVyLW5hdGl2ZS10dW4ta2V5ISE")
            AppLogRepository.info(LogSource.XRAY, "Loaded ${Libv2ray.checkVersionX()}")
            initialized = true
        }
    }
}
