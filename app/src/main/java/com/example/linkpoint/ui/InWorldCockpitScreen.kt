package com.example.linkpoint.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.DockPosition
import com.example.linkpoint.designsystem.DockToolId
import com.example.linkpoint.designsystem.JoystickSide
import com.example.linkpoint.designsystem.LinkpointPanelSurface
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.designsystem.TelemetryPosition
import com.example.linkpoint.protocol.LayerHealthStatus
import com.example.linkpoint.render.LinkpointGlRenderer
import com.example.linkpoint.scene.AvatarHudSlotState
import com.example.linkpoint.scene.CameraMode
import com.example.linkpoint.scene.HudAttachmentPoint
import com.example.linkpoint.scene.ProjectedNametag
import com.example.linkpoint.store.ConnectionState
import com.example.linkpoint.store.ViewerState
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun InWorldCockpitScreen(
    state: ViewerState,
    renderer: LinkpointGlRenderer,
    client: ViewerClient
) {
    val tokens = LocalLinkpointDesign.current
    var zenMode by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            zenMode -> zenMode = false
            state.selectedAvatarId != null -> client.onAvatarSelected(null)
            state.activeFloater != null -> client.toggleFloater(null)
            else -> client.disconnect()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 680.dp
        val viewportW = maxWidth
        val viewportH = maxHeight

        LaunchedEffect(isWideScreen) {
            client.onViewportMetricsChanged(isWideScreen)
        }

        // 1. Full-Bleed Native OpenGL ES 3.0 Dual-Pass Viewport (World + Pass-2 Avatar HUDs 31..38)
        NativeGlViewport(
            renderer = renderer,
            hudTouchCaptureEnabled = state.design.hudTouchCaptureEnabled,
            onHudHit = client::onHudAttachmentTapped,
            onAvatarSelected = client::onAvatarSelected,
            onWorldSelected = client::onWorldObjectSelected,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Floating 3D-Projected Avatar Nametags + OpenGL Avatar HUD Labels (hidden in Zen 3D mode)
        if (!zenMode) {
            FloatingAvatarNametagsOverlay(
                nametags = state.projectedNametags,
                selectedAvatarId = state.selectedAvatarId,
                viewportW = viewportW.value,
                viewportH = viewportH.value,
                onSelectAvatar = client::onAvatarSelected
            )
            AvatarHudLabelsOverlay(
                state = state,
                viewportW = viewportW.value,
                viewportH = viewportH.value
            )
        }

        // 3. Minimal, Unobtrusive Floating HUD Chrome (85%+ Clear 3D Viewport, Respects WindowInsets.safeDrawing)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(6.dp)
        ) {
            var showConnectionDialog by remember { mutableStateOf(false) }

            if (zenMode) {
                // Pure 100% 3D Zen Mode: single tiny translucent restore eye button in top-end corner
                val pillShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(20.dp))
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .alpha(0.55f)
                        .minimumInteractiveComponentSize()
                        .clip(pillShape)
                        .background(tokens.surfaceChrome.copy(alpha = 0.72f))
                        .border(1.dp, tokens.borderHairline, pillShape)
                        .clickable { zenMode = false }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("zen_mode_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Show HUD controls",
                        tint = tokens.accentPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                // Top-Start Slim Telemetry Pill (or Bottom-Start if user explicitly picks TelemetryPosition.BOTTOM)
                val telemetryAlign = when (state.design.telemetryPosition) {
                    TelemetryPosition.BOTTOM -> Alignment.BottomStart
                    TelemetryPosition.TOP, TelemetryPosition.COMPACT -> Alignment.TopStart
                }
                val telemetryBottomPad = if (state.design.telemetryPosition == TelemetryPosition.BOTTOM &&
                    state.design.dockPosition == DockPosition.BOTTOM
                ) 56.dp else 0.dp

                CompactTelemetryPill(
                    state = state,
                    isWideScreen = isWideScreen,
                    onTapTelemetry = {
                        if (state.connection is ConnectionState.Connected) showConnectionDialog = true
                        else client.toggleFloater(DockToolId.STUDIO)
                    },
                    modifier = Modifier
                        .align(telemetryAlign)
                        .padding(
                            end = if (state.design.telemetryPosition != TelemetryPosition.BOTTOM) 104.dp else 0.dp,
                            bottom = telemetryBottomPad
                        )
                )

                // Top-End Mini System Pill (Zen 3D Toggle + Disconnect)
                TopRightMiniControls(
                    zenMode = zenMode,
                    onToggleZen = {
                        client.toggleFloater(null)
                        zenMode = true
                    },
                    onDisconnect = client::disconnect,
                    modifier = Modifier.align(Alignment.TopEnd)
                )

                // Live Transport Recovery Banner (only when Live Grid LLUDP/CAPS is degraded/retrying/failed)
                val isLiveConnected = state.connection is ConnectionState.Connected
                val transportUnhealthy = isLiveConnected && (
                    state.connectionHealth.lludpStatus in setOf(
                        LayerHealthStatus.DEGRADED,
                        LayerHealthStatus.FAILED,
                        LayerHealthStatus.RETRYING
                    ) || state.connectionHealth.capsStatus == LayerHealthStatus.FAILED
                )
                if (transportUnhealthy) {
                    val bannerShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 46.dp)
                            .widthIn(max = 360.dp)
                            .clip(bannerShape)
                            .background(tokens.surfaceFloater.copy(alpha = 0.88f))
                            .border(1.dp, tokens.statusWarning, bannerShape)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = tokens.statusWarning, modifier = Modifier.size(16.dp))
                        Text(
                            text = state.connectionHealth.lludpDetail.ifBlank { state.connectionHealth.capsDetail },
                            style = MaterialTheme.typography.labelSmall.copy(color = tokens.textPrimary, fontSize = 10.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        LinkpointStatusChip(
                            label = "Retry",
                            color = tokens.accentPrimary,
                            onClick = client::retryTransportLayers,
                            modifier = Modifier.testTag("inworld_retry_banner_button")
                        )
                    }
                }

                // Minimized Avatar Attachment HUD Restore Pills
                val minimizedHuds = state.sceneSnapshot.hudAttachments.filter { hud ->
                    val ov = state.hudSlotStates[hud.attachmentPoint]
                    ov?.minimized == true && ov.visible
                }
                if (minimizedHuds.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = if (transportUnhealthy) 82.dp else 44.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        minimizedHuds.forEach { hud ->
                            val short = HudAttachmentPoint.fromSlotId(hud.attachmentPoint)?.shortCode ?: "HUD"
                            LinkpointStatusChip(
                                label = "+ $short: ${hud.name.take(10)}",
                                color = tokens.accentTertiary,
                                onClick = { client.toggleHudSlotMinimized(hud.attachmentPoint) }
                            )
                        }
                    }
                }

                // Sleek Slim Floating Tool Bar (Bottom Icon Strip by default; slim 48dp edge rail if Left/Right)
                val dockAlign = when (state.design.dockPosition) {
                    DockPosition.RIGHT -> Alignment.CenterEnd
                    DockPosition.LEFT -> Alignment.CenterStart
                    DockPosition.BOTTOM -> Alignment.BottomCenter
                }
                SlimActionDock(
                    state = state,
                    client = client,
                    isWideScreen = isWideScreen,
                    modifier = Modifier.align(dockAlign)
                )

                // Compact Translucent Thumb Joystick + Mini Flight/Camera Stack (zero overlap with dock or floaters)
                if (state.design.joystickSide != JoystickSide.HIDDEN && ((state.activeFloater == null && state.selectedAvatarId == null) || isWideScreen)) {
                    val joyAlign = if (state.design.joystickSide == JoystickSide.LEFT) Alignment.BottomStart else Alignment.BottomEnd
                    val extraBottom = when {
                        state.design.dockPosition == DockPosition.BOTTOM -> 58.dp
                        state.design.telemetryPosition == TelemetryPosition.BOTTOM -> 48.dp
                        else -> 6.dp
                    }
                    val extraSide = if ((state.design.joystickSide == JoystickSide.LEFT && state.design.dockPosition == DockPosition.LEFT) ||
                        (state.design.joystickSide == JoystickSide.RIGHT && state.design.dockPosition == DockPosition.RIGHT)
                    ) 56.dp else 2.dp

                    CompactMovementPad(
                        state = state,
                        client = client,
                        isRightSide = state.design.joystickSide == JoystickSide.RIGHT,
                        modifier = Modifier
                            .align(joyAlign)
                            .padding(
                                bottom = extraBottom,
                                start = if (state.design.joystickSide == JoystickSide.LEFT) extraSide else 0.dp,
                                end = if (state.design.joystickSide == JoystickSide.RIGHT) extraSide else 0.dp
                            )
                    )
                }

                // Ultra-Slim Single-Line Live Chat Whisper Caption (only when floater & profile closed and screen has room)
                val latestChat = state.localChat.lastOrNull()
                if (state.activeFloater == null && state.selectedAvatarId == null && latestChat != null && viewportW >= 340.dp) {
                    val toastAlign = if (state.design.joystickSide == JoystickSide.LEFT) Alignment.BottomEnd else Alignment.BottomStart
                    val bottomPad = if (state.design.dockPosition == DockPosition.BOTTOM) 58.dp else 8.dp
                    val sidePad = if ((toastAlign == Alignment.BottomEnd && state.design.dockPosition == DockPosition.RIGHT) ||
                        (toastAlign == Alignment.BottomStart && state.design.dockPosition == DockPosition.LEFT)
                    ) 56.dp else 4.dp
                    val chipShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(14.dp))
                    Box(
                        modifier = Modifier
                            .align(toastAlign)
                            .padding(bottom = bottomPad, start = sidePad, end = sidePad)
                            .widthIn(max = if (isWideScreen) 280.dp else 178.dp)
                            .clip(chipShape)
                            .background(tokens.surfaceChrome.copy(alpha = 0.68f))
                            .border(1.dp, tokens.borderHairline.copy(alpha = 0.7f), chipShape)
                            .clickable { client.toggleFloater(DockToolId.CHAT) }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "${latestChat.speaker}: ${latestChat.text}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = tokens.textPrimary,
                                fontSize = 10.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Adaptive Floater: Strictly Height-Capped Half-Sheet on Phones so Upper 50%+ of 3D Scene Stays Live!
                AnimatedVisibility(
                    visible = state.activeFloater != null,
                    enter = fadeIn() + slideInVertically { it / 4 },
                    exit = fadeOut() + slideOutVertically { it / 4 },
                    modifier = Modifier.align(
                        if (isWideScreen) {
                            if (state.design.dockPosition == DockPosition.LEFT) Alignment.CenterStart else Alignment.CenterEnd
                        } else {
                            Alignment.BottomCenter
                        }
                    )
                ) {
                    val active = state.activeFloater
                    if (active != null) {
                        val floaterPadding = if (isWideScreen) {
                            PaddingValues(
                                top = 46.dp,
                                bottom = 10.dp,
                                start = if (state.design.dockPosition == DockPosition.LEFT) 58.dp else 8.dp,
                                end = if (state.design.dockPosition == DockPosition.RIGHT) 58.dp else 8.dp
                            )
                        } else {
                            PaddingValues(
                                bottom = if (state.design.dockPosition == DockPosition.BOTTOM) 56.dp else 4.dp
                            )
                        }
                        val maxPhoneFloaterHeight = (viewportH * 0.46f).coerceAtLeast(220.dp)

                        LinkpointPanelSurface(
                            modifier = Modifier
                                .padding(floaterPadding)
                                .then(
                                    if (isWideScreen) {
                                        Modifier
                                            .widthIn(min = 300.dp, max = 370.dp)
                                            .heightIn(max = (viewportH * 0.80f).coerceAtLeast(300.dp))
                                    } else {
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = maxPhoneFloaterHeight)
                                    }
                                ),
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            when (active) {
                                DockToolId.CHAT -> ChatFloaterContent(state, client) { client.toggleFloater(null) }
                                DockToolId.RADAR -> RadarFloaterContent(state, client) { client.toggleFloater(null) }
                                DockToolId.MAP -> MapFloaterContent(state, client) { client.toggleFloater(null) }
                                DockToolId.INVENTORY -> InventoryFloaterContent(state, client) { client.toggleFloater(null) }
                                DockToolId.INSPECTOR -> ObjectInspectorContent(state, client) { client.toggleFloater(null) }
                                DockToolId.HUDS -> AvatarHudManagerContent(state, client) { client.toggleFloater(null) }
                                DockToolId.STUDIO -> LayoutDesignStudioContent(state, client) { client.toggleFloater(null) }
                            }
                        }
                    }
                }

                // Avatar Profile & Appearance Sheet (when tapping a 3D avatar, floating nametag, or Radar resident)
                val selectedAvatar = state.selectedAvatarId?.let { id ->
                    state.sceneSnapshot.avatars.firstOrNull { it.agentId == id }
                }
                AnimatedVisibility(
                    visible = selectedAvatar != null,
                    enter = fadeIn() + slideInVertically { it / 4 },
                    exit = fadeOut() + slideOutVertically { it / 4 },
                    modifier = Modifier.align(
                        if (isWideScreen) Alignment.CenterStart else Alignment.BottomCenter
                    )
                ) {
                    if (selectedAvatar != null) {
                        val sheetPad = if (isWideScreen) {
                            PaddingValues(top = 48.dp, bottom = 10.dp, start = 10.dp, end = 8.dp)
                        } else {
                            PaddingValues(bottom = if (state.design.dockPosition == DockPosition.BOTTOM) 56.dp else 4.dp)
                        }
                        LinkpointPanelSurface(
                            modifier = Modifier
                                .padding(sheetPad)
                                .then(
                                    if (isWideScreen) {
                                        Modifier
                                            .widthIn(min = 300.dp, max = 360.dp)
                                            .heightIn(max = (viewportH * 0.78f).coerceAtLeast(280.dp))
                                    } else {
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = (viewportH * 0.46f).coerceAtLeast(240.dp))
                                    }
                                ),
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            AvatarProfileSheetContent(
                                avatar = selectedAvatar,
                                state = state,
                                client = client,
                                onClose = { client.onAvatarSelected(null) }
                            )
                        }
                    }
                }

                // Active Hardware Keyboard Movement Feedback Pill (subtle, non-blocking)
                AnimatedVisibility(
                    visible = state.activeKeyboardHint != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (state.toastMessage != null) 78.dp else 44.dp)
                ) {
                    state.activeKeyboardHint?.let { keys ->
                        LinkpointStatusChip(
                            label = "KEY: $keys",
                            color = tokens.accentSecondary,
                            modifier = Modifier.testTag("keyboard_active_hint")
                        )
                    }
                }

                // Transient System Feedback Toast Pill (tucked cleanly under top bar)
                AnimatedVisibility(
                    visible = state.toastMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 44.dp)
                ) {
                    state.toastMessage?.let { msg ->
                        LinkpointStatusChip(
                            label = msg,
                            color = tokens.accentPrimary,
                            onClick = client::dismissToast
                        )
                    }
                }

                if (showConnectionDialog) {
                    ConnectionHealthDialog(
                        state = state,
                        onDismiss = { showConnectionDialog = false },
                        onRetryTransport = {
                            showConnectionDialog = false
                            client.retryTransportLayers()
                        },
                        onFullRelogin = {
                            showConnectionDialog = false
                            client.retryLastConnection()
                        }
                    )
                }
            }
        }
    }

    // LSL ScriptDialog Prompt Modal (from EventQueueGet CAPS)
    state.scriptDialog?.let { dlg ->
        val dlgTokens = LocalLinkpointDesign.current
        AlertDialog(
            onDismissRequest = client::dismissScriptDialog,
            containerColor = dlgTokens.surfaceFloater,
            title = { Text(dlg.objectName, color = dlgTokens.textPrimary) },
            text = { Text(dlg.message, color = dlgTokens.textSecondary) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dlg.buttons.take(3).forEach { label ->
                        TextButton(onClick = { client.respondScriptDialog(label, dlg.channel) }) {
                            Text(label, color = dlgTokens.accentPrimary)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = client::dismissScriptDialog) {
                    Text("Ignore", color = dlgTokens.textMuted)
                }
            }
        )
    }
}

