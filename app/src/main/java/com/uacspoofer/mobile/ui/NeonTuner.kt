package com.uacspoofer.mobile.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.ui.theme.AppThemeStore
import com.uacspoofer.mobile.ui.theme.SolarisTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.uacspoofer.mobile.ui.theme.AppTheme
import com.uacspoofer.mobile.ui.theme.NeonLive
import com.uacspoofer.mobile.ui.theme.NeonTokens
import com.uacspoofer.mobile.ui.theme.PageThemeColors
import com.uacspoofer.mobile.ui.theme.SolarisLive
import com.uacspoofer.mobile.ui.theme.UacThemeState
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.ui.theme.orDefault
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Ink = Color(0xFFF4EEFF)
private val Mute = Color(0xFFCDBFE0)
private val Cyan = Color(0xFF22D3EE)
private val Sheet = Color(0xFF181225)
private val LocalTokenHistory = staticCompositionLocalOf<TokenHistory> { error("Missing editor history") }

private class TokenHistory {
    var undo by mutableStateOf(emptyList<NeonTokens>())
        private set
    var redo by mutableStateOf(emptyList<NeonTokens>())
        private set

    fun commit(before: NeonTokens, after: NeonTokens) {
        if (before == after) return
        undo = (undo + before).takeLast(50)
        redo = emptyList()
    }

    fun undo(current: NeonTokens): NeonTokens? {
        val previous = undo.lastOrNull() ?: return null
        undo = undo.dropLast(1)
        redo = redo + current
        return previous
    }

    fun redo(current: NeonTokens): NeonTokens? {
        val next = redo.lastOrNull() ?: return null
        redo = redo.dropLast(1)
        undo = (undo + current).takeLast(50)
        return next
    }
}

private fun writeTokens(tokens: NeonTokens) {
    when (UacThemeState.theme) {
        AppTheme.SOLARIS -> SolarisLive.tokens = tokens
        else -> NeonLive.tokens = tokens
    }
}

