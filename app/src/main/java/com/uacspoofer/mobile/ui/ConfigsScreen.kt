package com.uacspoofer.mobile.ui

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.profiles.PhoneImportQr
import com.uacspoofer.mobile.profiles.PhoneImportServer
import com.uacspoofer.mobile.profiles.ProfileLibrary
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.profiles.ProfileEndpoint
import com.uacspoofer.mobile.profiles.ProfileLatencyTester
import com.uacspoofer.mobile.profiles.ProfileLatencyCache
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProfileUriParser
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.ProxyProtocol
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun configLtr(value: String): String = "\u2066$value\u2069"

internal fun showConfigsPhoneImport(wideShell: Boolean): Boolean = wideShell

internal fun emptyProfileImportHintEnglish(wideShell: Boolean): String =
    if (wideShell) {
        "Tap + or the QR icon to import VLESS, Trojan or VMess"
    } else {
        "Tap + to import VLESS, Trojan or VMess"
    }

internal fun emptyProfileImportHintPersian(wideShell: Boolean): String =
    if (wideShell) {
        "برای افزودن ${configLtr("VLESS")}، ${configLtr("Trojan")} یا ${configLtr("VMess")} روی + یا ${configLtr("QR")} بزن"
    } else {
        "برای افزودن ${configLtr("VLESS")}، ${configLtr("Trojan")} یا ${configLtr("VMess")} روی + بزن"
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ConfigsScreen(
    onMenuClick: () -> Unit,
    connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    activeProfileId: String? = null,
    activeEndpoint: ProfileEndpoint? = null,
    onSwitchProfile: () -> Unit = {},
) {
    val context = LocalContext.current
    val isPersian = LocalHomePersian.current
    val wideShell = LocalWideShell.current
    val store = remember(context) { ProfileStore(context) }
    var library by remember { mutableStateOf(store.snapshot()) }
    val latencyCache = remember(context) { ProfileLatencyCache(context) }
    var resolvedCountries by remember { mutableStateOf<Map<String, CountryMetadata>>(emptyMap()) }
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorName by rememberSaveable { mutableStateOf("") }
    var editorUri by rememberSaveable { mutableStateOf("") }
    var editorError by rememberSaveable { mutableStateOf<String?>(null) }
    var phoneImportVisible by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProxyProfile?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var markedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkDeletePending by remember { mutableStateOf(false) }
    var delayStates by remember {
        mutableStateOf<Map<String, DelayUiState>>(
            latencyCache.snapshot(library.allProfiles.mapTo(hashSetOf(), ProxyProfile::id))
                .mapValues { DelayUiState.Ready(it.value) },
        )
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var snackbarJob by remember { mutableStateOf<Job?>(null) }
    var delayJob by remember { mutableStateOf<Job?>(null) }
    var delayTesting by remember { mutableStateOf(false) }
    var delayGeneration by remember { mutableIntStateOf(0) }
    var sortOrder by rememberSaveable { mutableStateOf(ConfigLatencySort.DEFAULT) }
    val latencyTester = remember(context) { ProfileLatencyTester(context) }
    val accent = UacColors.DisconnectedBlue
    val latestConnectionState by rememberUpdatedState(connectionState)
    val countryQueue = remember { Channel<ProxyProfile>(Channel.UNLIMITED) }
    val queuedCountryLookups = remember { mutableSetOf<String>() }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        markedIds = emptySet()
    }

    LaunchedEffect(wideShell) {
        if (!wideShell) phoneImportVisible = false
    }

    LaunchedEffect(library.allProfiles.map { "${it.id}:${it.country.countryCode.orEmpty()}:${it.rawUri.hashCode()}" }) {
        resolvedCountries = library.allProfiles.associate { it.id to it.country }
        library.customProfiles
            .filterNot { it.country.isKnown }
            .forEach { profile ->
                val lookupKey = "${profile.id}:${profile.rawUri.hashCode()}"
                if (queuedCountryLookups.add(lookupKey)) countryQueue.trySend(profile)
            }
    }

    LaunchedEffect(countryQueue) {
        var session: com.uacspoofer.mobile.profiles.SniMakerTestSession? = null
        var preferredCandidateId: String? = null
        for (queuedProfile in countryQueue) {
            while (latestConnectionState == ConnectionState.CONNECTING) delay(400L)
            val currentProfile = store.snapshot().customProfiles.firstOrNull { it.id == queuedProfile.id }
                ?: continue
            if (currentProfile.country.isKnown || currentProfile.rawUri != queuedProfile.rawUri) continue
            try {
                val activeSession = session ?: latencyTester.prepareSniMakerSession().also {
                    session = it
                    preferredCandidateId = it.initialPreferredCandidateId
                }
                val result = latencyTester.measureForSniMaker(
                    profile = currentProfile,
                    session = activeSession,
                    preferredCandidateId = preferredCandidateId,
                )
                if (result.candidateId.isNotBlank()) preferredCandidateId = result.candidateId
                if (result.country.isKnown) {
                    library = store.updateCountry(currentProfile.id, result.country)
                    resolvedCountries = resolvedCountries + (currentProfile.id to result.country)
                    AppLogRepository.info(
                        LogSource.APP,
                        "Config country detected profile=${currentProfile.name} " +
                            "country=${result.country.countryCode} source=${result.countrySource}",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                session = null
                preferredCandidateId = null
                AppLogRepository.warning(
                    LogSource.APP,
                    "Config country detection failed profile=${currentProfile.name}",
                    error,
                )
            }
        }
    }

    fun notify(message: String) {
        snackbarJob?.cancel()
        snackbar.currentSnackbarData?.dismiss()
        snackbarJob = scope.launch { snackbar.showSnackbar(message) }
    }

    fun selectProfile(profile: ProxyProfile) {
        val selectionChanged = library.selectedId != profile.id
        val activeChanged = activeProfileId != profile.id
        if (!selectionChanged && (connectionState != ConnectionState.CONNECTED || !activeChanged)) return
        if (selectionChanged) library = store.select(profile.id)
        if (connectionState == ConnectionState.CONNECTED && activeChanged) {
            notify(if (isPersian) "در حال تغییر به ${profile.name}…" else "Switching to ${profile.name}...")
            onSwitchProfile()
        } else if (selectionChanged) {
            notify(if (isPersian) "${profile.name} برای اتصال بعدی انتخاب شد" else "${profile.name} selected for the next connection")
        }
    }

    fun testDelay(profiles: List<ProxyProfile>) {
        val token = ++delayGeneration
        delayJob?.cancel()
        delayStates = delayStates.filterValues { it != DelayUiState.Testing }
        delayTesting = true
        delayJob = scope.launch {
            try {
                profiles.forEach { profile ->
                    delayStates = delayStates + (profile.id to DelayUiState.Testing)
                    delayStates = try {
                        val millis = latencyTester.measure(profile)
                        latencyCache.put(profile.id, millis)
                        delayStates + (profile.id to DelayUiState.Ready(millis))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        delayStates + (profile.id to DelayUiState.Failed)
                    }
                }
            } finally {
                if (token == delayGeneration) delayTesting = false
            }
        }
    }

    fun consumeImportedText(text: String, optionalName: String? = null): Boolean {
        val result = store.importText(text, optionalName)
        if (result.importedCount == 0) {
            editorError = result.errors.firstOrNull() ?: if (isPersian) "کانفیگ معتبر نیست" else "Invalid configuration"
            return false
        }
        val newest = result.library.customProfiles.first()
        library = store.select(newest.id)
        editorError = result.errors.firstOrNull()
        if (connectionState == ConnectionState.CONNECTED) {
            notify(
                if (isPersian) {
                    if (result.importedCount == 1) "کانفیگ اضافه شد؛ در حال تغییر…" else "${result.importedCount} کانفیگ اضافه شد؛ در حال تغییر…"
                } else if (result.importedCount == 1) {
                    "Configuration imported; switching..."
                } else {
                    "${result.importedCount} configurations imported; switching..."
                },
            )
            onSwitchProfile()
        } else {
            notify(
                if (isPersian) {
                    if (result.importedCount == 1) "کانفیگ اضافه و انتخاب شد" else "${result.importedCount} کانفیگ اضافه شد"
                } else if (result.importedCount == 1) {
                    "Configuration imported and selected"
                } else {
                    "${result.importedCount} configurations imported"
                },
            )
        }
        return true
    }

    fun exportMarkedProfiles() {
        val selected = library.customProfiles.filter { it.id in markedIds }
        if (selected.isEmpty()) return
        val payload = selected.joinToString("\n") { profile ->
            profile.rawUri.trim()
                .takeIf { ProfileUriParser.extractUris(it).size == 1 }
                ?: ProfileUriParser.canonicalUri(profile)
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("UAC SNI Spoofer configurations", payload))
        notify(
            if (isPersian) "${selected.size} کانفیگ در کلیپ‌بورد کپی شد"
            else "${selected.size} configuration${if (selected.size == 1) "" else "s"} copied to clipboard",
        )
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.onSuccess { text ->
                if (consumeImportedText(text)) editorVisible = false
            }.onFailure {
                editorError = if (isPersian) "واردکردن فایل انجام نشد: ${it.message ?: "فایل نامعتبر"}"
                else "Import failed: ${it.message ?: "invalid file"}"
            }
        }
    }

    val latencyValues = delayStates.mapNotNull { (id, delay) ->
        (delay as? DelayUiState.Ready)?.millis?.let { id to it }
    }.toMap()
    val displayedProfiles = sortProfilesByLatency(
        library.allProfiles,
        latencyValues,
        sortOrder,
        resolvedCountries,
    )
    val profileListState = rememberLazyListState()
    LaunchedEffect(sortOrder) {
        profileListState.scrollToItem(0)
    }

    val baseTextStyle = LocalTextStyle.current
    val localizedTextStyle = homeLocalizedFont()?.let { baseTextStyle.copy(fontFamily = it) } ?: baseTextStyle
    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
    ToolPageBackground(accent = accent) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.safeDrawing.asPaddingValues()),
            ) {
                WideSplitColumn(
                    headerPadding = 16.dp,
                    header = {
                        Spacer(Modifier.height(8.dp))
                        ConfigsTopBar(
                    count = library.customProfiles.size,
                    selectionMode = selectionMode,
                    selectedCount = markedIds.size,
                    testing = delayTesting,
                    sortOrder = sortOrder,
                    onSortOrderChange = { sortOrder = it },
                    onMenuClick = onMenuClick,
                    onTestAll = { testDelay(library.allProfiles) },
                    onEnterSelection = { selectionMode = true; markedIds = emptySet() },
                    onPhoneImport = if (wideShell) {
                        { phoneImportVisible = true }
                    } else {
                        null
                    },
                    onCancelSelection = { selectionMode = false; markedIds = emptySet() },
                    onSelectAll = { markedIds = library.customProfiles.mapTo(linkedSetOf(), ProxyProfile::id) },
                    onExportSelected = ::exportMarkedProfiles,
                    onDeleteSelected = { if (markedIds.isNotEmpty()) bulkDeletePending = true },
                )
                    },
                ) {
                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = profileListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 104.dp),
                ) {
                    items(displayedProfiles, key = ProxyProfile::id) { profile ->
                        ProfileRow(
                            profile = profile,
                            country = resolvedCountries[profile.id] ?: profile.country,
                            selected = profile.id == library.selectedId,
                            active = connectionState == ConnectionState.CONNECTED && profile.id == activeProfileId,
                            selectionMode = selectionMode,
                            marked = profile.id in markedIds,
                            delayState = delayStates[profile.id],
                            onSelect = {
                                if (selectionMode && !profile.isBuiltIn) {
                                    markedIds = if (profile.id in markedIds) markedIds - profile.id else markedIds + profile.id
                                } else if (!selectionMode) {
                                    selectProfile(profile)
                                }
                            },
                            onLongSelect = if (profile.isBuiltIn) null else ({
                                selectionMode = true
                                markedIds = markedIds + profile.id
                            }),
                            onTestDelay = { testDelay(listOf(profile)) },
                            onEdit = if (profile.isBuiltIn) null else ({
                                editingId = profile.id
                                editorName = profile.name
                                editorUri = ProfileUriParser.canonicalUri(profile)
                                editorError = null
                                editorVisible = true
                            }),
                            onDelete = if (profile.isBuiltIn) null else ({ deleteTarget = profile }),
                        )
                    }

                    if (library.customProfiles.isEmpty()) {
                        item { EmptyProfileHint() }
                    }
                }
                }
            }

            FloatingActionButton(
                onClick = {
                    editingId = null
                    editorName = ""
                    editorUri = ""
                    editorError = null
                    editorVisible = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(20.dp),
                containerColor = accent,
                contentColor = Color(0xFF02101C),
                shape = RoundedCornerShape(17.dp),
            ) {
                    Icon(Icons.Outlined.Add, homeText("Add configuration", "افزودن کانفیگ"), modifier = Modifier.size(25.dp))
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp, start = 16.dp, end = 16.dp),
            )
        }
    }

    if (editorVisible) {
        ProfileEditorSheet(
            editing = editingId != null,
            name = editorName,
            uri = editorUri,
            error = editorError,
            onNameChange = { editorName = it; editorError = null },
            onUriChange = { editorUri = it; editorError = null },
            onDismiss = { editorVisible = false },
            onPaste = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isBlank()) {
                    editorError = if (isPersian) "کلیپ‌بورد کانفیگی نداره" else "Clipboard has no configuration"
                } else if (editingId == null) {
                    editorUri = text
                    if (consumeImportedText(text, editorName.takeIf(String::isNotBlank))) editorVisible = false
                } else {
                    editorUri = ProfileUriParser.extractUris(text).firstOrNull() ?: text.trim()
                    editorError = null
                }
            },
            onPhoneImport = if (editingId == null && wideShell) {
                { editorVisible = false; phoneImportVisible = true }
            } else {
                null
            },
            onImport = { importer.launch(arrayOf("text/*", "application/octet-stream")) },
            onSave = {
                runCatching {
                    val id = editingId
                    if (id == null) {
                        check(consumeImportedText(editorUri, editorName.takeIf(String::isNotBlank)))
                    } else {
                        library = store.update(id, editorUri, editorName)
                    notify(if (isPersian) "کانفیگ به‌روزرسانی شد" else "Configuration updated")
                    }
                }.onSuccess {
                    editorVisible = false
                    editorError = null
                }.onFailure { error ->
                    if (editorError == null) {
                        editorError = error.message ?: if (isPersian) "کانفیگ معتبر نیست" else "Invalid configuration"
                    }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Color(0xFF101C29),
            title = { Text(homeText("Delete ${target.name}?", "کانفیگ ${target.name} حذف بشه؟"), color = Color.White) },
            text = { Text(homeText("The profile will be removed from this device.", "این کانفیگ از دستگاه حذف می‌شه."), color = UacColors.TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        library = store.delete(target.id)
                        latencyCache.remove(target.id)
                        delayStates = delayStates - target.id
                        deleteTarget = null
                        notify(if (isPersian) "کانفیگ حذف شد" else "Profile deleted")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD94B5B)),
                ) { Text(homeText("Delete", "حذف")) }
            },
            dismissButton = { OutlinedButton(onClick = { deleteTarget = null }) { Text(homeText("Cancel", "لغو")) } },
        )
    }

    if (bulkDeletePending) {
        AlertDialog(
            onDismissRequest = { bulkDeletePending = false },
            containerColor = Color(0xFF101C29),
            title = { Text(homeText("Delete ${markedIds.size} profiles?", "${markedIds.size} کانفیگ حذف بشه؟"), color = Color.White) },
            text = { Text(homeText("Selected profiles will be removed from this device.", "کانفیگ‌های انتخاب‌شده از دستگاه حذف می‌شن."), color = UacColors.TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        library = store.deleteMany(markedIds)
                        latencyCache.removeAll(markedIds)
                        delayStates = delayStates - markedIds
                        markedIds = emptySet()
                        selectionMode = false
                        bulkDeletePending = false
                        notify(if (isPersian) "کانفیگ‌ها حذف شدن" else "Profiles deleted")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD94B5B)),
                ) { Text(homeText("Delete", "حذف")) }
            },
            dismissButton = { OutlinedButton(onClick = { bulkDeletePending = false }) { Text(homeText("Cancel", "لغو")) } },
        )
    }

    if (phoneImportVisible && wideShell) {
        PhoneImportQrDialog(
            onImported = { consumeImportedText(it) },
            onDismiss = { phoneImportVisible = false },
            onFailed = {
                phoneImportVisible = false
                notify(
                    if (isPersian) "وای‌فای محلی پیدا نشد. موبایل و این دستگاه باید روی یک شبکه باشند."
                    else "No local Wi-Fi address. Phone and this device must share the same network.",
                )
            },
        )
    }
    }
}

