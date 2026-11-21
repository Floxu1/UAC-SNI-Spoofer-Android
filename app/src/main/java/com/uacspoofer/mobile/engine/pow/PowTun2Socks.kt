package com.uacspoofer.mobile.engine.pow

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.TunBackend
import com.uacspoofer.mobile.vpn.SocksTunRelay
import com.uacspoofer.mobile.vpn.TunStats

object PowTun2Socks {
    private val relay = SocksTunRelay()
    private var relayDescriptor: ParcelFileDescriptor? = null

    data class PrivateAddress(
        val ipAddress: String,
        val subnet: String,
        val prefixLength: Int,
        val router: String,
    )

    @Volatile
    var privateAddress: PrivateAddress = defaultPrivateAddress()
        private set

    val isRunning: Boolean
        get() = relay.isRunning()

    fun selectPrivateAddress(): PrivateAddress {
        val selected = defaultPrivateAddress()
        privateAddress = selected
        AppLogRepository.info(
            LogSource.POW,
            "tun2socks address plan if=${selected.ipAddress}/${selected.prefixLength} router=${selected.router}",
        )
        return selected
    }

    @Synchronized
    fun start(
        service: VpnService,
        tunFd: ParcelFileDescriptor,
        socksProxyPort: Int,
        mtu: Int,
        address: PrivateAddress,
        backend: TunBackend,
        udp: Boolean,
    ): Boolean {
        if (socksProxyPort <= 0) return false
        stopLocked()
        try {
            relay.start(
                service, backend, tunFd, SocksTunRelay.Config(
                    socksProxyPort, mtu, udp, address.router, PowTunRelayConfig.MAPDNS_CACHE_SIZE,
                ),
            ) {
                "uac-pow/hev.yml" to PowTunRelayConfig.yaml(mtu, socksProxyPort, address.ipAddress, address.router)
            }
            relayDescriptor = tunFd
        } catch (error: Throwable) {
            if (backend == TunBackend.ZEPTUN) throw error
            return false
        }
        AppLogRepository.info(
            LogSource.POW,
            "tun2socks started mtu=${mtu.coerceIn(PowTunRelayConfig.MIN_MTU, PowTunRelayConfig.MAX_CUSTOM_MTU)} → SOCKS 127.0.0.1:$socksProxyPort",
        )
        return true
    }

    @Synchronized
    fun stats(): TunStats = relay.stats()

    @Synchronized
    fun stop(expected: ParcelFileDescriptor? = null) {
        if (expected == null || relayDescriptor === expected) stopLocked()
    }

    private fun stopLocked() {
        runCatching { relay.stop() }
        relayDescriptor = null
    }

    private fun defaultPrivateAddress() =
        PrivateAddress("198.18.0.1", "198.18.0.1", 32, "198.18.0.2")
}
