package com.example.linkpoint.ui.floaters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.linkpoint.build.PrimBuildPackets
import com.example.linkpoint.build.PrimTransformMath
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.LinkpointActionButton
import com.example.linkpoint.designsystem.LinkpointFloaterHeader
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.Vec3
import com.example.linkpoint.store.ViewerState
import java.util.Locale

enum class BuildStudioTab(val label: String, val tag: String) {
    TRANSFORM("Transform", "build_tab_transform"),
    FEATURES("Features & Tex", "build_tab_features"),
    LINKSET("Linkset", "build_tab_linkset")
}

private data class TransformStepPreset(
    val label: String,
    val meters: Float,
    val degrees: Float,
    val scaleDelta: Float
)

private val STEP_PRESETS = listOf(
    TransformStepPreset("Fine 0.05m / 5°", 0.05f, 5f, 0.05f),
    TransformStepPreset("Std 0.25m / 15°", 0.25f, 15f, 0.25f),
    TransformStepPreset("Coarse 1.0m / 45°", 1.0f, 45f, 0.50f)
)

private val TINT_SWATCHES = listOf(
    ColorRgba(0.22f, 0.78f, 0.98f, 1f), // Cyan
    ColorRgba(0.96f, 0.62f, 0.08f, 1f), // Amber
    ColorRgba(0.14f, 0.82f, 0.56f, 1f), // Emerald
    ColorRgba(0.94f, 0.34f, 0.44f, 1f), // Coral
    ColorRgba(0.68f, 0.42f, 0.96f, 1f), // Violet
    ColorRgba(0.96f, 0.97f, 0.99f, 1f), // White
    ColorRgba(0.32f, 0.38f, 0.46f, 1f), // Slate
    ColorRgba(0.95f, 0.82f, 0.26f, 1f)  // Gold
)

/**
 * Phase 7 Mobile Prim Build & Edit Studio — brings Firestorm / react-linkpoint parity
 * for 1-tap prim creation (`ObjectAdd`), X/Y/Z Position/Rotation/Scale (`MultipleObjectUpdate`),
 * Name/Description/Flags/TextureEntry tint+alpha+glow+fullbright, and Linkset/Duplicate/DeRez actions.
 */
