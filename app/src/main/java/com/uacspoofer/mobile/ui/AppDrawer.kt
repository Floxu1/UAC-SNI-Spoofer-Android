package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.update.AppUpdateManager

internal enum class DrawerDestination {
    HOME,
    CONFIGS,
    SNI_MAKER,
    LIVE_LOGS,
    APP_BYPASS,
    ADVANCED_SETTINGS,
    SUPPORT,
}

internal enum class DrawerLanguage {
    PERSIAN,
    ENGLISH,
}

private data class DrawerItem(
    val destination: DrawerDestination,
    val label: String,
    val icon: ImageVector,
)

private val DrawerBlue = Color(0xFF299EFF)
private val DrawerText = Color(0xFFE7EEF8)
private val DrawerMuted = Color(0xFF98A9BF)
private val DrawerDivider = Color(0x263E5874)

private val DrawerItems = listOf(
    DrawerItem(DrawerDestination.HOME, "Home", Icons.Outlined.Home),
    DrawerItem(DrawerDestination.CONFIGS, "Configs", Icons.Outlined.Description),
    DrawerItem(DrawerDestination.SNI_MAKER, "SNI Config Maker", Icons.Outlined.Code),
    DrawerItem(DrawerDestination.LIVE_LOGS, "Logs", Icons.AutoMirrored.Outlined.ListAlt),
    DrawerItem(DrawerDestination.APP_BYPASS, "App Bypass", Icons.Outlined.Block),
    DrawerItem(DrawerDestination.ADVANCED_SETTINGS, "Advanced Settings", Icons.Outlined.Tune),
    DrawerItem(DrawerDestination.SUPPORT, "Support", Icons.Outlined.SupportAgent),
)

@Composable
internal fun DrawerOverlay(
    visible: Boolean,
    selectedDestination: DrawerDestination,
    selectedLanguage: DrawerLanguage,
    onDestinationSelected: (DrawerDestination) -> Unit,
    onLanguageSelected: (DrawerLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.67f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.TopStart),
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(310, easing = FastOutSlowInEasing),
            ) + fadeIn(tween(180)),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(250, easing = FastOutSlowInEasing),
            ) + fadeOut(tween(150)),
        ) {
            AppDrawer(
                selectedDestination = selectedDestination,
                selectedLanguage = selectedLanguage,
                onDestinationSelected = onDestinationSelected,
                onLanguageSelected = onLanguageSelected,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.80f)
                    .widthIn(max = 340.dp)
                    .padding(start = 6.dp, top = 7.dp, bottom = 7.dp),
            )
        }
    }
}

@Composable
internal fun AppDrawer(
    selectedDestination: DrawerDestination,
    selectedLanguage: DrawerLanguage,
    onDestinationSelected: (DrawerDestination) -> Unit,
    onLanguageSelected: (DrawerLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelShape = RoundedCornerShape(28.dp)

    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer()
            .shadow(
                elevation = 10.dp,
                shape = panelShape,
                ambientColor = DrawerBlue.copy(alpha = 0.16f),
                spotColor = DrawerBlue.copy(alpha = 0.18f),
            )
            .clip(panelShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A192C),
                        Color(0xFF071526),
                        Color(0xFF09182B),
                    ),
                ),
            )
            .border(1.dp, Color(0x4A34516F), panelShape),
    ) {
        val compact = maxHeight < 720.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical),
                )
                .padding(horizontal = 17.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DrawerHeader(compact = compact)
            HorizontalDivider(color = DrawerDivider, thickness = 1.dp)
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

            DrawerItems.forEach { item ->
                DrawerNavItem(
                    item = item,
                    selected = item.destination == selectedDestination,
                    compact = compact,
                    onClick = { onDestinationSelected(item.destination) },
                )
                if (item != DrawerItems.last()) {
                    Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
                }
            }

            Spacer(Modifier.weight(1f))
            DrawerSupportCard(compact = compact)
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            HorizontalDivider(color = DrawerDivider, thickness = 1.dp)
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
            DrawerLanguageRow(
                selectedLanguage = selectedLanguage,
                onLanguageSelected = onLanguageSelected,
                compact = compact,
            )
            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
        }
    }
}

