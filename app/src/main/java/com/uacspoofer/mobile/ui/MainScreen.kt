package com.uacspoofer.mobile.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.engine.tor.TorExitCountry
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProfileLatencyCache
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.profiles.ProfileCountryRepository
import com.uacspoofer.mobile.profiles.ProfileEndpoint
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.ui.theme.UacSniSpooferTheme
import com.uacspoofer.mobile.ui.theme.colorsFor
import com.uacspoofer.mobile.update.AppRelease
import com.uacspoofer.mobile.update.AppUpdateManager
import com.uacspoofer.mobile.update.InstallLaunchResult
import com.uacspoofer.mobile.update.UpdateCheckResult
import com.uacspoofer.mobile.update.UpdateUiState
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun MainScreen(
    state: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitchProfile: () -> Unit,
    onMinimize: () -> Unit,
    onCloseApp: () -> Unit,
) {
    val context = LocalContext.current
    val profileStore = remember(context) { ProfileStore(context) }
    val profileLatencyCache = remember(context) { ProfileLatencyCache(context) }
    val countryRepository = remember(context) { ProfileCountryRepository.get(context) }
    val advancedSettings = remember(context) { AdvancedSettingsStore(context) }
    val engineModeStore = remember(context) { EngineModeStore.get(context) }
    val engineMode by engineModeStore.mode.collectAsStateWithLifecycle()
    val torEngineStore = remember(context) { TorEngineStore.get(context) }
    val torSettings by torEngineStore.settings.collectAsStateWithLifecycle()
    val sniMakerController = remember(context.applicationContext) { SniMakerController(context) }
    val routeSpeedTestController = remember(context.applicationContext) { RouteSpeedTestController.get(context) }
    val updateManager = remember(context.applicationContext) { AppUpdateManager(context.applicationContext) }
    val activity = context as? Activity
    var selectedDestination by rememberSaveable { mutableStateOf(DrawerDestination.HOME) }
    val languagePrefs = remember(context) {
        context.getSharedPreferences("app_preferences", android.content.Context.MODE_PRIVATE)
    }

    var selectedLanguage by rememberSaveable {
        mutableStateOf(
            when (languagePrefs.getString("language", "PERSIAN")) {
                "ENGLISH" -> DrawerLanguage.ENGLISH
                else -> DrawerLanguage.PERSIAN
            }
        )
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val homeRemoteFocus = remember { HomeRemoteFocus() }
    val drawerOpen = drawerState.currentValue == DrawerValue.Open ||
        drawerState.targetValue == DrawerValue.Open
    var drawerWidthPx by remember { mutableIntStateOf(0) }
    var homeMotionEnabled by remember { mutableStateOf(true) }
    var homeProfile by remember { mutableStateOf(profileStore.selectedProfile()) }
    var homeCountry by remember { mutableStateOf(homeProfile.country) }
    var homeConfigsVisible by remember { mutableStateOf(false) }
    var homeTorCountriesVisible by remember { mutableStateOf(false) }
    var homeConfigsLibrary by remember { mutableStateOf(profileStore.snapshot()) }
    var homeConfigLatencies by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var updateDialogVisible by remember { mutableStateOf(false) }
    var backPromptVisible by remember { mutableStateOf(false) }
    var backPromptConnected by remember { mutableStateOf(false) }

    suspend fun performUpdateCheck() {
        updateState = UpdateUiState.Checking
        updateState = try {
            when (val result = updateManager.checkForUpdate()) {
                UpdateCheckResult.Current -> UpdateUiState.UpToDate
                is UpdateCheckResult.Available -> {
                    updateDialogVisible = true
                    UpdateUiState.Available(result.release)
                }
            }
        } catch (error: Throwable) {
            UpdateUiState.Error(error.message ?: "Could not check GitHub Releases")
        }
    }

    DisposableEffect(sniMakerController) {
        onDispose {
            sniMakerController.close()
        }
    }
    LaunchedEffect(updateManager) {
        performUpdateCheck()
    }
    val openDrawer: () -> Unit = { drawerScope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { drawerScope.launch { drawerState.close() } }
    val checkForUpdates: () -> Unit = {
        if (updateState != UpdateUiState.Checking && updateState !is UpdateUiState.Downloading) {
            drawerScope.launch { performUpdateCheck() }
        }
    }
    val beginUpdate: (AppRelease) -> Unit = { release ->
        if (updateState !is UpdateUiState.Downloading) {
            if (activity == null) {
                updateState = UpdateUiState.Error("Android installer is unavailable", release)
                updateDialogVisible = true
            } else {
                drawerScope.launch {
                    updateDialogVisible = true
                    updateState = UpdateUiState.Downloading(release, 0)
                    updateState = try {
                        when (
                            updateManager.downloadAndInstall(activity, release) { progress ->
                                updateState = UpdateUiState.Downloading(release, progress)
                            }
                        ) {
                            InstallLaunchResult.INSTALLER_OPENED ->
                                UpdateUiState.Ready(release, "Android installer opened. Confirm the update to finish.")
                            InstallLaunchResult.PERMISSION_REQUESTED ->
                                UpdateUiState.Ready(release, "Allow installs from this app; the installer will open when you return.")
                        }
                    } catch (error: Throwable) {
                        UpdateUiState.Error(error.message ?: "The update could not be downloaded", release)
                    }
                }
            }
        }
    }

    
    
    
    
    LaunchedEffect(drawerState, drawerWidthPx) {
        if (drawerWidthPx <= 0) return@LaunchedEffect
        var closedOffset = Float.NaN
        snapshotFlow {
            DrawerMotionSnapshot(
                offset = drawerState.currentOffset,
                currentValue = drawerState.currentValue,
                targetValue = drawerState.targetValue,
                animationRunning = drawerState.isAnimationRunning,
            )
        }.collect { snapshot ->
            if (!snapshot.offset.isFinite()) return@collect
            val semanticallyClosed =
                snapshot.currentValue == DrawerValue.Closed &&
                    snapshot.targetValue == DrawerValue.Closed &&
                    !snapshot.animationRunning
            if (closedOffset.isNaN() && semanticallyClosed) {
                closedOffset = snapshot.offset
            }
            val fullyClosed =
                semanticallyClosed &&
                    !closedOffset.isNaN() &&
                    abs(snapshot.offset - closedOffset) <= DRAWER_CLOSED_EPSILON_PX
            if (homeMotionEnabled != fullyClosed) homeMotionEnabled = fullyClosed
        }
    }

    LaunchedEffect(engineMode, selectedDestination) {
        if (selectedDestination == DrawerDestination.ENGINE_MODE) {
            selectedDestination = DrawerDestination.SETTINGS
            return@LaunchedEffect
        }
        if (!selectedDestination.visibleFor(engineMode)) {
            selectedDestination = DrawerDestination.HOME
        }
        if (engineMode.isTor) homeConfigsVisible = false else homeTorCountriesVisible = false
    }
    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
    BackHandler(enabled = drawerState.isClosed && selectedDestination != DrawerDestination.HOME) {
        selectedDestination = if (
            selectedDestination == DrawerDestination.ADVANCED_SETTINGS ||
            selectedDestination == DrawerDestination.ENGINE_MODE
        ) {
            DrawerDestination.SETTINGS
        } else {
            DrawerDestination.HOME
        }
    }
    BackHandler(
        enabled = drawerState.isClosed &&
            selectedDestination == DrawerDestination.HOME &&
            !homeConfigsVisible &&
            !homeTorCountriesVisible &&
            !updateDialogVisible,
    ) {
        val connected = state == ConnectionState.CONNECTED
        if (backPromptVisible && backPromptConnected == connected) {
            backPromptVisible = false
            if (connected) onMinimize() else onCloseApp()
        } else {
            backPromptConnected = connected
            backPromptVisible = true
        }
    }

    LaunchedEffect(backPromptVisible, backPromptConnected) {
        if (backPromptVisible) {
            delay(DOUBLE_BACK_WINDOW_MS)
            backPromptVisible = false
        }
    }

    LaunchedEffect(selectedDestination, state) {
        val nextProfile = if (state == ConnectionState.CONNECTED) {
            profileStore.activeProfile() ?: profileStore.selectedProfile()
        } else {
            profileStore.selectedProfile()
        }
        homeProfile = nextProfile
        homeCountry = nextProfile.country
        val settings = advancedSettings.snapshot().validated()
        val endpoint = if (state == ConnectionState.CONNECTED) {
            profileStore.activeEndpoint()
        } else {
            null
        } ?: ProfileEndpoint(settings.primaryAddress, settings.primaryPort)
        homeCountry = countryRepository.resolve(nextProfile, endpoint).country
    }

    LaunchedEffect(drawerState.currentValue, selectedDestination, engineMode) {
        if (drawerState.currentValue != DrawerValue.Closed) return@LaunchedEffect
        if (selectedDestination != DrawerDestination.HOME) return@LaunchedEffect
        if (!KeyboardNavigationState.value) return@LaunchedEffect
        val guide = HomeGuideStore.get(context)
        if (
            HomeGuide.step(
                engineSeen = guide.engineSeen(),
                countrySeen = guide.countrySeen(),
                torMode = engineMode.isTor,
                drawerOpen = false,
            ) != null
        ) {
            return@LaunchedEffect
        }
        delay(80)
        runCatching { homeRemoteFocus.connect.requestFocus() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.66f),
        drawerContent = {
            AppDrawer(
                selectedDestination = when (selectedDestination) {
                    DrawerDestination.ADVANCED_SETTINGS,
                    DrawerDestination.ENGINE_MODE -> DrawerDestination.SETTINGS
                    else -> selectedDestination
                },
                selectedLanguage = selectedLanguage,
                drawerOpen = drawerOpen,
                onDestinationSelected = {
                    selectedDestination = it
                    closeDrawer()
                },
                onLanguageSelected = { language ->
                    selectedLanguage = language

                    languagePrefs.edit()
                        .putString("language", language.name)
                        .apply()
                },
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.80f)
                    .widthIn(max = 340.dp)
                    .onSizeChanged { size ->
                        if (drawerWidthPx != size.width) drawerWidthPx = size.width
                    }
                    .padding(start = 6.dp, top = 7.dp, bottom = 7.dp),
            )
        },
    ) {
        CompositionLocalProvider(
            LocalHomePersian provides (selectedLanguage == DrawerLanguage.PERSIAN),
            LocalHomeRemoteFocus provides homeRemoteFocus,
            LocalDrawerOpen provides drawerOpen,
            LocalWideShell provides rememberWideShell(),
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedDestination) {
                DrawerDestination.CONFIGS -> if (engineMode.isTor) {
                    TorCountryScreen(onMenuClick = openDrawer)
                } else {
                    ConfigsScreen(
                        onMenuClick = openDrawer,
                        connectionState = state,
                        activeProfileId = if (state == ConnectionState.CONNECTED) profileStore.activeProfile()?.id else null,
                        activeEndpoint = if (state == ConnectionState.CONNECTED) profileStore.activeEndpoint() else null,
                        onSwitchProfile = onSwitchProfile,
                    )
                }
                DrawerDestination.SNI_MAKER -> SniMakerScreen(
                    onMenuClick = openDrawer,
                    controller = sniMakerController,
                )
                DrawerDestination.ROUTE_SPEED_TEST -> RouteSpeedTestScreen(
                    controller = routeSpeedTestController,
                    onBackClick = { selectedDestination = DrawerDestination.HOME },
                )
                DrawerDestination.LIVE_LOGS -> LiveLogsScreen(onMenuClick = openDrawer)
                DrawerDestination.APP_BYPASS -> AppBypassScreen(onMenuClick = openDrawer)
                DrawerDestination.SETTINGS,
                DrawerDestination.ENGINE_MODE -> SettingsScreen(
                    onMenuClick = openDrawer,
                    onAdvancedSettingsClick = { selectedDestination = DrawerDestination.ADVANCED_SETTINGS },
                )
                DrawerDestination.ADVANCED_SETTINGS -> AdvancedSettingsScreen(
                    onBackClick = { selectedDestination = DrawerDestination.SETTINGS },
                )
                DrawerDestination.SUPPORT -> SupportScreen(
                    onMenuClick = openDrawer,
                    updateState = updateState,
                    onCheckForUpdate = checkForUpdates,
                    onUpdate = beginUpdate,
                )
                else -> HomeScreenContent(
                        state = state,
                        profile = if (homeCountry.isKnown) homeProfile.copy(country = homeCountry) else homeProfile,
                        motionEnabled = homeMotionEnabled,
                        onPrimaryAction = when (state) {
                            ConnectionState.CONNECTED -> onDisconnect
                            ConnectionState.CONNECTING -> onDisconnect
                            ConnectionState.DISCONNECTING -> ({})
                            ConnectionState.DISCONNECTED,
                            ConnectionState.ERROR -> onConnect
                        },
                        onMenuClick = openDrawer,
                        onConfigClick = {
                            if (engineMode.isTor) {
                                homeTorCountriesVisible = true
                            } else {
                                val latestLibrary = profileStore.snapshot()
                                homeConfigsLibrary = latestLibrary
                                homeConfigLatencies = profileLatencyCache.snapshot(
                                    latestLibrary.allProfiles.mapTo(hashSetOf(), ProxyProfile::id),
                                )
                                homeConfigsVisible = true
                            }
                        },
                    )
            }
            if (engineMode.isXray) {
                ConnectRescueOverlay(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(WindowInsets.safeDrawing.asPaddingValues())
                        .padding(bottom = 12.dp)
                        .zIndex(20f),
                )
            }
            HomeConfigsDialog(
                visible = homeConfigsVisible && engineMode.isXray,
                library = homeConfigsLibrary,
                activeProfileId = if (state == ConnectionState.CONNECTED) profileStore.activeProfile()?.id else null,
                latencies = homeConfigLatencies,
                onSelect = { profile ->
                    val selectionChanged = homeConfigsLibrary.selectedId != profile.id
                    val activeChanged = profileStore.activeProfile()?.id != profile.id
                    if (selectionChanged) {
                        homeConfigsLibrary = profileStore.select(profile.id)
                        homeProfile = profile
                        homeCountry = profile.country
                    }
                    homeConfigsVisible = false
                    if (state == ConnectionState.CONNECTED && activeChanged) onSwitchProfile()
                },
                onManage = {
                    homeConfigsVisible = false
                    selectedDestination = DrawerDestination.CONFIGS
                },
                onDismissRequest = { homeConfigsVisible = false },
            )
            HomeTorCountryDialog(
                visible = homeTorCountriesVisible && engineMode.isTor,
                selectedCode = torSettings.exitCountryCode,
                connected = state == ConnectionState.CONNECTED,
                onSelect = { code ->
                    val next = torEngineStore.snapshot().copy(
                        exitCountryCode = code,
                        exitStrict = code.isNotEmpty(),
                    ).validated()
                    val changed = next.exitCountryCode != torSettings.exitCountryCode ||
                        next.exitStrict != torSettings.exitStrict
                    if (changed) {
                        torEngineStore.save(next)
                        if (
                            state == ConnectionState.CONNECTED ||
                            state == ConnectionState.CONNECTING
                        ) {
                            VpnController.applyTorExit(context)
                        }
                    }
                    homeTorCountriesVisible = false
                },
                onManage = {
                    homeTorCountriesVisible = false
                    selectedDestination = DrawerDestination.CONFIGS
                },
                onDismissRequest = { homeTorCountriesVisible = false },
            )
            if (updateDialogVisible) {
                AppUpdateDialog(
                    state = updateState,
                    onUpdate = beginUpdate,
                    onDismiss = { updateDialogVisible = false },
                )
            }
            DoubleBackPrompt(
                visible = backPromptVisible,
                connected = backPromptConnected,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f),
            )
        }
        }
    }
}

