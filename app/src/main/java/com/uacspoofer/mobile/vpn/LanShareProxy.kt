package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.profiles.PhoneImportLan
import com.uacspoofer.mobile.settings.LanShareMode
import java.net.Socket

/** LAN-only frontend; engine SOCKS listeners remain on loopback. */
object LanShareProxy {
    @Volatile private var server: LanShareServer? = null
    val isRunning: Boolean get() = server?.isRunning == true

    @Synchronized
    fun start(
        listenPort: Int = LanSharePolicy.PORT,
        upstreamHost: String,
        upstreamPort: Int,
        protect: ((Socket) -> Boolean)? = null,
        mode: LanShareMode = LanShareMode.SOCKS5,
    ): LanShareEndpoint {
        stopLocked()
        if (upstreamPort !in 1..65535) {
            return LanShareEndpoint(detail = "upstream unavailable", mode = mode).also(LanShareStatus::update)
        }
        val lanIp = PhoneImportLan.pickLanIpv4()
        if (lanIp.isNullOrBlank()) {
            return LanShareEndpoint(detail = "no lan", mode = mode).also(LanShareStatus::update)
        }
        val listener = LanShareServer(listenPort, upstreamHost, upstreamPort, mode, protect).start()
        server = listener
        val endpoint = LanShareEndpoint(listening = true, address = lanIp, port = listener.port, mode = mode)
        LanShareStatus.update(endpoint)
        AppLogRepository.info(LogSource.SERVICE,
            "LAN share ${mode.proxyType} ${endpoint.label} → 127.0.0.1:$upstreamPort")
        return endpoint
    }

    @Synchronized
    fun stop() = stopLocked()

    @Synchronized
    fun refreshAdvertisedAddress() {
        if (server?.isRunning != true) return
        val ip = PhoneImportLan.pickLanIpv4()
        if (ip.isNullOrBlank()) { stopLocked(); return }
        val current = LanShareStatus.endpoint.value
        if (current.address != ip) LanShareStatus.update(current.copy(address = ip, listening = true))
    }

    private fun stopLocked() {
        val previous = server
        server = null
        previous?.close()
        LanShareStatus.clear()
    }
}
