package com.uacspoofer.mobile.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

internal object AppFontScale {
    const val FIXED = 1f

    fun wrap(base: Context): Context {
        val current = base.resources.configuration.fontScale
        if (current == FIXED) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = FIXED
        return base.createConfigurationContext(config)
    }

    fun lock(configuration: Configuration): Configuration {
        if (configuration.fontScale == FIXED) return configuration
        return Configuration(configuration).apply { fontScale = FIXED }
    }

    fun lock(resources: Resources) {
        val config = resources.configuration
        if (config.fontScale == FIXED) return
        config.fontScale = FIXED
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}

@Composable
internal fun ProvideFixedFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val fixed = remember(density.density) { Density(density.density, fontScale = AppFontScale.FIXED) }
    CompositionLocalProvider(LocalDensity provides fixed, content = content)
}
