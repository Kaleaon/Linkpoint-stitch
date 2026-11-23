package com.example.linkpoint.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.LinkpointActionButton
import com.example.linkpoint.designsystem.LinkpointFloaterHeader
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.protocol.SlBinaryParsers
import com.example.linkpoint.scene.AvatarEntity
import com.example.linkpoint.store.ViewerState

@Composable
fun AvatarProfileSheetContent(
    avatar: AvatarEntity,
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var activeTab by remember(avatar.agentId) { mutableIntStateOf(0) } // 0 = Profile, 1 = Appearance & Meshes
    var showPayDialog by remember(avatar.agentId) { mutableStateOf(false) }

    val portraitBitmap = remember(avatar.profileImageId, state.textureRevision) {
        client.getCachedTextureBitmap(avatar.profileImageId)
    }
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
    val distMeters = remember(avatar.position, state.avatarPosition) {
        avatar.position.distanceTo(state.avatarPosition)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("avatar_profile_sheet")
    ) {
        LinkpointFloaterHeader(
            title = avatar.displayName,
            subtitle = buildString {
                if (avatar.legacyName.isNotBlank() && !avatar.legacyName.equals(avatar.displayName, ignoreCase = true)) {
                    append(avatar.legacyName)
                    append(" • ")
                }
                if (avatar.isSelf) append("You")
                else append("${"%.1f".format(distMeters)}m away")
                if (avatar.bornOn.isNotBlank()) append(" • Born ${avatar.bornOn}")
            },
            lcarsCode = "07-BIO",
            onClose = onClose
        )

        // Header Portrait + Status Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(tokens.surfaceElevated.copy(alpha = 0.72f))
                .border(1.dp, tokens.borderHairline, cardShape)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp)))
                    .background(tokens.accentPrimary.copy(alpha = 0.16f))
                    .border(1.dp, tokens.accentPrimary.copy(alpha = 0.55f), RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))),
                contentAlignment = Alignment.Center
            ) {
                if (portraitBitmap != null && !portraitBitmap.isRecycled) {
                    Image(
                        bitmap = portraitBitmap.asImageBitmap(),
                        contentDescription = "${avatar.displayName} profile picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val initials = avatar.displayName.split(" ")
                        .filter { it.isNotBlank() }
                        .take(2)
                        .joinToString("") { it.first().uppercase() }
                        .ifBlank { "?" }
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = tokens.accentPrimary,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (avatar.isOnline) tokens.statusOnline else tokens.textMuted)
                    )
                    Text(
                        text = if (avatar.isOnline) "Online In-World" else "Offline / Away",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (avatar.isOnline) tokens.statusOnline else tokens.textMuted,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    if (avatar.isFlying) {
                        LinkpointStatusChip(label = "FLY", color = tokens.accentSecondary)
                    }
                }
                Text(
                    text = "Height ${"%.2f".format(avatar.heightMeters)}m • ${avatar.attachments.size} worn meshes • ${avatar.wearableCount} wearables",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = tokens.textSecondary,
                        fontSize = 10.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "UUID: ${avatar.agentId.toString().take(18)}…",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = tokens.textMuted,
                        fontSize = 9.sp
                    ),
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Tab Switcher: Profile Bio vs Appearance & Attachments
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkpointStatusChip(
                label = "Profile & Bio",
                color = if (activeTab == 0) tokens.accentPrimary else tokens.textMuted,
                onClick = { activeTab = 0 },
                modifier = Modifier.testTag("avatar_tab_profile")
            )
            LinkpointStatusChip(
                label = "Appearance (${avatar.attachments.size})",
                color = if (activeTab == 1) tokens.accentSecondary else tokens.textMuted,
                onClick = { activeTab = 1 },
                modifier = Modifier.testTag("avatar_tab_appearance")
            )
        }

        Spacer(Modifier.height(8.dp))

        if (activeTab == 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 155.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!avatar.profileLoaded) {
                    Text(
                        text = "Streaming profile from grid via AvatarPropertiesRequest…",
                        style = MaterialTheme.typography.bodySmall.copy(color = tokens.accentSecondary)
                    )
                }
                Text(
                    text = "ABOUT",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = tokens.accentPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = avatar.aboutText.ifBlank { "No bio written in profile." },
                    style = MaterialTheme.typography.bodySmall.copy(color = tokens.textPrimary)
                )
                if (avatar.firstLifeText.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "FIRST LIFE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.accentTertiary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = avatar.firstLifeText,
                        style = MaterialTheme.typography.bodySmall.copy(color = tokens.textSecondary)
                    )
                }
                if (avatar.profileUrl.isNotBlank()) {
                    Text(
                        text = "Web: ${avatar.profileUrl}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.accentPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            // Appearance & Worn Meshes Tab
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Baked Texture Sync Status Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    val bakes = listOf(
                        "HEAD" to avatar.bakedHeadTextureId,
                        "UPPER" to avatar.bakedUpperTextureId,
                        "LOWER" to avatar.bakedLowerTextureId,
                        "EYES" to avatar.bakedEyesTextureId,
                        "HAIR" to avatar.bakedHairTextureId
                    )
                    bakes.forEach { (label, texId) ->
                        val loaded = texId != null && client.getCachedTextureBitmap(texId) != null
                        val assigned = texId != null
                        val chipColor = when {
                            loaded -> tokens.statusOnline
                            assigned -> tokens.statusWarning
                            else -> tokens.textMuted
                        }
                        LinkpointStatusChip(
                            label = label,
                            color = chipColor
                        )
                    }
                }

                Text(
                    text = "Shape VisualParams: ${avatar.visualParamsCount} • Wearables: ${avatar.wearableCount}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = tokens.textSecondary,
                        fontSize = 10.sp
                    )
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 115.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (avatar.attachments.isEmpty()) {
                        item {
                            Text(
                                text = "No worn mesh/prim attachments received yet.",
                                style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
                            )
                        }
                    } else {
                        items(avatar.attachments, key = { it.localId }) { att ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(tokens.buttonRadius.coerceAtMost(8.dp)))
                                    .background(tokens.surfaceElevated.copy(alpha = 0.65f))
                                    .border(1.dp, tokens.borderHairline, RoundedCornerShape(tokens.buttonRadius.coerceAtMost(8.dp)))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Checkroom,
                                        contentDescription = null,
                                        tint = if (att.isMesh) tokens.accentPrimary else tokens.accentSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Column {
                                        Text(
                                            text = att.name,
                                            style = MaterialTheme.typography.labelMedium.copy(color = tokens.textPrimary),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${SlBinaryParsers.attachmentPointName(att.attachmentPoint)} • ${SlBinaryParsers.attachmentJointName(att.attachmentPoint)}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = tokens.monoFont,
                                                color = tokens.textMuted,
                                                fontSize = 9.sp
                                            )
                                        )
                                    }
                                }
                                LinkpointStatusChip(
                                    label = if (att.isMesh) "MESH" else "PRIM",
                                    color = if (att.isMesh) tokens.accentPrimary else tokens.accentTertiary
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Action Buttons Row
        if (!avatar.isSelf) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LinkpointActionButton(
                    text = "IM",
                    icon = Icons.Default.ChatBubbleOutline,
                    onClick = {
                        client.onAvatarSelected(null)
                        client.openImWith(avatar.agentId, avatar.displayName)
                    },
                    primary = true,
                    testTag = "avatar_profile_im_button",
                    modifier = Modifier.weight(1f)
                )
                LinkpointActionButton(
                    text = "Pay L$",
                    icon = Icons.Default.Payments,
                    onClick = { showPayDialog = true },
                    primary = false,
                    testTag = "avatar_profile_pay_button",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LinkpointActionButton(
                text = "Focus",
                icon = Icons.Default.CenterFocusStrong,
                onClick = { client.focusCameraOn(avatar.position) },
                primary = avatar.isSelf,
                testTag = "avatar_profile_focus_button",
                modifier = Modifier.weight(1f)
            )
            LinkpointActionButton(
                text = "Refresh",
                icon = Icons.Default.Refresh,
                onClick = { client.requestAvatarRefresh(avatar.agentId) },
                primary = false,
                testTag = "avatar_profile_refresh_button",
                modifier = Modifier.weight(1f)
            )
        }

        if (showPayDialog && !avatar.isSelf) {
            QuickPayModal(
                recipientName = avatar.displayName,
                isObjectPayment = false,
                currentBalance = state.lindenBalance,
                client = client,
                onConfirmPayment = { amount, note ->
                    client.payAvatar(avatar.agentId, avatar.displayName, amount, note)
                },
                onDismiss = { showPayDialog = false }
            )
        }
    }
}
