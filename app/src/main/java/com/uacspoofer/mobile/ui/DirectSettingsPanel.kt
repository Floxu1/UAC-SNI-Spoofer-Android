package com.uacspoofer.mobile.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.direct.DirectEngineSettings
import com.uacspoofer.mobile.engine.direct.DirectEngineStore
import com.uacspoofer.mobile.engine.direct.DirectPhase
import com.uacspoofer.mobile.engine.direct.DirectScanMode
import com.uacspoofer.mobile.engine.direct.DirectStatusStore
import com.uacspoofer.mobile.engine.pow.PowRegions
import com.uacspoofer.mobile.ui.theme.UacColors
import java.util.Locale

@Composable
internal fun DirectSettingsScreen(onBack: () -> Unit, unlocked: Boolean, onUnlock: () -> Unit) {
    val accent = DirectAccent
    val context = LocalContext.current
    val store = remember(context) { DirectEngineStore.get(context) }
    val s by store.settings.collectAsStateWithLifecycle()
    val status by DirectStatusStore.status.collectAsStateWithLifecycle()
    val connectionState by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val automaticDnsAddress = status.selectedDnsUrl.takeIf {
        s.autoSelectDns && connectionState == ConnectionState.CONNECTED && status.phase == DirectPhase.CONNECTED
    }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    val unlockTaps = remember { FiveTapSequence() }
    fun save(value: DirectEngineSettings) = store.save(value)

    PowDirectional {
        ToolPageScaffold(
            accent = accent,
            textStyle = homeLocalizedTextStyle().copy(textAlign = TextAlign.Start),
            header = {
                PowCompactHeader(
                    accent = accent,
                    onBack = onBack,
                    title = homeText("Direct settings", "تنظیمات دایرکت"),
                    subtitle = homeText("Engine 4", "موتور ۴"),
                    icon = Icons.Outlined.Bolt,
                )
            },
        ) {
            item {
                DirectConnectionCard(
                    unlocked = unlocked,
                    settings = s,
                    routePending = connectionState == ConnectionState.CONNECTED && status.phase == DirectPhase.CONNECTED &&
                        status.usingWireGuard != s.wireGuardOverDirect,
                    onRouteChange = { save(s.copy(wireGuardOverDirect = it)) },
                    pingMs = status.ipScan?.selectedPingMs.takeIf { connectionState == ConnectionState.CONNECTED },
                    onScanModeChange = { save(s.copy(scanMode = it)) },
                    dnsSummary = status.selectedDnsLabel?.takeIf { automaticDnsAddress != null }
                        ?.let { label ->
                            status.selectedDnsLatencyMs?.let { ms ->
                                homeText("$label · $ms ms", "$label · $ms میلی‌ثانیه")
                            }
                        },
                    onAutoDnsChange = { save(s.copy(autoSelectDns = it)) },
                    onCountryChange = { VpnController.selectDirectExit(context, it) },
                )
            }
            item {
                PowCard {
                    val actionLabel = if (advancedOpen) homeText("Collapse", "جمع کردن") else homeText("Expand", "باز کردن")
                    val arrowRotation by animateFloatAsState(
                        targetValue = if (advancedOpen) 180f else 0f,
                        animationSpec = tween(270),
                        label = "directAdvancedArrow",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .clickable(role = Role.Button, onClickLabel = actionLabel) { advancedOpen = !advancedOpen }
                            .padding(horizontal = PowPad, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PowGap),
                    ) {
                        PowIconTile(Icons.Outlined.Tune, UacColors.TextSecondary)
                        Text(
                            homeText("Advanced settings", "تنظیمات پیشرفته"),
                            modifier = Modifier.weight(1f),
                            color = UacColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Icon(
                            Icons.Outlined.ExpandMore,
                            null,
                            tint = if (advancedOpen) accent else UacColors.TextSecondary,
                            modifier = Modifier.size(20.dp).rotate(arrowRotation),
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(
                    visible = advancedOpen,
                    enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(320)) +
                        fadeIn(animationSpec = tween(220, delayMillis = 45)),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(240)) +
                        fadeOut(animationSpec = tween(140)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DirectNetworkSettings(s, ::save)
                        PowCard {
                            Column(Modifier.padding(PowPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                DirectField(
                                    label = homeText("DNS for international sites", "DNS سایت‌های خارجی"),
                                    value = automaticDnsAddress ?: s.unfilteredDns,
                                    modifier = Modifier.fillMaxWidth(),
                                    readOnly = automaticDnsAddress != null,
                                ) { save(s.copy(unfilteredDns = it)) }
                                DirectField(homeText("DNS for domestic sites", "DNS سایت‌های داخلی"), s.domesticDns, Modifier.fillMaxWidth()) { save(s.copy(domesticDns = it)) }
                                PowHint(homeText("Advanced changes apply on the next connection.", "تنظیمات پیشرفته از اتصال بعدی اعمال می‌شوند."))
                            }
                        }
                        PowButton(
                            text = homeText("Reset settings", "بازنشانی تنظیمات"),
                            enabled = true,
                            accent = accent,
                            filled = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            store.resetToDefaults()
                        }
                    }
                }
            }
            item {
                Spacer(
                    Modifier.fillMaxWidth().height(64.dp).pointerInput(unlockTaps) {
                        detectTapGestures {
                            if (unlockTaps.tap(SystemClock.elapsedRealtime())) {
                                onUnlock()
                            }
                        }
                    },
                )
            }
        }
    }
}

internal val DirectAccent = Color(0xFFFFC46B)

@Composable
private fun DirectConnectionCard(
    unlocked: Boolean,
    settings: DirectEngineSettings,
    routePending: Boolean,
    pingMs: Long?,
    onRouteChange: (Boolean) -> Unit,
    onScanModeChange: (DirectScanMode) -> Unit,
    dnsSummary: String?,
    onAutoDnsChange: (Boolean) -> Unit,
    onCountryChange: (String) -> Unit,
) {
    PowCard {
        if (unlocked) {
            Column(Modifier.padding(PowPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    homeText("Connection mode", "روش اتصال"),
                    color = UacColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DirectModeChoice(
                        label = homeText("Direct", "مستقیم"),
                        icon = Icons.Outlined.Bolt,
                        selected = !settings.wireGuardOverDirect,
                        modifier = Modifier.weight(1f),
                        onSelect = { onRouteChange(false) },
                    )
                    DirectModeChoice(
                        label = homeText("Cloudflare", "کلودفلر"),
                        icon = Icons.Outlined.Cloud,
                        selected = settings.wireGuardOverDirect,
                        modifier = Modifier.weight(1f),
                        onSelect = { onRouteChange(true) },
                    )
                }
                if (routePending) {
                    PowHint(homeText("The new mode applies on the next connection.", "روش جدید از اتصال بعدی اعمال می‌شود."))
                }
            }
        }
        if (unlocked && settings.wireGuardOverDirect) {
            PowRowDivider()
            val nameLocale = if (LocalHomePersian.current) Locale("fa") else Locale.ENGLISH
            val countries = remember(nameLocale) {
                PowRegions.options(emptyList()).map { PowMenuChoice(it, PowRegions.name(it, nameLocale)) }
            }
            PowPickerRow(
                icon = Icons.Outlined.Public,
                label = homeText("Exit country", "کشور خروجی"),
                sheetTitle = homeText("Exit country", "کشور خروجی"),
                subtitle = homeText("Picking a country turns on Psiphon.", "با انتخاب کشور، سایفون روشن می‌شود."),
                choices = listOf(PowMenuChoice("", homeText("Automatic", "خودکار"))) + countries,
                selectedId = settings.exitCountryCode,
                unlistedTitle = settings.exitCountryCode.takeIf { it.isNotBlank() }?.let { PowRegions.name(it, nameLocale) },
                accent = DirectAccent,
                onSelect = onCountryChange,
            )
            PowRowDivider()
        }
        if (settings.wireGuardOverDirect) {
            val scanLabel = homeText("Search mode", "حالت جست‌وجو")
            PowPickerRow(
                icon = Icons.Outlined.Speed,
                label = scanLabel,
                sheetTitle = scanLabel,
                choices = listOf(
                    PowMenuChoice(
                        DirectScanMode.TURBO.id,
                        homeText("Turbo", "توربو"),
                        homeText("Checks a short list for faster startup.", "برای اتصال سریع، چند مسیر را بررسی می‌کند."),
                    ),
                    PowMenuChoice(
                        DirectScanMode.BALANCED.id,
                        homeText("Balanced", "متعادل"),
                        homeText("Checks a sample of addresses for lower ping.", "نمونه‌ای از آدرس‌ها را برای پینگ کمتر بررسی می‌کند."),
                    ),
                    PowMenuChoice(
                        DirectScanMode.DEEP.id,
                        homeText("Deep", "عمیق"),
                        homeText("Checks the full address range. Takes longer.", "تمام آدرس‌های محدوده را بررسی می‌کند و بیشتر طول می‌کشد."),
                    ),
                ),
                subtitle = pingMs?.let {
                    homeText("Current route ping: $it ms", "پینگ مسیر فعلی: $it میلی‌ثانیه")
                },
                selectedId = settings.scanMode.id,
                accent = DirectAccent,
                onSelect = { onScanModeChange(DirectScanMode.fromStored(it)) },
            )
        }
        if (unlocked || settings.wireGuardOverDirect) PowRowDivider()
        PowSwitchRow(
            icon = Icons.Outlined.Public,
            title = homeText("Choose DNS automatically", "انتخاب خودکار DNS"),
            subtitle = dnsSummary ?: homeText(
                "Tests DNS services at each connection and picks the fastest that responds correctly.",
                "در هر اتصال، سریع‌ترین DNS با پاسخ سالم را انتخاب می‌کند.",
            ),
            checked = settings.autoSelectDns,
            accent = DirectAccent,
            onCheckedChange = onAutoDnsChange,
        )
    }
}

@Composable
private fun DirectModeChoice(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .heightIn(min = 54.dp)
            .clip(shape)
            .background(if (selected) DirectAccent.copy(alpha = 0.14f) else UacColors.BackgroundBottom.copy(alpha = 0.38f))
            .border(1.dp, if (selected) DirectAccent.copy(alpha = 0.55f) else UacColors.Divider, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = if (selected) DirectAccent else UacColors.TextSecondary, modifier = Modifier.size(18.dp))
        Text(label, Modifier.weight(1f), color = UacColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.size(18.dp),
            colors = RadioButtonDefaults.colors(selectedColor = DirectAccent, unselectedColor = UacColors.TextSecondary),
        )
    }
}

@Composable
private fun DirectNetworkSettings(s: DirectEngineSettings, save: (DirectEngineSettings) -> Unit) {
    PowCard {
        PowSwitchRow(Icons.Outlined.Speed, homeText("Split other packets", "تقسیم بسته‌های دیگر"), "", s.tcpFragmentEnabled, DirectAccent) {
            save(s.copy(tcpFragmentEnabled = it))
        }
        if (s.tcpFragmentEnabled) {
            DirectPacketFields(s.tcpLengths, s.tcpDelays, s.tcpMaxSplit,
                { save(s.copy(tcpLengths = it)) }, { save(s.copy(tcpDelays = it)) }, { save(s.copy(tcpMaxSplit = it)) })
        }
        PowRowDivider()
        PowSwitchRow(Icons.Outlined.Bolt, homeText("Send extra packets", "ارسال بسته‌های کمکی"), "", s.udpNoise, DirectAccent) {
            save(s.copy(udpNoise = it))
        }
        PowRowDivider()
        PowSwitchRow(Icons.Outlined.Shield, homeText("Browser compatibility", "سازگاری مرورگرها"), "", s.blockQuic, DirectAccent) {
            save(s.copy(blockQuic = it))
        }
        PowRowDivider()
        PowSwitchRow(Icons.Outlined.Public, homeText("IPv6 support", "پشتیبانی از IPv6"), "", s.ipv6, DirectAccent) {
            save(s.copy(ipv6 = it))
        }
    }
}

@Composable
private fun DirectPacketFields(
    lengths: String,
    delays: String,
    limit: Int,
    onLengthsChange: (String) -> Unit,
    onDelaysChange: (String) -> Unit,
    onLimitChange: (Int) -> Unit,
) {
    Row(Modifier.padding(PowPad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DirectField(homeText("Size", "اندازه"), lengths, Modifier.weight(1f), onChange = onLengthsChange)
        DirectField(homeText("Delay (ms)", "مکث"), delays, Modifier.weight(1f), onChange = onDelaysChange)
        DirectNumber(homeText("Max parts", "سقف بخش‌ها"), limit, Modifier.weight(1f), onLimitChange)
    }
}

@Composable
private fun DirectField(
    label: String,
    value: String,
    modifier: Modifier,
    readOnly: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        readOnly = readOnly,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = DirectAccent,
        ),
    )
}

@Composable
private fun DirectNumber(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { raw -> raw.filter(Char::isDigit).take(4).toIntOrNull()?.let(onChange) },
        modifier = modifier,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = DirectAccent,
        ),
    )
}