@Composable
private fun DoubleBackPrompt(
    visible: Boolean,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = if (connected) UacColors.ConnectedGreen else UacColors.DisconnectedBlue
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(170)) +
            scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.985f),
        exit = fadeOut(tween(140)) +
            scaleOut(tween(150), targetScale = 0.995f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB8040A11))
                .padding(WindowInsets.safeDrawing.asPaddingValues()),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 22.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .shadow(32.dp, RoundedCornerShape(22.dp), ambientColor = accent, spotColor = accent)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF07131F),
                                Color(0xFF0B232B),
                                Color(0xFF07131F),
                            ),
                        ),
                        RoundedCornerShape(22.dp),
                    )
                    .border(1.2.dp, accent.copy(alpha = 0.78f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp, vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(accent.copy(alpha = 0.18f), CircleShape)
                        .border(1.2.dp, accent.copy(alpha = 0.62f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (connected) {
                            Icons.Outlined.KeyboardDoubleArrowDown
                        } else {
                            Icons.Outlined.PowerSettingsNew
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (connected) "Keep VPN running" else "Close the app",
                        color = Color.White,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = if (connected) {
                            "Press back again to minimize"
                        } else {
                            "Press back again to exit completely"
                        },
                        color = Color(0xFFB8C8D4),
                        fontSize = 12.5.sp,
                    )
                }
                Box(Modifier.size(8.dp).background(accent, CircleShape))
            }
        }
    }
}