@Composable
private fun NativeGlViewport(
    renderer: LinkpointGlRenderer,
    hudTouchCaptureEnabled: Boolean,
    onHudHit: (com.example.linkpoint.scene.HudHitResult) -> Unit,
    onAvatarSelected: (UUID) -> Unit,
    onWorldSelected: (com.example.linkpoint.scene.SceneObject?) -> Unit,
    modifier: Modifier = Modifier
) {
    var glViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }

    DisposableEffect(Unit) {
        onDispose { glViewRef?.onPause() }
    }

    AndroidView(
        modifier = modifier.testTag("gl_3d_viewport"),
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                var activePointerId = MotionEvent.INVALID_POINTER_ID
                var lastX = 0f
                var lastY = 0f
                var lastMoveTimeMs = 0L
                var movedDist = 0f
                var wasMultiTouch = false
                var lastMidX = 0f
                var lastMidY = 0f
                var hasTwoFingerMid = false
                var lastEmptyTapTimeMs = 0L
                var lastEmptyTapX = 0f
                var lastEmptyTapY = 0f

                val scaleDetector = ScaleGestureDetector(
                    ctx,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            renderer.applyZoomScale(detector.scaleFactor)
                            return true
                        }
                    }
                )

                setOnTouchListener { v, event ->
                    scaleDetector.onTouchEvent(event)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            activePointerId = event.getPointerId(0)
                            lastX = event.x
                            lastY = event.y
                            lastMoveTimeMs = event.eventTime
                            movedDist = 0f
                            wasMultiTouch = false
                            hasTwoFingerMid = false
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            wasMultiTouch = true
                            renderer.releaseOrbitDrag()
                            if (event.pointerCount >= 2) {
                                lastMidX = (event.getX(0) + event.getX(1)) * 0.5f
                                lastMidY = (event.getY(0) + event.getY(1)) * 0.5f
                                hasTwoFingerMid = true
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (event.pointerCount >= 2) {
                                wasMultiTouch = true
                                val midX = (event.getX(0) + event.getX(1)) * 0.5f
                                val midY = (event.getY(0) + event.getY(1)) * 0.5f
                                if (hasTwoFingerMid && v.width > 0 && v.height > 0) {
                                    val dx = midX - lastMidX
                                    val dy = midY - lastMidY
                                    movedDist += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                                    renderer.applyTwoFingerPan(
                                        dxNorm = dx / v.width.toFloat(),
                                        dyNorm = dy / v.height.toFloat()
                                    )
                                }
                                lastMidX = midX
                                lastMidY = midY
                                hasTwoFingerMid = true
                            } else if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                                val idx = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                                activePointerId = event.getPointerId(idx)
                                val curX = event.getX(idx)
                                val curY = event.getY(idx)
                                val dx = curX - lastX
                                val dy = curY - lastY
                                movedDist += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                                val dtSec = ((event.eventTime - lastMoveTimeMs).coerceAtLeast(4L)) / 1000f
                                val minDim = minOf(v.width, v.height).coerceAtLeast(320).toFloat()
                                val yawSensitivity = 3.2f / minDim
                                val pitchSensitivity = 2.6f / minDim
                                renderer.applyOrbitDrag(
                                    deltaYawRad = -dx * yawSensitivity,
                                    deltaPitchRad = dy * pitchSensitivity,
                                    dtSec = dtSec
                                )
                                lastX = curX
                                lastY = curY
                                lastMoveTimeMs = event.eventTime
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            val liftedIdx = event.actionIndex
                            val liftedId = event.getPointerId(liftedIdx)
                            hasTwoFingerMid = false
                            // Smoothly hand off active pointer to remaining finger so camera never jumps
                            if (liftedId == activePointerId) {
                                val newIdx = if (liftedIdx == 0) 1 else 0
                                if (newIdx < event.pointerCount) {
                                    activePointerId = event.getPointerId(newIdx)
                                    lastX = event.getX(newIdx)
                                    lastY = event.getY(newIdx)
                                    lastMoveTimeMs = event.eventTime
                                }
                            } else {
                                val stayIdx = event.findPointerIndex(activePointerId)
                                if (stayIdx >= 0) {
                                    lastX = event.getX(stayIdx)
                                    lastY = event.getY(stayIdx)
                                    lastMoveTimeMs = event.eventTime
                                }
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            renderer.releaseOrbitDrag()
                            if (!wasMultiTouch && movedDist < 22f && v.width > 0 && v.height > 0) {
                                val nx = event.x / v.width.toFloat()
                                val ny = event.y / v.height.toFloat()
                                val captureHud = (v.tag as? Boolean) ?: true
                                val hudHit = if (captureHud) renderer.pickHudAttachment(nx, ny) else null
                                if (hudHit != null) {
                                    lastEmptyTapTimeMs = 0L
                                    onHudHit(hudHit)
                                } else {
                                    val avatarHit = renderer.pickAvatar(nx, ny)
                                    if (avatarHit != null) {
                                        lastEmptyTapTimeMs = 0L
                                        onAvatarSelected(avatarHit.agentId)
                                    } else {
                                        val worldObj = renderer.pickWorldObject(nx, ny)
                                        if (worldObj != null) {
                                            lastEmptyTapTimeMs = 0L
                                            onWorldSelected(worldObj)
                                        } else {
                                            val nowTap = event.eventTime
                                            val tapDist = hypot(
                                                (event.x - lastEmptyTapX).toDouble(),
                                                (event.y - lastEmptyTapY).toDouble()
                                            ).toFloat()
                                            if (nowTap - lastEmptyTapTimeMs in 40L..320L && tapDist < 72f) {
                                                renderer.resetOrbitView()
                                                lastEmptyTapTimeMs = 0L
                                            } else {
                                                lastEmptyTapTimeMs = nowTap
                                                lastEmptyTapX = event.x
                                                lastEmptyTapY = event.y
                                            }
                                        }
                                    }
                                }
                            }
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            hasTwoFingerMid = false
                            v.performClick()
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            renderer.releaseOrbitDrag()
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            hasTwoFingerMid = false
                        }
                    }
                    true
                }
                glViewRef = this
            }
        },
        update = { view ->
            view.tag = hudTouchCaptureEnabled
        }
    )
}

