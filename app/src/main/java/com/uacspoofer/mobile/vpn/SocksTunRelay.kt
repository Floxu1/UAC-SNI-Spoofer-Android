package com.uacspoofer.mobile.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.Keep
import com.uacspoofer.mobile.engine.tor.HevSocks5Tunnel
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.TunBackend
import com.uacspoofer.mobile.ui.LanguageStore
import com.uacspoofer.mobile.ui.DrawerLanguage
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject

internal class TunBackendStartException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)

/** The caller owns the descriptor until stop returns. Native backends only borrow it. */
internal class SocksTunRelay {
    data class Config(
        val port: Int,
        val mtu: Int,
        val udp: Boolean,
        val fakeDns: String? = null,
        val fakeDnsCache: Int = 10_000,
    ) {
        fun zeptunJson(fd: Int): String = JSONObject().apply {
            put("preset", "mobile")
            put("tun", JSONObject().put("fd", fd).put("mtu", mtu).put("configure", false))
            put("stack", JSONObject().put("mode", "userspace").put("icmp", "drop"))
            put("io", JSONObject().put("backend", "epoll").put("workers", 1).put("elastic", "off"))
            put("handler", JSONObject().put("kind", "socks5").put(
                "socks5", JSONObject().put("server", "127.0.0.1:$port")
                    .put("udp", udp).put("pipeline", false),
            ))
            fakeDns?.let { address ->
                put("dns", JSONObject().put("fake_ip", true)
                    .put("fake_ranges", JSONArray().put("100.64.0.0/10"))
                    .put("address", JSONArray().put(address))
                    .put("cache_size", fakeDnsCache).put("ttl", 1))
            }
        }.toString()
    }

    private var active: TunBackend? = null
    private var handle = 0L

    @Synchronized
    fun start(
        service: VpnService,
        backend: TunBackend,
        descriptor: ParcelFileDescriptor,
        config: Config,
        hevConfig: () -> Pair<String, String>,
    ) {
        check(active == null) { "A TUN relay is already started" }
        AppLogRepository.info(LogSource.TUN, "[TUN] Starting ${backend.label}")
        try {
            when (backend) {
                TunBackend.HEV -> {
                    val (path, yaml) = hevConfig()
                    val file = File(service.filesDir, path)
                    file.parentFile?.mkdirs()
                    file.writeText(yaml)
                    active = backend
                    check(HevSocks5Tunnel.TProxyStartService(file.absolutePath, descriptor.fd)) {
                        "HEV did not start"
                    }
                }
                TunBackend.ZEPTUN -> {
                    verifySocks(service, config.port)
                    val result = ZeptunNative.start(service, descriptor.fd, config.zeptunJson(descriptor.fd))
                    // Android can tag the high byte of a native pointer, so its signed value may be negative.
                    check(result !in -255L..0L) { "Zeptun setup returned $result" }
                    handle = result
                    awaitZeptun()
                }
            }
            active = backend
            check(isRunning()) { "${backend.label} did not enter running state" }
            AppLogRepository.info(LogSource.TUN, "[TUN] Active backend: ${backend.label}")
        } catch (error: Throwable) {
            runCatching { stop() }
            AppLogRepository.error(LogSource.TUN, "[TUN] ${backend.label} initialization failed", error)
            if (backend == TunBackend.HEV || error is InterruptedException) throw error
            val persian = LanguageStore.get(service).snapshot() == DrawerLanguage.PERSIAN
            val message = if (persian) {
                "راه‌اندازی Zeptun ناموفق بود. می‌توانید با HEV دوباره وصل شوید."
            } else {
                "Zeptun could not start. You can reconnect with HEV."
            }
            throw TunBackendStartException(message, error)
        }
    }

    @Synchronized
    fun isRunning(): Boolean = when (active) {
        TunBackend.HEV -> runCatching { HevSocks5Tunnel.TProxyIsRunning() }.getOrDefault(false)
        TunBackend.ZEPTUN -> ZeptunNative.status(handle) == 1
        null -> false
    }

    @Synchronized
    fun stats(): TunStats {
        if (!isRunning()) return TunStats.ZERO
        val counters = runCatching {
            when (active) {
                TunBackend.HEV -> HevSocks5Tunnel.TProxyGetStats()
                TunBackend.ZEPTUN -> ZeptunNative.stats(handle)
                null -> null
            }
        }.getOrNull() ?: return TunStats.ZERO
        if (counters.size < 4) return TunStats.ZERO
        return TunStats(
            counters[0].coerceAtLeast(0L), counters[1].coerceAtLeast(0L),
            counters[2].coerceAtLeast(0L), counters[3].coerceAtLeast(0L),
        )
    }

    @Synchronized
    fun stop() {
        val backend = active
        active = null
        if (handle != 0L) {
            val stopping = handle
            handle = 0L
            ZeptunNative.stop(stopping)
        }
        if (backend == TunBackend.HEV) HevSocks5Tunnel.TProxyStopService()
        if (backend != null) AppLogRepository.info(LogSource.TUN, "[TUN] ${backend.label} stopped")
    }

    private fun awaitZeptun() {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = ZeptunNative.status(handle)
            if (status == 1) return
            check(status == 0) { "Zeptun startup returned $status" }
            Thread.sleep(10L)
        }
        error("Zeptun startup timed out")
    }

    private fun verifySocks(service: VpnService, port: Int) {
        Socket(Proxy.NO_PROXY).use { socket ->
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            check(service.protect(socket)) { "Could not protect SOCKS readiness socket" }
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_500)
            socket.soTimeout = 1_500
            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
            val input = socket.getInputStream()
            check(input.read() == 5 && input.read() == 0) { "Local SOCKS greeting failed" }
        }
    }
}

@Keep
private object ZeptunNative {
    init {
        System.loadLibrary("zeptun-jni")
    }

    external fun start(service: VpnService, fd: Int, config: String): Long
    external fun status(handle: Long): Int
    external fun stats(handle: Long): LongArray?
    external fun stop(handle: Long)
}
