package com.uacspoofer.mobile.core

object ConnectionStateStore {
    private val machine = ConnectionStateMachine()
    val state = machine.state

    fun tryBeginConnect() = machine.tryBeginConnect()
    fun markConnecting() = machine.markConnecting()
    fun markConnected() = machine.markConnected()
    fun tryBeginDisconnect() = machine.tryBeginDisconnect()
    fun markDisconnected() = machine.markDisconnected()
    fun markError() = machine.markError()
}
