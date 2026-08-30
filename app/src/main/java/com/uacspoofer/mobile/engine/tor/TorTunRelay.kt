package com.uacspoofer.mobile.engine.tor

import android.content.Context
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.vpn.TunStats
import java.io.File

internal class TorTunRelay(context: Context) {
    private val appContext = context.applicationContext
    private var descriptor: ParcelFileDescriptor? = null

    @Synchronized
    fun start(
        yaml: String,
        establishTun: () -> ParcelFileDescriptor?,
    ) {
        stopLocked()
        val tun = checkNotNull(establishTun()) { "VpnService.Builder.establish returned null" }
        descriptor = tun
        val configFile = File(appContext.filesDir, "tor/hev.yml")
        configFile.parentFile?.mkdirs()
        configFile.writeText(yaml)
        val started = runCatching {
            HevSocks5Tunnel.TProxyStartService(configFile.absolutePath, tun.fd)
        }.getOrElse { error ->
            stopLocked()
            throw IllegalStateException("Tor tun2socks library failed to load", error)
        }
        if (!started || !HevSocks5Tunnel.TProxyIsRunning()) {
            stopLocked()
            error("Tor tun2socks did not enter running state")
        }
        AppLogRepository.info(LogSource.TOR, "Tor tun2socks TUN started")
    }

    @Synchronized
    fun stats(): TunStats {
        if (!isRunning()) return TunStats.ZERO
        val values = runCatching { HevSocks5Tunnel.TProxyGetStats() }.getOrNull()
            ?: return TunStats.ZERO
        if (values.size < 4) return TunStats.ZERO
        return TunStats(
            txPackets = values[0].coerceAtLeast(0L),
            txBytes = values[1].coerceAtLeast(0L),
            rxPackets = values[2].coerceAtLeast(0L),
            rxBytes = values[3].coerceAtLeast(0L),
        )
    }

    @Synchronized
    fun isRunning(): Boolean = descriptor != null &&
        runCatching { HevSocks5Tunnel.TProxyIsRunning() }.getOrDefault(false)

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        val hadTun = descriptor != null
        runCatching { HevSocks5Tunnel.TProxyStopService() }
        runCatching { descriptor?.close() }
        descriptor = null
        if (hadTun) AppLogRepository.info(LogSource.TOR, "Tor tun2socks TUN stopped")
    }
}
