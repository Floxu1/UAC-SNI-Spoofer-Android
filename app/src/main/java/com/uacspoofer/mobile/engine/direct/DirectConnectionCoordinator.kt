package com.uacspoofer.mobile.engine.direct

import android.content.Context
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.vpn.TunStats
import go.Seq
import java.io.File
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
        AppLogRepository.info(LogSource.XRAY, "UAC Direct: variant=${settings.variant} hello=${settings.helloLengths} noise=${settings.udpNoise} ipv6=${settings.ipv6}")
        DirectStatusStore.update(DirectPhase.STARTING, 50, "Starting Xray core (direct only, no server)")
        val attempt = runCatching { start(settings, s, proxyMode, noise = true, establishTun) }
        if (attempt.isFailure && settings.udpNoise) {
            AppLogRepository.warning(LogSource.XRAY, "UAC Direct: start with UDP noise failed, retrying without it", attempt.exceptionOrNull())
            stopLocked()
            start(settings, s, proxyMode, noise = false, establishTun)
        } else {
            attempt.getOrThrow()
        }
        DirectStatusStore.update(DirectPhase.CHECKING, 85, "Core running")
    }

    private fun start(
        settings: DirectEngineSettings,
        s: AdvancedSettingsData,
        proxyMode: Boolean,
        noise: Boolean,
        establishTun: () -> ParcelFileDescriptor?,
    ) {
        val config = DirectXrayConfig.build(settings, s.socksAddress, s.socksPort, s.tunMtu, nativeTun = !proxyMode, noiseAllowed = noise)
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
            check(p.isAliveCompat()) { "Xray exited during startup" }
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

    fun markConnected() = DirectStatusStore.update(
        DirectPhase.CONNECTED, 100,
        "Direct, no server. Sites blocked by IP stay unreachable.",
    )

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        runCatching { controller?.stopLoop() }
        controller = null
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { process?.destroy() }
        process = null
        up = 0L; down = 0L
    }

    @Synchronized
    fun isRunning(): Boolean = controller?.isRunning == true || process?.isAliveCompat() == true

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

    private fun Process.isAliveCompat(): Boolean = try { exitValue(); false } catch (_: IllegalThreadStateException) { true }

    companion object {
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
                Libv2ray.initCoreEnv(context.filesDir.absolutePath, "dWFjLXNuaS1zcG9vZmVyLW5hdGl2ZS10dW4ta2V5ISE")
                runtimeReady = true
            }
        }
    }
}
