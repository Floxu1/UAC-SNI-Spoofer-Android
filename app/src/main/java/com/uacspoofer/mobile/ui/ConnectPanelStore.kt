package com.uacspoofer.mobile.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.ui.theme.styleTokens
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ConnectPanelMode {
    OPEN,
    COLLAPSED,
    CLOSED,
}

/** Remembers whether the connect status card and the step list are open, collapsed, or closed. */
internal class ConnectPanelStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val statusFlow = MutableStateFlow(read(KEY_STATUS))
    private val stepsFlow = MutableStateFlow(read(KEY_STEPS))

    val status: StateFlow<ConnectPanelMode> = statusFlow.asStateFlow()
    val steps: StateFlow<ConnectPanelMode> = stepsFlow.asStateFlow()

    fun setStatus(mode: ConnectPanelMode) = write(KEY_STATUS, mode, statusFlow)

    fun setSteps(mode: ConnectPanelMode) = write(KEY_STEPS, mode, stepsFlow)

    private fun write(key: String, mode: ConnectPanelMode, flow: MutableStateFlow<ConnectPanelMode>) {
        if (flow.value == mode) return
        prefs.edit().putString(key, mode.name).apply()
        flow.value = mode
    }

    private fun read(key: String): ConnectPanelMode {
        val stored = prefs.getString(key, ConnectPanelMode.COLLAPSED.name)
        return ConnectPanelMode.entries.firstOrNull { it.name == stored } ?: ConnectPanelMode.COLLAPSED
    }

    companion object {
        const val PREFS = "uac_connect_panels"
        const val KEY_STATUS = "status"
        const val KEY_STEPS = "steps"

        @Volatile
        private var instance: ConnectPanelStore? = null

        fun get(context: Context): ConnectPanelStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ConnectPanelStore(context.applicationContext).also { instance = it }
            }
        }
    }
}

internal data class ConnectPanelPaint(
    val border: Color,
    val background: Color,
)

@Composable
internal fun connectPanelPaint(primary: Boolean): ConnectPanelPaint {
    val tokens = styleTokens()
    val border = when {
        tokens != null && primary -> tokens.gradientStart
        tokens != null -> tokens.gradientEnd
        primary -> UacColors.DisconnectedBlue
        else -> UacColors.ConnectingCyan
    }
    val background = tokens?.centerInner?.copy(alpha = 0.92f) ?: UacColors.Surface.copy(alpha = 0.94f)
    return ConnectPanelPaint(border, background)
}

@Composable
internal fun connectPanelText(@StringRes id: Int, vararg args: Any): String {
    val context = LocalContext.current
    val persian = LocalHomePersian.current
    val signature = args.joinToString("\u0000")
    return remember(id, persian, signature) {
        val locale = if (persian) Locale("fa") else Locale.ENGLISH
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.createConfigurationContext(config).resources.getString(id, *args)
    }
}

@Composable
internal fun ConnectPanelActions(onCollapse: () -> Unit, onClose: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ConnectPanelButton(
            icon = Icons.Rounded.Remove,
            label = connectPanelText(R.string.connect_panel_collapse),
            onClick = onCollapse,
        )
        ConnectPanelButton(
            icon = Icons.Rounded.Close,
            label = connectPanelText(R.string.connect_panel_close),
            onClick = onClose,
        )
    }
}

@Composable
internal fun ConnectPanelButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = UacColors.TextPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun ConnectPanelCount(done: Int, total: Int, tint: Color) {
    if (total <= 0) return
    Text(
        text = connectPanelText(R.string.connect_panel_count, done, total),
        color = tint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = homeLocalizedFont(),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