@Composable
internal fun NeonHomeTuner(
    onHome: Boolean,
    openRequested: Boolean,
    onRequestHandled: () -> Unit,
    onDisable: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { AppThemeStore.get(context) }
    val theme = UacThemeState.theme
    if (theme == AppTheme.CLASSIC) return

    val selectedId by store.selectedId.collectAsStateWithLifecycle()
    val savedThemes by store.savedThemes.collectAsStateWithLifecycle()
    val solaris = theme == AppTheme.SOLARIS
    val selected = savedThemes.firstOrNull { it.id == selectedId }
    val themeName = selected?.name ?: if (solaris) "Solaris" else "Neon"
    val history = remember(theme, selectedId) { TokenHistory() }
    var open by rememberSaveable { mutableStateOf(false) }
    var panelX by rememberSaveable { mutableStateOf(0.5f) }
    var panelY by rememberSaveable { mutableStateOf(1f) }
    var panelOpacity by rememberSaveable { mutableStateOf(1f) }
    var showOpacity by rememberSaveable { mutableStateOf(false) }
    var showSave by rememberSaveable(theme, selectedId) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val copied = homeText("Theme values copied", "مقادیر تم کپی شد")
    val saved = homeText("Theme saved", "تم ذخیره شد")
    val font = homeLocalizedFont()
    val accent = if (solaris) Color(0xFFFFAC75) else Cyan
    val drawerOpen = LocalDrawerOpen.current
    LaunchedEffect(openRequested) {
        if (openRequested) {
            open = true
            onRequestHandled()
        }
    }
    BackHandler(enabled = open && !drawerOpen) { open = false }
    if (drawerOpen) return

    BoxWithConstraints(Modifier.fillMaxSize().zIndex(40f)
        .padding(WindowInsets.safeDrawing.asPaddingValues()).padding(12.dp)) {
        if (!open) {
            Button(
                onClick = { open = true },
                modifier = Modifier.align(Alignment.BottomEnd),
                colors = ButtonDefaults.buttonColors(containerColor = Sheet, contentColor = Ink),
            ) {
                Icon(Icons.Outlined.Tune, null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(homeText("Theme editor", "تم‌ساز"), fontFamily = font)
            }
        } else {
            val panelHeight = (maxHeight * 0.57f).coerceIn(minOf(264.dp, maxHeight), minOf(460.dp, maxHeight))
            val panelWidth = minOf(460.dp, maxWidth * 0.96f)
            val density = LocalDensity.current
            val travelX = with(density) { (maxWidth - panelWidth).toPx() }
            val travelY = with(density) { (maxHeight - panelHeight).toPx() }
            Surface(
                modifier = Modifier.align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset { IntOffset((travelX * panelX).roundToInt(), (travelY * panelY).roundToInt()) }
                    .width(panelWidth).height(panelHeight),
                color = Sheet.copy(alpha = panelOpacity),
                contentColor = Ink,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 12.dp * panelOpacity,
            ) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Column(
                        Modifier.fillMaxWidth().pointerInput(travelX, travelY) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                if (travelX > 0f) panelX = (panelX + drag.x / travelX).coerceIn(0f, 1f)
                                if (travelY > 0f) panelY = (panelY + drag.y / travelY).coerceIn(0f, 1f)
                            }
                        },
                    ) {
                        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(width = 32.dp, height = 4.dp)
                                .background(Mute.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(start = 4.dp, top = 6.dp, bottom = 6.dp)) {
                                Text(homeText("Theme editor", "تم‌ساز"), fontFamily = font,
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(themeName, color = Mute, fontFamily = font,
                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(
                                onClick = { history.undo(activeTokens())?.let(::writeTokens) },
                                enabled = history.undo.isNotEmpty(),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Undo, homeText("Undo", "واگرد"))
                            }
                            IconButton(
                                onClick = { history.redo(activeTokens())?.let(::writeTokens) },
                                enabled = history.redo.isNotEmpty(),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Redo, homeText("Redo", "ازنو"))
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, homeText("More actions", "گزینه‌های بیشتر"))
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    if (selected != null) {
                                        DropdownMenuItem(
                                            text = { Text(homeText("Save as new theme", "ذخیره به‌عنوان تم جدید"), fontFamily = font) },
                                            onClick = { showMenu = false; showSave = true },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(homeText("Panel opacity", "شفافیت پنل"), fontFamily = font) },
                                        onClick = { showOpacity = !showOpacity; showMenu = false },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(homeText("Copy theme values", "کپی مقادیر تم"), fontFamily = font) },
                                        onClick = {
                                            showMenu = false
                                            val code = activeTokens().toKotlin(if (solaris) "SolarisTokens" else "NeonTokens")
                                            clipboard.setText(AnnotatedString(code))
                                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(homeText("Reset to base theme", "بازنشانی به تم پایه"), fontFamily = font) },
                                        onClick = {
                                            showMenu = false
                                            val before = activeTokens()
                                            val defaults = if (solaris) SolarisTokens else NeonTokens()
                                            writeTokens(defaults)
                                            history.commit(before, defaults)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(homeText("Turn off theme editor", "خاموش کردن تم‌ساز"), fontFamily = font) },
                                        onClick = onDisable,
                                    )
                                }
                            }
                            IconButton(onClick = { open = false }) {
                                Icon(Icons.Outlined.Close, homeText("Collapse editor", "جمع کردن تم‌ساز"))
                            }
                        }
                    }
                    if (showOpacity) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(homeText("Opacity", "پوشانندگی"), color = Mute, fontFamily = font, fontSize = 12.sp)
                            Slider(
                                value = panelOpacity,
                                onValueChange = { panelOpacity = it },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            Text("${(panelOpacity * 100).roundToInt()}%", fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = Ink.copy(alpha = 0.13f))
                    CompositionLocalProvider(LocalTokenHistory provides history) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                            Fold(homeText("Page colors", "رنگ‌بندی صفحات")) { PageColorControls() }
                            Fold(homeText("Connection colors", "رنگ‌های وضعیت اتصال")) { ConnectionColorControls() }
                            if (onHome) {
                                Fold(homeText("Home background", "پس‌زمینهٔ خانه")) { BackgroundControls() }
                                Fold(homeText("Title and underline", "عنوان و خط زیرش")) { TitleControls() }
                                Fold(homeText("Connect button", "دکمهٔ اتصال")) { ButtonControls() }
                                Fold(homeText("Connection status", "متن وضعیت")) { StatusControls() }
                                Fold(homeText("Selected profile", "ردیف انتخاب‌شده")) { RowControls() }
                                Fold(homeText("Traffic cards", "کارت دانلود و آپلود")) { TrafficControls() }
                                Fold(homeText("Ping, country and logs", "کارت پینگ، کشور و لاگ")) { InsightControls() }
                                Fold(homeText("Engine switcher", "انتخاب‌گر موتور")) { RailControls() }
                                Fold(homeText("Menu button", "دکمهٔ منو")) { MenuControls() }
                                Fold(homeText("Waves and dots", "موج‌ها و نقاط پایین")) { WaveControls() }
                            }
                        }
                    }
                    HorizontalDivider(color = Ink.copy(alpha = 0.13f))
                    Button(
                        onClick = {
                            if (selected == null) {
                                showSave = true
                            } else {
                                store.updateSelectedTheme(activeTokens())
                                Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Sheet),
                    ) {
                        val label = if (selected == null) homeText("Save theme", "ذخیرهٔ تم")
                            else homeText("Save changes", "ذخیرهٔ تغییرات")
                        Text(label, fontFamily = font, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showSave) {
        var name by rememberSaveable { mutableStateOf("") }
        var saveFailed by remember { mutableStateOf(false) }
        val duplicate = savedThemes.any { it.name.equals(name.trim(), ignoreCase = true) }
        val tooLong = name.trim().length > AppThemeStore.MAX_NAME_LENGTH
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(homeText("Save theme", "ذخیرهٔ تم"), fontFamily = font) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; saveFailed = false },
                    label = { Text(homeText("Theme name", "نام تم"), fontFamily = font) },
                    singleLine = true,
                    textStyle = homeLocalizedTextStyle(),
                    isError = duplicate || tooLong || saveFailed,
                    supportingText = {
                        val message = when {
                            duplicate -> homeText("This name is already in use.", "این نام قبلاً استفاده شده است.")
                            tooLong -> homeText("Use up to 48 characters.", "حداکثر ۴۸ نویسه وارد کن.")
                            saveFailed -> homeText("Unable to save this theme.", "ذخیرهٔ این تم ممکن نشد.")
                            else -> homeText("Find saved themes in General settings.", "تم ذخیره‌شده در تنظیمات کلی قابل انتخاب است.")
                        }
                        Text(message, fontFamily = font)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (store.saveTheme(name, theme, activeTokens())) {
                            showSave = false
                            Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
                        } else {
                            saveFailed = true
                        }
                    },
                    enabled = name.isNotBlank() && !duplicate && !tooLong,
                ) { Text(homeText("Save", "ذخیره"), fontFamily = font) }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) {
                    Text(homeText("Cancel", "انصراف"), fontFamily = font)
                }
            },
        )
    }
}

