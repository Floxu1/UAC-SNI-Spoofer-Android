package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.tor.TorDaemon
import com.uacspoofer.mobile.engine.tor.TorEngineSettings
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.engine.tor.WebTunnelBridgeParser
import com.uacspoofer.mobile.ui.theme.UacColors

@Composable
internal fun TorSettingsScreen(onBack: () -> Unit) {
    val accent = UacColors.DisconnectedBlue
    ToolPageScaffold(
        accent = accent,
        header = {
            ToolPageHeader(
                title = homeText("Tor", "Tor"),
                subtitle = homeText("WebTunnel bridges and TLS hop", "بریج‌های WebTunnel و پرش TLS"),
                icon = Icons.Outlined.Shield,
                accent = accent,
                onMenuClick = onBack,
                navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                navigationDescription = homeText("Back to settings", "برگشت به تنظیمات"),
            )
        },
    ) {
        item { TorSettingsPanel() }
    }
}

@Composable
private fun TorSettingsPanel() {
    val context = LocalContext.current
    val store = remember(context) { TorEngineStore.get(context) }
    val daemon = remember(context) { TorDaemon(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val accent = UacColors.DisconnectedBlue
    val parsedBridges = remember(settings.bridgeLines) { WebTunnelBridgeParser.parseAll(settings.bridgeLines) }
    val ignoredBridgeLines = remember(settings.bridgeLines) {
        settings.bridgeLines.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .count() - parsedBridges.size
    }
    val torBinaryReady = daemon.locateTorBinary() != null
    val pluginReady = daemon.locateWebTunnelPlugin() != null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!torBinaryReady) {
            TorNotice(
                homeText(
                    "Tor runtime is not bundled yet. Connect in this engine fails until libtor.so is present.",
                    "باینری Tor هنوز داخل برنامه نیست. تا وقتی libtor.so نباشد، اتصال این موتور انجام نمی‌شود.",
                ),
                error = true,
            )
        }
        if (parsedBridges.isNotEmpty() && !pluginReady) {
            TorNotice(
                homeText(
                    "WebTunnel plugin is missing. Pasted bridges need libwebtunnel.so.",
                    "پلاگین WebTunnel پیدا نشد. بریج‌های چسبانده‌شده به libwebtunnel.so نیاز دارند.",
                ),
                error = true,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ToolCardBrush, ToolCardShape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(homeText("WebTunnel bridges", "بریج‌های WebTunnel"))
            Text(
                homeText(
                    "Paste webtunnel lines, or leave empty to try the built-in WebTunnel list. vless / xhttp configs are not bridges.",
                    "خط‌های webtunnel را بچسبان، یا خالی بگذار تا لیست داخلی امتحان شود. کانفیگ vless / xhttp بریج نیست.",
                ),
                color = UacColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            OutlinedTextField(
                value = settings.bridgeLines,
                onValueChange = { store.save(settings.copy(bridgeLines = it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp),
                placeholder = {
                    Text(
                        "webtunnel 192.0.2.10:443 url=https://example.com/path",
                        color = UacColors.TextSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp,
                ),
                colors = torFieldColors(),
            )
            Text(
                when {
                    parsedBridges.isEmpty() && ignoredBridgeLines == 0 -> homeText(
                        "Empty list — built-in WebTunnel bridges will be tried",
                        "لیست خالی — بریج‌های داخلی WebTunnel امتحان می‌شوند",
                    )
                    ignoredBridgeLines > 0 -> homeText(
                        "${parsedBridges.size} WebTunnel bridge(s) ready · $ignoredBridgeLines line(s) ignored",
                        "${parsedBridges.size} بریج WebTunnel آماده · $ignoredBridgeLines خط نادیده گرفته شد",
                    )
                    else -> homeText(
                        "${parsedBridges.size} WebTunnel bridge(s) ready",
                        "${parsedBridges.size} بریج WebTunnel آماده است",
                    )
                },
                color = if (ignoredBridgeLines > 0) UacColors.ErrorRed else accent,
                fontSize = 12.sp,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ToolCardBrush, ToolCardShape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(homeText("WebTunnel TLS hop", "پرش WebTunnel TLS"))
            Text(
                homeText(
                    "Fragment applies only to the HTTPS hop, never to inner Tor traffic.",
                    "تکه کردن فقط روی پرش HTTPS اعمال می‌شود، نه روی ترافیک داخلی Tor.",
                ),
                color = UacColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    homeText("Fragment hop", "تکه کردن پرش"),
                    color = UacColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(
                    checked = settings.fragmentEnabled,
                    onCheckedChange = { store.save(settings.copy(fragmentEnabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = accent,
                        checkedThumbColor = Color.White,
                    ),
                )
            }
            if (settings.fragmentEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TorMiniField(
                        label = homeText("Packet", "پکت"),
                        value = settings.fragmentPacket,
                        onChange = { store.save(settings.copy(fragmentPacket = it)) },
                        modifier = Modifier.weight(1.2f),
                    )
                    TorMiniField(
                        label = homeText("Length", "طول"),
                        value = settings.fragmentLength.toString(),
                        onChange = { raw ->
                            val parsed = raw.toIntOrNull()
                            when {
                                parsed != null -> store.save(settings.copy(fragmentLength = parsed))
                                raw.isBlank() -> store.save(settings.copy(fragmentLength = 1))
                            }
                        },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(0.8f),
                    )
                    TorMiniField(
                        label = homeText("Delay ms", "تاخیر ms"),
                        value = settings.fragmentDelayMs.toString(),
                        onChange = { raw ->
                            val parsed = raw.toIntOrNull()
                            when {
                                parsed != null -> store.save(settings.copy(fragmentDelayMs = parsed))
                                raw.isBlank() -> store.save(settings.copy(fragmentDelayMs = 0))
                            }
                        },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(0.9f),
                    )
                }
            }
            Text(
                homeText(
                    "Tunnel = device VPN into Tor SOCKS 127.0.0.1:${TorEngineSettings.SOCKS_PORT}.",
                    "حالت Tunnel یعنی VPN دستگاه به SOCKS داخلی 127.0.0.1:${TorEngineSettings.SOCKS_PORT}.",
                ),
                color = UacColors.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun TorNotice(text: String, error: Boolean) {
    val color = if (error) UacColors.ErrorRed else UacColors.DisconnectedBlue
    Text(
        text = text,
        color = color,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), ToolCardShape)
            .border(1.dp, color.copy(alpha = 0.24f), ToolCardShape)
            .padding(14.dp),
    )
}

@Composable
private fun TorMiniField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = torFieldColors(),
    )
}

@Composable
private fun torFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = UacColors.DisconnectedBlue,
    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
    focusedLabelColor = UacColors.TextSecondary,
    unfocusedLabelColor = UacColors.TextSecondary,
    cursorColor = UacColors.DisconnectedBlue,
)
