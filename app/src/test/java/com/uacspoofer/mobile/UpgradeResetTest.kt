package com.uacspoofer.mobile

import com.uacspoofer.mobile.UpgradeReset.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class UpgradeResetTest {
    @Test fun upgradeFromOlderWipesOnce() = assertEquals(Action.WIPE, UpgradeReset.decide(450, markerPresent = false, hasExistingData = true))
    @Test fun markerPreventsRepeat() = assertEquals(Action.NONE, UpgradeReset.decide(450, markerPresent = true, hasExistingData = true))
    @Test fun laterUpdatesDoNotWipe() = assertEquals(Action.NONE, UpgradeReset.decide(451, markerPresent = true, hasExistingData = true))
    @Test fun freshInstallOnlyMarks() = assertEquals(Action.MARK_ONLY, UpgradeReset.decide(450, markerPresent = false, hasExistingData = false))
    @Test fun freshInstallOfLaterVersionOnlyMarks() = assertEquals(Action.MARK_ONLY, UpgradeReset.decide(455, markerPresent = false, hasExistingData = false))
    @Test fun olderBuildsNeverAct() = assertEquals(Action.NONE, UpgradeReset.decide(449, markerPresent = false, hasExistingData = true))
}