@Composable
private fun Fold(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾" else "▸", color = Cyan, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(title, color = Ink, fontSize = 14.sp)
        }
        if (open) {
            Column(
                modifier = Modifier.padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
    }
}

@Composable
private fun Knob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    val shown = value.coerceIn(range.start, range.endInclusive)
    var before by remember { mutableStateOf<NeonTokens?>(null) }
    val history = LocalTokenHistory.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Mute, fontSize = 12.sp, modifier = Modifier.width(118.dp), maxLines = 2)
        Slider(
            value = shown,
            onValueChange = { next ->
                if (before == null) before = activeTokens()
                onChange(next)
            },
            onValueChangeFinished = {
                val start = before
                before = null
                if (start != null) history.commit(start, activeTokens())
            },
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatNum(shown),
            color = Ink,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}

@Composable
private fun IntKnob(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Int) -> Unit,
) {
    Knob(label, value.toFloat(), range, steps) { onChange(it.roundToInt()) }
}

@Composable
private fun Flag(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val history = LocalTokenHistory.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Mute, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = value,
            onCheckedChange = { next ->
                val start = activeTokens()
                onChange(next)
                history.commit(start, activeTokens())
            },
        )
    }
}

@Composable
private fun Swatch(label: String, color: Color, onChange: (Color) -> Unit) {
    val hue = color.hue()
    val sat = color.sat()
    val light = color.light()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("#%08X".format(color.toHex()), color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Knob(homeText("Hue", "فام"), hue, 0f..360f) { onChange(fromHsl(it, sat, light, color.alpha)) }
        Knob(homeText("Saturation", "اشباع"), sat, 0f..1f) { onChange(fromHsl(hue, it, light, color.alpha)) }
        Knob(homeText("Lightness", "روشنایی"), light, 0f..1f) { onChange(fromHsl(hue, sat, it, color.alpha)) }
        Knob(homeText("Alpha", "پوشانندگی"), color.alpha, 0f..1f) { onChange(color.copy(alpha = it)) }
    }
}

private fun activeTokens(): NeonTokens = when (UacThemeState.theme) {
    AppTheme.SOLARIS -> SolarisLive.tokens
    else -> NeonLive.tokens
}

private fun edit(block: (NeonTokens) -> NeonTokens) {
    when (UacThemeState.theme) {
        AppTheme.SOLARIS -> SolarisLive.update(block)
        else -> NeonLive.update(block)
    }
}

private fun editPageColors(block: (PageThemeColors) -> PageThemeColors) {
    edit { it.copy(pageColors = block(it.pageColors)) }
}

@Composable
private fun PageColorControls() {
    Swatch(homeText("Background top", "بالای پس‌زمینه"), UacColors.BackgroundTop) { c -> editPageColors { it.copy(pageTop = c) } }
    Swatch(homeText("Background middle", "وسط پس‌زمینه"), UacColors.BackgroundMiddle) { c -> editPageColors { it.copy(pageMiddle = c) } }
    Swatch(homeText("Background bottom", "پایین پس‌زمینه"), UacColors.BackgroundBottom) { c -> editPageColors { it.copy(pageBottom = c) } }
    Swatch(homeText("Cards and dialogs", "کارت‌ها و پنجره‌ها"), UacColors.Surface) { c -> editPageColors { it.copy(pageSurface = c) } }
    Swatch(homeText("Settings cards", "کارت‌های تنظیمات کلی"), UacColors.SettingsSurface) { c -> editPageColors { it.copy(settingsSurface = c) } }
    Swatch(homeText("Navigation menu", "پس‌زمینهٔ منو"), UacColors.DrawerSurface.orDefault(UacColors.BackgroundMiddle)) { c ->
        editPageColors { it.copy(drawerSurface = c) }
    }
    Swatch(homeText("Text", "متن اصلی"), UacColors.TextPrimary) { c -> editPageColors { it.copy(pageText = c) } }
    Swatch(homeText("Secondary text", "متن فرعی"), UacColors.TextSecondary) { c -> editPageColors { it.copy(pageMutedText = c) } }
    Swatch(homeText("Borders", "حاشیه‌ها"), UacColors.CardBorder) { c -> editPageColors { it.copy(pageBorder = c) } }
    Swatch(homeText("Dividers", "خطوط جداکننده"), UacColors.Divider) { c -> editPageColors { it.copy(pageDivider = c) } }
    Swatch(homeText("Accent", "رنگ تأکیدی"), UacColors.NeonMagenta) { c -> editPageColors { it.copy(pageAccent = c) } }
    Swatch(homeText("Secondary accent", "رنگ تأکیدی دوم"), UacColors.NeonCyan) { c -> editPageColors { it.copy(secondaryAccent = c) } }
    Swatch(homeText("Tertiary accent", "رنگ تأکیدی سوم"), UacColors.NeonViolet) { c -> editPageColors { it.copy(tertiaryAccent = c) } }
}

