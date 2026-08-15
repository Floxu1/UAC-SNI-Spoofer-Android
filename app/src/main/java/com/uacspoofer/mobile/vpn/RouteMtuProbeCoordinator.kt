package com.uacspoofer.mobile.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.RouteTransferProbeConfig
import com.uacspoofer.mobile.profiles.RouteTransferProbeResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class RouteNativeMtuProbeRequest(
    val candidate: AdaptiveCandidate,
    val profile: ProxyProfile,
    val transferConfig: RouteTransferProbeConfig,
    val expectedNetworkKey: String,
    val underlyingNetwork: Network? = null,
)

data class RouteNativeMtuProbeResult(
    val accepted: Boolean,
    val transfer: RouteTransferProbeResult,
    val httpSucceeded: Int,
    val httpAttempted: Int,
    val httpDetail: String,
    val dnsSucceeded: Boolean,
    val dnsLatencyMs: Long?,
    val dnsAnswers: Int,
    val txDelta: Long,
    val rxDelta: Long,
    val detail: String,
)

class RouteProbePermissionRequiredException : IllegalStateException(
    "VPN permission is required before native MTU validation",
)

class RouteProbeBusyException(message: String) : IllegalStateException(message)

object RouteMtuProbeCoordinator {
    private data class Pending(
        val request: RouteNativeMtuProbeRequest,
        val result: CompletableDeferred<RouteNativeMtuProbeResult>,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val cancellationCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val measurementMutex = Mutex()

    suspend fun measure(
        context: Context,
        request: RouteNativeMtuProbeRequest,
    ): RouteNativeMtuProbeResult = measurementMutex.withLock {
        val appContext = context.applicationContext
        val state = ConnectionStateStore.state.value
        if (state !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) {
            throw RouteProbeBusyException("Disconnect the active VPN before native MTU validation")
        }
        if (VpnService.prepare(appContext) != null) throw RouteProbePermissionRequiredException()
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        if (!awaitNoVpnNetwork(connectivityManager)) {
            throw RouteProbeBusyException("Disconnect every active VPN before native MTU validation")
        }
        val capturedNetwork = NetworkFingerprintResolver(appContext).captureAdaptiveContext()
        check(capturedNetwork.fingerprint.exactStorageKey() == request.expectedNetworkKey) {
            "Network changed before native MTU validation was queued"
        }
        val underlyingNetwork = request.underlyingNetwork ?: capturedNetwork.network ?: connectivityManager.activeNetwork
            ?: throw IllegalStateException("No active underlying network for native MTU validation")
        check(capturedNetwork.network?.networkHandle == underlyingNetwork.networkHandle) {
            "The captured underlying network changed before native MTU validation was queued"
        }
        check(capturedNetwork.network?.networkHandle == underlyingNetwork.networkHandle) {
            "Requested underlying network is no longer active"
        }
        val boundRequest = request.copy(underlyingNetwork = underlyingNetwork)
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<RouteNativeMtuProbeResult>()
        pending[id] = Pending(boundRequest, deferred)
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, UacVpnService::class.java)
                    .setAction(UacVpnService.ACTION_ROUTE_MTU_PROBE)
                    .putExtra(UacVpnService.EXTRA_ROUTE_PROBE_ID, id),
            )
            withTimeout(nativeProbeTimeoutMs(request.transferConfig)) { deferred.await() }
        } catch (cancelled: CancellationException) {
            if (deferred.isActive) {
                val cancelledInProcess = cancellationCallbacks[id]?.let { cancel ->
                    runCatching(cancel).isSuccess
                } == true
                if (!cancelledInProcess) {
                    runCatching {
                        appContext.startService(
                            Intent(appContext, UacVpnService::class.java)
                                .setAction(UacVpnService.ACTION_CANCEL_ROUTE_MTU_PROBE)
                                .putExtra(UacVpnService.EXTRA_ROUTE_PROBE_ID, id),
                        )
                    }
                }
                withContext(NonCancellable) {
                    withTimeoutOrNull(NATIVE_PROBE_CANCEL_SETTLE_MS) { deferred.join() }
                }
            }
            throw cancelled
        } finally {
            cancellationCallbacks.remove(id)
            pending.remove(id)
        }
    }

    internal fun request(id: String): RouteNativeMtuProbeRequest? = pending[id]?.request

    internal fun registerCancellation(id: String, cancel: () -> Unit) {
        cancellationCallbacks[id] = cancel
    }

    internal fun unregisterCancellation(id: String) {
        cancellationCallbacks.remove(id)
    }

    internal fun complete(id: String, result: RouteNativeMtuProbeResult) {
        pending[id]?.result?.complete(result)
    }

    internal fun fail(id: String, error: Throwable) {
        pending[id]?.result?.completeExceptionally(error)
    }

    internal fun cancel(id: String) {
        pending[id]?.result?.cancel(CancellationException("Native MTU probe cancelled"))
    }

    private suspend fun awaitNoVpnNetwork(connectivityManager: ConnectivityManager): Boolean {
        val deadline = SystemClock.elapsedRealtime() + PREVIOUS_VPN_SETTLE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val vpnActive = connectivityManager.allNetworks.any { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (!vpnActive) return true
            delay(VPN_SETTLE_POLL_MS)
        }
        return false
    }

    private const val NATIVE_PROBE_CANCEL_SETTLE_MS = 10_000L
    private const val PREVIOUS_VPN_SETTLE_MS = 2_500L
    private const val VPN_SETTLE_POLL_MS = 50L
}

internal fun nativeProbeTimeoutMs(config: RouteTransferProbeConfig): Long {
    val requestCount = config.latencySamples + 2L
    val socketBudget = requestCount * (config.connectTimeoutMs.toLong() + config.readTimeoutMs.toLong())
    return (socketBudget + 20_000L).coerceIn(90_000L, 600_000L)
}
