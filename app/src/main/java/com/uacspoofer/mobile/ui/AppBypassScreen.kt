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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.vpn.AppRoutingMode
import com.uacspoofer.mobile.vpn.AppRoutingPreferences
import com.uacspoofer.mobile.vpn.InstalledVpnApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun bypassLtr(value: String): String = "\u2066$value\u2069"

private data class RoutingModeUi(
    val mode: AppRoutingMode,
    val title: String,
    val icon: ImageVector,
)

private val RoutingModes = listOf(
    RoutingModeUi(AppRoutingMode.ALL_APPS, "All apps", Icons.Outlined.Public),
    RoutingModeUi(
        AppRoutingMode.BYPASS_SELECTED,
        "Bypass selected",
        Icons.Outlined.Block,
    ),
    RoutingModeUi(
        AppRoutingMode.VPN_ONLY_SELECTED,
        "Selected apps only",
        Icons.Outlined.Shield,
    ),
)

@Composable
internal fun AppBypassScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isPersian = LocalHomePersian.current
    val baseTextStyle = LocalTextStyle.current
    val localizedTextStyle = homeLocalizedFont()?.let { baseTextStyle.copy(fontFamily = it) } ?: baseTextStyle
    AppRoutingPreferences.initialize(context)
    val settings by AppRoutingPreferences.settings.collectAsState()
    var apps by remember { mutableStateOf<List<InstalledVpnApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val appListState = rememberLazyListState()
    val listStartFocus = remember { FocusRequester() }
    val searchMode = searchFocused || query.isNotBlank()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AppRoutingPreferences.installedApps(context) }
    }

    val filteredApps = remember(apps, query, settings.selectedPackages) {
        val needle = query.trim().lowercase()
        apps.orEmpty()
            .mapNotNull { app ->
                val label = app.label.lowercase()
                val packageName = app.packageName.lowercase()
                val packageTail = packageName.substringAfterLast('.')
                val score = when {
                    needle.isBlank() -> 0
                    label == needle -> 0
                    label.startsWith(needle) -> 1
                    label.split(' ', '-', '_').any { it.startsWith(needle) } -> 2
                    label.contains(needle) -> 3
                    packageTail.startsWith(needle) -> 4
                    packageName.contains(needle) -> 5
                    else -> Int.MAX_VALUE
                }
                if (score == Int.MAX_VALUE) null else app to score
            }
            .sortedWith(
                compareBy<Pair<InstalledVpnApp, Int>> { it.second }
                    .thenByDescending { it.first.packageName in settings.selectedPackages }
                    .thenBy { it.first.label.lowercase() }
                    .thenBy { it.first.packageName },
            )
            .map(Pair<InstalledVpnApp, Int>::first)
    }

    LaunchedEffect(query) {
        if (query.isNotBlank() && filteredApps.isNotEmpty()) appListState.scrollToItem(0)
    }

    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
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
                .padding(WindowInsets.safeDrawing.asPaddingValues()),
        ) {
            WideSplitColumn(
                headerPadding = 16.dp,
                header = {
                    if (!searchMode) {
                        Spacer(Modifier.height(8.dp))
                        HomeHeader(
                            accent = UacColors.DisconnectedBlue,
                            compact = true,
                            onMenuClick = onMenuClick,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                    }
                },
            ) {
            if (!searchMode) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = homeText("App Bypass", "عبور انتخابی برنامه‌ها"),
                    color = UacColors.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = homeText("Choose which apps use the secure tunnel", "انتخاب کن کدوم برنامه‌ها از تونل امن استفاده کنن"),
                    color = UacColors.TextSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val visibleModes = if (isPersian) RoutingModes.asReversed() else RoutingModes
                    visibleModes.forEach { item ->
                        RoutingModeCard(
                            item = item,
                            selected = settings.mode == item.mode,
                            onClick = { AppRoutingPreferences.setMode(context, item.mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }

            if (settings.mode != AppRoutingMode.ALL_APPS) {
                Spacer(Modifier.height(if (searchMode) 4.dp else 15.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .onFocusChanged { searchFocused = it.isFocused }
                        .then(
                            if (filteredApps.isNotEmpty()) {
                                Modifier.dpadDownMovesFocus(listStartFocus) {
                                    keyboardController?.hide()
                                }
                            } else {
                                Modifier
                            }
                        ),
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                    ),
                    placeholder = { Text(homeText("Search apps", "جست‌وجوی برنامه‌ها"), color = UacColors.TextSecondary, fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = UacColors.TextSecondary)
                    },
                    trailingIcon = if (searchMode) {
                        {
                            RemoteIconButton(
                                onClick = {
                                    query = ""
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = homeText("Close search", "بستن جست‌وجو"),
                                    tint = UacColors.TextSecondary,
                                )
                            }
                        }
                    } else {
                        null
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
                        .padding(top = 11.dp, bottom = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val applicationsLabel = homeText("Applications", "برنامه‌ها")
                    val selectedLabel = homeText("${settings.selectedPackages.size} selected", "${settings.selectedPackages.size} انتخاب‌شده")
                    if (isPersian) {
                        Text(selectedLabel, color = UacColors.DisconnectedBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(applicationsLabel, color = UacColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(applicationsLabel, color = UacColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(selectedLabel, color = UacColors.DisconnectedBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
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
                        state = appListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        itemsIndexed(filteredApps, key = { _, app -> app.packageName }) { index, app ->
                            AppSelectionRow(
                                app = app,
                                selected = app.packageName in settings.selectedPackages,
                                onSelectedChange = { selected ->
                                    keyboardController?.hide()
                                    AppRoutingPreferences.setPackageSelected(
                                        context,
                                        app.packageName,
                                        selected,
                                    )
                                },
                                modifier = if (index == 0) Modifier.focusRequester(listStartFocus) else Modifier,
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
                        text = homeText("All installed apps are routed through the VPN.", "همه برنامه‌های نصب‌شده از ${bypassLtr("VPN")} عبور می‌کنن."),
                        color = UacColors.TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            }
        }
    }
    }
}

@Composable
private fun RoutingModeCard(
    item: RoutingModeUi,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val isPersian = LocalHomePersian.current
    val title = when (item.mode) {
        AppRoutingMode.ALL_APPS -> homeText(item.title, "همه برنامه‌ها")
        AppRoutingMode.BYPASS_SELECTED -> homeText(item.title, "عبور انتخابی برنامه‌ها")
        AppRoutingMode.VPN_ONLY_SELECTED -> homeText(item.title, "فقط برنامه‌های انتخابی")
    }
    Column(
        modifier = modifier
            .height(108.dp)
            .shadow(
                elevation = if (selected) 12.dp else 0.dp,
                shape = shape,
                ambientColor = UacColors.DisconnectedBlue.copy(alpha = 0.35f),
                spotColor = UacColors.DisconnectedBlue.copy(alpha = 0.55f),
            )
            .clip(shape)
            .background(
                if (selected) {
                    Brush.verticalGradient(listOf(Color(0xFF0B3C67), Color(0xFF081F35)))
                } else {
                    Brush.verticalGradient(listOf(Color(0xE6102030), Color(0xE6091724)))
                },
            )
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) Color(0xFF26B8FF) else UacColors.CardBorder,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(41.dp)
                .background(UacColors.DisconnectedBlue.copy(alpha = if (selected) 0.18f else 0.10f), CircleShape)
                .border(1.dp, UacColors.DisconnectedBlue.copy(alpha = 0.42f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, null, tint = if (selected) Color(0xFF32C2FF) else Color(0xFFB6CBD8), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = UacColors.TextPrimary,
            fontSize = if (isPersian) 12.5.sp else 12.sp,
            lineHeight = if (isPersian) 17.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppSelectionRow(
    app: InstalledVpnApp,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
    val isPersian = LocalHomePersian.current
    val focusRequester = remember { FocusRequester() }
    var restoreFocus by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (!restoreFocus) return@LaunchedEffect
        restoreFocus = false
        runCatching { focusRequester.requestFocus() }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .clip(shape)
            .background(if (selected) Color(0xCC0B2940) else Color(0xA30D1926))
            .border(1.dp, if (selected) Color(0xAA299EFF) else UacColors.CardBorder, shape)
            .clickable {
                restoreFocus = true
                onSelectedChange(!selected)
            }
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPersian) {
            AppSelectionControl(selected = selected)
            Spacer(Modifier.width(8.dp))
            AppIdentity(app = app, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            AppIcon(app = app, icon = icon)
        } else {
            AppIcon(app = app, icon = icon)
            Spacer(Modifier.width(12.dp))
            AppIdentity(app = app, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AppSelectionControl(selected = selected)
        }
    }
}

@Composable
private fun AppIcon(app: InstalledVpnApp, icon: ImageBitmap?) {
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)))
    } else {
        Box(
            Modifier.size(46.dp).background(Color(0x25299EFF), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(app.label.take(1).uppercase(), color = UacColors.DisconnectedBlue, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AppIdentity(app: InstalledVpnApp, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            app.label,
            color = UacColors.TextPrimary,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            app.packageName,
            color = UacColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AppSelectionControl(selected: Boolean) {
    RadioButton(
        selected = selected,
        onClick = null,
        colors = RadioButtonDefaults.colors(
            selectedColor = UacColors.DisconnectedBlue,
            unselectedColor = UacColors.TextSecondary,
        ),
    )
}