@Composable
private fun ConnectionColorControls() {
    Swatch(homeText("Disconnected", "قطع"), UacColors.DisconnectedBlue) { c -> editPageColors { it.copy(idleAccent = c) } }
    Swatch(homeText("Connecting", "در حال اتصال"), UacColors.ConnectingCyan) { c -> editPageColors { it.copy(connectingAccent = c) } }
    Swatch(homeText("Connected", "وصل"), UacColors.ConnectedGreen) { c -> editPageColors { it.copy(connectedAccent = c) } }
    Swatch(homeText("Disconnecting", "در حال قطع"), UacColors.DisconnectingAmber) { c -> editPageColors { it.copy(disconnectingAccent = c) } }
    Swatch(homeText("Error", "خطا"), UacColors.ErrorRed) { c -> editPageColors { it.copy(errorAccent = c) } }
}

@Composable
private fun BackgroundControls() {
    val t = activeTokens()
    Swatch("بالا", t.bgTop) { c -> edit { it.copy(bgTop = c) } }
    Swatch("وسط", t.bgMid) { c -> edit { it.copy(bgMid = c) } }
    Swatch("پایین", t.bgBottom) { c -> edit { it.copy(bgBottom = c) } }
    Knob("شدت هاله", t.bgGlowAlpha, 0f..0.4f) { v -> edit { it.copy(bgGlowAlpha = v) } }
    Knob("شدت هاله دوم", t.bgGlowMidAlpha, 0f..0.3f) { v -> edit { it.copy(bgGlowMidAlpha = v) } }
    Flag("هاله از رنگ وضعیت", t.bgGlowFollowState) { v -> edit { it.copy(bgGlowFollowState = v) } }
    Swatch("رنگ هاله", t.bgGlow) { c -> edit { it.copy(bgGlow = c) } }
}

@Composable
private fun TitleControls() {
    val t = activeTokens()
    Knob("اندازه متن", t.titleSize, 12f..48f) { v -> edit { it.copy(titleSize = v) } }
    IntKnob("وزن", t.titleWeight, 100f..900f, 7) { v -> edit { it.copy(titleWeight = v.coerceIn(100, 900)) } }
    Knob("شفافیت", t.titleAlpha, 0f..1f) { v -> edit { it.copy(titleAlpha = v) } }
    Knob("فاصله افقی", t.titlePad, 0f..120f) { v -> edit { it.copy(titlePad = v) } }
    Knob("محو عنوان", t.titleGlowBlur, 0f..40f) { v -> edit { it.copy(titleGlowBlur = v) } }
    Knob("شدت محو", t.titleGlowAlpha, 0f..1f) { v -> edit { it.copy(titleGlowAlpha = v) } }
    Swatch("شروع گرادیان", t.titleStart) { c -> edit { it.copy(titleStart = c) } }
    Swatch("وسط گرادیان", t.titleMid) { c -> edit { it.copy(titleMid = c) } }
    Swatch("پایان گرادیان", t.titleEnd) { c -> edit { it.copy(titleEnd = c) } }
    Swatch("رنگ محو", t.titleGlow) { c -> edit { it.copy(titleGlow = c) } }
    Knob("عرض خط", t.underlineWidth, 20f..280f) { v -> edit { it.copy(underlineWidth = v) } }
    Knob("ضخامت خط", t.underlineHeight, 0.5f..8f) { v -> edit { it.copy(underlineHeight = v) } }
    Swatch("رنگ خط", t.underlineStart) { c -> edit { it.copy(underlineStart = c) } }
    Swatch("وسط خط", t.underlineMid) { c -> edit { it.copy(underlineMid = c) } }
}

