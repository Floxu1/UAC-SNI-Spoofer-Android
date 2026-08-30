package com.uacspoofer.mobile.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal val LocalHomeGuideActive = staticCompositionLocalOf { false }

internal data class HomeGuideSession(
    val step: HomeGuideStep,
    val gotIt: FocusRequester,
)

internal val LocalHomeGuideSession = staticCompositionLocalOf<HomeGuideSession?> { null }

internal enum class HomeGuideStep {
    Engine,
    Country,
}

internal object HomeGuide {
    fun step(
        engineSeen: Boolean,
        countrySeen: Boolean,
        torMode: Boolean,
        drawerOpen: Boolean,
    ): HomeGuideStep? {
        if (drawerOpen) return null
        if (torMode && !countrySeen) return HomeGuideStep.Country
        if (!engineSeen) return HomeGuideStep.Engine
        return null
    }
}

internal class HomeGuideStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun engineSeen(): Boolean = prefs.getBoolean(KEY_ENGINE, false)

    fun countrySeen(): Boolean = prefs.getBoolean(KEY_COUNTRY, false)

    fun markEngineSeen() {
        prefs.edit().putBoolean(KEY_ENGINE, true).apply()
    }

    fun markCountrySeen() {
        prefs.edit().putBoolean(KEY_COUNTRY, true).apply()
    }

    companion object {
        private const val PREFS = "home_guide_v1"
        private const val KEY_ENGINE = "engine_seen"
        private const val KEY_COUNTRY = "tor_country_seen"

        @Volatile private var instance: HomeGuideStore? = null

        fun get(context: Context): HomeGuideStore = instance ?: synchronized(this) {
            instance ?: HomeGuideStore(context.applicationContext).also { instance = it }
        }
    }
}

