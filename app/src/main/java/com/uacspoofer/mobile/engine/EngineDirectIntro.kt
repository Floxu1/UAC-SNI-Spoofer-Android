package com.uacspoofer.mobile.engine

/**
 * Open on Direct once when installing or updating to 2.1.4.
 * Later launches keep whatever the user last picked.
 */
internal object EngineDirectIntro {
    const val FLAG_KEY = "direct_intro_214"

    fun resolve(stored: EngineMode, introApplied: Boolean): EngineMode =
        if (introApplied) stored else EngineMode.UAC_DIRECT
}
