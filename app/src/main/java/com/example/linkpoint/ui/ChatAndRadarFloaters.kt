package com.example.linkpoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.FlightTakeoff
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.LinkpointFloaterHeader
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.rlv.RlvConstants
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
    val activeIm = state.imConversations.firstOrNull { it.sessionId == state.activeImSessionId }

    DisposableEffect(Unit) {
        onDispose { client.setTextInputFocused(false) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LinkpointFloaterHeader(
            title = if (activeIm == null) "Local Chat" else "IM • ${activeIm.peerName}",
            subtitle = if (activeIm == null) "Supports /channel LSL commands (e.g. /1 help)" else "Direct Instant Message",
            lcarsCode = "01-COM",
            onClose = onClose
        )

        // Session Tabs: Local + Open IMs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LinkpointStatusChip(
                label = "Local (${state.localChat.size})",
                color = if (activeIm == null) tokens.accentPrimary else tokens.textMuted,
                onClick = { client.selectImSession(null) }
            )
            state.imConversations.forEach { im ->
                val isSelected = im.sessionId == state.activeImSessionId
                val badge = if (im.unreadCount > 0) " (${im.unreadCount})" else ""
                LinkpointStatusChip(
                    label = "${im.peerName}$badge",
                    color = if (isSelected) tokens.accentSecondary else tokens.textMuted,
                    onClick = { client.selectImSession(im.sessionId) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        val messages = activeIm?.messages ?: state.localChat
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = 230.dp)
                .clip(RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp)))
                .background(tokens.canvasVoid.copy(alpha = 0.55f))
                .border(1.dp, tokens.borderHairline, RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp)))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = if (activeIm == null) "No local chat yet. Say hello or type /1 command." else "Start chatting with ${activeIm.peerName}.",
                        style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
                    )
                }
            }
            items(messages, key = { it.id }) { msg ->
                val speakerColor = when {
                    msg.isFromHud -> tokens.accentTertiary
                    msg.isSystem -> tokens.accentSecondary
                    else -> tokens.accentPrimary
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (tokens.language == DesignLanguage.TERMINAL) "${msg.speaker}> ${msg.text}"
                        else "${msg.speaker}: ${msg.text}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (tokens.language == DesignLanguage.TERMINAL) tokens.monoFont else tokens.bodyFont,
                            color = if (msg.isSystem || msg.isFromHud) speakerColor else tokens.textPrimary
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (activeIm == null) {
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
                onValueChange = { draft = it },
                placeholder = {
                    Text(
                        if (tokens.language == DesignLanguage.TERMINAL) "avatar@region:~$"
                        else if (activeIm == null) "Type chat or /1 command..."
                        else "Message ${activeIm.peerName}..."
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
                        if (activeIm == null) client.sendLocalChat(draft, selectedRange)
                        else client.sendInstantMessage(activeIm.sessionId, draft)
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
    Column(modifier = Modifier.fillMaxWidth()) {
        LinkpointFloaterHeader(
            title = "Nearby Radar",
            subtitle = "${state.nearbyAvatars.size} residents detected in region",
            lcarsCode = "02-SEN",
            onClose = onClose
        )

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
                    .heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.nearbyAvatars, key = { it.agentId }) { av ->
                    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
                    val altSign = if (av.altitudeDelta >= 0) "+" else ""
                    val shownName = if (state.rlvSnapshot.showNamesRestricted) RlvConstants.REDACTED_TEXT else av.name
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
                                    text = shownName,
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
                            IconButton(onClick = { client.openImWith(av.agentId, shownName) }) {
                                Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Open IM", tint = tokens.accentSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
