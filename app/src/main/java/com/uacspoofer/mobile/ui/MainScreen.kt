package com.uacspoofer.mobile.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import com.uacspoofer.mobile.core.ConnectionState
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
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun MainScreen(
    state: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitchProfile: () -> Unit,
) {
    val context = LocalContext.current
    val profileStore = remember(context) { ProfileStore(context) }
    val profileLatencyCache = remember(context) { ProfileLatencyCache(context) }
    val countryRepository = remember(context) { ProfileCountryRepository.get(context) }
    val advancedSettings = remember(context) { AdvancedSettingsStore(context) }
    val sniMakerController = remember(context.applicationContext) { SniMakerController(context) }
    val updateManager = remember(context.applicationContext) { AppUpdateManager(context.applicationContext) }
    val activity = context as? Activity
    var selectedDestination by rememberSaveable { mutableStateOf(DrawerDestination.HOME) }
    var selectedLanguage by rememberSaveable { mutableStateOf(DrawerLanguage.ENGLISH) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var drawerWidthPx by remember { mutableIntStateOf(0) }
    var homeMotionEnabled by remember { mutableStateOf(true) }
    var homeProfile by remember { mutableStateOf(profileStore.selectedProfile()) }
    var homeCountry by remember { mutableStateOf(homeProfile.country) }
    var homeConfigsVisible by remember { mutableStateOf(false) }
    var homeConfigsLibrary by remember { mutableStateOf(profileStore.snapshot()) }
    var homeConfigLatencies by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var updateDialogVisible by remember { mutableStateOf(false) }

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
        onDispose { sniMakerController.close() }
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

    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
    BackHandler(enabled = drawerState.isClosed && selectedDestination != DrawerDestination.HOME) {
        selectedDestination = DrawerDestination.HOME
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.66f),
        drawerContent = {
            AppDrawer(
                selectedDestination = selectedDestination,
                selectedLanguage = selectedLanguage,
                onDestinationSelected = {
                    selectedDestination = it
                    closeDrawer()
                },
                onLanguageSelected = { selectedLanguage = it },
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
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedDestination) {
                DrawerDestination.CONFIGS -> ConfigsScreen(
                    onMenuClick = openDrawer,
                    connectionState = state,
                    activeProfileId = if (state == ConnectionState.CONNECTED) profileStore.activeProfile()?.id else null,
                    activeEndpoint = if (state == ConnectionState.CONNECTED) profileStore.activeEndpoint() else null,
                    onSwitchProfile = onSwitchProfile,
                )
                DrawerDestination.SNI_MAKER -> SniMakerScreen(
                    onMenuClick = openDrawer,
                    controller = sniMakerController,
                )
                DrawerDestination.LIVE_LOGS -> LiveLogsScreen(onMenuClick = openDrawer)
                DrawerDestination.APP_BYPASS -> AppBypassScreen(onMenuClick = openDrawer)
                DrawerDestination.ADVANCED_SETTINGS -> AdvancedSettingsScreen(onMenuClick = openDrawer)
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
                        val latestLibrary = profileStore.snapshot()
                        homeConfigsLibrary = latestLibrary
                        homeConfigLatencies = profileLatencyCache.snapshot(
                            latestLibrary.allProfiles.mapTo(hashSetOf(), ProxyProfile::id),
                        )
                        homeConfigsVisible = true
                    },
                )
            }
            HomeConfigsDialog(
                visible = homeConfigsVisible,
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
            if (updateDialogVisible) {
                AppUpdateDialog(
                    state = updateState,
                    onUpdate = beginUpdate,
                    onDismiss = { updateDialogVisible = false },
                )
            }
        }
    }
}

@Composable
private fun HomeScreenContent(
    state: ConnectionState,
    profile: ProxyProfile = ProxyProfile.MCI_BUILT_IN,
    motionEnabled: Boolean = true,
    onPrimaryAction: () -> Unit,
    onMenuClick: () -> Unit,
    onConfigClick: () -> Unit = {},
) {
    val stateColors = colorsFor(state)
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()

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
            ),
    ) {
        val compact = maxHeight < 700.dp
        val buttonDiameter = minOf(
            maxWidth * 0.54f,
            maxHeight * 0.30f,
            if (compact) 200.dp else 220.dp,
        ).coerceAtLeast(168.dp)
        val topSpacing = (maxHeight * 0.035f).coerceIn(12.dp, 28.dp)
        val selectorMaxWidth = maxWidth * 0.80f

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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (compact) 20.dp else 24.dp),
            )
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            AppTitle(compact = compact, accent = stateColors.accent)
            Spacer(Modifier.height(if (compact) 20.dp else 22.dp))
            ConnectButton(
                state = state,
                accent = stateColors.accent,
                diameter = buttonDiameter,
                onClick = onPrimaryAction,
            )
            Spacer(Modifier.height(if (compact) 7.dp else 11.dp))
            ConnectionStatus(state = state, accent = stateColors.accent)
            Spacer(Modifier.height(if (compact) 5.dp else 7.dp))
            SelectedProfileRow(
                profile = profile,
                onClick = onConfigClick,
                maxWidth = selectorMaxWidth,
            )
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
            Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
            AnimatedDottedWave(
                accent = stateColors.accent,
                motionEnabled = motionEnabled,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
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
        Text("Selected", color = UacColors.TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.size(9.dp))
        Box(Modifier.size(width = 1.dp, height = 16.dp).background(Color.White.copy(alpha = 0.13f)))
        Spacer(Modifier.size(9.dp))
        AnimatedContent(
            targetState = profile,
            modifier = Modifier.widthIn(max = (maxWidth - 128.dp).coerceAtLeast(88.dp)),
            contentAlignment = Alignment.CenterStart,
            transitionSpec = {
                (fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { slideDistancePx })
                    .togetherWith(fadeOut(tween(120)))
                    .using(SizeTransform(clip = false))
            },
            contentKey = ProxyProfile::id,
            label = "selected-config",
        ) { current ->
            Text(
                current.name,
                color = UacColors.DisconnectedBlue,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
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

@Preview(name = "Home - Error", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ErrorPreview() {
    UacSniSpooferTheme {
        HomeScreenContent(ConnectionState.ERROR, onPrimaryAction = {}, onMenuClick = {})
    }
}