@Composable
private fun ButtonControls() {
    val t = activeTokens()
    Knob("مقیاس دکمه", t.buttonScale, 0.6f..1.5f) { v -> edit { it.copy(buttonScale = v) } }
    Knob("شفافیت دکمه", t.buttonAlpha, 0f..1f) { v -> edit { it.copy(buttonAlpha = v) } }
    Knob("حاشیه glow", t.glowExtra, 0f..80f) { v -> edit { it.copy(glowExtra = v) } }
    Knob("ضخامت حلقه", t.ringWidth, 1f..16f) { v -> edit { it.copy(ringWidth = v) } }
    Knob("ضخامت هسته", t.ringCoreWidth, 0f..8f) { v -> edit { it.copy(ringCoreWidth = v) } }
    Knob("شدت هسته", t.ringCoreAlpha, 0f..1f) { v -> edit { it.copy(ringCoreAlpha = v) } }
    Swatch("رنگ هسته", t.ringCore) { c -> edit { it.copy(ringCore = c) } }
    Knob("زاویه گرادیان", t.gradientAngle, 0f..360f) { v -> edit { it.copy(gradientAngle = v) } }
    Swatch("شروع (بالا و چپ)", t.gradientStart) { c -> edit { it.copy(gradientStart = c) } }
    Swatch("پایان (پایین و راست)", t.gradientEnd) { c -> edit { it.copy(gradientEnd = c) } }
    IntKnob("تعداد حلقه بیرونی", t.hairlineCount, 0f..4f, 3) { v -> edit { it.copy(hairlineCount = v.coerceIn(0, 4)) } }
    Knob("شروع فاصله", t.hairlineStart, 0f..40f) { v -> edit { it.copy(hairlineStart = v) } }
    Knob("فاصله حلقه‌ها", t.hairlineGap, 0f..24f) { v -> edit { it.copy(hairlineGap = v) } }
    Knob("ضخامت حلقه بیرونی", t.hairlineWidth, 0.4f..6f) { v -> edit { it.copy(hairlineWidth = v) } }
    Knob("شدت حلقه ۱", t.hairlineAlpha1, 0f..1f) { v -> edit { it.copy(hairlineAlpha1 = v) } }
    Knob("شدت حلقه ۲", t.hairlineAlpha2, 0f..1f) { v -> edit { it.copy(hairlineAlpha2 = v) } }
    Knob("شدت حلقه ۳", t.hairlineAlpha3, 0f..1f) { v -> edit { it.copy(hairlineAlpha3 = v) } }
    Knob("شدت حلقه ۴", t.hairlineAlpha4, 0f..1f) { v -> edit { it.copy(hairlineAlpha4 = v) } }
    Knob("محو بیرونی", t.glowBlur, 0f..60f) { v -> edit { it.copy(glowBlur = v) } }
    Knob("محو وقتی وصل است", t.glowBlurConnected, 0f..70f) { v -> edit { it.copy(glowBlurConnected = v) } }
    Knob("شدت محو", t.glowAlpha, 0f..255f) { v -> edit { it.copy(glowAlpha = v) } }
    Knob("پخش محو", t.glowSpread, -8f..30f) { v -> edit { it.copy(glowSpread = v) } }
    Knob("شدت حالت قطع", t.glowDisconnected, 0f..1.4f) { v -> edit { it.copy(glowDisconnected = v) } }
    Knob("دور دنباله", t.connectOrbitMs, 800f..4000f) { v -> edit { it.copy(connectOrbitMs = v) } }
    Knob("کوتاهی دنباله", t.connectSweepMin, 40f..220f) { v -> edit { it.copy(connectSweepMin = v) } }
    Knob("بلندی دنباله", t.connectSweepMax, 60f..260f) { v -> edit { it.copy(connectSweepMax = v) } }
    Knob("محو نزدیک دنباله", t.connectNearBlur, 0f..40f) { v -> edit { it.copy(connectNearBlur = v) } }
    Knob("محو دور دنباله", t.connectFarBlur, 0f..60f) { v -> edit { it.copy(connectFarBlur = v) } }
    Knob("شدت محو نزدیک", t.connectNearAlpha, 0f..1f) { v -> edit { it.copy(connectNearAlpha = v) } }
    Knob("شدت محو دور", t.connectFarAlpha, 0f..1f) { v -> edit { it.copy(connectFarAlpha = v) } }
    Knob("اندازه سر دنباله", t.connectHeadRadius, 0.5f..8f) { v -> edit { it.copy(connectHeadRadius = v) } }
    Knob("محو داخلی", t.innerGlowBlur, 0f..48f) { v -> edit { it.copy(innerGlowBlur = v) } }
    Knob("شدت محو داخلی", t.innerGlowAlpha, 0f..255f) { v -> edit { it.copy(innerGlowAlpha = v) } }
    Knob("فرو رفتن محو", t.innerGlowInset, 0f..16f) { v -> edit { it.copy(innerGlowInset = v) } }
    Swatch("وسط دکمه", t.centerInner) { c -> edit { it.copy(centerInner = c) } }
    Swatch("لبه وسط", t.centerOuter) { c -> edit { it.copy(centerOuter = c) } }
    Knob("اندازه آیکون", t.iconScale, 0.08f..0.45f) { v -> edit { it.copy(iconScale = v) } }
    Swatch("آیکون قطع", t.iconIdle) { c -> edit { it.copy(iconIdle = c) } }
    Swatch("آیکون وصل", t.iconActive) { c -> edit { it.copy(iconActive = c) } }
    Knob("اندازه متن", t.labelSize, 8f..28f) { v -> edit { it.copy(labelSize = v) } }
    IntKnob("وزن متن", t.labelWeight, 100f..900f, 7) { v -> edit { it.copy(labelWeight = v.coerceIn(100, 900)) } }
    IntKnob("وزن متن فارسی", t.labelWeightFa, 100f..900f, 7) { v -> edit { it.copy(labelWeightFa = v.coerceIn(100, 900)) } }
    Swatch("متن قطع", t.labelIdle) { c -> edit { it.copy(labelIdle = c) } }
    Swatch("متن وصل", t.labelActive) { c -> edit { it.copy(labelActive = c) } }
    Knob("محو متن", t.labelGlowBlur, 0f..30f) { v -> edit { it.copy(labelGlowBlur = v) } }
    Knob("شدت محو متن", t.labelGlowAlpha, 0f..1f) { v -> edit { it.copy(labelGlowAlpha = v) } }
}