@Composable
private fun ConfigsTopBar(
    count: Int,
    selectionMode: Boolean,
    selectedCount: Int,
    testing: Boolean,
    sortOrder: ConfigLatencySort,
    onSortOrderChange: (ConfigLatencySort) -> Unit,
    onMenuClick: () -> Unit,
    onTestAll: () -> Unit,
    onPhoneImport: (() -> Unit)? = null,
    onEnterSelection: () -> Unit,
    onCancelSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onExportSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val isPersian = LocalHomePersian.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemoteIconButton(
            onClick = if (selectionMode) onCancelSelection else onMenuClick,
            modifier = Modifier
                .size(46.dp)
                .background(Color(0x99101C29), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                .then(if (selectionMode) Modifier else Modifier.openDrawerOnDpadLeft(onMenuClick)),
        ) {
            Icon(
                if (selectionMode) Icons.Outlined.Close else Icons.Outlined.Menu,
                if (selectionMode) homeText("Cancel selection", "لغو انتخاب") else homeText("Open navigation", "بازکردن منو"),
                tint = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (selectionMode) homeText("$selectedCount selected", "$selectedCount انتخاب‌شده") else homeText("Configs", "کانفیگ‌ها"),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = if (isPersian) TextAlign.Right else TextAlign.Start,
                style = LocalTextStyle.current.copy(
                    textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (selectionMode) {
                    homeText("Tap profiles to mark them", "برای علامت‌زدن، کانفیگ‌ها رو انتخاب کن")
                } else if (count == 0) {
                    homeText("UAC SNI built-in", "کانفیگ پیش‌فرض ${configLtr("UAC SNI")}")
                } else {
                    homeText("$count custom profile${if (count == 1) "" else "s"}", "$count کانفیگ شخصی")
                },
                color = UacColors.TextSecondary,
                fontSize = 11.sp,
                textAlign = if (isPersian) TextAlign.Right else TextAlign.Start,
                style = LocalTextStyle.current.copy(
                    textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (selectionMode) {
            RemoteIconButton(onClick = onSelectAll, enabled = count > 0) {
                Icon(Icons.Outlined.SelectAll, homeText("Select all", "انتخاب همه"), tint = UacColors.DisconnectedBlue)
            }
            RemoteIconButton(
                onClick = onExportSelected,
                enabled = selectedCount > 0,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (selectedCount > 0) UacColors.ConnectedGreen.copy(alpha = 0.12f)
                        else Color.White.copy(alpha = 0.035f),
                        RoundedCornerShape(12.dp),
                    )
                    .border(
                        1.dp,
                        if (selectedCount > 0) UacColors.ConnectedGreen.copy(alpha = 0.38f)
                        else Color.White.copy(alpha = 0.06f),
                        RoundedCornerShape(12.dp),
                    ),
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    homeText("Export selected to clipboard", "کپی موارد انتخابی در کلیپ‌بورد"),
                    tint = if (selectedCount > 0) UacColors.ConnectedGreen
                    else UacColors.TextSecondary.copy(alpha = 0.35f),
                    modifier = Modifier.size(20.dp),
                )
            }
            RemoteIconButton(onClick = onDeleteSelected, enabled = selectedCount > 0) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    homeText("Delete selected", "حذف موارد انتخابی"),
                    tint = if (selectedCount > 0) Color(0xFFFF7483) else UacColors.TextSecondary.copy(alpha = 0.35f),
                )
            }
        } else {
            Box {
                RemoteIconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(
                        when (sortOrder) {
                            ConfigLatencySort.DEFAULT -> Icons.Outlined.Sort
                            ConfigLatencySort.LATENCY_ASC -> Icons.Outlined.ArrowUpward
                            ConfigLatencySort.LATENCY_DESC -> Icons.Outlined.ArrowDownward
                            ConfigLatencySort.COUNTRY_ASC -> Icons.Outlined.Public
                        },
                        when (sortOrder) {
                            ConfigLatencySort.DEFAULT -> homeText("Default profile order", "ترتیب پیش‌فرض کانفیگ‌ها")
                            ConfigLatencySort.LATENCY_ASC -> homeText("Latency low to high", "تأخیر از کم به زیاد")
                            ConfigLatencySort.LATENCY_DESC -> homeText("Latency high to low", "تأخیر از زیاد به کم")
                            ConfigLatencySort.COUNTRY_ASC -> homeText("Group by country", "دسته‌بندی بر اساس کشور")
                        },
                        tint = if (sortOrder == ConfigLatencySort.DEFAULT) UacColors.TextSecondary else UacColors.DisconnectedBlue,
                    )
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                    modifier = Modifier.background(Color(0xFF142231)),
                ) {
                    ConfigLatencySort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (option) {
                                        ConfigLatencySort.DEFAULT -> homeText("Default order", "ترتیب پیش‌فرض")
                                        ConfigLatencySort.LATENCY_ASC -> homeText("Latency: low to high", "تأخیر: کم به زیاد")
                                        ConfigLatencySort.LATENCY_DESC -> homeText("Latency: high to low", "تأخیر: زیاد به کم")
                                        ConfigLatencySort.COUNTRY_ASC -> homeText("Country (A-Z)", "کشور (A-Z)")
                                    },
                                    color = Color.White,
                                )
                            },
                            trailingIcon = if (option == sortOrder) {
                                { Icon(Icons.Outlined.Check, null, tint = UacColors.ConnectedGreen) }
                            } else null,
                            onClick = {
                                sortMenuExpanded = false
                                onSortOrderChange(option)
                            },
                        )
                    }
                }
            }
            if (onPhoneImport != null) {
                RemoteIconButton(onClick = onPhoneImport) {
                    Icon(
                        Icons.Outlined.QrCode2,
                        homeText("Add configs from phone", "افزودن کانفیگ با موبایل"),
                        tint = UacColors.DisconnectedBlue,
                    )
                }
            }
            RemoteIconButton(onClick = onTestAll, enabled = !testing) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = UacColors.ConnectedGreen,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.Speed, homeText("Test real delay", "تست تأخیر واقعی"), tint = UacColors.ConnectedGreen)
                }
            }
            if (count > 0) {
                RemoteIconButton(onClick = onEnterSelection) {
                    Icon(Icons.Outlined.DeleteSweep, homeText("Select profiles to delete", "انتخاب کانفیگ‌ها برای حذف"), tint = UacColors.TextSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileRow(
    profile: ProxyProfile,
    country: CountryMetadata,
    selected: Boolean,
    active: Boolean,
    selectionMode: Boolean,
    marked: Boolean,
    delayState: DelayUiState?,
    onSelect: () -> Unit,
    onLongSelect: (() -> Unit)?,
    onTestDelay: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val accent = if (selected) UacColors.ConnectedGreen else UacColors.DisconnectedBlue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) accent.copy(alpha = 0.075f) else Color(0xC9101C29),
                RoundedCornerShape(16.dp),
            )
            .border(
                1.dp,
                accent.copy(alpha = if (selected) 0.48f else 0.13f),
                RoundedCornerShape(16.dp),
            )
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongSelect,
            )
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProtocolBadge(profile.protocol, selected, country)
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    modifier = Modifier.weight(1f, fill = false),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (profile.isBuiltIn) {
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.Outlined.Lock, null, tint = UacColors.TextSecondary, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    if (country.isKnown) append("${country.countryName}  •  ")
                    append("${profile.serverHost}:${profile.serverPort}  •  ${profile.network.uppercase()} / ${profile.security.uppercase()}")
                },
                color = if (selected) accent.copy(alpha = 0.92f) else UacColors.TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected || active || delayState != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (active) {
                        Text(homeText("UAC SNI / connected", "${configLtr("UAC SNI")} / متصل"), color = accent, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                    } else if (selected) {
                        Text(homeText("UAC SNI / selected", "${configLtr("UAC SNI")} / انتخاب‌شده"), color = accent, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.weight(1f))
                    when (delayState) {
                        DelayUiState.Testing -> Text(homeText("testing…", "در حال تست…"), color = UacColors.TextSecondary, fontSize = 9.sp)
                        DelayUiState.Failed -> Text(homeText("Timeout", "مهلت تمام شد"), color = Color(0xFFFF7483), fontSize = 9.sp)
                        is DelayUiState.Ready -> Text("${delayState.millis} ms", color = UacColors.ConnectedGreen, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        null -> Unit
                    }
                }
            }
        }
        if (selectionMode && !profile.isBuiltIn) {
            Checkbox(
                checked = marked,
                onCheckedChange = { onSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = UacColors.ConnectedGreen,
                    uncheckedColor = UacColors.TextSecondary,
                    checkmarkColor = Color(0xFF02101C),
                ),
            )
        } else if (selected) {
            Box(
                modifier = Modifier.size(30.dp).background(accent.copy(alpha = 0.13f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                    Icon(Icons.Outlined.Check, homeText("Selected", "انتخاب‌شده"), tint = accent, modifier = Modifier.size(18.dp))
            }
        }
        if (!selectionMode && onEdit != null && onDelete != null) {
            Box {
                RemoteIconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, homeText("More options for ${profile.name}", "گزینه‌های بیشتر برای ${profile.name}"), tint = UacColors.TextSecondary)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(Color(0xFF142231)),
                ) {
                    DropdownMenuItem(
                        text = { Text(homeText("Real delay", "تأخیر واقعی"), color = Color.White) },
                        leadingIcon = { Icon(Icons.Outlined.Speed, null, tint = UacColors.ConnectedGreen) },
                        onClick = { menuExpanded = false; onTestDelay() },
                    )
                    DropdownMenuItem(
                        text = { Text(homeText("Edit", "ویرایش"), color = Color.White) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = UacColors.DisconnectedBlue) },
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(homeText("Delete", "حذف"), color = Color(0xFFFF7A88)) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = Color(0xFFFF7A88)) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        } else if (!selectionMode || profile.isBuiltIn) {
            Spacer(Modifier.size(10.dp))
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: ProxyProtocol, selected: Boolean, country: CountryMetadata) {
    val color = when (protocol) {
        ProxyProtocol.VLESS -> Color(0xFF8D7CFF)
        ProxyProtocol.VMESS -> Color(0xFFFFB454)
        ProxyProtocol.TROJAN -> UacColors.DisconnectedBlue
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(color.copy(alpha = if (selected) 0.18f else 0.11f), RoundedCornerShape(13.dp))
            .border(1.dp, color.copy(alpha = 0.26f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (country.isKnown) {
            CountryFlagIcon(country, size = 29.dp)
        } else {
            Text(
                when (protocol) {
                    ProxyProtocol.VLESS -> "V"
                    ProxyProtocol.VMESS -> "M"
                    ProxyProtocol.TROJAN -> "T"
                },
                color = color,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptyProfileHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(homeText("No custom profiles", "هنوز کانفیگ شخصی اضافه نشده"), color = Color(0xFFB8C5D5), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            homeText(
                emptyProfileImportHintEnglish(LocalWideShell.current),
                emptyProfileImportHintPersian(LocalWideShell.current),
            ),
            color = UacColors.TextSecondary,
            fontSize = 10.5.sp,
        )
    }
}

private sealed interface DelayUiState {
    data object Testing : DelayUiState
    data object Failed : DelayUiState
    data class Ready(val millis: Long) : DelayUiState
}

internal enum class ConfigLatencySort {
    DEFAULT,
    LATENCY_ASC,
    LATENCY_DESC,
    COUNTRY_ASC,
}

internal fun sortProfilesByLatency(
    profiles: List<ProxyProfile>,
    latencies: Map<String, Long>,
    order: ConfigLatencySort,
    countries: Map<String, CountryMetadata> = emptyMap(),
): List<ProxyProfile> {
    if (order == ConfigLatencySort.DEFAULT || profiles.size < 2) return profiles
    val indexed = profiles.withIndex()
    val comparator = when (order) {
        ConfigLatencySort.DEFAULT -> return profiles
        ConfigLatencySort.LATENCY_ASC -> compareBy<IndexedValue<ProxyProfile>> {
            latencies[it.value.id] == null
        }.thenBy {
            latencies[it.value.id] ?: Long.MAX_VALUE
        }.thenBy(IndexedValue<ProxyProfile>::index)
        ConfigLatencySort.LATENCY_DESC -> compareBy<IndexedValue<ProxyProfile>> {
            latencies[it.value.id] == null
        }.thenByDescending {
            latencies[it.value.id] ?: Long.MIN_VALUE
        }.thenBy(IndexedValue<ProxyProfile>::index)
        ConfigLatencySort.COUNTRY_ASC -> compareBy<IndexedValue<ProxyProfile>> {
            !(countries[it.value.id] ?: it.value.country).isKnown
        }.thenBy {
            (countries[it.value.id] ?: it.value.country).countryName
        }.thenBy {
            latencies[it.value.id] == null
        }.thenBy {
            latencies[it.value.id] ?: Long.MAX_VALUE
        }.thenBy(IndexedValue<ProxyProfile>::index)
    }
    return indexed.sortedWith(comparator).map(IndexedValue<ProxyProfile>::value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorSheet(
    editing: Boolean,
    name: String,
    uri: String,
    error: String?,
    onNameChange: (String) -> Unit,
    onUriChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
    onPhoneImport: (() -> Unit)? = null,
    onImport: () -> Unit,
    onSave: () -> Unit,
) {
    val accent = UacColors.DisconnectedBlue
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF101C29),
        contentColor = Color.White,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp).size(width = 38.dp, height = 4.dp).background(Color.White.copy(alpha = 0.24f), CircleShape))
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(
                if (editing) homeText("Edit configuration", "ویرایش کانفیگ") else homeText("Add configuration", "افزودن کانفیگ"),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!editing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.ContentPaste, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(homeText("Paste", "چسباندن"))
                    }
                    OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.FileOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(homeText("Import file", "واردکردن فایل"))
                    }
                }
                if (onPhoneImport != null) {
                    OutlinedButton(onClick = onPhoneImport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.QrCode2, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(homeText("Add from phone", "افزودن با موبایل"))
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(homeText("Name (optional)", "نام (اختیاری)")) },
                singleLine = true,
                colors = toolTextFieldColors(accent),
            )
            OutlinedTextField(
                value = uri,
                onValueChange = onUriChange,
                modifier = Modifier.fillMaxWidth().height(122.dp),
                label = {
                    Text(
                        homeText(
                            "VLESS, Trojan or VMess URI",
                            "لینک ${configLtr("VLESS")}، ${configLtr("Trojan")} یا ${configLtr("VMess")}",
                        ),
                    )
                },
                placeholder = { Text("vless://…  trojan://…  vmess://…") },
                colors = toolTextFieldColors(accent),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                isError = error != null,
                supportingText = error?.let { message ->
                    { Text(if (LocalHomePersian.current) localizedConfigError(message) else message, color = Color(0xFFFF7A88)) }
                },
            )
            Text(
                homeText(
                    "Profile identity is used with UAC SNI adaptive edge, fragmentation and tunnel settings.",
                    "مشخصات کانفیگ همراه تنظیمات تطبیقی ${configLtr("Edge")}، ${configLtr("Fragment")} و تونل ${configLtr("UAC SNI")} استفاده می‌شه.",
                ),
                color = UacColors.TextSecondary,
                fontSize = 10.sp,
            )
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Outlined.Save, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.size(7.dp))
                Text(
                    if (editing) homeText("Save changes", "ذخیره تغییرات") else homeText("Add and select", "افزودن و انتخاب"),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun localizedConfigError(message: String): String = when {
    message.contains("clipboard", ignoreCase = true) -> "کلیپ‌بورد کانفیگ معتبری نداره"
    message.contains("invalid", ignoreCase = true) -> "کانفیگ معتبر نیست"
    message.contains("unsupported", ignoreCase = true) -> "این نوع کانفیگ پشتیبانی نمی‌شه"
    message.contains("empty", ignoreCase = true) -> "لینک کانفیگ خالیه"
    else -> "کانفیگ وارد نشد: $message"
}

@Composable
private fun PhoneImportQrDialog(
    onImported: (String) -> Unit,
    onDismiss: () -> Unit,
    onFailed: () -> Unit,
) {
    var url by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val server = PhoneImportServer(onImported)
        val started = server.start()
        if (!started) {
            onFailed()
            onDispose { }
        } else {
            url = server.url
            onDispose { server.stop() }
        }
    }
    val qr = remember(url) { url?.let { PhoneImportQr.bitmap(it)?.asImageBitmap() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101C29),
        title = {
            Text(homeText("Add configs from phone", "افزودن کانفیگ با موبایل"), color = Color.White)
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    homeText(
                        "Scan this QR with a phone on the same Wi-Fi. Paste VLESS, Trojan or VMess on the page.",
                        "با موبایل روی همین وای‌فای این ${configLtr("QR")} را اسکن کن. در صفحه، کانفیگ ${configLtr("VLESS")}، ${configLtr("Trojan")} یا ${configLtr("VMess")} را بچسبان.",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = homeText("Import QR code", "کد QR ورود کانفیگ"),
                        modifier = Modifier
                            .size(220.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    )
                }
                url?.let { address ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        address,
                        color = UacColors.DisconnectedBlue,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text(homeText("Close", "بستن")) }
        },
    )
}

@Composable
internal fun toolTextFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent.copy(alpha = 0.85f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.13f),
    errorBorderColor = Color(0xFFFF6B79),
    focusedLabelColor = accent,
    unfocusedLabelColor = UacColors.TextSecondary,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = accent,
    focusedContainerColor = Color(0x6105101C),
    unfocusedContainerColor = Color(0x6105101C),
)
