package com.example.linkpoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.AccentPreset
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.DesignPresets
import com.example.linkpoint.designsystem.DockPosition
import com.example.linkpoint.designsystem.DockToolId
import com.example.linkpoint.designsystem.JoystickSide
import com.example.linkpoint.designsystem.LinkpointActionButton
import com.example.linkpoint.designsystem.LinkpointFloaterHeader
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.designsystem.TelemetryPosition
import com.example.linkpoint.designsystem.UiDensity
import com.example.linkpoint.scene.AvatarHudSlotState
import com.example.linkpoint.scene.EnvironmentPreset
import com.example.linkpoint.scene.HudAttachmentPoint
import com.example.linkpoint.store.ViewerState

@Composable
fun ObjectInspectorContent(
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    com.example.linkpoint.ui.floaters.PrimBuildEditorContent(
        state = state,
        client = client,
        onClose = onClose
    )
}

@Composable
fun AvatarHudManagerContent(
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val huds = state.sceneSnapshot.hudAttachments
    val safe = state.sceneSnapshot.hudSafeRect

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LinkpointFloaterHeader(
            title = "Avatar Attachment HUDs",
            subtitle = "SL Slots 31–38 • Adaptive Safe Rect (${(safe.left * 100).toInt()}%–${(safe.right * 100).toInt()}%)",
            lcarsCode = "06-HUD",
            onClose = onClose
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("HUD Touch Capture (UV STCoord)", style = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary))
                Text("Route taps on slots 31–38 to LSL ObjectGrab before camera orbit", style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary))
            }
            Switch(
                checked = state.design.hudTouchCaptureEnabled,
                onCheckedChange = { client.toggleHudTouchCapture() },
                colors = SwitchDefaults.colors(checkedThumbColor = tokens.onAccent, checkedTrackColor = tokens.accentPrimary)
            )
        }

        Column {
            Text(
                text = "Global Attachment HUD Scale: ${(state.design.hudGlobalScale * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium.copy(color = tokens.textSecondary)
            )
            Slider(
                value = state.design.hudGlobalScale,
                onValueChange = client::setHudGlobalScale,
                valueRange = 0.55f..1.45f,
                colors = SliderDefaults.colors(thumbColor = tokens.accentPrimary, activeTrackColor = tokens.accentPrimary)
            )
        }

        if (huds.isEmpty()) {
            Text(
                text = "No Avatar HUD attachments worn right now. Open Inventory -> Worn & HUDs to attach a HUD to slots 31–38.",
                style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
            )
        } else {
            huds.forEach { hud ->
                val slot = HudAttachmentPoint.fromSlotId(hud.attachmentPoint)
                val slotState = state.hudSlotStates[hud.attachmentPoint] ?: AvatarHudSlotState(hud.attachmentPoint)
                val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(tokens.surfaceElevated.copy(alpha = 0.8f))
                        .border(1.dp, tokens.borderHairline, cardShape)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(hud.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = tokens.textPrimary))
                            Text(
                                text = "Slot ${hud.attachmentPoint} (${slot?.label ?: "HUD"}) • Scale ${(slotState.scale * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = tokens.monoFont, color = tokens.accentSecondary)
                            )
                        }
                        IconButton(onClick = { client.toggleHudSlotVisible(hud.attachmentPoint) }) {
                            Icon(
                                imageVector = if (slotState.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle HUD visibility",
                                tint = if (slotState.visible) tokens.accentPrimary else tokens.textMuted
                            )
                        }
                        LinkpointStatusChip(
                            label = if (slotState.minimized) "Expand" else "Minimize",
                            color = if (slotState.minimized) tokens.accentTertiary else tokens.accentPrimary,
                            onClick = { client.toggleHudSlotMinimized(hud.attachmentPoint) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Slider(
                            value = slotState.scale,
                            onValueChange = { client.updateHudSlotScale(hud.attachmentPoint, it) },
                            valueRange = 0.55f..1.45f,
                            colors = SliderDefaults.colors(thumbColor = tokens.accentSecondary, activeTrackColor = tokens.accentSecondary),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { client.nudgeHudSlot(hud.attachmentPoint, -0.04f, 0f) }, modifier = Modifier.size(34.dp)) {
                            Text("←", color = tokens.accentPrimary, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { client.nudgeHudSlot(hud.attachmentPoint, 0.04f, 0f) }, modifier = Modifier.size(34.dp)) {
                            Text("→", color = tokens.accentPrimary, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { client.nudgeHudSlot(hud.attachmentPoint, 0f, -0.04f) }, modifier = Modifier.size(34.dp)) {
                            Text("↑", color = tokens.accentPrimary, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { client.nudgeHudSlot(hud.attachmentPoint, 0f, 0.04f) }, modifier = Modifier.size(34.dp)) {
                            Text("↓", color = tokens.accentPrimary, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { client.resetHudSlot(hud.attachmentPoint) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Reset HUD slot", tint = tokens.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Live Personal Layout & Design Studio — lets residents switch between
 * Polished, Terminal, Sweep Console (LCARS), and Metro, edit chrome placement,
 * reorder pinned Dock tools, tune opacity/scale, and save personal presets live over 3D.
 */
@Composable
fun LayoutDesignStudioContent(
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var tab by remember { mutableIntStateOf(0) } // 0 = Design Language, 1 = Layout & Chrome, 2 = Tools & World

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LinkpointFloaterHeader(
            title = "Personal Layout & Design Studio",
            subtitle = "${state.design.designLanguage.displayName} • ${state.design.dockPosition.label} • Live Preview",
            lcarsCode = "07-SYS",
            onClose = onClose
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LinkpointStatusChip("Theme & Presets", if (tab == 0) tokens.accentPrimary else tokens.textMuted, onClick = { tab = 0 })
            LinkpointStatusChip("Layout & Ergonomics", if (tab == 1) tokens.accentPrimary else tokens.textMuted, onClick = { tab = 1 })
            LinkpointStatusChip("Dock Tools & Sky", if (tab == 2) tokens.accentPrimary else tokens.textMuted, onClick = { tab = 2 })
        }

        when (tab) {
            0 -> {
                Text("Design Language", style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DesignLanguage.entries.forEach { lang ->
                        val active = state.design.designLanguage == lang
                        val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(if (active) tokens.accentPrimary.copy(alpha = 0.18f) else tokens.surfaceElevated.copy(alpha = 0.7f))
                                .border(if (active) 1.5.dp else 1.dp, if (active) tokens.accentPrimary else tokens.borderHairline, shape)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(lang.displayName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = tokens.textPrimary))
                                Text(lang.tagline, style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary))
                            }
                            LinkpointStatusChip(
                                label = if (active) "Active" else "Apply",
                                color = if (active) tokens.statusOnline else tokens.accentPrimary,
                                onClick = { client.setDesignLanguage(lang) },
                                modifier = Modifier.testTag("studio_lang_${lang.name.lowercase()}")
                            )
                        }
                    }
                }

                Text("Accent Palette", style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AccentPreset.entries.forEach { preset ->
                        LinkpointStatusChip(
                            label = preset.label,
                            color = if (state.design.accentPreset == preset) tokens.accentPrimary else tokens.textMuted,
                            onClick = { client.setAccentPreset(preset) }
                        )
                    }
                }

                if (state.design.designLanguage == DesignLanguage.POLISHED || state.design.designLanguage == DesignLanguage.METRO) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Daylight Light Posture", style = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary))
                        Switch(
                            checked = state.design.useLightPosture,
                            onCheckedChange = client::setLightPosture,
                            colors = SwitchDefaults.colors(checkedTrackColor = tokens.accentPrimary)
                        )
                    }
                }

                Text("Starter & Personal Presets", style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DesignPresets.STARTERS.forEach { preset ->
                        LinkpointStatusChip(
                            label = preset.name,
                            color = tokens.accentSecondary,
                            onClick = { client.applyStarterPreset(preset) }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkpointActionButton(
                        text = "Save Mine",
                        icon = Icons.Default.Save,
                        onClick = client::saveAsCustomPreset,
                        primary = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.hasCustomPreset) {
                        LinkpointActionButton(
                            text = "Load Mine",
                            onClick = client::loadCustomPreset,
                            primary = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    LinkpointActionButton(
                        text = "Reset",
                        icon = Icons.Default.RestartAlt,
                        onClick = client::resetDesignToDefault,
                        primary = false,
                        modifier = Modifier.weight(0.85f)
                    )
                }
            }

            1 -> {
                Text("Action Dock Edge", style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DockPosition.entries.forEach { pos ->
                        LinkpointStatusChip(
                            label = pos.label,
                            color = if (state.design.dockPosition == pos) tokens.accentPrimary else tokens.textMuted,
                            onClick = { client.setDockPosition(pos) }
                        )
                    }
                }

                Text("Telemetry Bar Placement", style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TelemetryPosition.entries.forEach { pos ->
                        LinkpointStatusChip(
                            label = pos.label,
                            color = if (state.design.telemetryPosition == pos) tokens.accentPrimary else tokens.textMuted,
                            onClick = { client.setTelemetryPosition(pos) }
                        )
                    }
                }

                Text("Movement Pad Handedness", style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JoystickSide.entries.forEach { side ->
                        LinkpointStatusChip(
                            label = side.label,
                            color = if (state.design.joystickSide == side) tokens.accentPrimary else tokens.textMuted,
                            onClick = { client.setJoystickSide(side) }
                        )
                    }
                }

                Text("UI Density", style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiDensity.entries.forEach { d ->
                        LinkpointStatusChip(
                            label = d.label,
                            color = if (state.design.uiDensity == d) tokens.accentPrimary else tokens.textMuted,
                            onClick = { client.setUiDensity(d) }
                        )
                    }
                }

                Text("Panel Glass Opacity: ${(state.design.panelOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = tokens.textSecondary))
                Slider(
                    value = state.design.panelOpacity,
                    onValueChange = client::setPanelOpacity,
                    valueRange = 0.55f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = tokens.accentPrimary, activeTrackColor = tokens.accentPrimary)
                )

                Text("Joystick Scale: ${(state.design.joystickScale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = tokens.textSecondary))
                Slider(
                    value = state.design.joystickScale,
                    onValueChange = client::setJoystickScale,
                    valueRange = 0.75f..1.35f,
                    colors = SliderDefaults.colors(thumbColor = tokens.accentSecondary, activeTrackColor = tokens.accentSecondary)
                )
            }

            2 -> {
                Text("WindLight / EEP Atmosphere", style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EnvironmentPreset.entries.forEach { env ->
                        LinkpointStatusChip(
                            label = env.label,
                            color = if (state.design.environmentPreset == env) tokens.accentTertiary else tokens.textMuted,
                            onClick = { client.setEnvironmentPreset(env) }
                        )
                    }
                }

                Text("Draw Distance: ${state.design.drawDistance.toInt()}m", style = MaterialTheme.typography.labelMedium.copy(color = tokens.textSecondary))
                Slider(
                    value = state.design.drawDistance,
                    onValueChange = client::setDrawDistance,
                    valueRange = 48f..256f,
                    colors = SliderDefaults.colors(thumbColor = tokens.accentPrimary, activeTrackColor = tokens.accentPrimary)
                )

                Text("Pinned Dock Tools & Order", style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DockToolId.entries.forEach { tool ->
                        val pinned = tool in state.design.pinnedTools
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(tokens.surfaceElevated.copy(alpha = 0.72f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${tool.shortLabel} (${tool.lcarsCode})",
                                style = MaterialTheme.typography.bodySmall.copy(color = tokens.textPrimary)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (pinned) {
                                    IconButton(onClick = { client.movePinnedTool(tool, -1) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move tool up", tint = tokens.accentPrimary, modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(onClick = { client.movePinnedTool(tool, 1) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move tool down", tint = tokens.accentPrimary, modifier = Modifier.size(16.dp))
                                    }
                                }
                                Spacer(Modifier.width(4.dp))
                                LinkpointStatusChip(
                                    label = if (pinned) "Pinned" else "Hidden",
                                    color = if (pinned) tokens.statusOnline else tokens.textMuted,
                                    onClick = { client.togglePinnedTool(tool) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
