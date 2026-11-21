package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.profiles.PhoneImportLan
import com.uacspoofer.mobile.settings.LanShareMode
import java.net.BindException
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
        if (!LanSharePolicy.isValidPort(upstreamPort)) {
            return LanShareEndpoint(port = listenPort, detail = "upstream unavailable", mode = mode).also(LanShareStatus::update)
        }
        if (!LanSharePolicy.isValidPort(listenPort) || listenPort == upstreamPort) {
            return LanShareEndpoint(port = listenPort, detail = LanSharePolicy.PORT_UNAVAILABLE, mode = mode).also(LanShareStatus::update)
        }
        val lanIp = PhoneImportLan.pickLanIpv4()
        if (lanIp.isNullOrBlank()) {
            return LanShareEndpoint(port = listenPort, detail = "no lan", mode = mode).also(LanShareStatus::update)
        }
        val listener = try {
            LanShareServer(listenPort, upstreamHost, upstreamPort, mode, protect).start()
        } catch (error: BindException) {
            AppLogRepository.warning(LogSource.SERVICE, "LAN share could not bind port $listenPort", error)
            return LanShareEndpoint(port = listenPort, detail = LanSharePolicy.PORT_UNAVAILABLE, mode = mode).also(LanShareStatus::update)
        }
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