@Composable
private fun HomeScreenContent(
    state: ConnectionState,
    profile: ProxyProfile = ProxyProfile.UAC_SNI_BUILT_IN,
    motionEnabled: Boolean = true,
    onPrimaryAction: () -> Unit,
    onMenuClick: () -> Unit,
    onConfigClick: () -> Unit = {},
) {
    val stateColors = colorsFor(state)
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val context = LocalContext.current.applicationContext
    val engineStore = remember(context) { EngineModeStore.get(context) }
    val engineMode by engineStore.mode.collectAsStateWithLifecycle()
    val guideStore = remember(context) { HomeGuideStore.get(context) }
    val drawerOpen = LocalDrawerOpen.current
    var engineSeen by remember { mutableStateOf(guideStore.engineSeen()) }
    var countrySeen by remember { mutableStateOf(guideStore.countrySeen()) }
    var engineLayout by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var countryLayout by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var guideReady by remember { mutableStateOf(false) }
    val gotItFocus = remember { FocusRequester() }
    val guideScope = rememberCoroutineScope()
    val homeFocus = LocalHomeRemoteFocus.current
    LaunchedEffect(Unit) {
        delay(520)
        guideReady = true
    }
    LaunchedEffect(engineMode.isTor) {
        if (engineMode.isTor && !engineSeen) {
            guideStore.markEngineSeen()
            engineSeen = true
        }
    }
    val guideStep = if (guideReady) {
        HomeGuide.step(engineSeen, countrySeen, engineMode.isTor, drawerOpen)
    } else {
        null
    }
    val guideTarget = when (guideStep) {
        HomeGuideStep.Engine -> engineLayout
        HomeGuideStep.Country -> countryLayout
        null -> null
    }
    val showGuide = guideStep != null && guideTarget?.isAttached == true
    val guideSession = guideStep?.takeIf { showGuide }?.let { HomeGuideSession(it, gotItFocus) }

    CompositionLocalProvider(
        LocalHomeGuideActive provides showGuide,
        LocalHomeGuideSession provides guideSession,
    ) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        UacColors.BackgroundTop,
                        UacColors.BackgroundMiddle,
                        UacColors.BackgroundBottom,
                    ),
                ),
            )
            .homeRemoteDpad(onMenuClick),
    ) {
        val innerHeight = maxHeight -
            safeDrawingPadding.calculateTopPadding() -
            safeDrawingPadding.calculateBottomPadding()
        val compact = innerHeight < 700.dp
        val tight = innerHeight < 620.dp
        val wide = LocalWideShell.current || WideShell.isWide(maxWidth, maxHeight)
        val contentMax = if (wide) WideShell.HomeContentMax else maxWidth
        val selectorMaxWidth = contentMax * 0.80f
        val headerPad = if (wide) WideShell.EdgePadding else if (compact) 20.dp else 24.dp
        val topSpacing = (innerHeight * 0.028f).coerceIn(
            if (tight) 6.dp else 10.dp,
            if (compact) 18.dp else 28.dp,
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.width * 0.70f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        stateColors.accent.copy(alpha = 0.040f),
                        stateColors.accent.copy(alpha = 0.016f),
                        stateColors.accent.copy(alpha = 0f),
                    ),
                    center = Offset(size.width / 2f, size.height * 0.41f),
                    radius = radius,
                ),
                center = Offset(size.width / 2f, size.height * 0.41f),
                radius = radius,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(safeDrawingPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(topSpacing))
            HomeHeader(
                accent = stateColors.accent,
                compact = compact,
                onMenuClick = onMenuClick,
                engineToggleEnabled = true,
                onEngineLaidOut = { engineLayout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = headerPad),
            )
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AnimatedContent(
                    targetState = engineMode,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { engineSwitchTransition() },
                    contentAlignment = Alignment.TopCenter,
                    label = "engine-home-switch",
                ) { mode ->
                    CompositionLocalProvider(LocalDisplayedEngineMode provides mode) {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .then(
                                    if (wide) {
                                        Modifier.widthIn(max = contentMax).fillMaxWidth()
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppTitle(compact = compact, accent = stateColors.accent)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                ) {
                                    val halo = when {
                                        tight || maxHeight < 280.dp -> 22.dp
                                        compact -> 36.dp
                                        else -> 54.dp
                                    }
                                    val diameter = minOf(
                                        maxWidth * 0.54f,
                                        (maxHeight - halo).coerceAtLeast(0.dp),
                                        if (compact) 200.dp else 220.dp,
                                    )
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        if (diameter > 0.dp) {
                                            ConnectButton(
                                                state = state,
                                                accent = stateColors.accent,
                                                diameter = diameter,
                                                halo = halo,
                                                onClick = onPrimaryAction,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(if (tight) 4.dp else if (compact) 7.dp else 11.dp))
                                ConnectionStatus(state = state, accent = stateColors.accent)
                                Spacer(Modifier.height(if (tight) 2.dp else if (compact) 5.dp else 7.dp))
                                SelectedProfileRow(
                                    profile = profile,
                                    onClick = onConfigClick,
                                    maxWidth = selectorMaxWidth,
                                    modifier = Modifier.onGloballyPositioned { countryLayout = it },
                                )
                            }
                            Spacer(Modifier.height(if (compact) 6.dp else 9.dp))
                            TrafficStatsRow(
                                accent = stateColors.accent,
                                compact = compact,
                                modifier = Modifier.fillMaxWidth(0.86f),
                            )
                            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                            ConnectionAwareFeatureCard(
                                state = state,
                                profile = profile,
                                accent = stateColors.accent,
                                compact = compact,
                                modifier = Modifier.fillMaxWidth(0.86f),
                            )
                            Spacer(Modifier.height(if (tight) 2.dp else if (compact) 3.dp else 6.dp))
                        }
                    }
                }
            }
            AnimatedDottedWave(
                accent = stateColors.accent,
                motionEnabled = motionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (tight) 14.dp else if (compact) 22.dp else 32.dp),
            )
        }
        if (showGuide) {
            val step = checkNotNull(guideStep)
            HomeGuideOverlay(
                step = step,
                target = guideTarget,
                gotIt = gotItFocus,
                onDismiss = {
                    val nextEngineSeen = engineSeen || step == HomeGuideStep.Engine
                    val nextCountrySeen = countrySeen || step == HomeGuideStep.Country
                    when (step) {
                        HomeGuideStep.Engine -> {
                            guideStore.markEngineSeen()
                            engineSeen = true
                        }
                        HomeGuideStep.Country -> {
                            guideStore.markCountrySeen()
                            countrySeen = true
                        }
                    }
                    val next = HomeGuide.step(
                        engineSeen = nextEngineSeen,
                        countrySeen = nextCountrySeen,
                        torMode = engineMode.isTor,
                        drawerOpen = false,
                    )
                    if (next == null && homeFocus != null) {
                        homeFocus.suppressConfirmUp = true
                        guideScope.launch {
                            delay(120)
                            runCatching { homeFocus.connect.requestFocus() }
                        }
                    }
                },
            )
        }
    }
    }
}