@Composable
fun PrimBuildEditorContent(
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val clipboard = LocalClipboardManager.current
    val obj = state.selectedObject
    var activeTabOrdinal by rememberSaveable { mutableIntStateOf(BuildStudioTab.TRANSFORM.ordinal) }
    var stepIndex by rememberSaveable { mutableIntStateOf(1) }
    val activeTab = BuildStudioTab.entries[activeTabOrdinal.coerceIn(0, BuildStudioTab.entries.lastIndex)]
    val step = STEP_PRESETS[stepIndex.coerceIn(0, STEP_PRESETS.lastIndex)]

    val subtitle = if (obj != null) {
        val linkInfo = when {
            obj.parentId != 0 -> "Child of #${obj.parentId}"
            else -> {
                val children = state.sceneSnapshot.worldObjects.count { it.parentId == obj.localId }
                if (children > 0) "Root (${children + 1} prims)" else "Standalone"
            }
        }
        "LocalID #${obj.localId} • ${obj.shape.name} • $linkInfo"
    } else {
        "Rez a prim or tap any 3D object in world"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("prim_build_studio_root"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LinkpointFloaterHeader(
            title = "Build & Object Studio",
            subtitle = subtitle,
            lcarsCode = "05-BLD",
            onClose = onClose
        )

        // 1. Always-visible Create ("Rez Prim") Bar
        RezPrimBar(onRezPreset = { preset -> client.rezPrim(preset) })

        if (obj == null) {
            EmptySelectionCard(
                worldObjects = state.sceneSnapshot.worldObjects,
                onSelectObject = client::onWorldObjectSelected
            )
            return@Column
        }

        // 2. Selected Object Summary + Preserved Touch / Sit / Focus Quick Actions
        SelectedObjectSummaryCard(
            obj = obj,
            isSitting = state.isSitting,
            onCopyUuid = { clipboard.setText(AnnotatedString(obj.uuid.toString())) },
            onTouch = client::touchSelectedObject,
            onToggleSit = client::toggleSitOnSelected,
            onFocus = { client.focusCameraOn(obj.position) }
        )

        // 3. Studio Mode Tabs (Transform | Features & Tex | Linkset)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BuildStudioTab.entries.forEach { tab ->
                val selected = tab == activeTab
                val tabIcon = when (tab) {
                    BuildStudioTab.TRANSFORM -> Icons.Default.OpenWith
                    BuildStudioTab.FEATURES -> Icons.Default.Palette
                    BuildStudioTab.LINKSET -> Icons.Default.Link
                }
                StudioSegmentChip(
                    label = tab.label,
                    icon = tabIcon,
                    selected = selected,
                    onClick = { activeTabOrdinal = tab.ordinal },
                    testTag = tab.tag,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 4. Active Tab Body
        when (activeTab) {
            BuildStudioTab.TRANSFORM -> TransformStudioSection(
                obj = obj,
                editLinkedParts = state.editLinkedParts,
                stepIndex = stepIndex,
                step = step,
                onStepSelected = { stepIndex = it },
                onEditLinkedChanged = client::setEditLinkedParts,
                onTextFocusChanged = client::setTextInputFocused,
                onApplyPosition = { nextPos ->
                    client.updateObjectTransform(localId = obj.localId, position = nextPos)
                },
                onApplyEulerDeg = { nextEuler ->
                    val nextQuat = PrimTransformMath.eulerDegToQuat(nextEuler)
                    client.updateObjectTransform(localId = obj.localId, rotation = nextQuat)
                },
                onApplyScale = { nextScale ->
                    client.updateObjectTransform(
                        localId = obj.localId,
                        scale = PrimTransformMath.clampScale(nextScale)
                    )
                }
            )

            BuildStudioTab.FEATURES -> FeaturesAndTextureSection(
                obj = obj,
                onTextFocusChanged = client::setTextInputFocused,
                onApplyMetadata = { name, desc ->
                    client.updateObjectMetadata(obj.localId, name, desc)
                },
                onUpdateFlags = { physics, phantom ->
                    client.updateObjectFlags(
                        localId = obj.localId,
                        usePhysics = physics,
                        isPhantom = phantom,
                        isTemporary = obj.isTemporary,
                        castsShadows = obj.castsShadows
                    )
                },
                onUpdateAppearance = { color, fullbright, glow ->
                    client.updateObjectAppearance(
                        localId = obj.localId,
                        color = color,
                        isFullbright = fullbright,
                        glow = glow
                    )
                }
            )

            BuildStudioTab.LINKSET -> LinksetStudioSection(
                obj = obj,
                worldObjects = state.sceneSnapshot.worldObjects,
                buildSelectionIds = state.buildSelectionIds,
                onToggleQueueId = client::toggleBuildQueueSelection,
                onClearQueue = client::clearBuildQueueSelection,
                onSelectPrim = client::onWorldObjectSelected,
                onLinkSelected = { ids -> client.linkObjects(ids) },
                onUnlinkSelected = { ids -> client.unlinkObjects(ids) }
            )
        }

        HorizontalDivider(color = tokens.borderHairline.copy(alpha = 0.6f))

        // 5. Persistent Object Lifecycle Bar: Duplicate | Take | Delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactStudioActionButton(
                label = "Duplicate",
                icon = Icons.Default.ContentCopy,
                accent = tokens.accentSecondary,
                onClick = { client.duplicateObject(obj.localId) },
                testTag = "build_action_duplicate",
                modifier = Modifier.weight(1f)
            )
            CompactStudioActionButton(
                label = "Take",
                icon = Icons.Default.Archive,
                accent = tokens.accentPrimary,
                onClick = { client.takeObjectToInventory(obj.localId) },
                testTag = "build_action_take",
                modifier = Modifier.weight(0.9f)
            )
            CompactStudioActionButton(
                label = "Delete",
                icon = Icons.Default.DeleteOutline,
                accent = Color(0xFFE85566),
                onClick = { client.deleteObjectToTrash(obj.localId) },
                testTag = "build_action_delete",
                modifier = Modifier.weight(0.95f)
            )
        }
    }
}