@Composable
private fun DrawerHeader(compact: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (compact) 4.dp else 14.dp,
                bottom = if (compact) 6.dp else 12.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.VerifiedUser,
            contentDescription = null,
            tint = DrawerBlue,
            modifier = Modifier.size(if (compact) 28.dp else 38.dp),
        )
        Spacer(Modifier.height(if (compact) 4.dp else 9.dp))
        Text(
            text = "UAC SNI Spoofer",
            color = DrawerText,
            fontSize = if (compact) 16.sp else 19.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
        Text(
            text = "Secure network tools",
            color = DrawerMuted,
            fontSize = if (compact) 10.sp else 12.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.25.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DrawerNavItem(
    item: DrawerItem,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val background = if (selected) {
        Brush.horizontalGradient(
            colors = listOf(
                DrawerBlue.copy(alpha = 0.18f),
                DrawerBlue.copy(alpha = 0.075f),
            ),
        )
    } else {
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 35.dp else 46.dp)
            .clip(shape)
            .background(background)
            .then(
                if (selected) Modifier.border(1.dp, DrawerBlue.copy(alpha = 0.19f), shape)
                else Modifier,
            )
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(if (compact) 20.dp else 26.dp)
                    .background(DrawerBlue, RoundedCornerShape(50)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (compact) 14.dp else 18.dp,
                    end = 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (selected) Color(0xFFAAD7FF) else Color(0xFF95A9C4),
                modifier = Modifier.size(if (compact) 18.dp else 22.dp),
            )
            Spacer(Modifier.width(if (compact) 11.dp else 14.dp))
            Text(
                text = item.label,
                color = if (selected) DrawerText else Color(0xFFC2CEE0),
                fontSize = if (compact) 12.5.sp else 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun DrawerSupportCard(compact: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(19.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF282058).copy(alpha = 0.90f),
                        Color(0xFF171B42).copy(alpha = 0.90f),
                    ),
                ),
                shape,
            )
            .border(1.dp, Color(0xFF8B5CFF).copy(alpha = 0.44f), shape)
            .padding(if (compact) 8.dp else 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = null,
                tint = Color(0xFFA579FF),
                modifier = Modifier.size(if (compact) 18.dp else 22.dp),
            )
            Spacer(Modifier.width(if (compact) 7.dp else 9.dp))
            Text(
                text = "Love the app?",
                color = Color(0xFFF1ECFF),
                fontSize = if (compact) 13.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(if (compact) 3.dp else 5.dp))
        Text(
            text = "Your support helps us grow\nand build better tools.",
            color = Color(0xFFADA8CF),
            fontSize = if (compact) 9.5.sp else 11.5.sp,
            lineHeight = if (compact) 13.sp else 16.sp,
        )
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 32.dp else 39.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF6841C6), Color(0xFF3D2B79)),
                    ),
                )
                .border(1.dp, Color(0xFFB28AFF).copy(alpha = 0.42f), RoundedCornerShape(13.dp))
                .clickable(
                    role = Role.Button,
                    onClick = { openExternalLink(context, AppUpdateManager.REPOSITORY_URL) },
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (compact) 15.dp else 17.dp),
            )
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
            Text(
                text = "Star on GitHub",
                color = Color.White,
                fontSize = if (compact) 11.sp else 12.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DrawerLanguageRow(
    selectedLanguage: DrawerLanguage,
    onLanguageSelected: (DrawerLanguage) -> Unit,
    compact: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 40.dp else 50.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                tint = Color(0xFFA9BAD0),
                modifier = Modifier.size(if (compact) 18.dp else 21.dp),
            )
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
            Text(
                text = "Language",
                color = Color(0xFFC8D4E5),
                fontSize = if (compact) 11.sp else 12.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Row(
            modifier = Modifier
                .width(if (compact) 126.dp else 142.dp)
                .height(if (compact) 31.dp else 36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF07111F))
                .border(1.dp, Color(0x393B5875), RoundedCornerShape(12.dp))
                .padding(3.dp),
        ) {
            LanguageSegment(
                text = "فارسی",
                selected = selectedLanguage == DrawerLanguage.PERSIAN,
                compact = compact,
                onClick = { onLanguageSelected(DrawerLanguage.PERSIAN) },
                modifier = Modifier.weight(1f),
            )
            LanguageSegment(
                text = "English",
                selected = selectedLanguage == DrawerLanguage.ENGLISH,
                compact = compact,
                onClick = { onLanguageSelected(DrawerLanguage.ENGLISH) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LanguageSegment(
    text: String,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) DrawerBlue.copy(alpha = 0.88f) else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else DrawerMuted,
            fontSize = if (compact) 9.sp else 10.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