@Composable
private fun SelectedProfileRow(
    profile: ProxyProfile,
    onClick: () -> Unit,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val localizedFont = homeLocalizedFont()
    val context = LocalContext.current
    val engineMode = rememberDisplayedEngineMode()
    val torStore = remember(context) { TorEngineStore.get(context) }
    val torSettings by torStore.settings.collectAsStateWithLifecycle()
    val isPersian = LocalHomePersian.current
    val selectedLabel = if (engineMode.isTor) {
        if (torSettings.exitCountryCode.isEmpty()) {
            homeText("Automatic", "خودکار")
        } else {
            TorExitCountry.displayName(
                torSettings.exitCountryCode,
                if (isPersian) Locale("fa") else Locale.ENGLISH,
            )
        }
    } else {
        profile.name
    }
    val selectionKey = if (engineMode.isTor) "tor:${torSettings.exitCountryCode}" else profile.id
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val guideSession = LocalHomeGuideSession.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pressedOffset = remember(density) { with(density) { 2.dp.toPx() } }
    val slideDistancePx = remember(density) { with(density) { 4.dp.roundToPx() } }
    val chevronOffset by animateFloatAsState(
        targetValue = if (pressed) pressedOffset else 0f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "selected-config-chevron",
    )
    Row(
        modifier = modifier
            .widthIn(max = maxWidth)
            .height(44.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .then(
                if (guideSession?.step == HomeGuideStep.Country) {
                    Modifier.guideTargetDpad(guideSession.gotIt)
                } else {
                    Modifier
                },
            )
            .trackHomeSlot(HomeRemoteSlot.Profile)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.5.dp).background(UacColors.DisconnectedBlue, CircleShape))
        Spacer(Modifier.size(7.dp))
        Text(
            homeText("Selected", "انتخاب‌شده"),
            color = UacColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = localizedFont,
            fontWeight = if (LocalHomePersian.current) FontWeight.Medium else null,
        )
        Spacer(Modifier.size(9.dp))
        Box(Modifier.size(width = 1.dp, height = 16.dp).background(Color.White.copy(alpha = 0.13f)))
        Spacer(Modifier.size(9.dp))
        AnimatedContent(
            targetState = selectedLabel,
            modifier = Modifier.widthIn(max = (maxWidth - 128.dp).coerceAtLeast(88.dp)),
            contentAlignment = Alignment.CenterStart,
            transitionSpec = {
                (fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { slideDistancePx })
                    .togetherWith(fadeOut(tween(120)))
                    .using(SizeTransform(clip = false))
            },
            contentKey = { selectionKey },
            label = "selected-config",
        ) { current ->
            Text(
                current,
                color = UacColors.DisconnectedBlue,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = localizedFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(7.dp))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = UacColors.DisconnectedBlue,
            modifier = Modifier
                .size(17.dp)
                .graphicsLayer { translationX = chevronOffset },
        )
    }
}

