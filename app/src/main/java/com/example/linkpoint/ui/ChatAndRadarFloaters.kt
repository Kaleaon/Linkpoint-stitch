package com.example.linkpoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.LinkpointFloaterHeader
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.store.ChatRange
import com.example.linkpoint.store.ViewerState

@Composable
fun ChatFloaterContent(
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var draft by remember { mutableStateOf("") }
    var selectedRange by remember { mutableStateOf(ChatRange.SAY) }
    val activeGroup = state.groupChats.firstOrNull { it.sessionId == state.activeGroupSessionId }
    val activeIm = if (activeGroup == null) {
        state.imConversations.firstOrNull { it.sessionId == state.activeImSessionId }
    } else null
    val isLocalChat = activeGroup == null && activeIm == null

    DisposableEffect(activeIm?.sessionId) {
        val currentSessionId = activeIm?.sessionId
        onDispose {
            if (currentSessionId != null) {
                client.onImDraftChanged(currentSessionId, "")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { client.setTextInputFocused(false) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LinkpointFloaterHeader(
            title = when {
                activeGroup != null -> "Group • ${activeGroup.groupName}"
                activeIm != null -> "IM • ${activeIm.peerName}"
                else -> "Local Chat"
            },
            subtitle = when {
                activeGroup != null -> if (activeGroup.isJoined) {
                    "Second Life Group Session • Supports /me emotes"
                } else {
                    "Group Chat Invitation — tap Join to participate"
                }
                activeIm != null -> if (activeIm.isPeerTyping) {
                    "${activeIm.peerName} is typing…"
                } else {
                    "Direct Instant Message • Supports /me emotes"
                }
                else -> "Supports /me emotes & /channel LSL commands (e.g. /1 help)"
            },
            lcarsCode = "01-COM",
            onClose = onClose
        )

        // Session Tabs: Local + Group Chats + Open IMs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LinkpointStatusChip(
                label = "Local (${state.localChat.size})",
                color = if (isLocalChat) tokens.accentPrimary else tokens.textMuted,
                onClick = { client.selectImSession(null) },
                modifier = Modifier.testTag("chat_tab_local")
            )
            state.groupChats.forEach { group ->
                val isSelected = group.sessionId == state.activeGroupSessionId
                val unreadSuffix = if (group.unreadCount > 0) " (${group.unreadCount})" else ""
                val inviteSuffix = if (group.isPendingInvite && !group.isJoined) " • Invite" else ""
                LinkpointStatusChip(
                    label = "# ${group.groupName}$unreadSuffix$inviteSuffix",
                    color = if (isSelected) tokens.accentTertiary else tokens.textMuted,
                    onClick = { client.selectGroupChatSession(group.sessionId) },
                    modifier = Modifier.testTag("chat_tab_group_${group.sessionId}")
                )
            }
            state.imConversations.forEach { im ->
                val isSelected = activeGroup == null && im.sessionId == state.activeImSessionId
                val typingBadge = if (im.isPeerTyping) " • …" else ""
                val unreadBadge = if (im.unreadCount > 0) " (${im.unreadCount})" else ""
                LinkpointStatusChip(
                    label = "${im.peerName}$typingBadge$unreadBadge",
                    color = if (isSelected) tokens.accentSecondary else if (im.isPeerTyping) tokens.statusOnline else tokens.textMuted,
                    onClick = { client.selectImSession(im.sessionId) },
                    modifier = Modifier.testTag("chat_tab_im_${im.sessionId}")
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (activeGroup != null && !activeGroup.isJoined) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = tokens.accentTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Invited to ${activeGroup.groupName}",
                        style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
                    )
                }
                LinkpointStatusChip(
                    label = "Join Session",
                    color = tokens.accentTertiary,
                    onClick = { client.joinGroupChatSession(activeGroup.sessionId) },
                    modifier = Modifier.testTag("chat_group_join_button")
                )
            }
        }

        val messages = when {
            activeGroup != null -> activeGroup.messages
            activeIm != null -> activeIm.messages
            else -> state.localChat
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp, max = 216.dp)
                .clip(RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp)))
                .background(tokens.canvasVoid.copy(alpha = 0.55f))
                .border(1.dp, tokens.borderHairline, RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp)))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = when {
                            activeGroup != null -> "No messages in ${activeGroup.groupName} yet."
                            activeIm != null -> "Start chatting with ${activeIm.peerName}."
                            else -> "No local chat yet. Say hello, /me waves, or /1 command."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
                    )
                }
            }
            items(messages, key = { it.id }) { msg ->
                val speakerColor = when {
                    msg.isFromHud -> tokens.accentTertiary
                    msg.isSystem -> tokens.accentSecondary
                    msg.isEmote -> tokens.accentSecondary
                    else -> tokens.accentPrimary
                }
                val renderedLine = when {
                    msg.isEmote -> msg.formattedLine()
                    tokens.language == DesignLanguage.TERMINAL -> "${msg.speaker}> ${msg.text}"
                    else -> "${msg.speaker}: ${msg.text}"
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = renderedLine,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (tokens.language == DesignLanguage.TERMINAL) tokens.monoFont else tokens.bodyFont,
                            fontStyle = if (msg.isEmote) FontStyle.Italic else FontStyle.Normal,
                            color = if (msg.isSystem || msg.isFromHud || msg.isEmote) speakerColor else tokens.textPrimary
                        )
                    )
                }
            }
        }

        if (activeIm?.isPeerTyping == true) {
            Spacer(Modifier.height(6.dp))
            LinkpointStatusChip(
                label = "${activeIm.peerName} is typing…",
                color = tokens.statusOnline,
                modifier = Modifier.testTag("chat_typing_indicator")
            )
        }

        Spacer(Modifier.height(8.dp))

        if (isLocalChat) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatRange.entries.forEach { range ->
                    LinkpointStatusChip(
                        label = range.label,
                        color = if (selectedRange == range) tokens.accentPrimary else tokens.textMuted,
                        onClick = { selectedRange = range }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { next ->
                    draft = next
                    if (activeIm != null) {
                        client.onImDraftChanged(activeIm.sessionId, next)
                    }
                },
                placeholder = {
                    Text(
                        when {
                            tokens.language == DesignLanguage.TERMINAL -> "avatar@region:~$"
                            activeGroup != null -> "Message ${activeGroup.groupName} or /me..."
                            activeIm != null -> "Message ${activeIm.peerName} or /me..."
                            else -> "Type chat, /me emote, or /1 command..."
                        }
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.accentPrimary,
                    unfocusedBorderColor = tokens.borderHairline,
                    focusedTextColor = tokens.textPrimary,
                    unfocusedTextColor = tokens.textPrimary
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { client.setTextInputFocused(it.isFocused) }
                    .testTag("chat_input_field")
            )
            IconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        when {
                            activeGroup != null -> client.sendGroupChatMessage(activeGroup.sessionId, draft)
                            activeIm != null -> client.sendInstantMessage(activeIm.sessionId, draft)
                            else -> client.sendLocalChat(draft, selectedRange)
                        }
                        draft = ""
                    }
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(tokens.buttonRadius))
                    .background(tokens.accentPrimary)
                    .testTag("send_chat_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send chat", tint = tokens.onAccent)
            }
        }
    }
}

