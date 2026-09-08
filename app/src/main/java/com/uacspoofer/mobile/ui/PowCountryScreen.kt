package com.uacspoofer.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.pow.PowRegions
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.ui.theme.UacColors
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PowCountryScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val isPersian = LocalHomePersian.current
    val store = remember(context) { PowEngineStore.get(context) }
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
    val recommendedSet = remember { PowRegions.RECOMMENDED.toSet() }
    val allCodes = remember(settings) { PowRegions.options(store.availableRegions()) }
    val recommended = remember(query, allCodes) {
        PowRegions.RECOMMENDED.filter { it in allCodes && PowRegions.matches(it, query) }
    }
    val moreCountries = remember(query, allCodes) {
        allCodes.filter { code -> code !in recommendedSet && PowRegions.matches(code, query) }
    }
    val showAutomatic = PowRegions.matchesAutomatic(query)
    val hasListRows = showAutomatic || recommended.isNotEmpty() || moreCountries.isNotEmpty()
    val accent = UacColors.DisconnectedBlue
    val selectedLabel = if (selectedCode.isEmpty()) {
        homeText("Automatic", "خودکار")
    } else {
        PowRegions.name(selectedCode, nameLocale)
    }

    fun notify(message: String) {
        snackbarJob?.cancel()
        snackbar.currentSnackbarData?.dismiss()
        snackbarJob = scope.launch { snackbar.showSnackbar(message) }
    }

    fun persistCountry(code: String) {
        val live = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.CONNECTING
        val next = settings.copy(exitCountryCode = code).validated()
        if (next.exitCountryCode == settings.exitCountryCode) return
        store.save(next)
        val name = if (next.exitCountryCode.isEmpty()) {
            if (isPersian) "خودکار" else "Automatic"
        } else {
            PowRegions.name(next.exitCountryCode, nameLocale)
        }
        if (live) {
            applying = true
            VpnController.applyPowExit(context)
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

    LaunchedEffect(query) { listState.scrollToItem(0) }
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
                            modifier = Modifier.fillMaxWidth(),
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
                                            "Psiphon picks the fastest available country",
                                            "Psiphon سریع‌ترین کشور در دسترس را انتخاب می‌کند",
                                        ),
                                        country = null,
                                        selected = selectedCode.isEmpty(),
                                        applying = applying && selectedCode.isEmpty(),
                                        status = if (selectedCode.isEmpty()) {
                                            homeText("Selected / Automatic", "انتخاب‌شده / خودکار")
                                        } else {
                                            null
                                        },
                                        onSelect = { persistCountry(PowRegions.AUTOMATIC) },
                                        modifier = Modifier.focusRequester(listStartFocus),
                                    )
                                }
                            }
                            if (recommended.isNotEmpty()) {
                                item(key = "recommended-label") {
                                    CountrySectionLabel(homeText("Recommended", "پیشنهادی"))
                                }
                                itemsIndexed(recommended, key = { _, code -> "rec-$code" }) { _, code ->
                                    val selected = selectedCode == code
                                    TorCountryRow(
                                        title = PowRegions.name(code, nameLocale),
                                        subtitle = code.uppercase(Locale.US),
                                        country = CountryMetadata.resolve(code, null),
                                        selected = selected,
                                        applying = applying && selected,
                                        status = if (selected) homeText("Selected", "انتخاب‌شده") else null,
                                        onSelect = { persistCountry(code) },
                                    )
                                }
                            }
                            if (moreCountries.isNotEmpty()) {
                                item(key = "more-label") {
                                    CountrySectionLabel(homeText("All countries", "همه کشورها"))
                                }
                                itemsIndexed(moreCountries, key = { _, code -> code }) { _, code ->
                                    val selected = selectedCode == code
                                    TorCountryRow(
                                        title = PowRegions.name(code, nameLocale),
                                        subtitle = code.uppercase(Locale.US),
                                        country = CountryMetadata.resolve(code, null),
                                        selected = selected,
                                        applying = applying && selected,
                                        status = if (selected) homeText("Selected", "انتخاب‌شده") else null,
                                        onSelect = { persistCountry(code) },
                                    )
                                }
                            }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
