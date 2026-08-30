package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.engine.tor.TorExitCountry
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.ui.theme.UacColors
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TorCountryScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val isPersian = LocalHomePersian.current
    val store = remember(context) { TorEngineStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val connectionState by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val nameLocale = if (isPersian) Locale("fa") else Locale.ENGLISH
    var query by rememberSaveable { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var snackbarJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    val listStartFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val selectedCode = settings.exitCountryCode
    val recommendedSet = remember { TorExitCountry.RECOMMENDED.toSet() }
    val recommended = remember(query) {
        TorExitCountry.RECOMMENDED.filter { TorExitCountry.matches(it, query) }
    }
    val moreCountries = remember(query) {
        TorExitCountry.allCodes.filter { code ->
            code !in recommendedSet && TorExitCountry.matches(code, query)
        }
    }
    val showAutomatic = TorExitCountry.matchesAutomatic(query)
    val hasListRows = showAutomatic || recommended.isNotEmpty() || moreCountries.isNotEmpty()
    val accent = UacColors.DisconnectedBlue
    val selectedLabel = if (selectedCode.isEmpty()) {
        homeText("Automatic", "خودکار")
    } else {
        TorExitCountry.displayName(selectedCode, nameLocale)
    }

    fun notify(message: String) {
        snackbarJob?.cancel()
        snackbar.currentSnackbarData?.dismiss()
        snackbarJob = scope.launch { snackbar.showSnackbar(message) }
    }

    fun persistCountry(code: String) {
        val live = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.CONNECTING
        val next = settings.copy(exitCountryCode = code, exitStrict = code.isNotEmpty()).validated()
        val changed = next.exitCountryCode != settings.exitCountryCode || next.exitStrict != settings.exitStrict
        if (!changed) return
        store.save(next)
        val name = if (next.exitCountryCode.isEmpty()) {
            if (isPersian) "خودکار" else "Automatic"
        } else {
            TorExitCountry.displayName(next.exitCountryCode, nameLocale)
        }
        if (live) {
            applying = true
            VpnController.applyTorExit(context)
            notify(if (isPersian) "در حال اتصال مجدد به $name…" else "Reconnecting to $name…")
            scope.launch {
                delay(8_000)
                applying = false
            }
        } else {
            notify(
                if (isPersian) "$name برای اتصال بعدی انتخاب شد"
                else "$name selected for the next connection",
            )
        }
    }

    LaunchedEffect(query) {
        listState.scrollToItem(0)
    }

    val baseTextStyle = LocalTextStyle.current
    val localizedTextStyle = homeLocalizedFont()?.let { baseTextStyle.copy(fontFamily = it) } ?: baseTextStyle
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
        ToolPageBackground(accent = accent) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                        ),
                ) {
                    WideSplitColumn(
                        headerPadding = 16.dp,
                        header = {
                            Spacer(Modifier.height(if (imeVisible) 4.dp else 8.dp))
                            TorCountryTopBar(
                                subtitle = selectedLabel,
                                compact = imeVisible,
                                onMenuClick = onMenuClick,
                            )
                        },
                    ) {
                    Spacer(Modifier.height(if (imeVisible) 8.dp else 10.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (hasListRows) {
                                    Modifier.dpadDownMovesFocus(listStartFocus) {
                                        keyboard?.hide()
                                    }
                                } else {
                                    Modifier
                                }
                            ),
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                focusManager.clearFocus()
                            },
                        ),
                        placeholder = {
                            Text(
                                homeText("Search country", "جست‌وجوی کشور"),
                                color = UacColors.TextSecondary,
                                fontSize = 14.sp,
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null, tint = UacColors.TextSecondary)
                        },
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                RemoteIconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = homeText("Clear search", "پاک‌کردن جست‌وجو"),
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
                            focusedBorderColor = accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                            cursorColor = accent,
                            focusedContainerColor = Color(0x99101C29),
                            unfocusedContainerColor = Color(0x66101C29),
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(
                            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp,
                        ),
                    ) {
                        if (showAutomatic) {
                            item(key = "auto") {
                                TorCountryRow(
                                    title = homeText("Automatic", "خودکار"),
                                    subtitle = homeText(
                                        "Tor picks the best available exit",
                                        "شبکه Tor بهترین خروجی در دسترس را انتخاب می‌کند",
                                    ),
                                    country = null,
                                    selected = selectedCode.isEmpty(),
                                    applying = applying && selectedCode.isEmpty(),
                                    status = if (selectedCode.isEmpty()) {
                                        homeText("Selected / Automatic", "انتخاب‌شده / خودکار")
                                    } else {
                                        null
                                    },
                                    onSelect = { persistCountry(TorExitCountry.AUTOMATIC) },
                                    modifier = Modifier.focusRequester(listStartFocus),
                                )
                            }
                        }
                        if (recommended.isNotEmpty()) {
                            item(key = "recommended-label") {
                                CountrySectionLabel(homeText("Recommended", "پیشنهادی"))
                            }
                            itemsIndexed(recommended, key = { _, code -> "rec-$code" }) { index, code ->
                                val selected = selectedCode == code
                                TorCountryRow(
                                    title = TorExitCountry.displayName(code, nameLocale),
                                    subtitle = countrySubtitle(code),
                                    country = CountryMetadata.resolve(code, null),
                                    selected = selected,
                                    applying = applying && selected,
                                    status = if (selected) selectedStatus(code) else null,
                                    onSelect = { persistCountry(code) },
                                    modifier = if (!showAutomatic && index == 0) {
                                        Modifier.focusRequester(listStartFocus)
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                        if (moreCountries.isNotEmpty()) {
                            item(key = "more-label") {
                                CountrySectionLabel(homeText("All countries", "همه کشورها"))
                            }
                            itemsIndexed(moreCountries, key = { _, code -> code }) { index, code ->
                                val selected = selectedCode == code
                                TorCountryRow(
                                    title = TorExitCountry.displayName(code, nameLocale),
                                    subtitle = countrySubtitle(code),
                                    country = CountryMetadata.resolve(code, null),
                                    selected = selected,
                                    applying = applying && selected,
                                    status = if (selected) selectedStatus(code) else null,
                                    onSelect = { persistCountry(code) },
                                    modifier = if (!showAutomatic && recommended.isEmpty() && index == 0) {
                                        Modifier.focusRequester(listStartFocus)
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                    }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun TorCountryTopBar(
    subtitle: String,
    onMenuClick: () -> Unit,
    compact: Boolean = false,
) {
    val isPersian = LocalHomePersian.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemoteIconButton(
            onClick = onMenuClick,
            modifier = Modifier
                .size(46.dp)
                .background(Color(0x99101C29), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                .openDrawerOnDpadLeft(onMenuClick),
        ) {
            Icon(Icons.Outlined.Menu, homeText("Open navigation", "بازکردن منو"), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                homeText("Select country", "انتخاب کشور"),
                color = Color.White,
                fontSize = if (compact) 18.sp else 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = if (isPersian) TextAlign.Right else TextAlign.Start,
                style = LocalTextStyle.current.copy(
                    textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!compact) {
                Text(
                    subtitle,
                    color = UacColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (isPersian) TextAlign.Right else TextAlign.Start,
                    style = LocalTextStyle.current.copy(
                        textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CountrySectionLabel(text: String) {
    Text(
        text,
        color = UacColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun TorCountryRow(
    title: String,
    subtitle: String,
    country: CountryMetadata?,
    selected: Boolean,
    applying: Boolean,
    status: String?,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (selected) UacColors.ConnectedGreen else UacColors.DisconnectedBlue
    val shape = RoundedCornerShape(16.dp)
    val (interaction, remoteFocused) = rememberRemoteRowFocus()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                when {
                    remoteFocused -> UacColors.DisconnectedBlue.copy(alpha = 0.18f)
                    selected -> accent.copy(alpha = 0.075f)
                    else -> Color(0xC9101C29)
                },
                shape,
            )
            .border(
                width = if (remoteFocused) 2.dp else 1.dp,
                color = when {
                    remoteFocused -> UacColors.DisconnectedBlue
                    else -> accent.copy(alpha = if (selected) 0.48f else 0.13f)
                },
                shape,
            )
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onSelect,
            )
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountryBadge(country, selected)
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = if (selected) accent.copy(alpha = 0.92f) else UacColors.TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null) {
                Text(
                    if (applying) homeText("Applying…", "در حال اعمال…") else status,
                    color = accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier.size(30.dp).background(accent.copy(alpha = 0.13f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Check, homeText("Selected", "انتخاب‌شده"), tint = accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CountryBadge(country: CountryMetadata?, selected: Boolean) {
    val color = if (country?.isKnown == true) Color(0xFF8D7CFF) else UacColors.DisconnectedBlue
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(color.copy(alpha = if (selected) 0.18f else 0.11f), RoundedCornerShape(13.dp))
            .border(1.dp, color.copy(alpha = 0.26f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (country?.isKnown == true) {
            CountryFlagIcon(country, size = 29.dp)
        } else {
            Icon(Icons.Outlined.Public, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun countrySubtitle(code: String): String {
    val iso = code.uppercase(Locale.US)
    return homeText("$iso  •  Tor exit", "$iso  •  خروجی Tor")
}

@Composable
private fun selectedStatus(code: String): String {
    val iso = code.uppercase(Locale.US)
    return homeText("Selected / $iso", "انتخاب‌شده / $iso")
}