@Composable
private fun RezPrimBar(
    onRezPreset: (PrimBuildPackets.PrimPreset) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.surfaceElevated.copy(alpha = 0.78f))
            .border(1.dp, tokens.borderHairline, cardShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = tokens.accentPrimary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "REZ PRIM (1-TAP CREATE)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        fontWeight = FontWeight.Bold,
                        color = tokens.accentPrimary
                    )
                )
            }
            Text(
                text = "2.5m ahead",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    color = tokens.textMuted
                )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PrimBuildPackets.PrimPreset.entries.forEach { preset ->
                val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(tokens.accentPrimary.copy(alpha = 0.14f))
                        .border(1.dp, tokens.accentPrimary.copy(alpha = 0.65f), shape)
                        .clickable { onRezPreset(preset) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("rez_preset_${preset.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = tokens.accentPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = tokens.displayFont,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.textPrimary
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySelectionCard(
    worldObjects: List<SceneObject>,
    onSelectObject: (SceneObject) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.surfaceElevated.copy(alpha = 0.65f))
            .border(1.dp, tokens.borderHairline, cardShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Tap a preset above to rez a new 3D prim in front of your avatar, or select an existing world prim below to edit its transform, color, glow, physics, or linkset.",
            style = MaterialTheme.typography.bodySmall.copy(color = tokens.textSecondary)
        )
        if (worldObjects.isNotEmpty()) {
            Text(
                text = "NEARBY SCENE PRIMS (${worldObjects.size})",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    fontWeight = FontWeight.Bold,
                    color = tokens.accentSecondary
                )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                worldObjects.take(12).forEach { prim ->
                    LinkpointStatusChip(
                        label = "${prim.name} #${prim.localId}",
                        color = tokens.accentSecondary,
                        modifier = Modifier.testTag("build_pick_prim_${prim.localId}"),
                        onClick = { onSelectObject(prim) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedObjectSummaryCard(
    obj: SceneObject,
    isSitting: Boolean,
    onCopyUuid: () -> Unit,
    onTouch: () -> Unit,
    onToggleSit: () -> Unit,
    onFocus: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.surfaceElevated.copy(alpha = 0.78f))
            .border(1.dp, tokens.borderHairline, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = obj.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (obj.description.isNotBlank()) {
                    Text(
                        text = obj.description,
                        style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = onCopyUuid,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("inspector_copy_uuid_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Object UUID",
                    tint = tokens.accentPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LinkpointStatusChip(obj.shape.name, tokens.accentSecondary)
            if (obj.parentId != 0) {
                LinkpointStatusChip("Child -> #${obj.parentId}", tokens.accentTertiary)
            }
            if (obj.isScripted) LinkpointStatusChip("Scripted", tokens.accentTertiary)
            if (obj.isFullbright) LinkpointStatusChip("Fullbright", tokens.accentPrimary)
            if (obj.isPhantom) LinkpointStatusChip("Phantom", tokens.textSecondary)
            if (obj.isPhysical) LinkpointStatusChip("Physics", tokens.statusOnline)
            if (obj.glow > 0.01f) LinkpointStatusChip("Glow ${(obj.glow * 100).toInt()}%", tokens.accentPrimary)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LinkpointActionButton(
                text = "Touch",
                icon = Icons.Default.TouchApp,
                onClick = onTouch,
                primary = true,
                testTag = "inspector_touch_button",
                modifier = Modifier.weight(1f)
            )
            LinkpointActionButton(
                text = if (isSitting) "Stand" else "Sit",
                icon = Icons.Default.Chair,
                onClick = onToggleSit,
                primary = false,
                testTag = "inspector_sit_button",
                modifier = Modifier.weight(0.95f)
            )
            LinkpointActionButton(
                text = "Focus",
                icon = Icons.Default.CenterFocusStrong,
                onClick = onFocus,
                primary = false,
                testTag = "inspector_focus_button",
                modifier = Modifier.weight(0.95f)
            )
        }
    }
}

@Composable
private fun TransformStudioSection(
    obj: SceneObject,
    editLinkedParts: Boolean,
    stepIndex: Int,
    step: TransformStepPreset,
    onStepSelected: (Int) -> Unit,
    onEditLinkedChanged: (Boolean) -> Unit,
    onTextFocusChanged: (Boolean) -> Unit,
    onApplyPosition: (Vec3) -> Unit,
    onApplyEulerDeg: (Vec3) -> Unit,
    onApplyScale: (Vec3) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val euler = remember(obj.rotation) { PrimTransformMath.quatToEulerDeg(obj.rotation) }
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.surfaceElevated.copy(alpha = 0.72f))
            .border(1.dp, tokens.borderHairline, cardShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Edit Linked Parts toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Edit Linked Parts",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary
                    )
                )
                Text(
                    text = if (editLinkedParts) "Transforms apply to single prim only" else "Transforms move entire linkset (UPD_LINKED_SETS)",
                    style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
                )
            }
            Switch(
                checked = editLinkedParts,
                onCheckedChange = onEditLinkedChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = tokens.onAccent,
                    checkedTrackColor = tokens.accentPrimary
                ),
                modifier = Modifier.testTag("build_edit_linked_switch")
            )
        }

        // Step preset selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            STEP_PRESETS.forEachIndexed { idx, preset ->
                StudioSegmentChip(
                    label = preset.label,
                    icon = null,
                    selected = idx == stepIndex,
                    onClick = { onStepSelected(idx) },
                    testTag = "build_step_$idx",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Position (m)
        Vector3NudgeBlock(
            title = "POSITION (M)",
            prefixTag = "pos",
            vec = obj.position,
            step = step.meters,
            minVal = 0f,
            maxVal = 4096f,
            onTextFocusChanged = onTextFocusChanged,
            onValueChange = onApplyPosition
        )

        // Rotation (Euler Deg <-> Quaternion)
        Vector3NudgeBlock(
            title = "ROTATION (° EULER)",
            prefixTag = "rot",
            vec = euler,
            step = step.degrees,
            minVal = -360f,
            maxVal = 360f,
            wrapDegrees = true,
            onTextFocusChanged = onTextFocusChanged,
            onValueChange = onApplyEulerDeg
        )

        // Scale (m, 0.01 .. 64.0)
        Vector3NudgeBlock(
            title = "SCALE (M • 0.01–64.0)",
            prefixTag = "scale",
            vec = obj.scale,
            step = step.scaleDelta,
            minVal = PrimTransformMath.MIN_PRIM_SCALE,
            maxVal = PrimTransformMath.MAX_PRIM_SCALE,
            onTextFocusChanged = onTextFocusChanged,
            onValueChange = onApplyScale
        )
    }
}

@Composable
private fun Vector3NudgeBlock(
    title: String,
    prefixTag: String,
    vec: Vec3,
    step: Float,
    minVal: Float,
    maxVal: Float,
    wrapDegrees: Boolean = false,
    onTextFocusChanged: (Boolean) -> Unit,
    onValueChange: (Vec3) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.monoFont,
                fontWeight = FontWeight.Bold,
                color = tokens.accentSecondary
            )
        )
        AxisNudgeRow(
            axisLabel = "X",
            axisColor = Color(0xFFEF5350),
            value = vec.x,
            step = step,
            tagPrefix = "${prefixTag}_x",
            onTextFocusChanged = onTextFocusChanged,
            onCommit = { raw ->
                val next = sanitizeAxis(raw, minVal, maxVal, wrapDegrees)
                onValueChange(vec.copy(x = next))
            }
        )
        AxisNudgeRow(
            axisLabel = "Y",
            axisColor = Color(0xFF66BB6A),
            value = vec.y,
            step = step,
            tagPrefix = "${prefixTag}_y",
            onTextFocusChanged = onTextFocusChanged,
            onCommit = { raw ->
                val next = sanitizeAxis(raw, minVal, maxVal, wrapDegrees)
                onValueChange(vec.copy(y = next))
            }
        )
        AxisNudgeRow(
            axisLabel = "Z",
            axisColor = Color(0xFF42A5F5),
            value = vec.z,
            step = step,
            tagPrefix = "${prefixTag}_z",
            onTextFocusChanged = onTextFocusChanged,
            onCommit = { raw ->
                val next = sanitizeAxis(raw, minVal, maxVal, wrapDegrees)
                onValueChange(vec.copy(z = next))
            }
        )
    }
}