@Composable
private fun StatusControls() {
    val t = activeTokens()
    Knob("اندازه وضعیت", t.statusSize, 10f..32f) { v -> edit { it.copy(statusSize = v) } }
    IntKnob("وزن وضعیت", t.statusWeight, 100f..900f, 7) { v -> edit { it.copy(statusWeight = v.coerceIn(100, 900)) } }
    Knob("شفافیت وضعیت", t.statusAlpha, 0f..1f) { v -> edit { it.copy(statusAlpha = v) } }
    Flag("رنگ وضعیت از حالت اتصال", t.statusUseAccent) { v -> edit { it.copy(statusUseAccent = v) } }
    Swatch("رنگ ثابت وضعیت", t.statusColor) { c -> edit { it.copy(statusColor = c) } }
    Knob("اندازه توضیح", t.hintSize, 8f..22f) { v -> edit { it.copy(hintSize = v) } }
    IntKnob("وزن توضیح", t.hintWeight, 100f..900f, 7) { v -> edit { it.copy(hintWeight = v.coerceIn(100, 900)) } }
    Knob("شفافیت توضیح", t.hintAlpha, 0f..1f) { v -> edit { it.copy(hintAlpha = v) } }
    Knob("فاصله افقی توضیح", t.hintPad, 0f..64f) { v -> edit { it.copy(hintPad = v) } }
    Swatch("رنگ توضیح", t.hintColor) { c -> edit { it.copy(hintColor = c) } }
}

@Composable
private fun RowControls() {
    val t = activeTokens()
    Knob("عرض ردیف", t.rowWidth, 0.4f..1f) { v -> edit { it.copy(rowWidth = v) } }
    Knob("ارتفاع", t.rowHeight, 28f..72f) { v -> edit { it.copy(rowHeight = v) } }
    Knob("فاصله افقی", t.rowPadH, 0f..40f) { v -> edit { it.copy(rowPadH = v) } }
    Knob("شفافیت", t.rowAlpha, 0f..1f) { v -> edit { it.copy(rowAlpha = v) } }
    Knob("اندازه نقطه", t.dotSize, 2f..16f) { v -> edit { it.copy(dotSize = v) } }
    Swatch("رنگ نقطه", t.dotColor) { c -> edit { it.copy(dotColor = c) } }
    Knob("اندازه برچسب", t.selectedLabelSize, 8f..22f) { v -> edit { it.copy(selectedLabelSize = v) } }
    IntKnob("وزن برچسب", t.selectedLabelWeight, 100f..900f, 7) { v -> edit { it.copy(selectedLabelWeight = v.coerceIn(100, 900)) } }
    IntKnob("وزن برچسب فارسی", t.selectedLabelWeightFa, 100f..900f, 7) { v -> edit { it.copy(selectedLabelWeightFa = v.coerceIn(100, 900)) } }
    Swatch("رنگ برچسب", t.selectedLabelColor) { c -> edit { it.copy(selectedLabelColor = c) } }
    Knob("اندازه مقدار", t.selectedValueSize, 8f..24f) { v -> edit { it.copy(selectedValueSize = v) } }
    IntKnob("وزن مقدار", t.selectedValueWeight, 100f..900f, 7) { v -> edit { it.copy(selectedValueWeight = v.coerceIn(100, 900)) } }
    Swatch("رنگ مقدار", t.selectedValueColor) { c -> edit { it.copy(selectedValueColor = c) } }
    Knob("اندازه فلش", t.selectedChevron, 10f..32f) { v -> edit { it.copy(selectedChevron = v) } }
}

