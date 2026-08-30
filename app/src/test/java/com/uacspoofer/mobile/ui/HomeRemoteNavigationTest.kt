package com.uacspoofer.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRemoteNavigationTest {
    @Test
    fun firstConfirmWithoutHoverParksOnConnect() {
        assertTrue(
            HomeRemoteNavigation.shouldParkConfirmOnConnect(
                slot = HomeRemoteSlot.None,
                keyboardInput = false,
            ),
        )
        assertTrue(
            HomeRemoteNavigation.shouldParkConfirmOnConnect(
                slot = HomeRemoteSlot.Menu,
                keyboardInput = false,
            ),
        )
        assertTrue(
            HomeRemoteNavigation.shouldParkConfirmOnConnect(
                slot = HomeRemoteSlot.None,
                keyboardInput = true,
            ),
        )
    }

    @Test
    fun confirmOnHoveredConnectDoesNotPark() {
        assertFalse(
            HomeRemoteNavigation.shouldParkConfirmOnConnect(
                slot = HomeRemoteSlot.Connect,
                keyboardInput = true,
            ),
        )
        assertFalse(
            HomeRemoteNavigation.shouldParkConfirmOnConnect(
                slot = HomeRemoteSlot.Engine,
                keyboardInput = true,
            ),
        )
    }

    @Test
    fun hoverIsVisibleOnlyForKeyboardOnASlot() {
        assertFalse(HomeRemoteNavigation.hoverIsVisible(HomeRemoteSlot.Connect, keyboardInput = false))
        assertFalse(HomeRemoteNavigation.hoverIsVisible(HomeRemoteSlot.None, keyboardInput = true))
        assertTrue(HomeRemoteNavigation.hoverIsVisible(HomeRemoteSlot.Connect, keyboardInput = true))
    }

    @Test
    fun homeDownFromNowhereGoesToConnect() {
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Connect),
            HomeRemoteNavigation.action(HomeRemoteSlot.None, RemoteDpad.Down),
        )
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Connect),
            HomeRemoteNavigation.action(HomeRemoteSlot.Menu, RemoteDpad.Down),
        )
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Connect),
            HomeRemoteNavigation.action(HomeRemoteSlot.Engine, RemoteDpad.Down),
        )
    }

    @Test
    fun homeLeftOpensDrawerFromMainControls() {
        assertEquals(
            HomeRemoteAction.OpenDrawer,
            HomeRemoteNavigation.action(HomeRemoteSlot.None, RemoteDpad.Left),
        )
        assertEquals(
            HomeRemoteAction.OpenDrawer,
            HomeRemoteNavigation.action(HomeRemoteSlot.Menu, RemoteDpad.Left),
        )
        assertEquals(
            HomeRemoteAction.OpenDrawer,
            HomeRemoteNavigation.action(HomeRemoteSlot.Connect, RemoteDpad.Left),
        )
        assertEquals(
            HomeRemoteAction.OpenDrawer,
            HomeRemoteNavigation.action(HomeRemoteSlot.Profile, RemoteDpad.Left),
        )
    }

    @Test
    fun engineLeftGoesToMenuNotDrawer() {
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Menu),
            HomeRemoteNavigation.action(HomeRemoteSlot.Engine, RemoteDpad.Left),
        )
    }

    @Test
    fun connectUpGoesToMenuAndDownGoesToProfile() {
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Menu),
            HomeRemoteNavigation.action(HomeRemoteSlot.Connect, RemoteDpad.Up),
        )
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Profile),
            HomeRemoteNavigation.action(HomeRemoteSlot.Connect, RemoteDpad.Down),
        )
    }

    @Test
    fun countryRowMovesLeftToPingAndRightToLog() {
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Ping),
            HomeRemoteNavigation.action(HomeRemoteSlot.Country, RemoteDpad.Left),
        )
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Log),
            HomeRemoteNavigation.action(HomeRemoteSlot.Country, RemoteDpad.Right),
        )
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Profile),
            HomeRemoteNavigation.action(HomeRemoteSlot.Country, RemoteDpad.Up),
        )
    }

    @Test
    fun profileDownGoesToCountryRow() {
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Country),
            HomeRemoteNavigation.action(HomeRemoteSlot.Profile, RemoteDpad.Down),
        )
    }

    @Test
    fun pingAndLogStayOnTheMetricsRow() {
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Country),
            HomeRemoteNavigation.action(HomeRemoteSlot.Ping, RemoteDpad.Right),
        )
        assertEquals(
            HomeRemoteAction.Focus(HomeRemoteSlot.Country),
            HomeRemoteNavigation.action(HomeRemoteSlot.Log, RemoteDpad.Left),
        )
        assertEquals(
            HomeRemoteAction.Ignore,
            HomeRemoteNavigation.action(HomeRemoteSlot.Ping, RemoteDpad.Left),
        )
        assertEquals(
            HomeRemoteAction.Ignore,
            HomeRemoteNavigation.action(HomeRemoteSlot.Log, RemoteDpad.Right),
        )
    }

    @Test
    fun otherSlotsKeepDefaultHorizontalMoves() {
        assertEquals(
            HomeRemoteAction.Ignore,
            HomeRemoteNavigation.action(HomeRemoteSlot.Other, RemoteDpad.Left),
        )
        assertEquals(
            HomeRemoteAction.Ignore,
            HomeRemoteNavigation.action(HomeRemoteSlot.Other, RemoteDpad.Right),
        )
    }

    @Test
    fun drawerKeepsFocusOnLeftAndRight() {
        assertTrue(DrawerRemoteNavigation.consumesHorizontal(RemoteDpad.Left))
        assertTrue(DrawerRemoteNavigation.consumesHorizontal(RemoteDpad.Right))
        assertFalse(DrawerRemoteNavigation.consumesHorizontal(RemoteDpad.Up))
        assertFalse(DrawerRemoteNavigation.consumesHorizontal(RemoteDpad.Down))
    }
}
