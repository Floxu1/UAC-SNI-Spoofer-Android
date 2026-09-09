package com.uacspoofer.mobile.ui

internal enum class HomeRemoteSlot {
    None,
    Menu,
    EngineXray,
    EngineTor,
    EnginePow,
    Connect,
    Profile,
    Ping,
    Country,
    Log,
    Other,
}

internal enum class RemoteDpad {
    Left,
    Right,
    Up,
    Down,
}

internal sealed class HomeRemoteAction {
    data object OpenDrawer : HomeRemoteAction()
    data class Focus(val slot: HomeRemoteSlot) : HomeRemoteAction()
    data object Ignore : HomeRemoteAction()
}

internal object HomeRemoteNavigation {
    fun shouldDrawFocusRing(focused: Boolean, keyboardInput: Boolean): Boolean =
        focused && keyboardInput

    fun hoverIsVisible(slot: HomeRemoteSlot, keyboardInput: Boolean): Boolean =
        keyboardInput && slot != HomeRemoteSlot.None && slot != HomeRemoteSlot.Other

    fun shouldParkConfirmOnConnect(slot: HomeRemoteSlot, keyboardInput: Boolean): Boolean =
        !hoverIsVisible(slot, keyboardInput)

    fun action(slot: HomeRemoteSlot, dpad: RemoteDpad): HomeRemoteAction = when (slot) {
        HomeRemoteSlot.None -> when (dpad) {
            RemoteDpad.Down -> HomeRemoteAction.Focus(HomeRemoteSlot.Connect)
            RemoteDpad.Left -> HomeRemoteAction.OpenDrawer
            RemoteDpad.Up -> HomeRemoteAction.Focus(HomeRemoteSlot.Menu)
            RemoteDpad.Right -> HomeRemoteAction.Focus(HomeRemoteSlot.EngineXray)
        }
        HomeRemoteSlot.Menu -> when (dpad) {
            RemoteDpad.Left -> HomeRemoteAction.OpenDrawer
            RemoteDpad.Down -> HomeRemoteAction.Focus(HomeRemoteSlot.Connect)
            RemoteDpad.Right -> HomeRemoteAction.Focus(HomeRemoteSlot.EngineXray)
            RemoteDpad.Up -> HomeRemoteAction.Ignore
        }
        HomeRemoteSlot.EngineXray -> engineRailAction(
            dpad = dpad,
            up = null,
            down = HomeRemoteSlot.EngineTor,
        )
        HomeRemoteSlot.EngineTor -> engineRailAction(
            dpad = dpad,
            up = HomeRemoteSlot.EngineXray,
            down = HomeRemoteSlot.EnginePow,
        )
        HomeRemoteSlot.EnginePow -> engineRailAction(
            dpad = dpad,
            up = HomeRemoteSlot.EngineTor,
            down = HomeRemoteSlot.Connect,
        )
        HomeRemoteSlot.Connect -> when (dpad) {
            RemoteDpad.Left -> HomeRemoteAction.OpenDrawer
            RemoteDpad.Up -> HomeRemoteAction.Focus(HomeRemoteSlot.Menu)
            RemoteDpad.Down -> HomeRemoteAction.Focus(HomeRemoteSlot.Profile)
            RemoteDpad.Right -> HomeRemoteAction.Focus(HomeRemoteSlot.EngineXray)
        }
        HomeRemoteSlot.Profile -> when (dpad) {
            RemoteDpad.Left -> HomeRemoteAction.OpenDrawer
            RemoteDpad.Up -> HomeRemoteAction.Focus(HomeRemoteSlot.Connect)
            RemoteDpad.Down -> HomeRemoteAction.Focus(HomeRemoteSlot.Country)
            RemoteDpad.Right -> HomeRemoteAction.Ignore
        }
        HomeRemoteSlot.Ping -> when (dpad) {
            RemoteDpad.Right -> HomeRemoteAction.Focus(HomeRemoteSlot.Country)
            RemoteDpad.Up -> HomeRemoteAction.Focus(HomeRemoteSlot.Profile)
            RemoteDpad.Left,
            RemoteDpad.Down -> HomeRemoteAction.Ignore
        }
        HomeRemoteSlot.Country -> when (dpad) {
            RemoteDpad.Left -> HomeRemoteAction.Focus(HomeRemoteSlot.Ping)
            RemoteDpad.Right -> HomeRemoteAction.Focus(HomeRemoteSlot.Log)
            RemoteDpad.Up -> HomeRemoteAction.Focus(HomeRemoteSlot.Profile)
            RemoteDpad.Down -> HomeRemoteAction.Ignore
        }
        HomeRemoteSlot.Log -> when (dpad) {
            RemoteDpad.Left -> HomeRemoteAction.Focus(HomeRemoteSlot.Country)
            RemoteDpad.Up -> HomeRemoteAction.Focus(HomeRemoteSlot.Profile)
            RemoteDpad.Right,
            RemoteDpad.Down -> HomeRemoteAction.Ignore
        }
        HomeRemoteSlot.Other -> HomeRemoteAction.Ignore
    }

    private fun engineRailAction(
        dpad: RemoteDpad,
        up: HomeRemoteSlot?,
        down: HomeRemoteSlot,
    ): HomeRemoteAction = when (dpad) {
        RemoteDpad.Left -> HomeRemoteAction.Focus(HomeRemoteSlot.Menu)
        RemoteDpad.Down -> HomeRemoteAction.Focus(down)
        RemoteDpad.Up -> up?.let(HomeRemoteAction::Focus) ?: HomeRemoteAction.Ignore
        RemoteDpad.Right -> HomeRemoteAction.Ignore
    }
}

internal object DrawerRemoteNavigation {
    fun consumesHorizontal(dpad: RemoteDpad): Boolean =
        dpad == RemoteDpad.Left || dpad == RemoteDpad.Right
}
