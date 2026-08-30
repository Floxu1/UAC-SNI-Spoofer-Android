package com.uacspoofer.mobile.ui

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.IconButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.uacspoofer.mobile.ui.theme.UacColors
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal val KeyboardNavigationState = mutableStateOf(false)

internal val LocalHomeRemoteFocus = staticCompositionLocalOf<HomeRemoteFocus?> { null }

internal val LocalDrawerOpen = staticCompositionLocalOf { false }

internal object FocusHaloMotion {
    var sweepDegrees by mutableFloatStateOf(0f)
    var pulse by mutableFloatStateOf(0.55f)
}

@Composable
internal fun rememberRemoteRowFocus(): Pair<MutableInteractionSource, Boolean> {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = KeyboardNavigationState.value
    return interaction to HomeRemoteNavigation.shouldDrawFocusRing(focused, keyboard)
}

internal class HomeRemoteFocus {
    val menu = FocusRequester()
    val engine = FocusRequester()
    val connect = FocusRequester()
    val profile = FocusRequester()
    val ping = FocusRequester()
    val country = FocusRequester()
    val log = FocusRequester()
    var slot: HomeRemoteSlot = HomeRemoteSlot.None
        private set

    fun mark(slot: HomeRemoteSlot) {
        this.slot = slot
    }

    fun clearIf(slot: HomeRemoteSlot) {
        if (this.slot == slot) this.slot = HomeRemoteSlot.None
    }

    fun requester(slot: HomeRemoteSlot): FocusRequester? = when (slot) {
        HomeRemoteSlot.Menu -> menu
        HomeRemoteSlot.Engine -> engine
        HomeRemoteSlot.Connect -> connect
        HomeRemoteSlot.Profile -> profile
        HomeRemoteSlot.Ping -> ping
        HomeRemoteSlot.Country -> country
        HomeRemoteSlot.Log -> log
        HomeRemoteSlot.None,
        HomeRemoteSlot.Other -> null
    }

    var suppressConfirmUp: Boolean = false
}

@Composable
internal fun TvFocusProvider(content: @Composable () -> Unit) {
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    SideEffect { KeyboardNavigationState.value = keyboard }
    LaunchedEffect(Unit) {
        snapshotFlow { KeyboardNavigationState.value }.collectLatest { keyboardMode ->
            if (!keyboardMode) return@collectLatest
            while (true) {
                withFrameNanos { nanos ->
                    val seconds = nanos / 1_000_000_000f
                    FocusHaloMotion.sweepDegrees = (seconds * 140f) % 360f
                    FocusHaloMotion.pulse = (sin(seconds * 2.2f).toFloat() * 0.5f + 0.5f)
                }
            }
        }
    }
    val rippleIndication = ripple()
    val indication = remember(rippleIndication) { RippleAndKeyboardFocusIndication(rippleIndication) }
    CompositionLocalProvider(LocalIndication provides indication, content = content)
}

@Composable
internal fun RemoteIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.keyboardFocusRing(),
        enabled = enabled,
        content = content,
    )
}

@Composable
internal fun Modifier.keyboardFocusRing(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val keyboard = KeyboardNavigationState.value
    val active = HomeRemoteNavigation.shouldDrawFocusRing(focused, keyboard)
    val sweep = if (active) FocusHaloMotion.sweepDegrees else 0f
    val pulse = if (active) FocusHaloMotion.pulse else 0.55f
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (active) {
                drawKeyboardFocusHalo(sweepDegrees = sweep, pulse = pulse)
            }
        }
}

@Composable
internal fun Modifier.openDrawerOnDpadLeft(onOpen: () -> Unit): Modifier {
    val drawerOpen = LocalDrawerOpen.current
    return onPreviewKeyEvent { event ->
        if (!RemoteKeys.isLeftDown(event)) return@onPreviewKeyEvent false
        if (!drawerOpen) onOpen()
        true
    }
}

internal fun Modifier.keepFocusInDrawerHorizontally(consumeUp: Boolean = false): Modifier =
    onPreviewKeyEvent { event ->
        val dpad = RemoteKeys.toDpad(event) ?: return@onPreviewKeyEvent false
        when {
            DrawerRemoteNavigation.consumesHorizontal(dpad) -> true
            consumeUp && dpad == RemoteDpad.Up -> true
            else -> false
        }
    }