@Composable
internal fun HomeGuideOverlay(
    step: HomeGuideStep,
    target: LayoutCoordinates?,
    gotIt: FocusRequester,
    onDismiss: () -> Unit,
) {
    val isPersian = LocalHomePersian.current
    val localizedFont = homeLocalizedFont()
    val homeFocus = LocalHomeRemoteFocus.current
    var overlayCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val targetRect = remember(step, target, overlayCoords, target?.isAttached, overlayCoords?.size) {
        val source = target
        val overlay = overlayCoords
        if (source == null || overlay == null || !source.isAttached || !overlay.isAttached) {
            null
        } else {
            runCatching {
                val topLeft = overlay.localPositionOf(source, Offset.Zero)
                Rect(topLeft, Size(source.size.width.toFloat(), source.size.height.toFloat()))
            }.getOrNull()
        }
    }
    val targetRequester = when (step) {
        HomeGuideStep.Engine -> homeFocus?.engine
        HomeGuideStep.Country -> homeFocus?.profile
    }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(step, targetRect != null, targetRequester) {
        runCatching { targetRequester?.requestFocus() }
        delay(80)
        runCatching { targetRequester?.requestFocus() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayCoords = it }
            .zIndex(24f),
    ) {
        val hole = targetRect
        if (hole == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.58f))
                    .pointerInput(onDismiss) {
                        detectTapGestures { onDismiss() }
                    },
            )
        } else {
            GuideHoleScrim(hole = hole, onDismiss = onDismiss)
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 2.dp.toPx())
                val color = UacColors.DisconnectedBlue
                if (step == HomeGuideStep.Engine) {
                    drawCircle(
                        color = color,
                        radius = hole.width / 2f,
                        center = hole.center,
                        style = stroke,
                    )
                } else {
                    drawRoundRect(
                        color = color,
                        topLeft = hole.topLeft,
                        size = hole.size,
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = stroke,
                    )
                }
            }
        }
        if (targetRect != null) {
            val density = LocalDensity.current
            val padPx = with(density) { 16.dp.toPx() }
            val gapPx = with(density) { 12.dp.toPx() }
            val cardWidthPx = (constraints.maxWidth - padPx * 2f)
                .coerceAtMost(with(density) { 328.dp.toPx() })
            val preferX = if (step == HomeGuideStep.Engine) {
                targetRect.right - cardWidthPx
            } else {
                targetRect.center.x - cardWidthPx / 2f
            }
            val maxX = (constraints.maxWidth - cardWidthPx - padPx).coerceAtLeast(padPx)
            val cardX = preferX.coerceIn(padPx, maxX)
            val cardY = targetRect.bottom + gapPx
            val title = when (step) {
                HomeGuideStep.Engine -> homeText("Engine", "راهنمای موتور")
                HomeGuideStep.Country -> homeText("Exit country", "انتخاب کشور")
            }
            val body = when (step) {
                HomeGuideStep.Engine -> homeText(
                    "Use this button to put the engine on Tor. Each tap switches the connection engine between Tor and SNI Spoofing.",
                    "از این دکمه میتونی موتور برنامه را روی Tor بگذاری. هر بار که بزنی، موتور اتصال بین Tor و Sni Spoofing تغییر میکنه",
                )
                HomeGuideStep.Country -> homeText(
                    "From this row on Home you can pick the Tor exit country. If you are not sure, Automatic is the best choice.",
                    "از این بخش در خانه می‌توانی کشور خروجی Tor را انتخاب کنی. اگر مطمئن نیستی، خودکار بهترین انتخاب است.",
                )
            }
            val arrowX = (targetRect.center.x - cardX - with(density) { 8.dp.toPx() })
                .coerceIn(with(density) { 16.dp.toPx() }, cardWidthPx - with(density) { 32.dp.toPx() })
            Column(
                modifier = Modifier
                    .offset { IntOffset(cardX.roundToInt(), cardY.roundToInt()) }
                    .width(with(density) { cardWidthPx.toDp() })
                    .widthIn(max = 328.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = with(density) { arrowX.toDp() })
                        .size(width = 16.dp, height = 10.dp)
                        .clip(
                            GenericShape { size, _ ->
                                moveTo(0f, size.height)
                                lineTo(size.width / 2f, 0f)
                                lineTo(size.width, size.height)
                                close()
                            },
                        )
                        .background(Color(0xFF0E2236)),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(12.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(0.4f))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0E2236))
                        .border(1.dp, UacColors.DisconnectedBlue.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .focusProperties { canFocus = false }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = localizedFont,
                        textAlign = if (isPersian) TextAlign.Right else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = body,
                        color = UacColors.TextSecondary,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        fontFamily = localizedFont,
                        textAlign = if (isPersian) TextAlign.Right else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .align(if (isPersian) Alignment.Start else Alignment.End)
                            .clip(RoundedCornerShape(11.dp))
                            .background(UacColors.DisconnectedBlue)
                            .focusRequester(gotIt)
                            .keyboardFocusRing()
                            .then(
                                if (targetRequester != null) {
                                    Modifier.dpadMovesFocus(up = targetRequester)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable(role = Role.Button, onClick = onDismiss)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = homeText("Got it", "متوجه شدم"),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = localizedFont,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideHoleScrim(
    hole: Rect,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val scrim = Color.Black.copy(alpha = 0.58f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = with(density) { hole.top.toDp().coerceAtLeast(0.dp) }
        val left = with(density) { hole.left.toDp().coerceAtLeast(0.dp) }
        val holeH = with(density) { hole.height.toDp().coerceAtLeast(0.dp) }
        val holeW = with(density) { hole.width.toDp().coerceAtLeast(0.dp) }
        val bottomH = (maxHeight - top - holeH).coerceAtLeast(0.dp)
        val rightW = (maxWidth - left - holeW).coerceAtLeast(0.dp)
        if (top > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(top)
                    .background(scrim)
                    .pointerInput(onDismiss) {
                        detectTapGestures { onDismiss() }
                    },
            )
        }
        if (bottomH > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(y = top + holeH)
                    .fillMaxWidth()
                    .height(bottomH)
                    .background(scrim)
                    .pointerInput(onDismiss) {
                        detectTapGestures { onDismiss() }
                    },
            )
        }
        if (left > 0.dp && holeH > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(y = top)
                    .width(left)
                    .height(holeH)
                    .background(scrim)
                    .pointerInput(onDismiss) {
                        detectTapGestures { onDismiss() }
                    },
            )
        }
        if (rightW > 0.dp && holeH > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(x = left + holeW, y = top)
                    .width(rightW)
                    .height(holeH)
                    .background(scrim)
                    .pointerInput(onDismiss) {
                        detectTapGestures { onDismiss() }
                    },
            )
        }
    }
}