private fun sanitizeAxis(value: Float, minVal: Float, maxVal: Float, wrapDegrees: Boolean): Float {
    if (!value.isFinite()) return minVal
    return if (wrapDegrees) {
        var deg = value % 360f
        if (deg > 180f) deg -= 360f
        if (deg < -180f) deg += 360f
        deg
    } else {
        value.coerceIn(minVal, maxVal)
    }
}

@Composable
private fun AxisNudgeRow(
    axisLabel: String,
    axisColor: Color,
    value: Float,
    step: Float,
    tagPrefix: String,
    onTextFocusChanged: (Boolean) -> Unit,
    onCommit: (Float) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val formatted = String.format(Locale.US, "%.2f", value)
    var textValue by remember(formatted) { mutableStateOf(formatted) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .width(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(axisColor.copy(alpha = 0.20f))
                .border(1.dp, axisColor.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = axisLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    fontWeight = FontWeight.Bold,
                    color = axisColor
                )
            )
        }

        NudgeStepButton(
            icon = Icons.Default.Remove,
            contentDescription = "Decrease $axisLabel",
            onClick = { onCommit(value - step) },
            testTag = "${tagPrefix}_minus"
        )

        OutlinedTextField(
            value = textValue,
            onValueChange = { next ->
                textValue = next
                next.toFloatOrNull()?.let { parsed -> onCommit(parsed) }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium.copy(
                fontFamily = tokens.monoFont,
                color = tokens.textPrimary
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { textValue.toFloatOrNull()?.let(onCommit) }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = tokens.accentPrimary,
                unfocusedBorderColor = tokens.borderHairline,
                focusedContainerColor = tokens.surfaceChrome.copy(alpha = 0.65f),
                unfocusedContainerColor = tokens.surfaceChrome.copy(alpha = 0.45f)
            ),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 46.dp)
                .onFocusChanged { onTextFocusChanged(it.isFocused) }
                .testTag("${tagPrefix}_input")
        )

        NudgeStepButton(
            icon = Icons.Default.Add,
            contentDescription = "Increase $axisLabel",
            onClick = { onCommit(value + step) },
            testTag = "${tagPrefix}_plus"
        )
    }
}

