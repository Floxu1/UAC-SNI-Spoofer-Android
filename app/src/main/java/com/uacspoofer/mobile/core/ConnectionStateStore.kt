package com.uacspoofer.mobile.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ConnectionStateStore {
    private val machine = ConnectionStateMachine()
    private val mutableRouteProgress = MutableStateFlow(ConnectRouteProgress.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingError: Job? = null
    val state = machine.state
    val routeProgress: StateFlow<ConnectRouteProgress> = mutableRouteProgress.asStateFlow()

    fun tryBeginConnect(): Boolean {
        cancelPendingError()
        val started = machine.tryBeginConnect()
        if (started) clearRouteProgress()
        return started
    }

    fun markConnecting() {
        cancelPendingError()
        clearRouteProgress()
        machine.markConnecting()
    }

    fun markConnected(): Boolean {
        cancelPendingError()
        val connected = machine.markConnected()
        if (connected) clearRouteProgress()
        return connected
    }

    fun tryBeginDisconnect(): Boolean {
        cancelPendingError()
        val started = machine.tryBeginDisconnect()
        if (started) clearRouteProgress()
        return started
    }

    fun markDisconnected() {
        cancelPendingError()
        clearRouteProgress()
        machine.markDisconnected()
    }

    /**
     * A connect often reports a miss about a second before the winning path comes up.
     * Hold that error while the attempt is still in progress, and drop it if we connect.
     */
    fun markError() {
        clearRouteProgress()
        if (machine.state.value != ConnectionState.CONNECTING) {
            cancelPendingError()
            machine.markError()
            return
        }
        if (pendingError?.isActive == true) return
        pendingError = scope.launch {
            delay(ERROR_HOLD_MS)
            pendingError = null
            if (machine.state.value == ConnectionState.CONNECTING) {
                machine.markError()
            }
        }
    }

    private fun cancelPendingError() {
        pendingError?.cancel()
        pendingError = null
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

    private const val ERROR_HOLD_MS = 1_600L
}
