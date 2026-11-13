package com.uacspoofer.mobile.engine.pow

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.uacspoofer.mobile.engine.tor.HevSocks5Tunnel
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.vpn.TunStats
import java.io.File

object PowTun2Socks {
    private const val NATIVE_EXIT_GRACE_MS = 5_000L

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
        get() = runCatching { HevSocks5Tunnel.TProxyIsRunning() }.getOrDefault(false)

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
        context: Context,
        tunFd: ParcelFileDescriptor,
        socksProxyPort: Int,
        mtu: Int,
        address: PrivateAddress = privateAddress,
    ): Boolean {
        if (socksProxyPort <= 0) return false
        stopLocked()
        val yaml = PowTunRelayConfig.yaml(
            mtu = mtu,
            socksPort = socksProxyPort,
            tunIpv4 = address.ipAddress,
            mapDns = address.router,
        )
        val configFile = File(context.applicationContext.filesDir, "uac-pow/hev.yml")
        configFile.parentFile?.mkdirs()
        configFile.writeText(yaml)
        val started = runCatching {
            HevSocks5Tunnel.TProxyStartService(configFile.absolutePath, tunFd.fd)
        }.getOrElse { error ->
            AppLogRepository.warning(LogSource.POW, "tun2socks library failed to load: ${error.message}")
            stopLocked()
            return false
        }
        if (!started || !isRunning) {
            stopLocked()
            AppLogRepository.warning(LogSource.POW, "tun2socks did not enter running state")
            return false
        }
        AppLogRepository.info(
            LogSource.POW,
            "tun2socks started mtu=${mtu.coerceIn(PowTunRelayConfig.MIN_MTU, PowTunRelayConfig.MAX_CUSTOM_MTU)} → SOCKS 127.0.0.1:$socksProxyPort",
        )
        return true
    }

    @Synchronized
    fun stats(): TunStats {
        if (!isRunning) return TunStats.ZERO
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
    fun stop() = stopLocked()

    fun awaitNativeExit(timeoutMs: Long = NATIVE_EXIT_GRACE_MS) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (isRunning && SystemClock.elapsedRealtime() < deadline) {
            runCatching { Thread.sleep(50L) }
        }
    }

    private fun stopLocked() {
        runCatching { HevSocks5Tunnel.TProxyStopService() }
    }

    private fun defaultPrivateAddress() =
        PrivateAddress("198.18.0.1", "198.18.0.1", 32, "198.18.0.2")
}