@Composable
fun RadarFloaterContent(
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var activeTab by remember { mutableIntStateOf(0) } // 0 = Nearby, 1 = Friends
    val onlineFriendsCount = remember(state.friends) { state.friends.count { it.isOnline } }
    val sortedFriends = remember(state.friends) {
        state.friends.sortedWith(compareByDescending<com.example.linkpoint.store.FriendEntry> { it.isOnline }.thenBy { it.name })
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LinkpointFloaterHeader(
            title = if (activeTab == 0) "Nearby Radar" else "Friends List",
            subtitle = if (activeTab == 0) {
                "${state.nearbyAvatars.size} residents detected in region"
            } else {
                "$onlineFriendsCount online • ${state.friends.size} Second Life friends"
            },
            lcarsCode = "02-SEN",
            onClose = onClose
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LinkpointStatusChip(
                label = "Nearby (${state.nearbyAvatars.size})",
                color = if (activeTab == 0) tokens.accentPrimary else tokens.textMuted,
                onClick = { activeTab = 0 },
                modifier = Modifier.testTag("radar_tab_nearby")
            )
            LinkpointStatusChip(
                label = "Friends ($onlineFriendsCount/${state.friends.size})",
                color = if (activeTab == 1) tokens.accentSecondary else tokens.textMuted,
                onClick = { activeTab = 1 },
                modifier = Modifier.testTag("radar_tab_friends")
            )
        }

        if (activeTab == 0) {
            if (state.nearbyAvatars.isEmpty()) {
                Text(
                    text = "No other avatars currently within region sensor range.",
                    style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted),
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.nearbyAvatars, key = { it.agentId }) { av ->
                        val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
                        val altSign = if (av.altitudeDelta >= 0) "+" else ""
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(tokens.surfaceElevated.copy(alpha = 0.78f))
                                .border(1.dp, tokens.borderHairline, shape)
                                .clickable { client.onAvatarSelected(av.agentId) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (av.isFlying) Icons.Default.FlightTakeoff else Icons.Default.Person,
                                    contentDescription = null,
                                    tint = if (av.isFlying) tokens.accentSecondary else tokens.accentPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = av.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = tokens.textPrimary
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${"%.1f".format(av.distanceMeters)}m • ${av.bearingDeg}° • ΔZ $altSign${"%.1f".format(av.altitudeDelta)}m",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = tokens.monoFont,
                                            color = tokens.textSecondary
                                        )
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(onClick = { client.focusCameraOn(av.position) }) {
                                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Focus camera", tint = tokens.accentPrimary)
                                }
                                IconButton(onClick = { client.openImWith(av.agentId, av.name) }) {
                                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Open IM", tint = tokens.accentSecondary)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (sortedFriends.isEmpty()) {
                Text(
                    text = "No Second Life friends found yet. Select a nearby resident to offer friendship.",
                    style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted),
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .testTag("radar_friends_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedFriends, key = { it.agentId }) { friend ->
                        val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
                        val nearbyMatch = state.nearbyAvatars.firstOrNull { it.agentId == friend.agentId }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(tokens.surfaceElevated.copy(alpha = 0.78f))
                                .border(1.dp, tokens.borderHairline, shape)
                                .clickable {
                                    if (nearbyMatch != null) client.onAvatarSelected(friend.agentId)
                                    else client.openImWith(friend.agentId, friend.name)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("friend_row_${friend.agentId}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (friend.isOnline) tokens.statusOnline else tokens.textMuted)
                                )
                                Column {
                                    Text(
                                        text = friend.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (friend.isOnline) tokens.textPrimary else tokens.textSecondary
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            append(if (friend.isOnline) "Online" else "Offline")
                                            if (nearbyMatch != null) {
                                                append(" • ${"%.1f".format(nearbyMatch.distanceMeters)}m nearby")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = tokens.monoFont,
                                            color = if (friend.isOnline) tokens.statusOnline else tokens.textMuted
                                        )
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = { client.openImWith(friend.agentId, friend.name) },
                                    modifier = Modifier.testTag("friend_im_${friend.agentId}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ChatBubbleOutline,
                                        contentDescription = "IM ${friend.name}",
                                        tint = tokens.accentSecondary
                                    )
                                }
                                IconButton(
                                    onClick = { client.offerTeleport(friend.agentId, friend.name) },
                                    modifier = Modifier.testTag("friend_lure_${friend.agentId}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Explore,
                                        contentDescription = "Offer Teleport to ${friend.name}",
                                        tint = if (friend.isOnline) tokens.accentPrimary else tokens.textMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
