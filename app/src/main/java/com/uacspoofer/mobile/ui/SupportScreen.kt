package com.uacspoofer.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uacspoofer.mobile.BuildConfig
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.update.AppRelease
import com.uacspoofer.mobile.update.AppUpdateManager
import com.uacspoofer.mobile.update.UpdateUiState

private val SupportPurple = Color(0xFF9A72FF)
private val TelegramBlue = Color(0xFF37AEE2)

@Composable
internal fun SupportScreen(
    onMenuClick: () -> Unit,
    updateState: UpdateUiState,
    onCheckForUpdate: () -> Unit,
    onUpdate: (AppRelease) -> Unit,
) {
    val context = LocalContext.current
    ToolPageBackground(accent = SupportPurple) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(10.dp))
                ToolPageHeader(
                    title = "Support",
                    subtitle = "Community, updates and project links",
                    icon = Icons.Outlined.SupportAgent,
                    accent = SupportPurple,
                    onMenuClick = onMenuClick,
                )
            }
            item {
                SupportIntroCard()
            }
                item { SectionLabel("Telegram") }
            item {
                SupportLinkCard(
                    title = "Telegram Channel",
                    address = "t.me/UacSniSpoofer",
                    icon = Icons.Outlined.Send,
                    accent = TelegramBlue,
                    onClick = { openExternalLink(context, TELEGRAM_CHANNEL_URL) },
                )
            }
            item {
                SupportLinkCard(
                    title = "Telegram Group",
                    address = "t.me/UacSniSpooferGroup",
                    icon = Icons.Outlined.Forum,
                    accent = Color(0xFF6EC8FF),
                    onClick = { openExternalLink(context, TELEGRAM_GROUP_URL) },
                )
            }
                item { SectionLabel("Application") }
            item {
                VersionAndUpdateCard(
                    state = updateState,
                    onCheckForUpdate = onCheckForUpdate,
                    onUpdate = onUpdate,
                )
            }
            item {
                SupportLinkCard(
                    title = "GitHub Project",
                    address = "Floxu1/UAC-SNI-Spoofer-Android",
                    icon = Icons.Outlined.NewReleases,
                    accent = SupportPurple,
                    onClick = { openExternalLink(context, AppUpdateManager.REPOSITORY_URL) },
                )
            }
            item { Spacer(Modifier.height(8.dp).navigationBarsPadding()) }
        }
    }
}