@Composable
private fun NudgeStepButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String
) {
    val tokens = LocalLinkpointDesign.current
    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(8.dp))
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(tokens.surfaceChrome.copy(alpha = 0.85f))
            .border(1.dp, tokens.borderHairline, shape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tokens.accentPrimary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun FeaturesAndTextureSection(
    obj: SceneObject,
    onTextFocusChanged: (Boolean) -> Unit,
    onApplyMetadata: (String, String) -> Unit,
    onUpdateFlags: (Boolean, Boolean) -> Unit,
    onUpdateAppearance: (ColorRgba, Boolean, Float) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var nameDraft by remember(obj.localId, obj.name) { mutableStateOf(obj.name) }
    var descDraft by remember(obj.localId, obj.description) { mutableStateOf(obj.description) }
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.surfaceElevated.copy(alpha = 0.72f))
            .border(1.dp, tokens.borderHairline, cardShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "IDENTITY (OBJECT NAME & DESCRIPTION)",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.monoFont,
                fontWeight = FontWeight.Bold,
                color = tokens.accentSecondary
            )
        )

        OutlinedTextField(
            value = nameDraft,
            onValueChange = { nameDraft = it },
            label = { Text("Name") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = tokens.accentPrimary,
                unfocusedBorderColor = tokens.borderHairline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onTextFocusChanged(it.isFocused) }
                .testTag("build_name_input")
        )

        OutlinedTextField(
            value = descDraft,
            onValueChange = { descDraft = it },
            label = { Text("Description") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = tokens.accentPrimary,
                unfocusedBorderColor = tokens.borderHairline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onTextFocusChanged(it.isFocused) }
                .testTag("build_desc_input")
        )

        CompactStudioActionButton(
            label = "Apply Name & Description",
            icon = Icons.Default.Check,
            accent = tokens.accentPrimary,
            onClick = { onApplyMetadata(nameDraft.trim().ifBlank { "Object" }, descDraft.trim()) },
            testTag = "build_apply_metadata",
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(color = tokens.borderHairline.copy(alpha = 0.6f))

        Text(
            text = "PRIM FLAGS & EMISSIVE",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.monoFont,
                fontWeight = FontWeight.Bold,
                color = tokens.accentSecondary
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlagToggleChip(
                label = "Phantom",
                checked = obj.isPhantom,
                onToggle = { onUpdateFlags(obj.isPhysical, !obj.isPhantom) },
                testTag = "build_flag_phantom",
                modifier = Modifier.weight(1f)
            )
            FlagToggleChip(
                label = "Physics",
                checked = obj.isPhysical,
                onToggle = { onUpdateFlags(!obj.isPhysical, obj.isPhantom) },
                testTag = "build_flag_physics",
                modifier = Modifier.weight(1f)
            )
            FlagToggleChip(
                label = "Fullbright",
                checked = obj.isFullbright,
                onToggle = { onUpdateAppearance(obj.color, !obj.isFullbright, obj.glow) },
                testTag = "build_flag_fullbright",
                modifier = Modifier.weight(1f)
            )
        }

        // Glow slider
        Column {
            Text(
                text = "Glow: ${(obj.glow * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    color = tokens.textSecondary
                )
            )
            Slider(
                value = obj.glow.coerceIn(0f, 1f),
                onValueChange = { nextGlow ->
                    onUpdateAppearance(obj.color, obj.isFullbright, nextGlow)
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = tokens.accentPrimary,
                    activeTrackColor = tokens.accentPrimary
                ),
                modifier = Modifier.testTag("build_glow_slider")
            )
        }

        HorizontalDivider(color = tokens.borderHairline.copy(alpha = 0.6f))

        Text(
            text = "FACE TINT (COLOR & ALPHA)",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.monoFont,
                fontWeight = FontWeight.Bold,
                color = tokens.accentSecondary
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TINT_SWATCHES.forEachIndexed { idx, swatch ->
                val composeColor = Color(swatch.r, swatch.g, swatch.b, 1f)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(composeColor)
                        .border(2.dp, tokens.textPrimary.copy(alpha = 0.7f), CircleShape)
                        .clickable {
                            onUpdateAppearance(
                                swatch.copy(a = obj.color.a),
                                obj.isFullbright,
                                obj.glow
                            )
                        }
                        .testTag("build_tint_swatch_$idx")
                )
            }
        }

        ColorChannelSlider(
            label = "Red ${(obj.color.r * 255).toInt()}",
            value = obj.color.r,
            trackColor = Color(0xFFEF5350),
            testTag = "build_color_r_slider",
            onChange = { r -> onUpdateAppearance(obj.color.copy(r = r), obj.isFullbright, obj.glow) }
        )
        ColorChannelSlider(
            label = "Green ${(obj.color.g * 255).toInt()}",
            value = obj.color.g,
            trackColor = Color(0xFF66BB6A),
            testTag = "build_color_g_slider",
            onChange = { g -> onUpdateAppearance(obj.color.copy(g = g), obj.isFullbright, obj.glow) }
        )
        ColorChannelSlider(
            label = "Blue ${(obj.color.b * 255).toInt()}",
            value = obj.color.b,
            trackColor = Color(0xFF42A5F5),
            testTag = "build_color_b_slider",
            onChange = { b -> onUpdateAppearance(obj.color.copy(b = b), obj.isFullbright, obj.glow) }
        )
        ColorChannelSlider(
            label = "Alpha ${(obj.color.a * 100).toInt()}%",
            value = obj.color.a,
            trackColor = tokens.accentPrimary,
            testTag = "build_alpha_slider",
            onChange = { a -> onUpdateAppearance(obj.color.copy(a = a), obj.isFullbright, obj.glow) }
        )
    }
}

