package com.uacspoofer.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.direct.DirectEngineSettings
import com.uacspoofer.mobile.engine.direct.DirectEngineStore
import com.uacspoofer.mobile.engine.direct.DirectStatusStore
import com.uacspoofer.mobile.ui.theme.UacColors

/** Engine 4 (UAC Direct) settings: serverless direct DPI bypass. */
@Composable
internal fun DirectSettingsScreen(onBack: () -> Unit) {
    val accent = DirectAccent
    ToolPageScaffold(
        accent = accent,
        verticalSpacing = PowGap,
        header = {
            PowCompactHeader(
                accent = accent,
                onBack = onBack,
                title = homeText("UAC Direct", "UAC Direct"),
                subtitle = homeText("Engine 4 · serverless", "موتور ۴ · بدون سرور"),
                icon = Icons.Outlined.Bolt,
            )
        },
    ) {
        item { DirectSettingsPanel() }
    }
}

internal val DirectAccent = Color(0xFFFFC46B)

@Composable
private fun DirectSettingsPanel() {
    val context = LocalContext.current
    val store = remember(context) { DirectEngineStore.get(context) }
    val s by store.settings.collectAsStateWithLifecycle()
    val status by DirectStatusStore.status.collectAsStateWithLifecycle()
    val accent = DirectAccent
    fun save(v: DirectEngineSettings) = store.save(v)

    PowDirectional {
        Column(verticalArrangement = Arrangement.spacedBy(PowGap)) {
            PowCard {
                Column(Modifier.padding(PowPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PowHint(
                        homeText(
                            "No server and no worker: traffic goes straight to each site and only the TLS ClientHello is split so SNI-based DPI cannot read it. Sites blocked by IP cannot be opened this way, and sites that sanction Iran still see your Iranian IP.",
                            "بدون سرور و بدون ورکر: ترافیک مستقیم به خود سایت می‌ره و فقط ClientHello تیکه‌تیکه می‌شه تا DPI اسم سایت رو نخونه. سایت‌هایی که IPشون بسته‌ست با این روش باز نمی‌شن، و سایت‌هایی که ایران رو تحریم کردن هنوز IP ایرانی رو می‌بینن.",
                        ),
                        UacColors.TextPrimary,
                    )
                    if (status.detail.isNotBlank()) PowHint(status.detail)
                }
            }
            SectionLabel(homeText("TLS ClientHello", "ClientHello در TLS"))
            PowCard {
                PowPickerRow(
                    icon = Icons.Outlined.Tune,
                    label = homeText("Variant", "نسخه"),
                    sheetTitle = homeText("Fragment variant", "نسخه‌ی تیکه‌کردن"),
                    choices = listOf(
                        PowMenuChoice(DirectEngineSettings.VARIANT_A, "A · 6/98/1", homeText("Default, light split", "پیش‌فرض، تیکه‌کردن سبک")),
                        PowMenuChoice(DirectEngineSettings.VARIANT_B, "B · 0/104/1", homeText("Alternative split", "تیکه‌کردن جایگزین")),
                    ),
                    selectedId = s.variant,
                    accent = accent,
                    onSelect = { save(s.withVariant(it)) },
                )
                PowRowDivider()
                Column(Modifier.padding(PowPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DirectField(homeText("Lengths", "طول‌ها"), s.helloLengths, Modifier.weight(1f)) { save(s.copy(helloLengths = it)) }
                        DirectField(homeText("Delays ms", "تأخیر"), s.helloDelays, Modifier.weight(1f)) { save(s.copy(helloDelays = it)) }
                        DirectNumber(homeText("Max split", "حداکثر تیکه"), s.helloMaxSplit, Modifier.weight(1f)) { save(s.copy(helloMaxSplit = it)) }
                    }
                }
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Shield,
                    title = homeText("Second stage", "مرحله‌ی دوم"),
                    subtitle = homeText("Splits the first packets again", "پکت‌های اول رو دوباره تیکه می‌کنه"),
                    checked = s.secondStageEnabled,
                    accent = accent,
                ) { save(s.copy(secondStageEnabled = it)) }
                if (s.secondStageEnabled) {
                    Row(Modifier.padding(PowPad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DirectField(homeText("Lengths", "طول‌ها"), s.secondLengths, Modifier.weight(1f)) { save(s.copy(secondLengths = it)) }
                        DirectField(homeText("Delays ms", "تأخیر"), s.secondDelays, Modifier.weight(1f)) { save(s.copy(secondDelays = it)) }
                        DirectNumber(homeText("Max split", "حداکثر تیکه"), s.secondMaxSplit, Modifier.weight(1f)) { save(s.copy(secondMaxSplit = it)) }
                    }
                }
            }
            SectionLabel(homeText("Other TCP and UDP", "بقیه‌ی TCP و UDP"))
            PowCard {
                PowSwitchRow(
                    icon = Icons.Outlined.Speed,
                    title = homeText("Fragment other TCP", "تیکه‌کردن بقیه‌ی TCP"),
                    subtitle = homeText("Non-TLS TCP in small pieces", "TCP غیر TLS به تیکه‌های کوچیک"),
                    checked = s.tcpFragmentEnabled,
                    accent = accent,
                ) { save(s.copy(tcpFragmentEnabled = it)) }
                if (s.tcpFragmentEnabled) {
                    Row(Modifier.padding(PowPad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DirectField(homeText("Lengths", "طول‌ها"), s.tcpLengths, Modifier.weight(1f)) { save(s.copy(tcpLengths = it)) }
                        DirectField(homeText("Delays ms", "تأخیر"), s.tcpDelays, Modifier.weight(1f)) { save(s.copy(tcpDelays = it)) }
                        DirectNumber(homeText("Max split", "حداکثر تیکه"), s.tcpMaxSplit, Modifier.weight(1f)) { save(s.copy(tcpMaxSplit = it)) }
                    }
                }
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = homeText("UDP noise", "نویز UDP"),
                    subtitle = homeText("Random packets before UDP traffic", "پکت‌های الکی قبل از ترافیک UDP"),
                    checked = s.udpNoise,
                    accent = accent,
                ) { save(s.copy(udpNoise = it)) }
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Shield,
                    title = homeText("Block QUIC", "بستن QUIC"),
                    subtitle = homeText("Apps fall back to TCP, where splitting works", "برنامه‌ها می‌رن سراغ TCP که تیکه‌کردن روش جواب می‌ده"),
                    checked = s.blockQuic,
                    accent = accent,
                ) { save(s.copy(blockQuic = it)) }
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Public,
                    title = homeText("IPv6", "IPv6"),
                    subtitle = homeText("Off: IPv4 only", "خاموش: فقط IPv4"),
                    checked = s.ipv6,
                    accent = accent,
                ) { save(s.copy(ipv6 = it)) }
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Public,
                    title = homeText("Iranian sites direct", "سایت‌های ایرانی مستقیم"),
                    subtitle = homeText("No splitting, domestic DNS", "بدون تیکه‌کردن، با DNS داخلی"),
                    checked = s.iranDirect,
                    accent = accent,
                ) { save(s.copy(iranDirect = it)) }
            }
            SectionLabel(homeText("DNS", "DNS"))
            PowCard {
                Column(Modifier.padding(PowPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DirectField(homeText("Unfiltered DNS (DoH)", "DNS فیلترنشده (DoH)"), s.unfilteredDns, Modifier.fillMaxWidth()) { save(s.copy(unfilteredDns = it)) }
                    DirectField(homeText("Domestic DNS", "DNS داخلی"), s.domesticDns, Modifier.fillMaxWidth()) { save(s.copy(domesticDns = it)) }
                    PowHint(homeText("Changes apply on the next connect.", "تغییرات از اتصال بعدی اعمال می‌شن."))
                    PowButton(
                        text = homeText("Reset to defaults", "برگشت به پیش‌فرض"),
                        enabled = true,
                        accent = accent,
                        filled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) { store.resetToDefaults() }
                }
            }
        }
    }
}

@Composable
private fun DirectField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
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