@Composable
private fun SupportIntroCard() {
    val shape = RoundedCornerShape(23.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(Color(0xFF241D50), Color(0xFF121C35))),
                shape,
            )
            .border(1.dp, SupportPurple.copy(alpha = 0.34f), shape)
            .padding(17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(SupportPurple.copy(alpha = 0.14f), CircleShape)
                .border(1.dp, SupportPurple.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.SupportAgent, null, tint = SupportPurple, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.size(13.dp))
        Column(Modifier.weight(1f)) {
            Text("We're here to help", color = UacColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                "Follow releases, ask questions and join the UAC community.",
                color = UacColors.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun SupportLinkCard(
    title: String,
    address: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, shape)
            .border(1.dp, accent.copy(alpha = 0.22f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(39.dp).background(accent.copy(alpha = 0.13f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(address, color = UacColors.TextSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
            Icon(Icons.Outlined.OpenInNew, "Open $title", tint = accent.copy(alpha = 0.82f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun VersionAndUpdateCard(
    state: UpdateUiState,
    onCheckForUpdate: () -> Unit,
    onUpdate: (AppRelease) -> Unit,
) {
    val availableRelease = when (state) {
        is UpdateUiState.Available -> state.release
        is UpdateUiState.Error -> state.release
        else -> null
    }
    val status = when (state) {
        UpdateUiState.Idle -> "Automatic GitHub release checks are enabled"
        UpdateUiState.Checking -> "Checking GitHub Releases…"
        UpdateUiState.UpToDate -> "You are using the latest release"
        is UpdateUiState.Available -> "Version ${state.release.version} is ready"
        is UpdateUiState.Downloading -> state.progress?.let { "Downloading update… $it%" } ?: "Downloading update…"
        is UpdateUiState.Ready -> state.message
        is UpdateUiState.Error -> state.message
    }
    val statusColor = when (state) {
        UpdateUiState.UpToDate -> UacColors.ConnectedGreen
        is UpdateUiState.Available -> SupportPurple
        is UpdateUiState.Error -> UacColors.ErrorRed
        else -> UacColors.TextSecondary
    }
    val shape = RoundedCornerShape(21.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, shape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(43.dp).background(SupportPurple.copy(alpha = 0.13f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.NewReleases, null, tint = SupportPurple, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Current version", color = UacColors.TextSecondary, fontSize = 10.5.sp)
                Text("v${BuildConfig.VERSION_NAME}", color = UacColors.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            if (state == UpdateUiState.Checking) {
                CircularProgressIndicator(modifier = Modifier.size(21.dp), color = SupportPurple, strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(status, color = statusColor, fontSize = 11.5.sp)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { if (availableRelease != null) onUpdate(availableRelease) else onCheckForUpdate() },
            enabled = state !is UpdateUiState.Downloading && state != UpdateUiState.Checking,
            modifier = Modifier.fillMaxWidth().height(43.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B49C7), contentColor = Color.White),
        ) {
            Icon(
                if (availableRelease != null) Icons.Outlined.Download else Icons.Outlined.NewReleases,
                null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(7.dp))
            Text(if (availableRelease != null) "Update now" else "Check for updates", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun AppUpdateDialog(
    state: UpdateUiState,
    onUpdate: (AppRelease) -> Unit,
    onDismiss: () -> Unit,
) {
    val release = when (state) {
        is UpdateUiState.Available -> state.release
        is UpdateUiState.Downloading -> state.release
        is UpdateUiState.Ready -> state.release
        is UpdateUiState.Error -> state.release
        else -> null
    } ?: return
    val downloading = state is UpdateUiState.Downloading
    val title = when (state) {
        is UpdateUiState.Downloading -> "Downloading update"
        is UpdateUiState.Ready -> "Ready to install"
        is UpdateUiState.Error -> "Update interrupted"
        else -> "A new update is ready"
    }
    val description = when (state) {
        is UpdateUiState.Downloading -> "Keep the app open for a moment. The Android installer will appear automatically."
        is UpdateUiState.Ready -> state.message
        is UpdateUiState.Error -> state.message
        else -> "Version ${release.version} is available. Download it securely from the official GitHub release."
    }

    Dialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !downloading,
            dismissOnClickOutside = !downloading,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.70f)).padding(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 410.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF0B1625),
                border = BorderStroke(1.dp, SupportPurple.copy(alpha = 0.38f)),
                shadowElevation = 22.dp,
            ) {
                Column(Modifier.padding(21.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(
                                Brush.radialGradient(listOf(SupportPurple.copy(alpha = 0.34f), SupportPurple.copy(alpha = 0.10f))),
                                CircleShape,
                            )
                            .border(1.dp, SupportPurple.copy(alpha = 0.38f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (downloading) Icons.Outlined.Download else Icons.Outlined.NewReleases,
                            null,
                            tint = Color(0xFFC7B4FF),
                            modifier = Modifier.size(29.dp),
                        )
                    }
                    Spacer(Modifier.height(15.dp))
                    Text(title, color = UacColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        description,
                        color = UacColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Surface(color = SupportPurple.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp)) {
                        Text(
                            "v${BuildConfig.VERSION_NAME}  →  v${release.version}",
                            color = Color(0xFFD7CAFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                        )
                    }
                    if (state is UpdateUiState.Downloading) {
                        Spacer(Modifier.height(17.dp))
                        if (state.progress == null) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = SupportPurple,
                                trackColor = Color.White.copy(alpha = 0.08f),
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { state.progress.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = SupportPurple,
                                trackColor = Color.White.copy(alpha = 0.08f),
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Text("${state.progress ?: 0}%", color = Color(0xFFD7CAFF), fontSize = 10.5.sp)
                    } else {
                        Spacer(Modifier.height(18.dp))
                        if (state !is UpdateUiState.Ready) {
                            Button(
                                onClick = { onUpdate(release) },
                                modifier = Modifier.fillMaxWidth().height(47.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7652D5), contentColor = Color.White),
                            ) {
                                Icon(Icons.Outlined.Download, null, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(if (state is UpdateUiState.Error) "Try again" else "Download & install", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        TextButton(onClick = onDismiss) {
                            Text(if (state is UpdateUiState.Ready) "Close" else "Later", color = UacColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

internal fun openExternalLink(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private const val TELEGRAM_CHANNEL_URL = "https://t.me/UacSniSpoofer"
private const val TELEGRAM_GROUP_URL = "https://t.me/UacSniSpooferGroup"