internal fun Modifier.drawerLanguageDpad(
    left: FocusRequester? = null,
    right: FocusRequester? = null,
): Modifier =
    onPreviewKeyEvent { event ->
        when (RemoteKeys.toDpad(event)) {
            RemoteDpad.Left -> {
                left?.let { runCatching { it.requestFocus() } }
                true
            }
            RemoteDpad.Right -> {
                right?.let { runCatching { it.requestFocus() } }
                true
            }
            else -> false
        }
    }

internal fun Modifier.dpadDownMovesFocus(to: FocusRequester, onMoved: () -> Unit = {}): Modifier =
    dpadMovesFocus(down = to, onMoved = onMoved)

internal fun Modifier.dpadMovesFocus(
    down: FocusRequester? = null,
    up: FocusRequester? = null,
    onMoved: () -> Unit = {},
): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val target = when (event.key) {
            Key.DirectionDown -> down
            Key.DirectionUp -> up
            else -> null
        } ?: return@onPreviewKeyEvent false
        if (runCatching { target.requestFocus() }.isFailure) return@onPreviewKeyEvent false
        onMoved()
        true
    }

@Composable
internal fun Modifier.trackHomeSlot(slot: HomeRemoteSlot): Modifier {
    val focus = LocalHomeRemoteFocus.current ?: return this
    val requester = focus.requester(slot) ?: return this
    return this
        .focusRequester(requester)
        .onFocusChanged { change ->
            if (change.isFocused) focus.mark(slot) else focus.clearIf(slot)
        }
}

@Composable
internal fun Modifier.trackHomeOther(): Modifier {
    val focus = LocalHomeRemoteFocus.current ?: return this
    return this.onFocusChanged { change ->
        if (change.isFocused) focus.mark(HomeRemoteSlot.Other) else focus.clearIf(HomeRemoteSlot.Other)
    }
}

internal fun Modifier.guideTargetDpad(gotIt: FocusRequester): Modifier =
    onPreviewKeyEvent { event ->
        when (RemoteKeys.toDpad(event)) {
            RemoteDpad.Down -> {
                if (runCatching { gotIt.requestFocus() }.isFailure) return@onPreviewKeyEvent false
                true
            }
            RemoteDpad.Left,
            RemoteDpad.Right,
            RemoteDpad.Up -> true
            else -> false
        }
    }

@Composable
internal fun Modifier.homeRemoteDpad(onOpenDrawer: () -> Unit): Modifier {
    val focus = LocalHomeRemoteFocus.current ?: return this
    val drawerOpen = LocalDrawerOpen.current
    val guideActive = LocalHomeGuideActive.current
    return this.onPreviewKeyEvent { event ->
        if (drawerOpen) return@onPreviewKeyEvent false
        if (RemoteKeys.isConfirm(event)) {
            if (guideActive) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyUp) {
                if (!focus.suppressConfirmUp) return@onPreviewKeyEvent false
                focus.suppressConfirmUp = false
                return@onPreviewKeyEvent true
            }
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (!HomeRemoteNavigation.shouldParkConfirmOnConnect(focus.slot, KeyboardNavigationState.value)) {
                return@onPreviewKeyEvent false
            }
            focus.suppressConfirmUp = true
            KeyboardNavigationState.value = true
            if (runCatching { focus.connect.requestFocus() }.isFailure) {
                focus.suppressConfirmUp = false
                return@onPreviewKeyEvent false
            }
            return@onPreviewKeyEvent true
        }
        if (guideActive) return@onPreviewKeyEvent false
        val dpad = RemoteKeys.toDpad(event) ?: return@onPreviewKeyEvent false
        when (val action = HomeRemoteNavigation.action(focus.slot, dpad)) {
            HomeRemoteAction.OpenDrawer -> {
                onOpenDrawer()
                true
            }
            is HomeRemoteAction.Focus -> {
                val requester = focus.requester(action.slot) ?: return@onPreviewKeyEvent false
                if (runCatching { requester.requestFocus() }.isFailure) return@onPreviewKeyEvent false
                true
            }
            HomeRemoteAction.Ignore -> false
        }
    }
}

internal object RemoteKeys {
    fun isLeftDown(event: KeyEvent): Boolean =
        event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft

