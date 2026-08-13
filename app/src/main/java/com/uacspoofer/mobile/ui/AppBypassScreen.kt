package com.uacspoofer.mobile.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.vpn.AppRoutingMode
import com.uacspoofer.mobile.vpn.AppRoutingPreferences
import com.uacspoofer.mobile.vpn.InstalledVpnApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class RoutingModeUi(
    val mode: AppRoutingMode,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val RoutingModes = listOf(
    RoutingModeUi(AppRoutingMode.ALL_APPS, "All apps", "Use VPN for every app", Icons.Outlined.Apps),
    RoutingModeUi(
        AppRoutingMode.BYPASS_SELECTED,
        "Bypass selected",
        "Selected apps use the normal network",
        Icons.Outlined.Block,
    ),
    RoutingModeUi(
        AppRoutingMode.VPN_ONLY_SELECTED,
        "VPN only selected",
        "Only selected apps use this VPN",
        Icons.Outlined.CheckCircle,
    ),
)

@Composable
internal fun AppBypassScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    AppRoutingPreferences.initialize(context)
    val settings by AppRoutingPreferences.settings.collectAsState()
    var apps by remember { mutableStateOf<List<InstalledVpnApp>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AppRoutingPreferences.installedApps(context) }
    }

    val filteredApps = remember(apps, query) {
        val needle = query.trim()
        apps.orEmpty().filter {
            needle.isBlank() ||
                it.label.contains(needle, ignoreCase = true) ||
                it.packageName.contains(needle, ignoreCase = true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(UacColors.BackgroundTop, UacColors.BackgroundMiddle, UacColors.BackgroundBottom),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            HomeHeader(
                accent = UacColors.DisconnectedBlue,
                compact = false,
                onMenuClick = onMenuClick,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                    text = "App Bypass",
                color = UacColors.TextPrimary,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                    text = "Choose which apps use the secure tunnel",
                color = UacColors.TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(15.dp))

            RoutingModes.forEach { item ->
                RoutingModeCard(
                    item = item,
                    selected = settings.mode == item.mode,
                    onClick = { AppRoutingPreferences.setMode(context, item.mode) },
                )
                Spacer(Modifier.height(8.dp))
            }

            if (settings.mode != AppRoutingMode.ALL_APPS) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp),
                    placeholder = { Text("Search apps", color = UacColors.TextSecondary) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = UacColors.TextSecondary)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = UacColors.TextPrimary,
                        unfocusedTextColor = UacColors.TextPrimary,
                        focusedBorderColor = UacColors.DisconnectedBlue,
                        unfocusedBorderColor = UacColors.CardBorder,
                        cursorColor = UacColors.DisconnectedBlue,
                        focusedContainerColor = Color(0x99101C29),
                        unfocusedContainerColor = Color(0x66101C29),
                    ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Applications", color = UacColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${settings.selectedPackages.size} selected",
                        color = UacColors.DisconnectedBlue,
                        fontSize = 12.sp,
                    )
                }
                if (apps == null) {
                    Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = UacColors.DisconnectedBlue,
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(filteredApps, key = InstalledVpnApp::packageName) { app ->
                            AppSelectionRow(
                                app = app,
                                selected = app.packageName in settings.selectedPackages,
                                onSelectedChange = { selected ->
                                    AppRoutingPreferences.setPackageSelected(
                                        context,
                                        app.packageName,
                                        selected,
                                    )
                                },
                            )
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
            text = "All installed apps are routed through the VPN.",
                        color = UacColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingModeCard(item: RoutingModeUi, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color(0x26299EFF) else Color(0x99101C29))
            .border(
                1.dp,
                if (selected) UacColors.DisconnectedBlue.copy(alpha = 0.65f) else UacColors.CardBorder,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(UacColors.DisconnectedBlue.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, null, tint = UacColors.DisconnectedBlue, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(item.subtitle, color = UacColors.TextSecondary, fontSize = 11.sp)
        }
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = UacColors.DisconnectedBlue,
                unselectedColor = UacColors.TextSecondary,
            ),
        )
    }
}

@Composable
private fun AppSelectionRow(
    app: InstalledVpnApp,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var icon by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) {
        icon = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(app.packageName)
                    .toBitmap(72, 72)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xA30D1926))
            .border(1.dp, if (selected) Color(0x4D299EFF) else UacColors.CardBorder, shape)
            .clickable { onSelectedChange(!selected) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon!!, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
        } else {
            Box(
                Modifier.size(36.dp).background(Color(0x25299EFF), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(app.label.take(1).uppercase(), color = UacColors.DisconnectedBlue, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                color = UacColors.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                color = UacColors.TextSecondary,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RadioButton(
            selected = selected,
            onClick = { onSelectedChange(!selected) },
            colors = RadioButtonDefaults.colors(
                selectedColor = UacColors.DisconnectedBlue,
                unselectedColor = UacColors.TextSecondary,
            ),
        )
    }
}
