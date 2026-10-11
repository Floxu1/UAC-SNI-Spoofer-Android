package com.uacspoofer.mobile.engine.pow

import android.net.VpnService
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The optional second Psiphon tunnel ("two tunnels at once").
 *
 * Psiphon's TunnelPoolSize picks a tunnel per port forward round-robin
 * (controller.go getNextActiveTunnel) and never pools two tunnels to the same
 * server (registerTunnel), so it cannot keep one exit IP per service. Instead a
 * second, independent Psiphon (TunnelPoolSize 1) runs in :uacpow2 with its own
 * data root and SOCKS port, and [PowStickyRoute] pins each service to one tunnel.
 * Tunnel 1 stays the session's main tunnel; this one only adds capacity, and if it
 * is down every service simply uses tunnel 1.
 */
internal class PowPsiphonSidecar(
    private val vpn: VpnService,
    private val onBytes: (Long, Long) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val generation = AtomicInteger(0)
    private val failures = AtomicInteger(0)
    @Volatile private var client: PowPsiphonClient? = null
    @Volatile private var routeKey: String? = null
    @Volatile private var baseConfig = ""
    @Volatile private var retryJob: Job? = null
    @Volatile private var listenPort = 0

    /** Starts tunnel 2 from tunnel 1's winning config, or restarts it when the route changed. */
    fun ensure(primaryConfig: String, direct: Boolean) {
        val key = routeKeyOf(primaryConfig, direct)
        scope.launch {
            lock.withLock {
                if (client != null && routeKey == key) return@withLock
                if (client != null) stopLocked("route changed")
                baseConfig = primaryConfig
                routeKey = key
                failures.set(0)
                startLocked()
            }
        }
    }

    suspend fun stop() {
        retryJob?.cancel()
        lock.withLock { stopLocked("session stopped") }
    }

    /** Never suspends; used by the coordinator's hard-timeout teardown. */
    fun forceRelease() {
        generation.incrementAndGet()
        retryJob?.cancel()
        retryJob = null
        PowSocksConnectOnly.setSecondaryPort(0)
        PowStickyRoute.reset()
        val old = client
        client = null
        routeKey = null
        if (old != null) {
            Thread({
                runCatching { runBlocking { withTimeoutOrNull(FORCE_STOP_MS) { old.stop() } } }
                runCatching { old.close() }
            }, "uac-pow-tunnel2-release").apply {
                isDaemon = true
                start()
            }
        }
    }

    private suspend fun startLocked() {
        val gen = generation.incrementAndGet()
        val config = try {
            PowPsiphonProtocols.sidecarConfig(baseConfig, dataRoot(), PowCoreConfig.SIDECAR_SOCKS_PORT)
        } catch (error: Throwable) {
            AppLogRepository.warning(LogSource.POW, "Psiphon tunnel 2 config failed: ${error.message}")
            return
        }
        val next = PowPsiphonClient(vpn, Callbacks(gen), PowPsiphonIpc.SIDECAR_SERVICE_CLASS)
        client = next
        listenPort = 0
        AppLogRepository.info(
            LogSource.POW,
            "Psiphon tunnel 2 starting in :uacpow2 (SOCKS ${PowCoreConfig.SIDECAR_SOCKS_PORT})",
        )
        try {
            next.start(config)
        } catch (error: Throwable) {
            AppLogRepository.warning(LogSource.POW, "Psiphon tunnel 2 did not start: ${error.message}")
            scheduleRetry(gen)
        }
    }

    private suspend fun stopLocked(reason: String) {
        generation.incrementAndGet()
        PowSocksConnectOnly.setSecondaryPort(0)
        PowStickyRoute.reset()
        val old = client ?: return
        client = null
        routeKey = null
        withContext(NonCancellable) {
            runCatching { old.stop() }
            runCatching { old.close() }
        }
        AppLogRepository.info(LogSource.POW, "Psiphon tunnel 2 stopped ($reason)")
    }

    private fun scheduleRetry(gen: Int) {
        val attempt = failures.incrementAndGet()
        if (attempt > MAX_RETRIES) {
            AppLogRepository.warning(
                LogSource.POW,
                "Psiphon tunnel 2 gave up after $MAX_RETRIES tries; every service uses tunnel 1",
            )
            return
        }
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RETRY_BASE_MS * attempt)
            lock.withLock {
                if (generation.get() != gen || routeKey == null) return@withLock
                val old = client
                client = null
                if (old != null) {
                    withContext(NonCancellable) {
                        runCatching { old.stop() }
                        runCatching { old.close() }
                    }
                }
                AppLogRepository.info(LogSource.POW, "Psiphon tunnel 2 retry $attempt/$MAX_RETRIES")
                startLocked()
            }
        }
    }

    private fun dataRoot(): String {
        // Its own data root: the Psiphon datastore is a locked bolt file and must
        // not be shared with tunnel 1 (which uses the library's default root).
        val dir = File(vpn.filesDir, DATA_ROOT_NAME)
        if (!dir.isDirectory) dir.mkdirs()
        return dir.absolutePath
    }

    private fun routeKeyOf(config: String, direct: Boolean): String {
        val upstream = runCatching { JSONObject(config).optString("UpstreamProxyURL") }.getOrDefault("")
        return "$direct|$upstream"
    }

    private inner class Callbacks(private val gen: Int) : PowPsiphonClient.Callbacks {
        private fun current(): Boolean = generation.get() == gen

        override fun onConnecting() {
            if (!current()) return
            // Lost its server and is reconnecting: its services move to tunnel 1.
            PowSocksConnectOnly.setSecondaryPort(0)
        }

        override fun onConnected() {
            if (!current()) return
            failures.set(0)
            val port = listenPort.takeIf { it > 0 } ?: PowCoreConfig.SIDECAR_SOCKS_PORT
            PowSocksConnectOnly.setSecondaryPort(port)
            AppLogRepository.success(
                LogSource.POW,
                "Psiphon tunnel 2 connected; each service now sticks to one tunnel",
            )
        }

        override fun onExiting() {
            if (!current()) return
            PowSocksConnectOnly.setSecondaryPort(0)
            AppLogRepository.warning(LogSource.POW, "Psiphon tunnel 2 exited; its services use tunnel 1")
            scheduleRetry(gen)
        }

        override fun onListeningSocksProxyPort(port: Int) {
            if (current()) listenPort = port
        }

        override fun onDiagnosticMessage(message: String) {
            if (!current()) return
            if (message.startsWith("ActiveTunnel:")) {
                AppLogRepository.info(LogSource.POW, "[tunnel 2] ${message.take(200)}")
            }
        }

        override fun onBytesTransferred(sent: Long, received: Long) {
            if (current()) onBytes(sent, received)
        }

        override fun onClientRegion(region: String) = Unit

        override fun onConnectedServerRegion(region: String) {
            if (!current()) return
            AppLogRepository.info(LogSource.POW, "Psiphon tunnel 2 exit country ${PowRegions.name(region)} ($region)")
        }

        override fun onAvailableEgressRegions(regions: List<String>) = Unit

        override fun onClientAddress(address: String) = Unit

        override fun onStartFailed(message: String) {
            if (!current()) return
            PowSocksConnectOnly.setSecondaryPort(0)
            AppLogRepository.warning(LogSource.POW, "Psiphon tunnel 2 failed to start: $message")
            scheduleRetry(gen)
        }
    }

    companion object {
        private const val DATA_ROOT_NAME = "psiphon-tunnel2"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_MS = 20_000L
        private const val FORCE_STOP_MS = 3_000L
    }
}
