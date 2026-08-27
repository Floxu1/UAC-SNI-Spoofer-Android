package com.uacspoofer.mobile.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConnectionStateStore {
    private val machine = ConnectionStateMachine()
    private val mutableRouteProgress = MutableStateFlow(ConnectRouteProgress.Idle)
    val state = machine.state
    val routeProgress: StateFlow<ConnectRouteProgress> = mutableRouteProgress.asStateFlow()

    fun tryBeginConnect(): Boolean {
        val started = machine.tryBeginConnect()
        if (started) clearRouteProgress()
        return started
    }

    fun markConnecting() {
        clearRouteProgress()
        machine.markConnecting()
    }

    fun markConnected(): Boolean {
        val connected = machine.markConnected()
        if (connected) clearRouteProgress()
        return connected
    }

    fun tryBeginDisconnect(): Boolean {
        val started = machine.tryBeginDisconnect()
        if (started) clearRouteProgress()
        return started
    }

    fun markDisconnected() {
        clearRouteProgress()
        machine.markDisconnected()
    }

    fun markError() {
        clearRouteProgress()
        machine.markError()
    }

    fun updateConnectRouteProgress(current: Int, total: Int) {
        if (state.value != ConnectionState.CONNECTING) return
        mutableRouteProgress.value = ConnectRouteProgress(
            current = current.coerceAtLeast(0),
            total = total.coerceAtLeast(0),
        )
    }

    private fun clearRouteProgress() {
        mutableRouteProgress.value = ConnectRouteProgress.Idle
    }
}
