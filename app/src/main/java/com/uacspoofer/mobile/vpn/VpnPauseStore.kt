package com.uacspoofer.mobile.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session-only "pause tunnel" flag. Does not persist across process death.
 * Cleared on full disconnect. Engines honor this by routing everything direct
 * without tearing down the VPN interface or discarding session keys.
 */
object VpnPauseStore {
    private val flag = AtomicBoolean(false)
    private val mutable = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = mutable.asStateFlow()

    fun isPaused(): Boolean = flag.get()

    fun setPaused(value: Boolean) {
        flag.set(value)
        mutable.value = value
    }

    fun toggle(): Boolean {
        while (true) {
            val cur = flag.get()
            if (flag.compareAndSet(cur, !cur)) {
                mutable.value = !cur
                return !cur
            }
        }
    }

    fun clear() {
        flag.set(false)
        mutable.value = false
    }
}
