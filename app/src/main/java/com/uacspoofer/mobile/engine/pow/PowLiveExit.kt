package com.uacspoofer.mobile.engine.pow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live UAC PoW exit, published by [PowConnectionCoordinator] (main process, same as the UI).
 * In WARP-only mode the device SOCKS is Aether's 1820, not Psiphon's 1819.
 */
/** Live path snapshot for the UI graph. Pushed on events only; nothing polls. */
data class PowLivePath(
    /** chain, warp, psiphon (user picked), direct (WARP failed, Psiphon fell back). */
    val mode: String = "",
    val mtu: Int = 0,
    val outerProtocol: String = "",
    val outerTransport: String = "",
    val psiphonProtocol: String = "",
    val ghost: Boolean = false,
    val retuning: Boolean = false,
    val h2Fragment: Boolean? = null,
)

object PowLiveExit {
    private val pathFlow = MutableStateFlow(PowLivePath())
    val path: StateFlow<PowLivePath> = pathFlow.asStateFlow()

    fun updatePath(block: (PowLivePath) -> PowLivePath) {
        pathFlow.value = block(pathFlow.value)
    }

    private val warpOnlyFlow = MutableStateFlow(false)
    val warpOnlyState: StateFlow<Boolean> = warpOnlyFlow.asStateFlow()

    @Volatile var socksPort: Int = PowCoreConfig.SOCKS_PORT
        private set

    val warpOnly: Boolean get() = warpOnlyFlow.value

    /** SOCKS port for UI probes in TUN mode. */
    fun tunSocksPort(): Int = if (warpOnly) PowCoreConfig.CHAIN_SOCKS_PORT else socksPort

    fun publish(port: Int, warp: Boolean) {
        socksPort = if (port > 0) port else PowCoreConfig.SOCKS_PORT
        warpOnlyFlow.value = warp
    }

    fun reset() {
        publish(PowCoreConfig.SOCKS_PORT, false)
        pathFlow.value = PowLivePath()
    }
}