@Composable
private fun ColorChannelSlider(
    label: String,
    value: Float,
    trackColor: Color,
    testTag: String,
    onChange: (Float) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.monoFont,
                color = tokens.textSecondary
            )
        )
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = trackColor,
                activeTrackColor = trackColor
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun LinksetStudioSection(
    obj: SceneObject,
    worldObjects: List<SceneObject>,
    buildSelectionIds: List<Int>,
    onToggleQueueId: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onSelectPrim: (SceneObject) -> Unit,
    onLinkSelected: (List<Int>) -> Unit,
    onUnlinkSelected: (List<Int>) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
    val effectiveLinkSelection = remember(obj.localId, buildSelectionIds) {
        if (buildSelectionIds.isEmpty()) listOf(obj.localId)
        else if (obj.localId in buildSelectionIds) buildSelectionIds
        else listOf(obj.localId) + buildSelectionIds
    }
    val rootId = if (obj.parentId != 0) obj.parentId else obj.localId
    val linksetMembers = remember(worldObjects, rootId) {
        worldObjects.filter { it.localId == rootId || it.parentId == rootId }
    }

    LaunchedEffect(obj.localId) {
        // Keep UI responsive when switching active prim
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.surfaceElevated.copy(alpha = 0.72f))
            .border(1.dp, tokens.borderHairline, cardShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "MULTI-SELECT LINK QUEUE (${effectiveLinkSelection.size} SELECTED)",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.monoFont,
                fontWeight = FontWeight.Bold,
                color = tokens.accentSecondary
            )
        )
        Text(
            text = "First queued prim becomes the Root Prim on ObjectLink. Tap scene prims below to queue 2+ prims on touch screens.",
            style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val inQueue = obj.localId in buildSelectionIds
            CompactStudioActionButton(
                label = if (inQueue) "Queued #${obj.localId}" else "Queue #${obj.localId}",
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                accent = if (inQueue) tokens.statusOnline else tokens.accentPrimary,
                onClick = { onToggleQueueId(obj.localId) },
                testTag = "build_queue_current",
                modifier = Modifier.weight(1f)
            )
            if (buildSelectionIds.isNotEmpty()) {
                CompactStudioActionButton(
                    label = "Clear (${buildSelectionIds.size})",
                    icon = Icons.Default.LinkOff,
                    accent = tokens.textSecondary,
                    onClick = onClearQueue,
                    testTag = "build_queue_clear",
                    modifier = Modifier.weight(0.85f)
                )
            }
        }

        // Tappable scene prim chips to build a multi-prim selection effortlessly on mobile
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            worldObjects.take(16).forEach { prim ->
                val queued = prim.localId in effectiveLinkSelection
                LinkpointStatusChip(
                    label = "${if (queued) "✓ " else ""}${prim.name} #${prim.localId}",
                    color = if (queued) tokens.accentPrimary else tokens.textSecondary,
                    modifier = Modifier.testTag("build_queue_item_${prim.localId}"),
                    onClick = { onToggleQueueId(prim.localId) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactStudioActionButton(
                label = "Link (${effectiveLinkSelection.size})",
                icon = Icons.Default.Link,
                accent = tokens.accentPrimary,
                enabled = effectiveLinkSelection.size >= 2,
                onClick = { onLinkSelected(effectiveLinkSelection) },
                testTag = "build_action_link",
                modifier = Modifier.weight(1f)
            )
            val canUnlink = linksetMembers.size >= 2 || obj.parentId != 0 || buildSelectionIds.size >= 2
            CompactStudioActionButton(
                label = "Unlink",
                icon = Icons.Default.LinkOff,
                accent = tokens.accentTertiary,
                enabled = canUnlink,
                onClick = { onUnlinkSelected(effectiveLinkSelection) },
                testTag = "build_action_unlink",
                modifier = Modifier.weight(1f)
            )
        }

        if (linksetMembers.size > 1) {
            HorizontalDivider(color = tokens.borderHairline.copy(alpha = 0.6f))
            Text(
                text = "CURRENT LINKSET PARTS (${linksetMembers.size})",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    fontWeight = FontWeight.Bold,
                    color = tokens.accentSecondary
                )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                linksetMembers.forEach { member ->
                    val role = if (member.parentId == 0) "Root" else "Child"
                    LinkpointStatusChip(
                        label = "$role: ${member.name} #${member.localId}",
                        color = if (member.localId == obj.localId) tokens.accentPrimary else tokens.accentSecondary,
                        modifier = Modifier.testTag("build_linkset_member_${member.localId}"),
                        onClick = { onSelectPrim(member) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioSegmentChip(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
    val bg = if (selected) tokens.accentPrimary.copy(alpha = 0.22f) else tokens.surfaceChrome.copy(alpha = 0.55f)
    val border = if (selected) tokens.accentPrimary else tokens.borderHairline
    val fg = if (selected) tokens.accentPrimary else tokens.textSecondary

    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .testTag(testTag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.displayFont,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = fg,
                fontSize = 11.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FlagToggleChip(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
    val activeColor = tokens.accentPrimary
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (checked) activeColor.copy(alpha = 0.20f) else tokens.surfaceChrome.copy(alpha = 0.5f))
            .border(1.dp, if (checked) activeColor else tokens.borderHairline, shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(testTag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (checked) activeColor else tokens.textMuted)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                color = if (checked) tokens.textPrimary else tokens.textSecondary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactStudioActionButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val tokens = LocalLinkpointDesign.current
    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = accent.copy(alpha = if (enabled) 0.14f else 0.05f),
            contentColor = if (enabled) tokens.textPrimary else tokens.textMuted
        ),
        modifier = modifier
            .heightIn(min = 40.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) accent else tokens.textMuted,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.displayFont,
                fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
