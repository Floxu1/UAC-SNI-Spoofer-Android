package com.uacspoofer.mobile.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectRouteProgressTest {
    @After
    fun resetStore() {
        ConnectionStateStore.markDisconnected()
    }

    @Test
    fun idleProgressIsNotActive() {
        assertFalse(ConnectRouteProgress.Idle.isActive)
        assertFalse(ConnectRouteProgress(current = 1, total = 0).isActive)
        assertTrue(ConnectRouteProgress(current = 2, total = 7).isActive)
    }

    @Test
    fun connectingUpdatesRouteProgressAndTerminalStatesClearIt() {
        assertTrue(ConnectionStateStore.tryBeginConnect())
        ConnectionStateStore.updateConnectRouteProgress(1, 7)
        assertEquals(ConnectRouteProgress(1, 7), ConnectionStateStore.routeProgress.value)

        ConnectionStateStore.updateConnectRouteProgress(3, 7)
        assertEquals(ConnectRouteProgress(3, 7), ConnectionStateStore.routeProgress.value)

        assertTrue(ConnectionStateStore.markConnected())
        assertEquals(ConnectRouteProgress.Idle, ConnectionStateStore.routeProgress.value)
    }

    @Test
    fun progressIsIgnoredUnlessConnecting() {
        ConnectionStateStore.markDisconnected()
        ConnectionStateStore.updateConnectRouteProgress(1, 7)
        assertEquals(ConnectRouteProgress.Idle, ConnectionStateStore.routeProgress.value)
    }
}