@Composable
private fun TrafficControls() {
    val t = activeTokens()
    Knob("عرض", t.trafficWidth, 0.5f..1f) { v -> edit { it.copy(trafficWidth = v) } }
    Knob("ارتفاع", t.cardHeight, 60f..160f) { v -> edit { it.copy(cardHeight = v) } }
    Knob("شعاع گوشه", t.cardCorner, 0f..40f) { v -> edit { it.copy(cardCorner = v) } }
    Knob("ضخامت خط", t.cardBorder, 0f..6f) { v -> edit { it.copy(cardBorder = v) } }
    Knob("فاصله داخل", t.cardPadH, 0f..28f) { v -> edit { it.copy(cardPadH = v) } }
    Knob("شفافیت", t.cardAlpha, 0f..1f) { v -> edit { it.copy(cardAlpha = v) } }
    Knob("کادر آیکون", t.cardIconBox, 20f..64f) { v -> edit { it.copy(cardIconBox = v) } }
    Knob("اندازه آیکون", t.cardIcon, 10f..40f) { v -> edit { it.copy(cardIcon = v) } }
    Flag("آیکون از رنگ وضعیت", t.cardIconFollowState) { v -> edit { it.copy(cardIconFollowState = v) } }
    Swatch("رنگ آیکون", t.cardIconColor) { c -> edit { it.copy(cardIconColor = c) } }
    Knob("اندازه عنوان", t.cardLabelSize, 8f..20f) { v -> edit { it.copy(cardLabelSize = v) } }
    IntKnob("وزن عنوان", t.cardLabelWeight, 100f..900f, 7) { v -> edit { it.copy(cardLabelWeight = v.coerceIn(100, 900)) } }
    IntKnob("وزن عنوان فارسی", t.cardLabelWeightFa, 100f..900f, 7) { v -> edit { it.copy(cardLabelWeightFa = v.coerceIn(100, 900)) } }
    Swatch("رنگ عنوان", t.cardLabelColor) { c -> edit { it.copy(cardLabelColor = c) } }
    Knob("اندازه عدد", t.cardValueSize, 12f..40f) { v -> edit { it.copy(cardValueSize = v) } }
    IntKnob("وزن عدد", t.cardValueWeight, 100f..900f, 7) { v -> edit { it.copy(cardValueWeight = v.coerceIn(100, 900)) } }
    Swatch("رنگ عدد", t.cardValueColor) { c -> edit { it.copy(cardValueColor = c) } }
    Knob("ضخامت نوار", t.cardBarHeight, 1f..12f) { v -> edit { it.copy(cardBarHeight = v) } }
    Swatch("شروع نوار", t.cardBarStart) { c -> edit { it.copy(cardBarStart = c) } }
    Swatch("پایان نوار", t.cardBarEnd) { c -> edit { it.copy(cardBarEnd = c) } }
    Knob("اندازه جمع", t.cardTotalSize, 8f..18f) { v -> edit { it.copy(cardTotalSize = v) } }
    Swatch("رنگ جمع", t.cardTotalColor) { c -> edit { it.copy(cardTotalColor = c) } }
    Swatch("شروع پس‌زمینه", t.cardFillStart) { c -> edit { it.copy(cardFillStart = c) } }
    Swatch("پایان پس‌زمینه", t.cardFillEnd) { c -> edit { it.copy(cardFillEnd = c) } }
    Swatch("رنگ خط", t.cardBorderColor) { c -> edit { it.copy(cardBorderColor = c) } }
    Swatch("شروع خط", t.cardBorderStart) { c -> edit { it.copy(cardBorderStart = c) } }
    Swatch("پایان خط", t.cardBorderEnd) { c -> edit { it.copy(cardBorderEnd = c) } }
    Swatch("شروع هاله", t.cardGlowColorStart) { c -> edit { it.copy(cardGlowColorStart = c) } }
    Swatch("پایان هاله", t.cardGlowColorEnd) { c -> edit { it.copy(cardGlowColorEnd = c) } }
    Knob("بلور هاله", t.cardGlowBlur, 0f..40f) { v -> edit { it.copy(cardGlowBlur = v) } }
    Knob("شفافیت هاله", t.cardGlowAlpha, 0f..1f) { v -> edit { it.copy(cardGlowAlpha = v) } }
    Knob("پهنای هاله", t.cardGlowSpread, 0f..36f) { v -> edit { it.copy(cardGlowSpread = v) } }
}

@Composable
private fun InsightControls() {
    val t = activeTokens()
    Knob("عرض", t.insightWidth, 0.5f..1f) { v -> edit { it.copy(insightWidth = v) } }
    Knob("ارتفاع", t.insightHeight, 60f..150f) { v -> edit { it.copy(insightHeight = v) } }
    Knob("شعاع گوشه", t.insightCorner, 0f..36f) { v -> edit { it.copy(insightCorner = v) } }
    Knob("ضخامت خط", t.insightBorder, 0f..6f) { v -> edit { it.copy(insightBorder = v) } }
    Knob("شفافیت", t.insightAlpha, 0f..1f) { v -> edit { it.copy(insightAlpha = v) } }
    Knob("اندازه آیکون", t.insightIcon, 12f..36f) { v -> edit { it.copy(insightIcon = v) } }
    Swatch("رنگ آیکون", t.insightIconColor) { c -> edit { it.copy(insightIconColor = c) } }
    Knob("اندازه عنوان", t.insightLabelSize, 7f..16f) { v -> edit { it.copy(insightLabelSize = v) } }
    IntKnob("وزن عنوان", t.insightLabelWeight, 100f..900f, 7) { v -> edit { it.copy(insightLabelWeight = v.coerceIn(100, 900)) } }
    Knob("اندازه مقدار", t.insightValueSize, 8f..24f) { v -> edit { it.copy(insightValueSize = v) } }
    IntKnob("وزن مقدار", t.insightValueWeight, 100f..900f, 7) { v -> edit { it.copy(insightValueWeight = v.coerceIn(100, 900)) } }
    Knob("اندازه توضیح", t.insightDetailSize, 7f..16f) { v -> edit { it.copy(insightDetailSize = v) } }
    Swatch("رنگ خوب", t.insightGood) { c -> edit { it.copy(insightGood = c) } }
    Swatch("رنگ متوسط", t.insightMid) { c -> edit { it.copy(insightMid = c) } }
    Swatch("رنگ کند", t.insightBad) { c -> edit { it.copy(insightBad = c) } }
    Swatch("پس‌زمینه", t.insightFill) { c -> edit { it.copy(insightFill = c) } }
    Swatch("رنگ خط", t.insightBorderColor) { c -> edit { it.copy(insightBorderColor = c) } }
}

