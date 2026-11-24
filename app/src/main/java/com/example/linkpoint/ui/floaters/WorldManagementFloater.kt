package com.example.linkpoint.ui.floaters

import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.LinkpointActionButton
import com.example.linkpoint.designsystem.LinkpointFloaterHeader
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.management.DirectoryCategory
import com.example.linkpoint.management.MuteType
import com.example.linkpoint.management.ParcelReturnType
import com.example.linkpoint.store.ViewerState
import java.util.UUID

/**
 * Phase 10 World Management Floater (`04-WLD`) uniting:
 * 1. Directory Search (`DirFindQuery` / `DirPlacesQuery` across People, Places, Groups, Events)
 * 2. Group Info, Role Titles & Notices (`ActivateGroup`, `GroupTitleUpdate`, `IM_GROUP_NOTICE`)
 * 3. Parcel Management (`ParcelProperties`, `ParcelInfoReply`, `ParcelReturnObjects`, `ParcelBuyPass`)
 * 4. Outfit Saving & Notecard Editing (`CreateInventoryFolder` + `LinkInventoryItem`, `UpdateNotecardAgentInventory`)
 * 5. Server-Synced Mute List (`MuteListRequest`, `UpdateMuteListEntry`, `RemoveMuteListEntry`)
 */
@Composable
fun WorldManagementFloaterContent(
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val wm = state.worldManagement
    var activeTab by remember { mutableIntStateOf(0) }
    val bodyScroll = remember(activeTab) { ScrollState(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 390.dp)
            .testTag("world_management_floater"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LinkpointFloaterHeader(
            title = "World, Groups & Land",
            subtitle = "${wm.currentParcel.name} • L$${state.lindenBalance} • ${wm.lastManagementStatus}",
            lcarsCode = "04-WLD",
            onClose = onClose
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val tabs = listOf(
                "Search" to "world_tab_search",
                "Groups (${wm.groups.size})" to "world_tab_groups",
                "Land" to "world_tab_land",
                "Outfits & Notes" to "world_tab_outfits_notes",
                "Mute (${wm.muteList.size})" to "world_tab_mute"
            )
            tabs.forEachIndexed { idx, (label, tag) ->
                LinkpointStatusChip(
                    label = label,
                    color = if (activeTab == idx) tokens.accentPrimary else tokens.textMuted,
                    onClick = { activeTab = idx },
                    modifier = Modifier.testTag(tag)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(bodyScroll),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (activeTab) {
                0 -> DirectorySearchSection(state = state, client = client)
                1 -> GroupsManagementSection(state = state, client = client)
                2 -> ParcelManagementSection(state = state, client = client)
                3 -> OutfitsAndNotecardsSection(state = state, client = client)
                4 -> MuteListSection(state = state, client = client)
            }
        }
    }
}

@Composable
private fun DirectorySearchSection(
    state: ViewerState,
    client: ViewerClient
) {
    val tokens = LocalLinkpointDesign.current
    val wm = state.worldManagement
    var queryText by remember(wm.searchQuery) { mutableStateOf(wm.searchQuery) }
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DirectoryCategory.entries.forEach { cat ->
            val selected = wm.searchCategory == cat
            LinkpointStatusChip(
                label = cat.label,
                color = if (selected) tokens.accentSecondary else tokens.textMuted,
                onClick = { client.setSearchCategory(cat) },
                modifier = Modifier.testTag("dir_cat_${cat.name.lowercase()}")
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = queryText,
            onValueChange = { queryText = it },
            placeholder = { Text("Search ${wm.searchCategory.label.lowercase()}...", color = tokens.textMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = tokens.textPrimary,
                unfocusedTextColor = tokens.textPrimary,
                focusedBorderColor = tokens.accentPrimary,
                unfocusedBorderColor = tokens.borderHairline
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("dir_search_input")
        )
        LinkpointActionButton(
            text = "Find",
            icon = Icons.Default.Search,
            onClick = { client.searchDirectory(queryText, wm.searchCategory) },
            primary = true,
            testTag = "dir_search_button"
        )
    }

    if (wm.isSearching) {
        LinearProgressIndicator(
            color = tokens.accentPrimary,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (wm.searchResults.isEmpty()) {
        Text(
            text = "No ${wm.searchCategory.label.lowercase()} matched. Enter a query and tap Find.",
            style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            wm.searchResults.forEach { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(tokens.surfaceElevated.copy(alpha = 0.78f))
                        .border(1.dp, tokens.borderHairline, cardShape)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.textPrimary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        LinkpointStatusChip(
                            label = item.metricLabel,
                            color = tokens.accentTertiary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (item.category) {
                            DirectoryCategory.PEOPLE -> {
                                LinkpointStatusChip(
                                    label = "IM",
                                    color = tokens.accentPrimary,
                                    onClick = { client.openImWith(item.targetUuid, item.title) }
                                )
                            }
                            DirectoryCategory.PLACES, DirectoryCategory.EVENTS -> {
                                LinkpointStatusChip(
                                    label = "Teleport",
                                    color = tokens.accentPrimary,
                                    onClick = {
                                        client.teleportToRegion(
                                            regionName = item.regionName.ifBlank { item.title },
                                            x = 128,
                                            y = 128,
                                            z = 25
                                        )
                                    }
                                )
                            }
                            DirectoryCategory.GROUPS -> {
                                LinkpointStatusChip(
                                    label = "Inspect",
                                    color = tokens.accentPrimary,
                                    onClick = { client.inspectGroup(item.targetUuid) }
                                )
                            }
                        }
                        if (item.forSale) {
                            LinkpointStatusChip(label = "For Sale", color = tokens.statusWarning)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupsManagementSection(
    state: ViewerState,
    client: ViewerClient
) {
    val tokens = LocalLinkpointDesign.current
    val wm = state.worldManagement
    val selectedGroup = wm.selectedGroup
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
    var noticeSubject by remember { mutableStateOf("") }
    var noticeBody by remember { mutableStateOf("") }

    Text(
        text = "Active Tag: ${wm.activeGroup?.let { "${it.activeTitle} (${it.name})" } ?: "None"}",
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = tokens.monoFont,
            color = tokens.accentPrimary,
            fontWeight = FontWeight.Bold
        ),
        modifier = Modifier.testTag("active_group_readout")
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        wm.groups.forEach { grp ->
            val isActive = grp.groupId == wm.activeGroupId
            val isSelected = grp.groupId == selectedGroup?.groupId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(
                        if (isSelected) tokens.accentPrimary.copy(alpha = 0.16f)
                        else tokens.surfaceElevated.copy(alpha = 0.75f)
                    )
                    .border(
                        width = if (isActive) 1.5.dp else 1.dp,
                        color = if (isActive) tokens.accentPrimary else tokens.borderHairline,
                        shape = cardShape
                    )
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = grp.name,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = tokens.textPrimary
                        )
                    )
                    Text(
                        text = "Title: ${grp.activeTitle}",
                        style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinkpointStatusChip(
                        label = "Inspect",
                        color = tokens.accentSecondary,
                        onClick = { client.inspectGroup(grp.groupId) }
                    )
                    LinkpointStatusChip(
                        label = if (isActive) "Active" else "Activate",
                        color = if (isActive) tokens.statusOnline else tokens.accentPrimary,
                        onClick = { client.activateGroup(grp.groupId) },
                        modifier = Modifier.testTag("activate_group_${grp.name.take(8).lowercase().replace(' ', '_')}")
                    )
                }
            }
        }
    }

    if (selectedGroup != null) {
        val profile = wm.groupProfiles[selectedGroup.groupId]
        val titles = wm.groupTitles[selectedGroup.groupId].orEmpty()
        val roles = wm.groupRoles[selectedGroup.groupId].orEmpty()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(tokens.surfaceElevated.copy(alpha = 0.72f))
                .border(1.dp, tokens.borderHairline, cardShape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${selectedGroup.name} Charter & Roles",
                style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary)
            )
            if (profile != null) {
                Text(
                    text = profile.charter,
                    style = MaterialTheme.typography.bodySmall.copy(color = tokens.textSecondary)
                )
                Text(
                    text = "${profile.memberCount} members • ${profile.roleCount} roles • Fee L$${profile.membershipFee}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = tokens.accentTertiary
                    )
                )
            }
            if (titles.isNotEmpty()) {
                Text(
                    text = "Role Title Tag:",
                    style = MaterialTheme.typography.labelSmall.copy(color = tokens.textMuted)
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    titles.forEach { t ->
                        LinkpointStatusChip(
                            label = t.title,
                            color = if (t.selected) tokens.statusOnline else tokens.accentSecondary,
                            onClick = { client.selectGroupTitle(selectedGroup.groupId, t.roleId) }
                        )
                    }
                }
            }
            roles.forEach { role ->
                Text(
                    text = "• ${role.name} (${role.title}) — ${role.memberCount} members",
                    style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
                )
            }
        }

        if (selectedGroup.canSendNotices) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(tokens.surfaceElevated.copy(alpha = 0.72f))
                    .border(1.dp, tokens.borderHairline, cardShape)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Send Group Notice (${selectedGroup.name})",
                    style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary)
                )
                OutlinedTextField(
                    value = noticeSubject,
                    onValueChange = { noticeSubject = it },
                    placeholder = { Text("Notice subject...", color = tokens.textMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = tokens.textPrimary,
                        unfocusedTextColor = tokens.textPrimary,
                        focusedBorderColor = tokens.accentPrimary,
                        unfocusedBorderColor = tokens.borderHairline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_notice_subject_input")
                )
                OutlinedTextField(
                    value = noticeBody,
                    onValueChange = { noticeBody = it },
                    placeholder = { Text("Notice announcement body...", color = tokens.textMuted) },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = tokens.textPrimary,
                        unfocusedTextColor = tokens.textPrimary,
                        focusedBorderColor = tokens.accentPrimary,
                        unfocusedBorderColor = tokens.borderHairline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_notice_body_input")
                )
                LinkpointActionButton(
                    text = "Send Group Notice",
                    icon = Icons.AutoMirrored.Filled.Send,
                    onClick = {
                        client.sendGroupNotice(selectedGroup.groupId, noticeSubject, noticeBody)
                        noticeSubject = ""
                        noticeBody = ""
                    },
                    primary = true,
                    testTag = "send_group_notice_button",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (wm.groupNotices.isNotEmpty()) {
        Text("Recent Group Notices", style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary))
        wm.groupNotices.take(4).forEach { notice ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(tokens.surfaceElevated.copy(alpha = 0.68f))
                    .border(1.dp, tokens.borderHairline, cardShape)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "${notice.subject} (${notice.groupName})",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = tokens.accentPrimary
                    )
                )
                Text(
                    text = notice.message,
                    style = MaterialTheme.typography.bodySmall.copy(color = tokens.textPrimary)
                )
                Text(
                    text = "From ${notice.senderName} • ${notice.timestampLabel}",
                    style = MaterialTheme.typography.labelSmall.copy(color = tokens.textMuted)
                )
            }
        }
    }
}

@Composable
private fun ParcelManagementSection(
    state: ViewerState,
    client: ViewerClient
) {
    val tokens = LocalLinkpointDesign.current
    val parcel = state.worldManagement.currentParcel
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.surfaceElevated.copy(alpha = 0.78f))
            .border(1.dp, tokens.borderHairline, cardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = parcel.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary
                    )
                )
                Text(
                    text = "${parcel.simName} • ${parcel.areaSqm} m² • ${parcel.maturity}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = tokens.accentSecondary
                    )
                )
            }
            LinkpointStatusChip(
                label = "Refresh",
                color = tokens.accentPrimary,
                onClick = client::refreshParcelDetails
            )
        }
        if (parcel.description.isNotBlank()) {
            Text(
                text = parcel.description,
                style = MaterialTheme.typography.bodySmall.copy(color = tokens.textSecondary)
            )
        }
        Text(
            text = "Owner: ${parcel.ownerName} • Group: ${parcel.groupName}",
            style = MaterialTheme.typography.labelSmall.copy(color = tokens.textPrimary)
        )
        Text(
            text = "Land Impact: ${parcel.totalPrims} / ${parcel.maxPrims} (${parcel.availablePrimHeadroom} free) — Own ${parcel.ownerPrims}, Group ${parcel.groupPrims}, Others ${parcel.otherPrims}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.monoFont,
                color = tokens.accentTertiary
            ),
            modifier = Modifier.testTag("parcel_prim_summary")
        )

        val progress = (parcel.totalPrims.toFloat() / parcel.maxPrims.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            color = tokens.accentPrimary,
            trackColor = tokens.borderHairline,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LinkpointStatusChip(
                label = if (parcel.allowFly) "Fly: ON" else "Fly: OFF",
                color = if (parcel.allowFly) tokens.statusOnline else tokens.statusError
            )
            LinkpointStatusChip(
                label = if (parcel.allowScripts) "Scripts: All" else if (parcel.allowGroupScripts) "Scripts: Group" else "Scripts: OFF",
                color = if (parcel.allowScripts || parcel.allowGroupScripts) tokens.statusOnline else tokens.statusWarning
            )
            LinkpointStatusChip(
                label = if (parcel.allowBuild) "Build: All" else if (parcel.allowGroupBuild) "Build: Group" else "Build: OFF",
                color = if (parcel.allowBuild || parcel.allowGroupBuild) tokens.statusOnline else tokens.textMuted
            )
            if (parcel.restrictPush) {
                LinkpointStatusChip(label = "No Push", color = tokens.accentSecondary)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LinkpointActionButton(
            text = "Return Own",
            onClick = { client.returnParcelObjects(ParcelReturnType.OWNER) },
            primary = false,
            testTag = "parcel_return_own",
            modifier = Modifier.weight(1f)
        )
        LinkpointActionButton(
            text = "Return Group",
            onClick = { client.returnParcelObjects(ParcelReturnType.GROUP) },
            primary = false,
            testTag = "parcel_return_group",
            modifier = Modifier.weight(1f)
        )
        LinkpointActionButton(
            text = "Return Others",
            onClick = { client.returnParcelObjects(ParcelReturnType.OTHER) },
            primary = true,
            testTag = "parcel_return_others",
            modifier = Modifier.weight(1f)
        )
    }

    if (parcel.canBuyPass) {
        LinkpointActionButton(
            text = "Buy Parcel Pass (L$${parcel.passPrice} / ${parcel.passHours}h)",
            icon = Icons.Default.Landscape,
            onClick = client::buyParcelPass,
            primary = false,
            testTag = "parcel_buy_pass_button",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OutfitsAndNotecardsSection(
    state: ViewerState,
    client: ViewerClient
) {
    val tokens = LocalLinkpointDesign.current
    val wm = state.worldManagement
    val wornCount = state.inventoryEntries.count { !it.isFolder && it.isWorn }
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))

    var outfitName by remember { mutableStateOf("") }
    var newNoteTitle by remember { mutableStateOf("") }

    val activeNote = wm.notecards.firstOrNull { it.itemId == wm.activeNotecardId } ?: wm.notecards.firstOrNull()
    var noteDraft by remember(activeNote?.itemId) { mutableStateOf(activeNote?.bodyText.orEmpty()) }
    LaunchedEffect(activeNote?.itemId, activeNote?.assetId) {
        if (activeNote != null) {
            noteDraft = activeNote.bodyText
        }
    }

    // Save Current Outfit card
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.surfaceElevated.copy(alpha = 0.78f))
            .border(1.dp, tokens.borderHairline, cardShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Appearance Outfits ($wornCount worn attachments & wearables)",
            style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = outfitName,
                onValueChange = { outfitName = it },
                placeholder = { Text("Outfit folder name...", color = tokens.textMuted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = tokens.textPrimary,
                    unfocusedTextColor = tokens.textPrimary,
                    focusedBorderColor = tokens.accentPrimary,
                    unfocusedBorderColor = tokens.borderHairline
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("save_outfit_name_input")
            )
            LinkpointActionButton(
                text = "Save Outfit",
                icon = Icons.Default.Checkroom,
                onClick = {
                    client.saveCurrentOutfit(outfitName)
                    outfitName = ""
                },
                primary = true,
                testTag = "save_outfit_button"
            )
        }
        wm.savedOutfits.take(4).forEach { outfit ->
            Text(
                text = "• ${outfit.name} (${outfit.createdAtLabel})",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    color = tokens.accentSecondary
                )
            )
        }
    }

    // Notecard Editor (UpdateNotecardAgentInventory)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.surfaceElevated.copy(alpha = 0.78f))
            .border(1.dp, tokens.borderHairline, cardShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Notecard Editor (Linden text version 2)",
            style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary)
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            wm.notecards.forEach { note ->
                val selected = note.itemId == activeNote?.itemId
                LinkpointStatusChip(
                    label = note.title,
                    color = if (selected) tokens.accentPrimary else tokens.textMuted,
                    onClick = { client.selectNotecard(note.itemId) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newNoteTitle,
                onValueChange = { newNoteTitle = it },
                placeholder = { Text("New notecard title...", color = tokens.textMuted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = tokens.textPrimary,
                    unfocusedTextColor = tokens.textPrimary,
                    focusedBorderColor = tokens.accentPrimary,
                    unfocusedBorderColor = tokens.borderHairline
                ),
                modifier = Modifier.weight(1f)
            )
            LinkpointActionButton(
                text = "New",
                icon = Icons.Default.Add,
                onClick = {
                    client.createNewNotecard(newNoteTitle)
                    newNoteTitle = ""
                },
                primary = false,
                testTag = "create_notecard_button"
            )
        }

        if (activeNote != null) {
            Text(
                text = "Editing: ${activeNote.title} • ${activeNote.lastSavedStatus}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    color = tokens.accentTertiary
                )
            )
            OutlinedTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it },
                minLines = 3,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = tokens.textPrimary,
                    unfocusedTextColor = tokens.textPrimary,
                    focusedBorderColor = tokens.accentPrimary,
                    unfocusedBorderColor = tokens.borderHairline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notecard_body_editor")
            )
            LinkpointActionButton(
                text = if (wm.isSavingNotecard) "Uploading..." else "Save Notecard",
                icon = Icons.Default.Save,
                onClick = { client.saveNotecard(activeNote.itemId, noteDraft) },
                primary = true,
                testTag = "save_notecard_button",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MuteListSection(
    state: ViewerState,
    client: ViewerClient
) {
    val tokens = LocalLinkpointDesign.current
    val wm = state.worldManagement
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))

    var muteName by remember { mutableStateOf("") }
    var muteType by remember { mutableStateOf(MuteType.AGENT) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Server-Synced Mute List (${wm.muteList.size})",
            style = MaterialTheme.typography.titleSmall.copy(color = tokens.textPrimary)
        )
        LinkpointStatusChip(
            label = "Sync Server",
            color = tokens.accentPrimary,
            onClick = client::syncMuteList,
            modifier = Modifier.testTag("sync_mute_list_button")
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(MuteType.AGENT, MuteType.OBJECT, MuteType.BY_NAME).forEach { t ->
            LinkpointStatusChip(
                label = t.label,
                color = if (muteType == t) tokens.accentSecondary else tokens.textMuted,
                onClick = { muteType = t }
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = muteName,
            onValueChange = { muteName = it },
            placeholder = { Text("Resident or object name to mute...", color = tokens.textMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = tokens.textPrimary,
                unfocusedTextColor = tokens.textPrimary,
                focusedBorderColor = tokens.accentPrimary,
                unfocusedBorderColor = tokens.borderHairline
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("mute_name_input")
        )
        LinkpointActionButton(
            text = "Mute",
            icon = Icons.Default.Block,
            onClick = {
                val id = if (muteType == MuteType.BY_NAME) UUID(0L, 0L) else UUID.nameUUIDFromBytes(muteName.trim().toByteArray())
                client.muteEntity(id, muteName, muteType)
                muteName = ""
            },
            primary = true,
            testTag = "add_mute_button"
        )
    }

    if (wm.muteList.isEmpty()) {
        Text(
            text = "No muted residents or objects.",
            style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            wm.muteList.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(tokens.surfaceElevated.copy(alpha = 0.75f))
                        .border(1.dp, tokens.borderHairline, cardShape)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = tokens.textPrimary
                            )
                        )
                        Text(
                            text = "${entry.type.label} • ${entry.muteId.toString().take(8)}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = tokens.monoFont,
                                color = tokens.textMuted
                            )
                        )
                    }
                    LinkpointStatusChip(
                        label = "Unmute",
                        color = tokens.statusError,
                        onClick = { client.unmuteEntity(entry.muteId, entry.name) },
                        modifier = Modifier.testTag("unmute_${entry.name.take(8).lowercase().replace(' ', '_')}")
                    )
                }
            }
        }
    }
}
