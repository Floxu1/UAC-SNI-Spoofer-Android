package com.uacspoofer.mobile.engine.pow

import android.net.VpnService
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Make-before-break Psiphon for quality retunes (ghost handover).
 *
 * A second Psiphon starts in :uacpow2 on [PowGhostHandover.SHADOW_SOCKS_PORT]
 * while tunnel 1 keeps carrying traffic. Only when it connects does the relay
 * move to it; if it does not connect within the timeout it is thrown away and
 * the working tunnel is never touched. Lives for seconds, so the battery cost
 * is one extra handshake. Never used together with the tunnel pool (which owns
 * :uacpow2) and never with the screen off.
 */
internal class PowGhostInner(
    private val vpn: VpnService,
    private val onBytes: (Long, Long) -> Unit,
) {
    private val generation = AtomicInteger(0)
    @Volatile private var client: PowPsiphonClient? = null
    @Volatile private var listenPort = 0

    val active: Boolean get() = client != null

    /** Returns the ghost's SOCKS port once connected, or 0 (ghost already discarded). */
    suspend fun warm(primaryConfig: String, timeoutMs: Long): Int {
        release("replaced")
        val gen = generation.incrementAndGet()
        val connected = CompletableDeferred<Boolean>()
        val config = try {
            PowPsiphonProtocols.sidecarConfig(primaryConfig, dataRoot(), PowGhostHandover.SHADOW_SOCKS_PORT)
        } catch (error: Throwable) {
            AppLogRepository.warning(LogSource.POW, "Ghost Psiphon config failed: ${error.message}")
            return 0
        }
        val next = PowPsiphonClient(vpn, Callbacks(gen, connected), PowPsiphonIpc.SIDECAR_SERVICE_CLASS)
        client = next
        PowLiveExit.updatePath { it.copy(ghost = true) }
        listenPort = 0
        AppLogRepository.info(
            LogSource.POW,
            "Ghost handover: second Psiphon warming in :uacpow2 (SOCKS ${PowGhostHandover.SHADOW_SOCKS_PORT}); live tunnel untouched",
        )
        val ok = try {
            next.start(config)
            withTimeoutOrNull(timeoutMs) { connected.await() } ?: false
        } catch (error: Throwable) {
            AppLogRepository.warning(LogSource.POW, "Ghost Psiphon did not start: ${error.message}")
            false
        }
        if (!ok || generation.get() != gen) {
            release("did not connect in time")
            return 0
        }
        return listenPort.takeIf { it > 0 } ?: PowGhostHandover.SHADOW_SOCKS_PORT
    }

    suspend fun release(reason: String) {
        generation.incrementAndGet()
        val old = client ?: return
        client = null
        PowLiveExit.updatePath { it.copy(ghost = false) }
        withContext(NonCancellable) {
            runCatching { withTimeoutOrNull(STOP_MS) { old.stop() } }
            runCatching { old.close() }
        }
        AppLogRepository.info(LogSource.POW, "Ghost Psiphon stopped ($reason)")
    }

    /** Never suspends; for hard teardown. */
    fun forceRelease() {
        generation.incrementAndGet()
        val old = client ?: return
        client = null
        PowLiveExit.updatePath { it.copy(ghost = false) }
        Thread({
            runCatching { runBlocking { withTimeoutOrNull(STOP_MS) { old.stop() } } }
            runCatching { old.close() }
        }, "uac-pow-ghost-release").apply {
            isDaemon = true
            start()
        }
    }

    private fun dataRoot(): String {
        val dir = File(vpn.filesDir, DATA_ROOT_NAME)
        if (!dir.isDirectory) dir.mkdirs()
        return dir.absolutePath
    }

    private inner class Callbacks(
        private val gen: Int,
        private val connected: CompletableDeferred<Boolean>,
    ) : PowPsiphonClient.Callbacks {
        private fun current(): Boolean = generation.get() == gen
        override fun onConnecting() = Unit
        override fun onConnected() {
            if (!current()) return
            AppLogRepository.success(LogSource.POW, "Ghost Psiphon connected")
            connected.complete(true)
        }
        override fun onExiting() {
            if (current()) connected.complete(false)
        }
        override fun onListeningSocksProxyPort(port: Int) {
            if (current()) listenPort = port
        }
        override fun onDiagnosticMessage(message: String) = Unit
        override fun onBytesTransferred(sent: Long, received: Long) {
            if (current()) onBytes(sent, received)
        }
        override fun onClientRegion(region: String) = Unit
        override fun onConnectedServerRegion(region: String) {
            if (current()) AppLogRepository.info(LogSource.POW, "Ghost Psiphon exit ${PowRegions.name(region)} ($region)")
        }
        override fun onAvailableEgressRegions(regions: List<String>) = Unit
        override fun onClientAddress(address: String) = Unit
        override fun onStartFailed(message: String) {
            if (!current()) return
            AppLogRepository.warning(LogSource.POW, "Ghost Psiphon failed to start: $message")
            connected.complete(false)
        }
    }

    companion object {
        private const val DATA_ROOT_NAME = "psiphon-ghost"
        private const val STOP_MS = 3_000L
    }
}