package com.uacspoofer.mobile.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import android.os.SystemClock
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.vpn.UacQuickSettingsTileService

@Composable
internal fun SettingsScreen(
    onMenuClick: () -> Unit,
    onAdvancedSettingsClick: () -> Unit,
) {
    val context = LocalContext.current
    val isPersian = LocalHomePersian.current
    val baseTextStyle = LocalTextStyle.current
    val localizedTextStyle = homeLocalizedFont()?.let { baseTextStyle.copy(fontFamily = it) } ?: baseTextStyle
    var tileNotice by remember { mutableStateOf<String?>(null) }
    val accent = UacColors.DisconnectedBlue
    val tileAddedMessage = homeText("Tile added successfully", "دکمه به پنل اضافه شد")
    val tileAlreadyAddedMessage = homeText("Tile is already in Quick Settings", "دکمه از قبل داخل پنل هست")
    val tileNotAddedMessage = homeText("Tile was not added", "دکمه اضافه نشد")
    val tileManualMessage = homeText(
        "Open Quick Settings edit mode and drag UAC SNI Spoofer into the panel",
        "ویرایش پنل تنظیمات سریع رو باز کن و UAC SNI Spoofer رو به پنل بکش",
    )
    val tileErrorMessage = homeText("Could not request the tile", "درخواست افزودن دکمه انجام نشد")
    val diagnosticsOpen = remember { mutableStateOf(false) }
    val secretTaps = remember { mutableIntStateOf(0) }
    val lastSecretTapAt = remember { mutableLongStateOf(0L) }

    if (diagnosticsOpen.value) {
        RuntimeDiagnosticsScreen(onClose = { diagnosticsOpen.value = false })
        return
    }

    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
        ToolPageScaffold(
            accent = accent,
            header = {
                ToolPageHeader(
                    title = homeText("Settings", "تنظیمات"),
                    subtitle = homeText("App and connection preferences", "تنظیمات برنامه و اتصال"),
                    icon = Icons.Outlined.Settings,
                    accent = accent,
                    onMenuClick = onMenuClick,
                )
            },
        ) {
                item {
                    SettingsNavigationCard(
                        icon = Icons.Outlined.Tune,
                        title = homeText("Advanced Settings", "تنظیمات پیشرفته"),
                        subtitle = homeText(
                            "Connection mode, DNS, TUN and transport controls",
                            "حالت اتصال، DNS، TUN و تنظیمات انتقال",
                        ),
                        isPersian = isPersian,
                        onClick = onAdvancedSettingsClick,
                    )
                }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ToolCardBrush, ToolCardShape)
                            .border(1.dp, accent.copy(alpha = 0.24f), ToolCardShape)
                            .padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIcon(Icons.Outlined.DashboardCustomize, accent)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    homeText("Quick Settings Tile", "دکمه تنظیمات سریع"),
                                    color = UacColors.TextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    homeText(
                                        "Connect or disconnect from the notification shade",
                                        "اتصال یا قطع VPN از پنل بالای گوشی",
                                    ),
                                    color = UacColors.TextSecondary,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                        Button(
                            onClick = {
                                requestQuickSettingsTile(context) { result ->
                                    tileNotice = when (result) {
                                        TileRequestResult.ADDED -> tileAddedMessage
                                        TileRequestResult.ALREADY_ADDED -> tileAlreadyAddedMessage
                                        TileRequestResult.NOT_ADDED -> tileNotAddedMessage
                                        TileRequestResult.ADD_MANUALLY -> tileManualMessage
                                        TileRequestResult.ERROR -> tileErrorMessage
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.size(7.dp))
                            Text(homeText("Add to Quick Settings", "افزودن به تنظیمات سریع"), fontWeight = FontWeight.SemiBold)
                        }
                        tileNotice?.let {
                            Text(
                                text = it,
                                color = accent,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(accent.copy(alpha = 0.08f), RoundedCornerShape(11.dp))
                                    .padding(horizontal = 11.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(168.dp)
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastSecretTapAt.longValue < 500L) {
                                        secretTaps.intValue += 1
                                    } else {
                                        secretTaps.intValue = 1
                                    }
                                    lastSecretTapAt.longValue = now
                                    if (secretTaps.intValue >= 3) {
                                        secretTaps.intValue = 0
                                        diagnosticsOpen.value = true
                                    }
                                }
                            },
                    )
                }
        }
    }
}

@Composable
private fun SettingsNavigationCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isPersian: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, ToolCardShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
            .clickable(onClick = onClick)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon, UacColors.DisconnectedBlue)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = UacColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = UacColors.TextSecondary, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
        Icon(
            if (isPersian) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = UacColors.TextSecondary,
        )
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector, accent: Color) {
    Box(
        modifier = Modifier
            .size(45.dp)
            .background(accent.copy(alpha = 0.12f), CircleShape)
            .border(1.dp, accent.copy(alpha = 0.30f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp))
    }
}

private enum class TileRequestResult { ADDED, ALREADY_ADDED, NOT_ADDED, ADD_MANUALLY, ERROR }

private fun requestQuickSettingsTile(
    context: android.content.Context,
    onResult: (TileRequestResult) -> Unit,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        onResult(TileRequestResult.ADD_MANUALLY)
        return
    }
    runCatching {
        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
            ?: error("StatusBarManager unavailable")
        statusBarManager.requestAddTileService(
            ComponentName(context, UacQuickSettingsTileService::class.java),
            context.getString(R.string.quick_settings_tile_label),
            Icon.createWithResource(context, R.drawable.ic_stat_vpn),
            context.mainExecutor,
        ) { result ->
            onResult(
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> TileRequestResult.ADDED
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> TileRequestResult.ALREADY_ADDED
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> TileRequestResult.NOT_ADDED
                    else -> TileRequestResult.ERROR
                },
            )
        }
    }.onFailure { onResult(TileRequestResult.ERROR) }
}