private data class DrawerMotionSnapshot(
    val offset: Float,
    val currentValue: DrawerValue,
    val targetValue: DrawerValue,
    val animationRunning: Boolean,
)

private const val DRAWER_CLOSED_EPSILON_PX = 0.5f
private const val DOUBLE_BACK_WINDOW_MS = 2_200L

@Preview(name = "Home - Disconnected", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DisconnectedPreview() {
    UacSniSpooferTheme {
        HomeScreenContent(ConnectionState.DISCONNECTED, onPrimaryAction = {}, onMenuClick = {})
    }
}

@Preview(name = "Home - Connected", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ConnectedPreview() {
    UacSniSpooferTheme {
        HomeScreenContent(ConnectionState.CONNECTED, onPrimaryAction = {}, onMenuClick = {})
    }
}

@Preview(name = "Home - Compact 640", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun CompactHomePreview() {
    UacSniSpooferTheme {
        HomeScreenContent(ConnectionState.CONNECTED, onPrimaryAction = {}, onMenuClick = {})
    }
}

@Preview(name = "Home - Short 568", showBackground = true, widthDp = 360, heightDp = 568)
@Composable
private fun ShortHomePreview() {
    UacSniSpooferTheme {
        HomeScreenContent(ConnectionState.CONNECTED, onPrimaryAction = {}, onMenuClick = {})
    }
}