@Composable
private fun RailControls() {
    val t = activeTokens()
    Knob("عرض", t.railWidth, 32f..80f) { v -> edit { it.copy(railWidth = v) } }
    Knob("ارتفاع آیتم", t.railItem, 24f..64f) { v -> edit { it.copy(railItem = v) } }
    Knob("ارتفاع انتخاب", t.railItemOn, 32f..90f) { v -> edit { it.copy(railItemOn = v) } }
    Knob("فاصله داخل", t.railPad, 0f..16f) { v -> edit { it.copy(railPad = v) } }
    Knob("فاصله آیتم‌ها", t.railGap, 0f..16f) { v -> edit { it.copy(railGap = v) } }
    IntKnob("شعاع گوشه", t.railCorner, 0f..50f, 49) { v -> edit { it.copy(railCorner = v.coerceIn(0, 50)) } }
    Knob("سایه", t.railShadow, 0f..32f) { v -> edit { it.copy(railShadow = v) } }
    Knob("ضخامت خط", t.railBorder, 0f..4f) { v -> edit { it.copy(railBorder = v) } }
    Knob("شفافیت", t.railAlpha, 0f..1f) { v -> edit { it.copy(railAlpha = v) } }
    Knob("اندازه آیکون", t.railIcon, 12f..36f) { v -> edit { it.copy(railIcon = v) } }
    Knob("اندازه برچسب", t.railLabelSize, 7f..16f) { v -> edit { it.copy(railLabelSize = v) } }
    IntKnob("وزن برچسب", t.railLabelWeight, 100f..900f, 7) { v -> edit { it.copy(railLabelWeight = v.coerceIn(100, 900)) } }
    Knob("شدت آیکون خاموش", t.railIdleAlpha, 0f..1f) { v -> edit { it.copy(railIdleAlpha = v) } }
    Swatch("بالا", t.railFillTop) { c -> edit { it.copy(railFillTop = c) } }
    Swatch("پایین", t.railFillBottom) { c -> edit { it.copy(railFillBottom = c) } }
    Swatch("خط", t.railBorderColor) { c -> edit { it.copy(railBorderColor = c) } }
    Swatch("انتخاب", t.railSelected) { c -> edit { it.copy(railSelected = c) } }
}

@Composable
private fun MenuControls() {
    val t = activeTokens()
    Knob("اندازه دکمه", t.menuButton, 28f..64f) { v -> edit { it.copy(menuButton = v) } }
    Knob("اندازه آیکون", t.menuIcon, 14f..40f) { v -> edit { it.copy(menuIcon = v) } }
    Knob("شفافیت", t.menuAlpha, 0f..1f) { v -> edit { it.copy(menuAlpha = v) } }
    Swatch("رنگ", t.menuColor) { c -> edit { it.copy(menuColor = c) } }
}

@Composable
private fun WaveControls() {
    val t = activeTokens()
    Knob("ارتفاع", t.waveHeight, 8f..80f) { v -> edit { it.copy(waveHeight = v) } }
    Knob("محو", t.waveGlowBlur, 0f..40f) { v -> edit { it.copy(waveGlowBlur = v) } }
    Knob("شدت محو", t.waveGlowAlpha, 0f..1f) { v -> edit { it.copy(waveGlowAlpha = v) } }
    Knob("شفافیت", t.waveAlpha, 0f..1f) { v -> edit { it.copy(waveAlpha = v) } }
    Swatch("شروع", t.waveStart) { c -> edit { it.copy(waveStart = c) } }
    Swatch("پایان", t.waveEnd) { c -> edit { it.copy(waveEnd = c) } }
}

private fun formatNum(value: Float): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

private fun Color.toHex(): Int = android.graphics.Color.argb(
    (alpha * 255f).roundToInt().coerceIn(0, 255),
    (red * 255f).roundToInt().coerceIn(0, 255),
    (green * 255f).roundToInt().coerceIn(0, 255),
    (blue * 255f).roundToInt().coerceIn(0, 255),
)

private fun Color.hue(): Float = hsl()[0]
private fun Color.sat(): Float = hsl()[1]
private fun Color.light(): Float = hsl()[2]

private fun Color.hsl(): FloatArray {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val light = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return floatArrayOf(0f, 0f, light)
    val sat = delta / (1f - abs(2f * light - 1f))
    val hue = when (max) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    return floatArrayOf(if (hue < 0f) hue + 360f else hue, sat.coerceIn(0f, 1f), light.coerceIn(0f, 1f))
}

private fun fromHsl(hue: Float, sat: Float, light: Float, alpha: Float): Color {
    val chroma = (1f - abs(2f * light - 1f)) * sat.coerceIn(0f, 1f)
    val sector = ((hue % 360f) + 360f) % 360f / 60f
    val x = chroma * (1f - abs(sector % 2f - 1f))
    val (r, g, b) = when {
        sector < 1f -> Triple(chroma, x, 0f)
        sector < 2f -> Triple(x, chroma, 0f)
        sector < 3f -> Triple(0f, chroma, x)
        sector < 4f -> Triple(0f, x, chroma)
        sector < 5f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val match = light - chroma / 2f
    return Color(
        (r + match).coerceIn(0f, 1f),
        (g + match).coerceIn(0f, 1f),
        (b + match).coerceIn(0f, 1f),
        alpha.coerceIn(0f, 1f),
    )
}