    fun isDownDown(event: KeyEvent): Boolean =
        event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown

    fun isConfirm(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) return false
        return event.key == Key.DirectionCenter ||
            event.key == Key.Enter ||
            event.key == Key.NumPadEnter
    }

    fun toDpad(event: KeyEvent): RemoteDpad? {
        if (event.type != KeyEventType.KeyDown) return null
        return when (event.key) {
            Key.DirectionLeft -> RemoteDpad.Left
            Key.DirectionRight -> RemoteDpad.Right
            Key.DirectionUp -> RemoteDpad.Up
            Key.DirectionDown -> RemoteDpad.Down
            else -> null
        }
    }
}

private class RippleAndKeyboardFocusIndication(
    private val ripple: IndicationNodeFactory,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : DelegatingNode() {
            init {
                delegate(ripple.create(interactionSource))
                delegate(KeyboardFocusRingNode(interactionSource))
            }
        }

    override fun equals(other: Any?): Boolean =
        other is RippleAndKeyboardFocusIndication && other.ripple == ripple

    override fun hashCode(): Int = ripple.hashCode()
}

private class KeyboardFocusRingNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private var focused = false
    private var keyboardMode = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                }
                invalidateDraw()
            }
        }
        coroutineScope.launch {
            snapshotFlow { KeyboardNavigationState.value }.collect { keyboard ->
                keyboardMode = keyboard
                invalidateDraw()
            }
        }
        coroutineScope.launch {
            snapshotFlow { FocusHaloMotion.sweepDegrees }.collect {
                if (HomeRemoteNavigation.shouldDrawFocusRing(focused, keyboardMode)) {
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (HomeRemoteNavigation.shouldDrawFocusRing(focused, keyboardMode)) {
            drawKeyboardFocusHalo(
                sweepDegrees = FocusHaloMotion.sweepDegrees,
                pulse = FocusHaloMotion.pulse,
            )
        }
    }
}

internal fun DrawScope.drawKeyboardFocusHalo(
    sweepDegrees: Float = 0f,
    pulse: Float = 0.55f,
) {
    val inset = 2.dp.toPx()
    val accent = UacColors.DisconnectedBlue
    val shortest = size.minDimension
    val longest = size.maxDimension
    val nearlySquare = longest <= shortest * 1.18f
    val corner = if (nearlySquare) {
        (shortest / 2f - inset).coerceAtLeast(8.dp.toPx())
    } else {
        (12.dp.toPx() - inset).coerceAtLeast(6.dp.toPx())
    }
    val topLeft = Offset(inset, inset)
    val rectSize = Size(
        (size.width - inset * 2).coerceAtLeast(0f),
        (size.height - inset * 2).coerceAtLeast(0f),
    )
    val radius = CornerRadius(corner)
    drawRoundRect(
        color = accent.copy(alpha = 0.035f + pulse * 0.03f),
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = radius,
    )
    drawRoundRect(
        color = accent.copy(alpha = 0.08f + pulse * 0.10f),
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = radius,
        style = Stroke(width = 4.5.dp.toPx()),
    )
    drawRoundRect(
        color = accent.copy(alpha = 0.42f),
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = radius,
        style = Stroke(width = 1.2.dp.toPx()),
    )
    val cx = size.width / 2f
    val cy = size.height / 2f
    val shader = SweepGradient(
        cx,
        cy,
        intArrayOf(
            Color.Transparent.toArgb(),
            accent.copy(alpha = 0.10f).toArgb(),
            Color.White.copy(alpha = 0.72f).toArgb(),
            accent.copy(alpha = 0.95f).toArgb(),
            Color.Transparent.toArgb(),
            Color.Transparent.toArgb(),
        ),
        floatArrayOf(0.00f, 0.12f, 0.20f, 0.28f, 0.40f, 1.00f),
    ).also { sweep ->
        val matrix = Matrix()
        matrix.setRotate(sweepDegrees, cx, cy)
        sweep.setLocalMatrix(matrix)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.15.dp.toPx()
        strokeCap = Paint.Cap.ROUND
        this.shader = shader
    }
    val frame = RectF(
        topLeft.x,
        topLeft.y,
        topLeft.x + rectSize.width,
        topLeft.y + rectSize.height,
    )
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(frame, corner, corner, paint)
    }
}