/**
 * Floating 3D-projected avatar nametag pills anchored above each visible resident's head.
 * Tapping any nametag opens the Avatar Profile & Appearance sheet.
 */
@Composable
private fun FloatingAvatarNametagsOverlay(
    nametags: List<ProjectedNametag>,
    selectedAvatarId: UUID?,
    viewportW: Float,
    viewportH: Float,
    onSelectAvatar: (UUID) -> Unit
) {
    if (nametags.isEmpty()) return
    val tokens = LocalLinkpointDesign.current
    val pillShape = RoundedCornerShape(
        when (tokens.language) {
            DesignLanguage.LCARS -> 99.dp
            DesignLanguage.METRO -> 0.dp
            DesignLanguage.TERMINAL -> 2.dp
            DesignLanguage.POLISHED -> 12.dp
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        nametags.forEach { tag ->
            val isSelected = selectedAvatarId == tag.agentId
            val approxW = (tag.displayName.length * 6.5f + 42f).coerceIn(76f, 178f)
            val xDp = (tag.normX * viewportW - approxW * 0.5f).coerceIn(8f, (viewportW - approxW - 8f).coerceAtLeast(8f))
            val yDp = (tag.normY * viewportH - 18f).coerceIn(48f, (viewportH - 84f).coerceAtLeast(48f))

            Row(
                modifier = Modifier
                    .offset(x = xDp.dp, y = yDp.dp)
                    .widthIn(max = 180.dp)
                    .clip(pillShape)
                    .background(
                        if (isSelected) tokens.accentPrimary.copy(alpha = 0.88f)
                        else tokens.surfaceChrome.copy(alpha = 0.70f)
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) tokens.onAccent else if (tag.isSelf) tokens.accentPrimary.copy(alpha = 0.7f) else tokens.borderHairline,
                        shape = pillShape
                    )
                    .clickable { onSelectAvatar(tag.agentId) }
                    .padding(horizontal = 7.dp, vertical = 3.dp)
                    .testTag("nametag_${tag.agentId.toString().take(8)}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                tag.isStreaming -> tokens.statusWarning
                                tag.isSelf -> tokens.accentPrimary
                                else -> tokens.statusOnline
                            }
                        )
                )
                Text(
                    text = if (tag.isSelf) tag.displayName else "${tag.displayName} • ${tag.distanceMeters.roundToInt()}m",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) tokens.onAccent else tokens.textPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Draws crisp non-blocking Compose text captions over each active OpenGL Avatar Attachment HUD.
 */
@Composable
private fun AvatarHudLabelsOverlay(
    state: ViewerState,
    viewportW: Float,
    viewportH: Float
) {
    val safe = state.sceneSnapshot.hudSafeRect
    val safeW = (safe.right - safe.left).coerceAtLeast(0.20f)
    val safeH = (safe.bottom - safe.top).coerceAtLeast(0.20f)
    val isCompactPortrait = viewportW / viewportH.coerceAtLeast(1f) < 0.85f

    Box(modifier = Modifier.fillMaxSize()) {
        state.sceneSnapshot.hudAttachments.forEach { hud ->
            val slot = HudAttachmentPoint.fromSlotId(hud.attachmentPoint) ?: return@forEach
            val ov = state.hudSlotStates[slot.slotId] ?: AvatarHudSlotState(slot.slotId)
            if (!ov.visible || ov.minimized) return@forEach

            val scale = (ov.scale * state.design.hudGlobalScale).coerceIn(0.45f, 1.55f)
            val wFrac = ((if (isCompactPortrait) 0.34f else 0.19f) * scale).coerceAtMost(safeW * 0.48f)
            val hFrac = ((if (isCompactPortrait) 0.088f else 0.125f) * scale).coerceAtMost(safeH * 0.34f)
            val xFrac = (safe.left + slot.anchorX * (safeW - wFrac) + ov.offsetX * safeW)
                .coerceIn(safe.left, (safe.right - wFrac).coerceAtLeast(safe.left))
            val yFrac = (safe.top + slot.anchorY * (safeH - hFrac) + ov.offsetY * safeH)
                .coerceIn(safe.top, (safe.bottom - hFrac).coerceAtLeast(safe.top))

            Column(
                modifier = Modifier
                    .offset(x = (xFrac * viewportW).dp, y = (yFrac * viewportH).dp)
                    .size(width = (wFrac * viewportW).dp, height = (hFrac * viewportH).dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${slot.shortCode} • ${hud.name}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = 0.92f),
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hud.hudButtons.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        hud.hudButtons.forEach { btn ->
                            Text(
                                text = btn.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

/**
 * Sleek translucent floating pill replacing the bulky full-width top header slab.
 * Displays region name, coordinates, FPS, ping/status dot, and live mesh/texture stream counters.
 */
@Composable
private fun CompactTelemetryPill(
    state: ViewerState,
    isWideScreen: Boolean,
    onTapTelemetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val health = state.connectionHealth
    val (regionTitle, statusText, statusColor) = when (val c = state.connection) {
        is ConnectionState.Connected -> {
            val (lbl, col) = when (health.lludpStatus) {
                LayerHealthStatus.HEALTHY -> "${c.pingMs}ms" to tokens.statusOnline
                LayerHealthStatus.RETRYING -> "RETRY" to tokens.statusWarning
                LayerHealthStatus.DEGRADED -> "DEGR" to tokens.statusWarning
                LayerHealthStatus.FAILED -> "LOST" to tokens.statusError
                else -> "SYNC" to tokens.accentSecondary
            }
            Triple(c.regionName, lbl, col)
        }
        is ConnectionState.LocalSandbox -> Triple("Sandbox", "LOCAL", tokens.accentTertiary)
        else -> Triple("Offline", "DISC", tokens.statusError)
    }

    val pos = state.avatarPosition
    val stats = state.assetStats
    val pillShape = RoundedCornerShape(
        when (tokens.language) {
            DesignLanguage.LCARS -> 99.dp
            DesignLanguage.METRO -> 0.dp
            DesignLanguage.TERMINAL -> 2.dp
            DesignLanguage.POLISHED -> 20.dp
        }
    )

    Row(
        modifier = modifier
            .widthIn(max = if (isWideScreen) 420.dp else 255.dp)
            .clip(pillShape)
            .background(tokens.surfaceChrome.copy(alpha = 0.72f))
            .border(1.dp, tokens.borderHairline.copy(alpha = 0.75f), pillShape)
            .clickable(onClick = onTapTelemetry)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("connection_status_chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // Live status indicator dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = tokens.formatHeader(regionTitle),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (tokens.language == DesignLanguage.LCARS) tokens.accentPrimary else tokens.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    ),
                    maxLines = 1
                )
            }
            val assetSuffix = if (state.connection is ConnectionState.Connected) {
                " • M:${stats.meshesLoaded} T:${stats.texturesLoaded}"
            } else ""
            Text(
                text = "<${pos.x.toInt()},${pos.y.toInt()},${pos.z.toInt()}> • ${state.fps}fps$assetSuffix",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    color = tokens.textSecondary,
                    fontSize = 9.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TopRightMiniControls(
    zenMode: Boolean,
    onToggleZen: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val pillShape = RoundedCornerShape(
        when (tokens.language) {
            DesignLanguage.LCARS -> 99.dp
            DesignLanguage.METRO -> 0.dp
            DesignLanguage.TERMINAL -> 2.dp
            DesignLanguage.POLISHED -> 20.dp
        }
    )

    Row(
        modifier = modifier
            .clip(pillShape)
            .background(tokens.surfaceChrome.copy(alpha = 0.72f))
            .border(1.dp, tokens.borderHairline.copy(alpha = 0.75f), pillShape)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(pillShape)
                .clickable(onClick = onToggleZen)
                .padding(8.dp)
                .testTag("zen_mode_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (zenMode) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = "Hide HUD for full 3D view",
                tint = tokens.textPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(pillShape)
                .clickable(onClick = onDisconnect)
                .padding(8.dp)
                .testTag("disconnect_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = "Disconnect from world",
                tint = tokens.statusError,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Ultra-slim floating icon toolbar (Bottom horizontal strip by default, or slim icon-only rail on Left/Right).
 * Keeps the 3D viewport unobstructed while preserving fast 1-tap access to all 7 tools.
 */
@Composable
private fun SlimActionDock(
    state: ViewerState,
    client: ViewerClient,
    isWideScreen: Boolean,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val isHorizontal = state.design.dockPosition == DockPosition.BOTTOM
    val tools = state.design.pinnedTools
    val showMicroLabel = isWideScreen || tokens.language == DesignLanguage.LCARS
    val containerShape = RoundedCornerShape(
        when (tokens.language) {
            DesignLanguage.LCARS -> 99.dp
            DesignLanguage.METRO -> 0.dp
            DesignLanguage.TERMINAL -> 2.dp
            DesignLanguage.POLISHED -> 22.dp
        }
    )

    Box(
        modifier = modifier
            .clip(containerShape)
            .background(tokens.surfaceChrome.copy(alpha = 0.74f))
            .border(1.dp, tokens.borderHairline.copy(alpha = 0.75f), containerShape)
            .padding(horizontal = 4.dp, vertical = 3.dp)
    ) {
        if (isHorizontal) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tools.forEach { tool ->
                    SlimDockToolButton(tool, state.activeFloater == tool, showMicroLabel) {
                        client.toggleFloater(tool)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                tools.forEach { tool ->
                    SlimDockToolButton(tool, state.activeFloater == tool, showMicroLabel = false) {
                        client.toggleFloater(tool)
                    }
                }
            }
        }
    }
}

@Composable
private fun SlimDockToolButton(
    tool: DockToolId,
    active: Boolean,
    showMicroLabel: Boolean,
    onClick: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val icon: ImageVector = when (tool) {
        DockToolId.CHAT -> Icons.AutoMirrored.Filled.Chat
        DockToolId.RADAR -> Icons.Default.Radar
        DockToolId.MAP -> Icons.Default.Explore
        DockToolId.INVENTORY -> Icons.Default.Inventory2
        DockToolId.INSPECTOR -> Icons.Default.Info
        DockToolId.HUDS -> Icons.Default.Layers
        DockToolId.STUDIO -> Icons.Default.Palette
    }
    val shape = RoundedCornerShape(
        when (tokens.language) {
            DesignLanguage.LCARS -> 99.dp
            DesignLanguage.METRO -> 0.dp
            DesignLanguage.TERMINAL -> 2.dp
            DesignLanguage.POLISHED -> 16.dp
        }
    )
    val bg = when {
        active -> tokens.accentPrimary
        tokens.language == DesignLanguage.LCARS -> tokens.accentSecondary.copy(alpha = 0.22f)
        else -> Color.Transparent
    }
    val fg = if (active) tokens.onAccent else tokens.textPrimary

    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = if (showMicroLabel) 8.dp else 10.dp, vertical = 6.dp)
            .testTag("dock_tool_${tool.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = tool.shortLabel, tint = fg, modifier = Modifier.size(18.dp))
        if (showMicroLabel) {
            Text(
                text = if (tokens.language == DesignLanguage.LCARS) tool.lcarsCode else tool.shortLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontFamily = tokens.monoFont,
                    fontWeight = FontWeight.Bold,
                    color = fg
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * Compact translucent thumb joystick (~78dp) paired with a slender vertical mini-column of Fly/Cam pills.
 */
@Composable
private fun CompactMovementPad(
    state: ViewerState,
    client: ViewerClient,
    isRightSide: Boolean,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val scale = state.design.joystickScale.coerceIn(0.75f, 1.3f)
    val padSize = (80f * scale).dp
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val stickShape = if (tokens.language == DesignLanguage.METRO) RoundedCornerShape(0.dp) else CircleShape

    val joystickBlock: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(padSize)
                .clip(stickShape)
                .background(tokens.surfaceChrome.copy(alpha = 0.56f))
                .border(1.2.dp, tokens.borderHairline.copy(alpha = 0.78f), stickShape)
                .pointerInput(Unit) {
                    val maxR = size.width * 0.35f
                    detectDragGestures(
                        onDragEnd = {
                            knobOffset = Offset.Zero
                            client.updateJoystickInput(0f, 0f, 0f)
                        },
                        onDragCancel = {
                            knobOffset = Offset.Zero
                            client.updateJoystickInput(0f, 0f, 0f)
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        val next = knobOffset + dragAmount
                        val len = hypot(next.x.toDouble(), next.y.toDouble()).toFloat()
                        knobOffset = if (len > maxR) Offset(next.x / len * maxR, next.y / len * maxR) else next
                        val normLen = (len / maxR).coerceIn(0f, 1f)
                        val deadzone = 0.10f
                        if (normLen <= deadzone) {
                            client.updateJoystickInput(forward = 0f, strafe = 0f, turn = 0f)
                        } else {
                            // Smooth precision curve: fine control near center, brisk run/sprint at outer edge
                            val scaled = (normLen - deadzone) / (1f - deadzone)
                            val curved = scaled * (0.45f + 0.55f * scaled)
                            val rawFwd = (-knobOffset.y / maxR).coerceIn(-1f, 1f)
                            val rawTurn = (knobOffset.x / maxR).coerceIn(-1f, 1f)
                            val dirLen = hypot(rawFwd.toDouble(), rawTurn.toDouble()).toFloat().coerceAtLeast(1e-4f)
                            val forward = (rawFwd / dirLen) * curved
                            val turn = (rawTurn / dirLen) * (abs(rawTurn) * 0.65f + 0.35f) * curved
                            client.updateJoystickInput(
                                forward = forward.coerceIn(-1f, 1f),
                                strafe = 0f,
                                turn = turn.coerceIn(-1f, 1f)
                            )
                        }
                    }
                }
                .testTag("virtual_joystick"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = tokens.borderHairline.copy(alpha = 0.5f)
                drawLine(c, Offset(size.width * 0.5f, 10f), Offset(size.width * 0.5f, size.height - 10f), 1f)
                drawLine(c, Offset(10f, size.height * 0.5f), Offset(size.width - 10f, size.height * 0.5f), 1f)
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) }
                    .size((30f * scale).dp)
                    .clip(stickShape)
                    .background(tokens.accentPrimary.copy(alpha = 0.80f))
                    .border(1.dp, tokens.onAccent.copy(alpha = 0.55f), stickShape)
            )
        }
    }

    val controlsColumn: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.isFlying) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactMiniPill(
                        icon = Icons.Default.ArrowUpward,
                        label = "UP",
                        active = false,
                        onClick = { client.elevateAvatar(3.0f) },
                        tag = "fly_up_button"
                    )
                    CompactMiniPill(
                        icon = Icons.Default.ArrowDownward,
                        label = "DN",
                        active = false,
                        onClick = { client.elevateAvatar(-3.0f) },
                        tag = "fly_down_button"
                    )
                }
            } else {
                CompactMiniPill(
                    icon = Icons.Default.ArrowUpward,
                    label = "JMP",
                    active = false,
                    onClick = client::triggerJump,
                    tag = "jump_button"
                )
            }
            CompactMiniPill(
                icon = Icons.Default.FlightTakeoff,
                label = "FLY",
                active = state.isFlying,
                onClick = client::toggleFly,
                tag = "toggle_fly_button"
            )
            CompactMiniPill(
                icon = Icons.Default.Videocam,
                label = state.cameraMode.label.take(3).uppercase(),
                active = state.cameraMode != CameraMode.FOLLOW,
                onClick = { client.cycleCameraMode() },
                tag = "cycle_camera_button"
            )
        }
    }

    Row(
        modifier = modifier.alpha(state.design.joystickOpacity.coerceIn(0.35f, 1f)),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isRightSide) {
            controlsColumn()
            joystickBlock()
        } else {
            joystickBlock()
            controlsColumn()
        }
    }
}

@Composable
private fun CompactMiniPill(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    val tokens = LocalLinkpointDesign.current
    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(if (active) tokens.accentPrimary else tokens.surfaceChrome.copy(alpha = 0.68f))
            .border(1.dp, if (active) tokens.accentPrimary else tokens.borderHairline.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (active) tokens.onAccent else tokens.accentPrimary, modifier = Modifier.size(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontFamily = tokens.monoFont,
                fontWeight = FontWeight.Bold,
                color = if (active) tokens.onAccent else tokens.textPrimary
            )
        )
    }
}

@Composable
private fun ConnectionHealthDialog(
    state: ViewerState,
    onDismiss: () -> Unit,
    onRetryTransport: () -> Unit,
    onFullRelogin: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val health = state.connectionHealth
    val stats = state.assetStats
    val conn = state.connection as? ConnectionState.Connected

    fun colorFor(status: LayerHealthStatus): Color = when (status) {
        LayerHealthStatus.HEALTHY -> tokens.statusOnline
        LayerHealthStatus.RETRYING, LayerHealthStatus.DEGRADED -> tokens.statusWarning
        LayerHealthStatus.FAILED -> tokens.statusError
        LayerHealthStatus.CONNECTING -> tokens.accentSecondary
        LayerHealthStatus.IDLE -> tokens.textMuted
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tokens.surfaceFloater,
        title = {
            Text(
                text = tokens.formatHeader("Connection & Asset Stream"),
                color = tokens.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (conn != null) {
                    Text(
                        text = "${conn.gridName} • ${conn.simIp}:${conn.simPort}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.textSecondary
                        )
                    )
                }
                LayerStatusRow("XML-RPC", health.xmlRpcStatus.label, health.xmlRpcDetail, colorFor(health.xmlRpcStatus))
                LayerStatusRow("CAPS / EQ", health.capsStatus.label, health.capsDetail, colorFor(health.capsStatus))
                LayerStatusRow(
                    "LLUDP",
                    health.lludpStatus.label,
                    "${health.lludpDetail} (Tx:${health.packetsTx} Rx:${health.packetsRx})",
                    colorFor(health.lludpStatus)
                )
                val avatarCount = state.sceneSnapshot.avatars.size
                val wornMeshCount = state.sceneSnapshot.avatars.sumOf { it.attachments.size }
                LayerStatusRow(
                    "3D STREAM",
                    if (stats.meshesPending + stats.texturesPending > 0) "STREAMING" else "SYNCED",
                    "Avatars:$avatarCount (Worn:$wornMeshCount) • Meshes:${stats.meshesLoaded}/${stats.meshesLoaded + stats.meshesPending} • Tex:${stats.texturesLoaded}/${stats.texturesLoaded + stats.texturesPending}",
                    if (stats.meshesLoaded + stats.texturesLoaded > 0) tokens.statusOnline else tokens.accentSecondary
                )
                if (health.diagnostics.isNotEmpty()) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    var copiedLog by remember(health.diagnostics.size) { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Protocol Log (${health.diagnostics.size})",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = tokens.accentPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        LinkpointStatusChip(
                            label = if (copiedLog) "Copied" else "Copy Trace",
                            color = tokens.accentSecondary,
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val text = health.diagnostics.joinToString("\n")
                                cm?.setPrimaryClip(ClipData.newPlainText("Linkpoint Protocol Trace", text))
                                copiedLog = true
                            },
                            modifier = Modifier.testTag("copy_protocol_log_button")
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.canvasVoid.copy(alpha = 0.72f))
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        health.diagnostics.takeLast(40).forEach { line ->
                            Text(
                                text = "• $line",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = tokens.monoFont,
                                    color = tokens.textSecondary
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onRetryTransport,
                    modifier = Modifier.testTag("retry_transport_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = tokens.accentPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry UDP/CAPS", color = tokens.accentPrimary)
                }
                TextButton(
                    onClick = onFullRelogin,
                    modifier = Modifier.testTag("full_relogin_button")
                ) {
                    Text("Re-Login", color = tokens.accentSecondary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = tokens.textSecondary)
            }
        }
    )
}

@Composable
private fun LayerStatusRow(
    layerName: String,
    statusLabel: String,
    detail: String,
    statusColor: Color
) {
    val tokens = LocalLinkpointDesign.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(tokens.surfaceElevated.copy(alpha = 0.72f))
            .border(1.dp, tokens.borderHairline, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = layerName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = tokens.monoFont,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textPrimary
                )
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            )
        }
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
