package com.uacspoofer.mobile.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectionStateMachine(initial: ConnectionState = ConnectionState.DISCONNECTED) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    @Synchronized
    fun tryBeginConnect(): Boolean {
        if (mutableState.value !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) {
            return false
        }
        mutableState.value = ConnectionState.CONNECTING
        return true
    }

    @Synchronized
    fun markConnecting() {
        if (mutableState.value != ConnectionState.DISCONNECTING) {
            mutableState.value = ConnectionState.CONNECTING
        }
    }

    @Synchronized
    fun markConnected(): Boolean {
        if (mutableState.value != ConnectionState.CONNECTING) return false
        mutableState.value = ConnectionState.CONNECTED
        return true
    }

    @Synchronized
    fun tryBeginDisconnect(): Boolean {
        if (mutableState.value !in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED)) {
            return false
        }
        mutableState.value = ConnectionState.DISCONNECTING
        return true
    }

    @Synchronized
    fun markDisconnected() {
        mutableState.value = ConnectionState.DISCONNECTED
    }

    @Synchronized
    fun markError() {
        if (mutableState.value != ConnectionState.DISCONNECTING) {
            mutableState.value = ConnectionState.ERROR
        }
    }
}
