package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.linkpoint.client.ViewerSessionViewModel
import com.example.linkpoint.designsystem.LinkpointTheme
import com.example.linkpoint.management.Ais3OutfitProtocol
import com.example.linkpoint.management.DirectoryCategory
import com.example.linkpoint.management.DirectorySearchResult
import com.example.linkpoint.management.GroupMembershipInfo
import com.example.linkpoint.management.GroupNoticeRecord
import com.example.linkpoint.management.GroupPowers
import com.example.linkpoint.management.GroupProfileInfo
import com.example.linkpoint.management.GroupRoleInfo
import com.example.linkpoint.management.GroupTitleInfo
import com.example.linkpoint.management.LindenNotecardCodec
import com.example.linkpoint.management.MuteEntry
import com.example.linkpoint.management.MuteFlags
import com.example.linkpoint.management.MuteType
import com.example.linkpoint.management.NotecardCapProtocol
import com.example.linkpoint.management.OutfitFolderPlanner
import com.example.linkpoint.management.OutfitInventoryConstants
import com.example.linkpoint.management.OutfitSaveCoordinator
import com.example.linkpoint.management.ParcelFlags
import com.example.linkpoint.management.ParcelManagementInfo
import com.example.linkpoint.management.ParcelReturnType
import com.example.linkpoint.management.PayPriceInfo
import com.example.linkpoint.management.WorldManagementPackets
import com.example.linkpoint.protocol.CapabilityManager
import com.example.linkpoint.protocol.LlsdCodec
import com.example.linkpoint.protocol.LlsdValue
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.store.InventoryEntry
import com.example.linkpoint.ui.ObjectInspectorContent
import com.example.linkpoint.ui.floaters.WorldManagementFloaterContent
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase10WorldManagementTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun wrapLe(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `frames and round-trips DirFindQuery, DirPlacesQuery, and People Places Groups Events replies`() {
        val agentId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val queryId = UUID.randomUUID()

        val findBytes = WorldManagementPackets.buildDirFindQuery(
            agentId = agentId,
            sessionId = sessionId,
            queryId = queryId,
            queryText = "Harbor Jazz",
            queryFlags = DirectoryCategory.EVENTS.defaultFlags,
            queryStart = 0
        )
        val framedFind = WorldManagementPackets.framePacket(11, true, WorldManagementPackets.MSG_DIR_FIND_QUERY, findBytes)
        assertTrue((framedFind[0].toInt() and LludpPackets.FLAG_ZEROCODED) != 0)
        val decodedFindPkt = LludpPackets.decodePacket(framedFind, framedFind.size)!!
        assertEquals(WorldManagementPackets.MSG_DIR_FIND_QUERY, decodedFindPkt.messageId)
        val parsedFind = WorldManagementPackets.parseDirFindQuery(decodedFindPkt.payload)!!
        assertEquals(agentId, parsedFind.agentId)
        assertEquals(queryId, parsedFind.queryId)
        assertEquals("Harbor Jazz", parsedFind.queryText)
        assertEquals(DirectoryCategory.EVENTS.defaultFlags, parsedFind.queryFlags)

        val placesQueryBytes = WorldManagementPackets.buildDirPlacesQuery(
            agentId = agentId,
            sessionId = sessionId,
            queryId = queryId,
            queryText = "Marina",
            simName = "Harbor Reach"
        )
        val parsedPlacesQ = WorldManagementPackets.parseDirPlacesQuery(wrapLe(placesQueryBytes))!!
        assertEquals("Marina", parsedPlacesQ.queryText)
        assertEquals("Harbor Reach", parsedPlacesQ.simName)

        // People Reply
        val personId = UUID.randomUUID()
        val peopleReply = WorldManagementPackets.buildDirPeopleReply(
            agentId = agentId,
            queryId = queryId,
            results = listOf(
                DirectorySearchResult(
                    id = personId.toString(),
                    targetUuid = personId,
                    category = DirectoryCategory.PEOPLE,
                    title = "Aria Vance",
                    subtitle = "Linkpoint Pioneers",
                    metricLabel = "Online",
                    online = true
                )
            )
        )
        val (repQueryId, parsedPeople) = WorldManagementPackets.parseDirPeopleReply(wrapLe(peopleReply))
        assertEquals(queryId, repQueryId)
        assertEquals(1, parsedPeople.size)
        assertEquals("Aria Vance", parsedPeople[0].title)
        assertTrue(parsedPeople[0].online)

        // Places Reply
        val placeId = UUID.randomUUID()
        val placesReply = WorldManagementPackets.buildDirPlacesReply(
            agentId = agentId,
            queryId = queryId,
            results = listOf(
                DirectorySearchResult(
                    id = placeId.toString(),
                    targetUuid = placeId,
                    category = DirectoryCategory.PLACES,
                    title = "Harbor Marina",
                    subtitle = "Harbor Reach",
                    metricLabel = "890 dwell",
                    forSale = true,
                    dwell = 890f
                )
            )
        )
        val (_, parsedPlaces) = WorldManagementPackets.parseDirPlacesReply(wrapLe(placesReply))
        assertEquals(1, parsedPlaces.size)
        assertEquals("Harbor Marina", parsedPlaces[0].title)
        assertTrue(parsedPlaces[0].forSale)
        assertEquals(890f, parsedPlaces[0].dwell, 0.1f)

        // Groups Reply
        val groupId = UUID.randomUUID()
        val groupsReply = WorldManagementPackets.buildDirGroupsReply(
            agentId = agentId,
            queryId = queryId,
            results = listOf(
                DirectorySearchResult(
                    id = groupId.toString(),
                    targetUuid = groupId,
                    category = DirectoryCategory.GROUPS,
                    title = "Linkpoint Pioneers",
                    subtitle = "348 members",
                    metricLabel = "348 mbrs",
                    memberCount = 348
                )
            )
        )
        val (_, parsedGroups) = WorldManagementPackets.parseDirGroupsReply(wrapLe(groupsReply))
        assertEquals(1, parsedGroups.size)
        assertEquals("Linkpoint Pioneers", parsedGroups[0].title)
        assertEquals(348, parsedGroups[0].memberCount)

        // Events Reply
        val eventsReply = WorldManagementPackets.buildDirEventsReply(
            agentId = agentId,
            queryId = queryId,
            results = listOf(
                DirectorySearchResult(
                    id = "event-4102",
                    targetUuid = agentId,
                    category = DirectoryCategory.EVENTS,
                    title = "Sunset Jazz",
                    subtitle = "Today 18:00 SLT",
                    metricLabel = "Moderate",
                    eventId = 4102,
                    eventDate = "Today 18:00 SLT",
                    maturity = "Moderate"
                )
            )
        )
        val (_, parsedEvents) = WorldManagementPackets.parseDirEventsReply(wrapLe(eventsReply))
        assertEquals(1, parsedEvents.size)
        assertEquals("Sunset Jazz", parsedEvents[0].title)
        assertEquals(4102, parsedEvents[0].eventId)
        assertEquals("Moderate", parsedEvents[0].maturity)
    }

    @Test
    fun `round-trips Group activation, GroupProfile, GroupTitles, GroupRoles, and GroupNotice IM`() {
        val agentId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val roleId = UUID.randomUUID()

        val actBytes = WorldManagementPackets.buildActivateGroup(agentId, sessionId, groupId)
        val parsedAct = WorldManagementPackets.parseActivateGroup(wrapLe(actBytes))!!
        assertEquals(groupId, parsedAct.groupId)

        val grpUpdateBytes = WorldManagementPackets.buildAgentGroupDataUpdate(
            agentId = agentId,
            groups = listOf(
                GroupMembershipInfo(
                    groupId = groupId,
                    name = "Linkpoint Pioneers",
                    powers = GroupPowers.GP_NOTICES_SEND or GroupPowers.GP_NOTICES_RECEIVE,
                    contribution = 512
                )
            )
        )
        val parsedMemberships = WorldManagementPackets.parseAgentGroupDataUpdate(wrapLe(grpUpdateBytes))
        assertEquals(1, parsedMemberships.size)
        assertEquals("Linkpoint Pioneers", parsedMemberships[0].name)
        assertTrue(parsedMemberships[0].canSendNotices)

        val profileBytes = WorldManagementPackets.buildGroupProfileReply(
            agentId = agentId,
            profile = GroupProfileInfo(
                groupId = groupId,
                name = "Linkpoint Pioneers",
                charter = "Mobile viewer creators & testers",
                memberTitle = "Architect",
                powersMask = GroupPowers.GP_NOTICES_SEND,
                membershipFee = 25,
                memberCount = 348,
                roleCount = 3
            )
        )
        val parsedProfile = WorldManagementPackets.parseGroupProfileReply(wrapLe(profileBytes))!!
        assertEquals("Mobile viewer creators & testers", parsedProfile.charter)
        assertEquals(348, parsedProfile.memberCount)

        val titlesBytes = WorldManagementPackets.buildGroupTitlesReply(
            agentId = agentId,
            groupId = groupId,
            requestId = UUID.randomUUID(),
            titles = listOf(GroupTitleInfo(roleId, "Grid Architect", selected = true))
        )
        val (titleGrp, parsedTitles) = WorldManagementPackets.parseGroupTitlesReply(wrapLe(titlesBytes))
        assertEquals(groupId, titleGrp)
        assertEquals("Grid Architect", parsedTitles.single().title)
        assertTrue(parsedTitles.single().selected)

        val titleUpdateBytes = WorldManagementPackets.buildGroupTitleUpdate(agentId, sessionId, groupId, roleId)
        val parsedTitleUpdate = WorldManagementPackets.parseGroupTitleUpdate(wrapLe(titleUpdateBytes))!!
        assertEquals(roleId, parsedTitleUpdate.titleRoleId)

        val rolesBytes = WorldManagementPackets.buildGroupRoleDataReply(
            agentId = agentId,
            groupId = groupId,
            requestId = UUID.randomUUID(),
            roles = listOf(GroupRoleInfo(roleId, "Architects", "Grid Architect", "Full build rights", GroupPowers.GP_NOTICES_SEND, 14))
        )
        val (_, parsedRoles) = WorldManagementPackets.parseGroupRoleDataReply(wrapLe(rolesBytes))
        assertEquals(14, parsedRoles.single().memberCount)

        val noticeIm = WorldManagementPackets.buildGroupNoticePacket(
            agentId = agentId,
            sessionId = sessionId,
            groupId = groupId,
            fromAgentName = "Aria Vance",
            subject = "Server Deploy",
            message = "Rolling restart at 08:00 SLT"
        )
        val noticeAscii = String(noticeIm, Charsets.UTF_8)
        assertTrue(noticeAscii.contains("Server Deploy|Rolling restart at 08:00 SLT"))
    }

    @Test
    fun `round-trips ParcelProperties, ParcelInfoReply, ParcelReturnObjects, and ParcelBuyPass`() {
        val agentId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val parcel = ParcelManagementInfo(
            parcelId = UUID.randomUUID(),
            localId = 17,
            name = "Harbor Pier Slip #4",
            description = "Deep water mooring",
            ownerId = agentId,
            groupId = UUID.randomUUID(),
            isGroupOwned = true,
            maturity = "Moderate",
            areaSqm = 2048,
            maxPrims = 703,
            totalPrims = 210,
            ownerPrims = 150,
            groupPrims = 45,
            otherPrims = 15,
            parcelFlags = ParcelFlags.PF_ALLOW_FLY or ParcelFlags.PF_ALLOW_OTHER_SCRIPTS,
            passPrice = 50,
            passHours = 4.0f,
            simName = "Harbor Reach",
            dwell = 945f
        )

        val propsBytes = WorldManagementPackets.buildParcelProperties(parcel)
        val parsedProps = WorldManagementPackets.parseParcelProperties(wrapLe(propsBytes))!!
        assertEquals(17, parsedProps.localId)
        assertEquals("Harbor Pier Slip #4", parsedProps.name)
        assertEquals(2048, parsedProps.areaSqm)
        assertEquals(210, parsedProps.totalPrims)
        assertEquals(703, parsedProps.maxPrims)
        assertEquals(50, parsedProps.passPrice)
        assertTrue(parsedProps.allowFly)
        assertTrue(parsedProps.allowScripts)

        val infoBytes = WorldManagementPackets.buildParcelInfoReply(agentId, parcel)
        val parsedInfo = WorldManagementPackets.parseParcelInfoReply(wrapLe(infoBytes), parsedProps)!!
        assertEquals("Moderate", parsedInfo.maturity)
        assertEquals("Harbor Reach", parsedInfo.simName)
        assertEquals(945f, parsedInfo.dwell, 0.1f)

        val retBytes = WorldManagementPackets.buildParcelReturnObjects(agentId, sessionId, 17, ParcelReturnType.OTHER)
        val parsedRet = WorldManagementPackets.parseParcelReturnObjects(wrapLe(retBytes))!!
        assertEquals(17, parsedRet.localId)
        assertEquals(ParcelReturnType.OTHER.wireValue, parsedRet.returnType)

        val passBytes = WorldManagementPackets.buildParcelBuyPass(agentId, sessionId, 17)
        val parsedPass = WorldManagementPackets.parseParcelBuyPass(wrapLe(passBytes))!!
        assertEquals(17, parsedPass.localId)
    }

    @Test
    fun `round-trips MuteList packets, Vendor PayPriceReply button rules, and MoneyTransferRequest`() {
        val agentId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val spammerId = UUID.randomUUID()

        val reqBytes = WorldManagementPackets.buildMuteListRequest(agentId, sessionId, muteCrc = 0)
        assertEquals(0, WorldManagementPackets.parseMuteListRequest(wrapLe(reqBytes))!!.muteCrc)

        val entry = MuteEntry(spammerId, "Griefer Bot", MuteType.OBJECT, MuteFlags.PARTICLES)
        val updBytes = WorldManagementPackets.buildUpdateMuteListEntry(agentId, sessionId, entry)
        val parsedUpd = WorldManagementPackets.parseUpdateMuteListEntry(wrapLe(updBytes))!!
        assertEquals(spammerId, parsedUpd.entry.muteId)
        assertEquals("Griefer Bot", parsedUpd.entry.name)
        assertEquals(MuteType.OBJECT, parsedUpd.entry.type)
        assertEquals(MuteFlags.PARTICLES, parsedUpd.entry.flags)

        val remBytes = WorldManagementPackets.buildRemoveMuteListEntry(agentId, sessionId, spammerId, "Griefer Bot")
        val parsedRem = WorldManagementPackets.parseRemoveMuteListEntry(wrapLe(remBytes))!!
        assertEquals("Griefer Bot", parsedRem.muteName)

        val cacheEntries = WorldManagementPackets.parseMuteListCacheText(
            """
            1 $spammerId Griefer Resident|0
            2 ${UUID.randomUUID()} Loud Emitter|4
            """.trimIndent()
        )
        assertEquals(2, cacheEntries.size)
        assertEquals(MuteType.AGENT, cacheEntries[0].type)
        assertEquals(MuteType.OBJECT, cacheEntries[1].type)

        // Vendor Quick-Pay: PAY_HIDE (-1) & PAY_DEFAULT (-2) & custom L$ slots
        val vendorId = UUID.randomUUID()
        val customVendor = PayPriceInfo(
            objectId = vendorId,
            defaultPayPrice = 100,
            buttonPrices = listOf(25, PayPriceInfo.PAY_DEFAULT, PayPriceInfo.PAY_HIDE, 500)
        )
        val payReplyBytes = WorldManagementPackets.buildPayPriceReply(customVendor)
        val parsedPay = WorldManagementPackets.parsePayPriceReply(wrapLe(payReplyBytes))!!
        assertEquals(vendorId, parsedPay.objectId)
        assertEquals(100, parsedPay.defaultPayPrice)
        // Slot 0 = 25, Slot 1 = PAY_DEFAULT (5), Slot 2 = PAY_HIDE (omitted), Slot 3 = 500
        assertEquals(listOf(25, 5, 500), parsedPay.resolvedQuickButtons())
        assertTrue(parsedPay.allowCustomAmount)

        val hiddenCustomVendor = PayPriceInfo(
            objectId = vendorId,
            defaultPayPrice = PayPriceInfo.PAY_HIDE,
            buttonPrices = listOf(150, PayPriceInfo.PAY_HIDE, PayPriceInfo.PAY_HIDE, PayPriceInfo.PAY_HIDE)
        )
        assertEquals(listOf(150), hiddenCustomVendor.resolvedQuickButtons())
        assertFalse(hiddenCustomVendor.allowCustomAmount)

        val moneyBytes = WorldManagementPackets.buildMoneyTransferRequest(
            agentId = agentId,
            sessionId = sessionId,
            destId = vendorId,
            amount = 250,
            description = "Pay Aether Nexus Beacon"
        )
        val parsedMoney = WorldManagementPackets.parseMoneyTransferRequest(wrapLe(moneyBytes))!!
        assertEquals(vendorId, parsedMoney.destId)
        assertEquals(250, parsedMoney.amount)
        assertEquals(PayPriceInfo.TRANS_PAY_OBJECT, parsedMoney.transactionType)
        assertEquals("Pay Aether Nexus Beacon", parsedMoney.description)
    }

    @Test
    fun `executes 2-stage UpdateNotecardAgentInventory LLSD upload and builds Outfit AT_LINK folders`() = runBlocking {
        // 1. Linden text version 2 round-trip with UTF-8 multi-byte characters
        val originalNote = "Linkpoint Phase 10 Notes — L$ Quick-Pay & Ktheme Parity ✓\nLine 2: Coordinates <128, 128, 25>"
        val encodedV2 = LindenNotecardCodec.encode(originalNote)
        val encodedText = String(encodedV2, Charsets.UTF_8)
        assertTrue(encodedText.startsWith("Linden text version 2\n"))
        assertEquals(originalNote, LindenNotecardCodec.decode(encodedV2))

        // 2. Two-stage Seed CAP upload via CapabilityManager + mock OkHttp Interceptor
        val noteItemId = UUID.randomUUID()
        val uploadedAssetId = UUID.randomUUID()
        val capturedBodies = mutableListOf<String>()

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                capturedBodies += buf.readUtf8()

                val xmlReply = if (req.url.encodedPath.endsWith("/cap/update-note")) {
                    """<?xml version="1.0"?><llsd><map><key>uploader</key><string>https://internal-sim-01.agni:12043/uploader/note-xyz</string></map></llsd>"""
                } else {
                    """<?xml version="1.0"?><llsd><map><key>state</key><string>complete</string><key>new_asset</key><uuid>$uploadedAssetId</uuid></map></llsd>"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(xmlReply.toResponseBody("application/llsd+xml".toMediaType()))
                    .build()
            })
            .build()

        val capMgr = CapabilityManager(httpClient = mockHttp, seedHttpClient = mockHttp)
        capMgr.registerCapabilityUrl(
            NotecardCapProtocol.CAP_UPDATE_NOTECARD_AGENT,
            "https://simhost.agni.lindenlab.com:12043/cap/update-note"
        )

        val uploadRes = capMgr.updateNotecardAgentInventory(noteItemId, originalNote)
        assertTrue(uploadRes.success)
        assertEquals(uploadedAssetId, uploadRes.newAssetId)
        assertEquals(2, capturedBodies.size)
        assertTrue(capturedBodies[0].contains(noteItemId.toString()))
        assertEquals(originalNote, LindenNotecardCodec.decode(capturedBodies[1]))

        // 3. Outfit Folder + AT_LINK symbolic links planner & packet round-trip
        val myOutfitsId = UUID.randomUUID()
        val cofId = UUID.randomUUID()
        val jacketId = UUID.randomUUID()
        val hudId = UUID.randomUUID()
        val plan = OutfitFolderPlanner.buildSaveOutfitPlan(
            outfitName = "Harbor Yachting Look",
            wornItems = listOf(
                InventoryEntry(jacketId, myOutfitsId, "Skipper Jacket", false, OutfitInventoryConstants.AT_CLOTHING, isWorn = true),
                InventoryEntry(hudId, myOutfitsId, "Compass HUD", false, OutfitInventoryConstants.AT_OBJECT, isWorn = true, attachmentPoint = 35)
            ),
            myOutfitsFolderId = myOutfitsId,
            currentOutfitFolderId = cofId
        )
        // 2 links in new outfit folder + 2 links in COF + 1 AT_LINK_FOLDER in COF = 5 links
        assertEquals(5, plan.linksToCreate.size)
        assertTrue(plan.linksToCreate.any { it.assetType == OutfitInventoryConstants.AT_LINK_FOLDER && it.targetFolderId == cofId })

        val folderBytes = WorldManagementPackets.buildCreateInventoryFolder(
            agentId = UUID.randomUUID(),
            sessionId = UUID.randomUUID(),
            folderId = plan.outfitFolderId,
            parentId = plan.parentMyOutfitsId,
            type = OutfitInventoryConstants.FT_OUTFIT,
            name = plan.outfitName
        )
        val parsedFolder = WorldManagementPackets.parseCreateInventoryFolder(wrapLe(folderBytes))!!
        assertEquals("Harbor Yachting Look", parsedFolder.name)
        assertEquals(OutfitInventoryConstants.FT_OUTFIT, parsedFolder.type)

        val firstLink = plan.linksToCreate.first()
        val linkBytes = WorldManagementPackets.buildLinkInventoryItem(
            agentId = UUID.randomUUID(),
            sessionId = UUID.randomUUID(),
            callbackId = 1,
            folderId = firstLink.targetFolderId,
            oldItemId = firstLink.oldItemId,
            assetType = firstLink.assetType,
            invType = firstLink.inventoryType,
            name = firstLink.name,
            description = firstLink.description
        )
        val parsedLink = WorldManagementPackets.parseLinkInventoryItem(wrapLe(linkBytes))!!
        assertEquals(OutfitInventoryConstants.AT_LINK, parsedLink.assetType)
        assertEquals(jacketId, parsedLink.oldItemId)
    }

    @Test
    fun `renders WorldManagementFloater and ObjectInspector Quick-Pay end-to-end in Compose`() {
        val vm = ViewerSessionViewModel(ApplicationProvider.getApplicationContext())
        vm.enterLocalSandbox()

        var showInspector by mutableStateOf(false)
        var liveState by mutableStateOf(vm.state.value)

        composeRule.setContent {
            LinkpointTheme(designState = liveState.design) {
                if (showInspector) {
                    ObjectInspectorContent(state = liveState, client = vm, onClose = {})
                } else {
                    WorldManagementFloaterContent(state = liveState, client = vm, onClose = {})
                }
            }
        }

        // 1. Directory Search tab
        composeRule.onNodeWithTag("world_management_floater").assertIsDisplayed()
        composeRule.onNodeWithTag("dir_cat_events").performClick()
        liveState = vm.state.value
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Live Sunset Jazz @ Harbor Pier").assertIsDisplayed()

        // 2. Groups tab: send notice
        composeRule.onNodeWithTag("world_tab_groups").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("group_notice_subject_input").performScrollTo().performTextInput("Regatta Start")
        composeRule.onNodeWithTag("group_notice_body_input").performScrollTo().performTextInput("Meet at Pier 1")
        composeRule.onNodeWithTag("send_group_notice_button").performScrollTo().performClick()
        liveState = vm.state.value
        composeRule.waitForIdle()
        assertEquals("Regatta Start", liveState.worldManagement.groupNotices.first().subject)

        // 3. Land tab: Return Others & Buy Pass
        composeRule.onNodeWithTag("world_tab_land").performScrollTo().performClick()
        composeRule.waitForIdle()
        val initialPrims = liveState.worldManagement.currentParcel.totalPrims
        composeRule.onNodeWithTag("parcel_return_others").performScrollTo().performClick()
        liveState = vm.state.value
        composeRule.waitForIdle()
        assertTrue(liveState.worldManagement.currentParcel.totalPrims < initialPrims)
        assertEquals(0, liveState.worldManagement.currentParcel.otherPrims)

        // 4. Outfits & Notes tab: Save Outfit & Save Notecard
        composeRule.onNodeWithTag("world_tab_outfits_notes").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("save_outfit_name_input").performScrollTo().performTextInput("Midnight Neon Rig")
        composeRule.onNodeWithTag("save_outfit_button").performScrollTo().performClick()
        liveState = vm.state.value
        composeRule.waitForIdle()
        assertEquals("Midnight Neon Rig", liveState.worldManagement.savedOutfits.first().name)

        composeRule.onNodeWithTag("notecard_body_editor")
            .performScrollTo()
            .performTextReplacement("Updated Linden Notecard Body")
        composeRule.onNodeWithTag("save_notecard_button").performScrollTo().performClick()
        liveState = vm.state.value
        composeRule.waitForIdle()
        assertEquals("Updated Linden Notecard Body", liveState.worldManagement.notecards.first().bodyText)

        // 5. Mute tab: Add Mute
        composeRule.onNodeWithTag("world_tab_mute").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mute_name_input").performScrollTo().performTextInput("Spam Beacon")
        composeRule.onNodeWithTag("add_mute_button").performScrollTo().performClick()
        liveState = vm.state.value
        composeRule.waitForIdle()
        assertTrue(liveState.worldManagement.muteList.any { it.name == "Spam Beacon" })

        // 6. Object Inspector Vendor Quick-Pay: select Aether Nexus Beacon (custom [25, 100, PAY_HIDE, 500])
        val beacon = liveState.sceneSnapshot.worldObjects.first { it.name == "Aether Nexus Beacon" }
        vm.onWorldObjectSelected(beacon)
        liveState = vm.state.value
        val balanceBeforePay = liveState.lindenBalance
        showInspector = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("vendor_quick_pay_panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("quick_pay_25").performScrollTo().performClick()
        liveState = vm.state.value
        composeRule.waitForIdle()
        assertEquals(balanceBeforePay - 25, liveState.lindenBalance)
    }

    @Test
    fun `verifies CapabilityManager REQUESTED_CAPS includes AIS3, COF, Group, and Notecard caps`() {
        val caps = CapabilityManager.REQUESTED_CAPS
        assertTrue(caps.contains("InventoryAPIv3"))
        assertTrue(caps.contains("LibraryAPIv3"))
        assertTrue(caps.contains("IncrementCOFVersion"))
        assertTrue(caps.contains("GroupAPIv1"))
        assertTrue(caps.contains("GroupMemberData"))
        assertTrue(caps.contains("UpdateNotecardAgentInventory"))
    }

    @Test
    fun `verifies exact message_template IDs and Zerocoded vs Unencoded header flags across all Phase 10 UDP packets`() {
        // Non-zero UUIDs ensure Zerocoded packets without natural 0x00 runs still set FLAG_ZEROCODED,
        // while zero UUIDs ensure Unencoded packets with 0x00 runs NEVER set FLAG_ZEROCODED.
        val denseUuidA = UUID(-0x0101010101010101L, -0x0202020202020202L)
        val denseUuidB = UUID(-0x0303030303030303L, -0x0404040404040404L)
        val denseUuidC = UUID(-0x0505050505050505L, -0x0606060606060606L)
        val zeroUuid = UUID(0L, 0L)

        // Exact Low message IDs per message_template.msg
        assertEquals(0xFFFF001F.toInt(), WorldManagementPackets.MSG_DIR_FIND_QUERY)
        assertEquals(0xFFFF0021.toInt(), WorldManagementPackets.MSG_DIR_PLACES_QUERY)
        assertEquals(0xFFFF0023.toInt(), WorldManagementPackets.MSG_DIR_PLACES_REPLY)
        assertEquals(0xFFFF0024.toInt(), WorldManagementPackets.MSG_DIR_PEOPLE_REPLY)
        assertEquals(0xFFFF0025.toInt(), WorldManagementPackets.MSG_DIR_EVENTS_REPLY)
        assertEquals(0xFFFF0026.toInt(), WorldManagementPackets.MSG_DIR_GROUPS_REPLY)
        assertEquals(0xFFFF0036.toInt(), WorldManagementPackets.MSG_PARCEL_INFO_REQUEST)
        assertEquals(0xFFFF0037.toInt(), WorldManagementPackets.MSG_PARCEL_INFO_REPLY)
        assertEquals(0xFFFF003A.toInt(), WorldManagementPackets.MSG_GROUP_NOTICES_LIST_REQUEST)
        assertEquals(0xFFFF003B.toInt(), WorldManagementPackets.MSG_GROUP_NOTICES_LIST_REPLY)
        assertEquals(0xFFFF003C.toInt(), WorldManagementPackets.MSG_GROUP_NOTICE_REQUEST)
        assertEquals(0xFFFF00A1.toInt(), WorldManagementPackets.MSG_REQUEST_PAY_PRICE)
        assertEquals(0xFFFF00A2.toInt(), WorldManagementPackets.MSG_PAY_PRICE_REPLY)
        assertEquals(0xFFFF00C7.toInt(), WorldManagementPackets.MSG_PARCEL_RETURN_OBJECTS)
        assertEquals(0xFFFF00CE.toInt(), WorldManagementPackets.MSG_PARCEL_BUY_PASS)
        assertEquals(0xFFFF0106.toInt(), WorldManagementPackets.MSG_MUTE_LIST_REQUEST)
        assertEquals(0xFFFF0107.toInt(), WorldManagementPackets.MSG_UPDATE_MUTE_LIST_ENTRY)
        assertEquals(0xFFFF0108.toInt(), WorldManagementPackets.MSG_REMOVE_MUTE_LIST_ENTRY)
        assertEquals(0xFFFF0111.toInt(), WorldManagementPackets.MSG_CREATE_INVENTORY_FOLDER)
        assertEquals(0xFFFF013E.toInt(), WorldManagementPackets.MSG_MUTE_LIST_UPDATE)
        assertEquals(0xFFFF013F.toInt(), WorldManagementPackets.MSG_USE_CACHED_MUTE_LIST)
        assertEquals(0xFFFF015F.toInt(), WorldManagementPackets.MSG_GROUP_PROFILE_REQUEST)
        assertEquals(0xFFFF0170.toInt(), WorldManagementPackets.MSG_ACTIVATE_GROUP)
        assertEquals(0xFFFF0177.toInt(), WorldManagementPackets.MSG_GROUP_TITLES_REQUEST)
        assertEquals(0xFFFF0179.toInt(), WorldManagementPackets.MSG_GROUP_TITLE_UPDATE)
        assertEquals(0xFFFF0185.toInt(), WorldManagementPackets.MSG_AGENT_GROUP_DATA_UPDATE)
        assertEquals(0xFFFF01AA.toInt(), WorldManagementPackets.MSG_LINK_INVENTORY_ITEM)

        val sampleParcel = ParcelManagementInfo(parcelId = denseUuidC, localId = 9, name = "Slip 9")
        val sampleNoticeId = UUID.randomUUID()

        val zeroCodedCases = listOf(
            "DirFindQuery" to (WorldManagementPackets.MSG_DIR_FIND_QUERY to
                WorldManagementPackets.buildDirFindQuery(denseUuidA, denseUuidB, denseUuidC, "Jazz", 1)),
            "DirPlacesQuery" to (WorldManagementPackets.MSG_DIR_PLACES_QUERY to
                WorldManagementPackets.buildDirPlacesQuery(denseUuidA, denseUuidB, denseUuidC, "Pier")),
            "DirPlacesReply" to (WorldManagementPackets.MSG_DIR_PLACES_REPLY to
                WorldManagementPackets.buildDirPlacesReply(denseUuidA, denseUuidC, emptyList())),
            "DirPeopleReply" to (WorldManagementPackets.MSG_DIR_PEOPLE_REPLY to
                WorldManagementPackets.buildDirPeopleReply(denseUuidA, denseUuidC, emptyList())),
            "DirEventsReply" to (WorldManagementPackets.MSG_DIR_EVENTS_REPLY to
                WorldManagementPackets.buildDirEventsReply(denseUuidA, denseUuidC, emptyList())),
            "DirGroupsReply" to (WorldManagementPackets.MSG_DIR_GROUPS_REPLY to
                WorldManagementPackets.buildDirGroupsReply(denseUuidA, denseUuidC, emptyList())),
            "ParcelInfoReply" to (WorldManagementPackets.MSG_PARCEL_INFO_REPLY to
                WorldManagementPackets.buildParcelInfoReply(denseUuidA, sampleParcel)),
            "ParcelReturnObjects" to (WorldManagementPackets.MSG_PARCEL_RETURN_OBJECTS to
                WorldManagementPackets.buildParcelReturnObjects(denseUuidA, denseUuidB, 9, ParcelReturnType.OTHER)),
            "ActivateGroup" to (WorldManagementPackets.MSG_ACTIVATE_GROUP to
                WorldManagementPackets.buildActivateGroup(denseUuidA, denseUuidB, denseUuidC)),
            "AgentGroupDataUpdate" to (WorldManagementPackets.MSG_AGENT_GROUP_DATA_UPDATE to
                WorldManagementPackets.buildAgentGroupDataUpdate(
                    denseUuidA,
                    listOf(GroupMembershipInfo(groupId = denseUuidC, name = "Pioneers", powers = GroupPowers.GP_NOTICES_SEND))
                )),
            "LinkInventoryItem" to (WorldManagementPackets.MSG_LINK_INVENTORY_ITEM to
                WorldManagementPackets.buildLinkInventoryItem(
                    agentId = denseUuidA,
                    sessionId = denseUuidB,
                    callbackId = 1,
                    folderId = denseUuidC,
                    oldItemId = denseUuidA,
                    assetType = 24,
                    invType = 18,
                    name = "Coat",
                    description = "Link"
                ))
        )

        for ((label, spec) in zeroCodedCases) {
            val (msgId, body) = spec
            assertTrue("$label should be marked Zerocoded", WorldManagementPackets.isZeroCoded(msgId))
            val framed = WorldManagementPackets.framePacket(42, reliable = true, messageId = msgId, body = body)
            assertTrue(
                "$label header must set FLAG_ZEROCODED (0x80)",
                (framed[0].toInt() and LludpPackets.FLAG_ZEROCODED) != 0
            )
            val decoded = LludpPackets.decodePacket(framed, framed.size)
            assertNotNull("Failed to decode $label", decoded)
            assertEquals("$label messageId mismatch", msgId, decoded!!.messageId)
        }

        val unencodedCases = listOf(
            "ParcelInfoRequest" to (WorldManagementPackets.MSG_PARCEL_INFO_REQUEST to
                WorldManagementPackets.buildParcelInfoRequest(zeroUuid, zeroUuid, zeroUuid)),
            "ParcelBuyPass" to (WorldManagementPackets.MSG_PARCEL_BUY_PASS to
                WorldManagementPackets.buildParcelBuyPass(zeroUuid, zeroUuid, 0)),
            "GroupNoticesListRequest" to (WorldManagementPackets.MSG_GROUP_NOTICES_LIST_REQUEST to
                WorldManagementPackets.buildGroupNoticesListRequest(zeroUuid, zeroUuid, denseUuidC)),
            "GroupNoticesListReply" to (WorldManagementPackets.MSG_GROUP_NOTICES_LIST_REPLY to
                WorldManagementPackets.buildGroupNoticesListReply(
                    zeroUuid,
                    denseUuidC,
                    listOf(
                        GroupNoticeRecord(
                            id = sampleNoticeId.toString(),
                            groupId = denseUuidC,
                            groupName = "Pioneers",
                            subject = "Weekly Build Jam",
                            message = "",
                            senderName = "Aria Vance",
                            timestampLabel = "2025-01-01",
                            timestampEpochSec = 1735689600L,
                            hasAttachment = true,
                            assetType = 7
                        )
                    )
                )),
            "GroupNoticeRequest" to (WorldManagementPackets.MSG_GROUP_NOTICE_REQUEST to
                WorldManagementPackets.buildGroupNoticeRequest(zeroUuid, zeroUuid, sampleNoticeId)),
            "RequestPayPrice" to (WorldManagementPackets.MSG_REQUEST_PAY_PRICE to
                WorldManagementPackets.buildRequestPayPrice(zeroUuid)),
            "PayPriceReply" to (WorldManagementPackets.MSG_PAY_PRICE_REPLY to
                WorldManagementPackets.buildPayPriceReply(PayPriceInfo(zeroUuid, 0, listOf(0, 0, 0, 0)))),
            "MuteListRequest" to (WorldManagementPackets.MSG_MUTE_LIST_REQUEST to
                WorldManagementPackets.buildMuteListRequest(zeroUuid, zeroUuid, 0)),
            "UpdateMuteListEntry" to (WorldManagementPackets.MSG_UPDATE_MUTE_LIST_ENTRY to
                WorldManagementPackets.buildUpdateMuteListEntry(zeroUuid, zeroUuid, MuteEntry(zeroUuid, "Bot", MuteType.OBJECT, 0))),
            "RemoveMuteListEntry" to (WorldManagementPackets.MSG_REMOVE_MUTE_LIST_ENTRY to
                WorldManagementPackets.buildRemoveMuteListEntry(zeroUuid, zeroUuid, zeroUuid, "Bot")),
            "CreateInventoryFolder" to (WorldManagementPackets.MSG_CREATE_INVENTORY_FOLDER to
                WorldManagementPackets.buildCreateInventoryFolder(zeroUuid, zeroUuid, denseUuidA, denseUuidB, 47, "Look")),
            "MuteListUpdate" to (WorldManagementPackets.MSG_MUTE_LIST_UPDATE to
                WorldManagementPackets.buildMuteListUpdate(zeroUuid, "mute_0001.tmp")),
            "UseCachedMuteList" to (WorldManagementPackets.MSG_USE_CACHED_MUTE_LIST to
                WorldManagementPackets.buildUseCachedMuteList(zeroUuid)),
            "GroupProfileRequest" to (WorldManagementPackets.MSG_GROUP_PROFILE_REQUEST to
                WorldManagementPackets.buildGroupProfileRequest(zeroUuid, zeroUuid, denseUuidC)),
            "GroupTitlesRequest" to (WorldManagementPackets.MSG_GROUP_TITLES_REQUEST to
                WorldManagementPackets.buildGroupTitlesRequest(zeroUuid, zeroUuid, denseUuidC, zeroUuid)),
            "GroupTitleUpdate" to (WorldManagementPackets.MSG_GROUP_TITLE_UPDATE to
                WorldManagementPackets.buildGroupTitleUpdate(zeroUuid, zeroUuid, denseUuidC, zeroUuid))
        )

        for ((label, spec) in unencodedCases) {
            val (msgId, body) = spec
            assertFalse("$label should be marked Unencoded", WorldManagementPackets.isZeroCoded(msgId))
            val framed = WorldManagementPackets.framePacket(43, reliable = true, messageId = msgId, body = body)
            assertEquals(
                "$label header must NOT set FLAG_ZEROCODED (0x80)",
                0,
                framed[0].toInt() and LludpPackets.FLAG_ZEROCODED
            )
            val decoded = LludpPackets.decodePacket(framed, framed.size)
            assertNotNull("Failed to decode $label", decoded)
            assertEquals("$label messageId mismatch", msgId, decoded!!.messageId)
        }

        // Verify round-trip decoding of GroupNoticesListRequest/Reply, GroupNoticeRequest, MuteListUpdate, UseCachedMuteList
        val noticesListReq = WorldManagementPackets.parseGroupNoticesListRequest(
            wrapLe(WorldManagementPackets.buildGroupNoticesListRequest(denseUuidA, denseUuidB, denseUuidC))
        )!!
        assertEquals(denseUuidC, noticesListReq.groupId)

        val (noticeGrpId, parsedNotices) = WorldManagementPackets.parseGroupNoticesListReply(
            wrapLe(unencodedCases.first { it.first == "GroupNoticesListReply" }.second.second)
        )
        assertEquals(denseUuidC, noticeGrpId)
        assertEquals(1, parsedNotices.size)
        assertEquals("Weekly Build Jam", parsedNotices.single().subject)
        assertEquals("Aria Vance", parsedNotices.single().senderName)
        assertTrue(parsedNotices.single().hasAttachment)
        assertEquals(7, parsedNotices.single().assetType)

        val noticeReq = WorldManagementPackets.parseGroupNoticeRequest(
            wrapLe(WorldManagementPackets.buildGroupNoticeRequest(denseUuidA, denseUuidB, sampleNoticeId))
        )!!
        assertEquals(sampleNoticeId, noticeReq.groupNoticeId)

        val muteUpd = WorldManagementPackets.parseMuteListUpdate(
            wrapLe(WorldManagementPackets.buildMuteListUpdate(denseUuidA, "mute_0001.tmp"))
        )!!
        assertEquals("mute_0001.tmp", muteUpd.second)
        assertEquals(denseUuidA, WorldManagementPackets.parseUseCachedMuteList(wrapLe(WorldManagementPackets.buildUseCachedMuteList(denseUuidA))))
    }

    @Test
    fun `parses AgentGroupDataUpdate from EventQueueGet LLSD first with UDP Low 389 Zerocoded OpenSim fallback`() {
        val vm = ViewerSessionViewModel(ApplicationProvider.getApplicationContext())
        vm.enterLocalSandbox()
        val selfId = vm.state.value.sceneSnapshot.avatars.first { it.isSelf }.agentId

        val openSimGroupId = UUID.randomUUID()
        val agniGroupId = UUID.randomUUID()
        val staleUdpGroupId = UUID.randomUUID()

        // 1. OpenSim fallback: before EventQueueGet delivers AgentGroupDataUpdate, UDP Low 389 (Zerocoded) updates groups
        val udpFallbackBody = WorldManagementPackets.buildAgentGroupDataUpdate(
            agentId = selfId,
            groups = listOf(
                GroupMembershipInfo(
                    groupId = openSimGroupId,
                    name = "OSGrid Builders",
                    powers = GroupPowers.GP_NOTICES_RECEIVE,
                    acceptNotices = true,
                    contribution = 256
                )
            )
        )
        val framedUdpFallback = WorldManagementPackets.framePacket(
            sequenceNumber = 77,
            reliable = true,
            messageId = WorldManagementPackets.MSG_AGENT_GROUP_DATA_UPDATE,
            body = udpFallbackBody
        )
        assertTrue((framedUdpFallback[0].toInt() and LludpPackets.FLAG_ZEROCODED) != 0)
        vm.dispatchUdpPacketForTesting(framedUdpFallback)
        composeRule.waitForIdle()

        assertEquals(listOf("OSGrid Builders"), vm.state.value.worldManagement.groups.map { it.name })
        assertTrue(vm.state.value.worldManagement.lastManagementStatus.contains("OpenSim UDP"))

        // 2. Agni EventQueueGet: LLSD AgentGroupDataUpdate with 8-byte big-endian binary U64 GroupPowers (1L shl 42)
        val agniPowers = GroupPowers.GP_NOTICES_SEND or GroupPowers.GP_NOTICES_RECEIVE or GroupPowers.GP_LAND_RETURN_NON_GROUP
        val eqBodyNode = WorldManagementPackets.buildAgentGroupDataUpdateLlsd(
            agentId = selfId,
            groups = listOf(
                GroupMembershipInfo(
                    groupId = agniGroupId,
                    name = "Agni Mainland Architects",
                    powers = agniPowers,
                    acceptNotices = true,
                    contribution = 4096
                )
            )
        )
        val eqEnvelopeXml = LlsdCodec.toXml(
            LlsdValue.MapVal(
                mapOf(
                    "id" to LlsdValue.IntVal(12),
                    "events" to LlsdValue.ArrayVal(
                        listOf(
                            LlsdValue.MapVal(
                                mapOf(
                                    "message" to LlsdValue.Str("AgentGroupDataUpdate"),
                                    "body" to eqBodyNode
                                )
                            )
                        )
                    )
                )
            )
        )
        vm.dispatchEventQueueXmlForTesting(eqEnvelopeXml)
        composeRule.waitForIdle()

        val syncedAgni = vm.state.value.worldManagement.groups.single()
        assertEquals(agniGroupId, syncedAgni.groupId)
        assertEquals("Agni Mainland Architects", syncedAgni.name)
        assertEquals(agniPowers, syncedAgni.powers)
        assertTrue(syncedAgni.canSendNotices)
        assertTrue(vm.state.value.worldManagement.lastManagementStatus.contains("EventQueueGet"))

        // 3. Stale/deprecated UDP Low 389 arriving AFTER EventQueueGet must NOT clobber EventQueueGet state
        val staleUdpBody = WorldManagementPackets.buildAgentGroupDataUpdate(
            agentId = selfId,
            groups = listOf(GroupMembershipInfo(groupId = staleUdpGroupId, name = "Stale UDP Group", powers = 0L))
        )
        val framedStaleUdp = WorldManagementPackets.framePacket(
            sequenceNumber = 78,
            reliable = true,
            messageId = WorldManagementPackets.MSG_AGENT_GROUP_DATA_UPDATE,
            body = staleUdpBody
        )
        vm.dispatchUdpPacketForTesting(framedStaleUdp)
        composeRule.waitForIdle()

        assertEquals(listOf("Agni Mainland Architects"), vm.state.value.worldManagement.groups.map { it.name })
    }

    @Test
    fun `prefers AIS3 InventoryAPIv3 and IncrementCOFVersion on Agni and falls back to UDP on OpenSim`() = runBlocking {
        val myOutfitsId = UUID.randomUUID()
        val cofId = UUID.randomUUID()
        val jacketId = UUID.randomUUID()
        val hudId = UUID.randomUUID()
        val serverAssignedOutfitId = UUID.randomUUID()
        val createdLink1 = UUID.randomUUID()
        val createdLink2 = UUID.randomUUID()
        val createdCofLink = UUID.randomUUID()

        val plan = OutfitFolderPlanner.buildSaveOutfitPlan(
            outfitName = "Agni Cruiser Fit",
            wornItems = listOf(
                InventoryEntry(jacketId, myOutfitsId, "Cruiser Jacket", false, OutfitInventoryConstants.AT_CLOTHING, isWorn = true),
                InventoryEntry(hudId, myOutfitsId, "Cruiser HUD", false, OutfitInventoryConstants.AT_OBJECT, isWorn = true, attachmentPoint = 35)
            ),
            myOutfitsFolderId = myOutfitsId,
            currentOutfitFolderId = cofId
        )

        // Case A: Agni with InventoryAPIv3 + IncrementCOFVersion
        val agniRequests = mutableListOf<Triple<String, String, String>>()
        val agniHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                val bodyUtf8 = buf.readUtf8()
                agniRequests += Triple(req.method, req.url.encodedPath, bodyUtf8)

                val replyXml = when {
                    req.url.encodedPath.endsWith("/category/$myOutfitsId") -> {
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <llsd><map>
                          <key>_embedded</key><map>
                            <key>categories</key><map>
                              <key>$serverAssignedOutfitId</key><map>
                                <key>category_id</key><uuid>$serverAssignedOutfitId</uuid>
                                <key>name</key><string>Agni Cruiser Fit</string>
                              </map>
                            </map>
                            <key>links</key><map>
                              <key>$createdLink1</key><map><key>item_id</key><uuid>$createdLink1</uuid></map>
                              <key>$createdLink2</key><map><key>item_id</key><uuid>$createdLink2</uuid></map>
                            </map>
                          </map>
                        </map></llsd>
                        """.trimIndent()
                    }
                    req.url.encodedPath.endsWith("/category/$cofId") -> {
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <llsd><map>
                          <key>_embedded</key><map>
                            <key>links</key><map>
                              <key>$createdCofLink</key><map><key>item_id</key><uuid>$createdCofLink</uuid></map>
                            </map>
                          </map>
                        </map></llsd>
                        """.trimIndent()
                    }
                    req.url.encodedPath.endsWith("/cap/inc-cof") -> {
                        """<?xml version="1.0" encoding="UTF-8"?><llsd><map><key>cof_version</key><integer>42</integer></map></llsd>"""
                    }
                    else -> """<?xml version="1.0"?><llsd><map/></llsd>"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(replyXml.toResponseBody("application/llsd+xml".toMediaType()))
                    .build()
            })
            .build()

        val agniCapMgr = CapabilityManager(httpClient = agniHttp, seedHttpClient = agniHttp)
        agniCapMgr.registerCapabilityUrl(Ais3OutfitProtocol.CAP_INVENTORY_API_V3, "https://simhost.agni.lindenlab.com:12043/cap/ais")
        agniCapMgr.registerCapabilityUrl(Ais3OutfitProtocol.CAP_INCREMENT_COF_VERSION, "https://simhost.agni.lindenlab.com:12043/cap/inc-cof")

        var agniUdpFallbackInvoked = false
        val agniRes = OutfitSaveCoordinator.executeSaveOutfit(
            plan = plan,
            capabilityManager = agniCapMgr,
            onUdpFallback = { agniUdpFallbackInvoked = true }
        )
        assertTrue(agniRes.success)
        assertTrue(agniRes.usedAis3)
        assertFalse(agniUdpFallbackInvoked)
        assertEquals(serverAssignedOutfitId, agniRes.outfitFolderId)
        assertEquals(3, agniRes.createdLinkIds.size)
        assertTrue(agniRes.cofVersionIncremented)
        assertEquals(42, agniRes.cofVersion)
        assertEquals(3, agniRequests.size)
        assertEquals("POST", agniRequests[0].first)
        assertTrue(agniRequests[0].third.contains("<integer>${OutfitInventoryConstants.FT_OUTFIT}</integer>"))
        assertEquals("POST", agniRequests[1].first)
        assertTrue("COF AT_LINK_FOLDER must point to server-assigned outfit folder ID", agniRequests[1].third.contains(serverAssignedOutfitId.toString()))
        assertEquals("GET", agniRequests[2].first)

        // Case B: OpenSim without InventoryAPIv3 -> UDP CreateInventoryFolder (Unencoded) + LinkInventoryItem (Zerocoded)
        val openSimCapMgr = CapabilityManager()
        val openSimUdpFrames = mutableListOf<ByteArray>()
        val openSimRes = OutfitSaveCoordinator.executeSaveOutfit(
            plan = plan,
            capabilityManager = openSimCapMgr,
            onUdpFallback = { fallbackPlan ->
                openSimUdpFrames += WorldManagementPackets.framePacket(
                    sequenceNumber = 1,
                    reliable = true,
                    messageId = WorldManagementPackets.MSG_CREATE_INVENTORY_FOLDER,
                    body = WorldManagementPackets.buildCreateInventoryFolder(
                        agentId = UUID.randomUUID(),
                        sessionId = UUID.randomUUID(),
                        folderId = fallbackPlan.outfitFolderId,
                        parentId = fallbackPlan.parentMyOutfitsId,
                        type = OutfitInventoryConstants.FT_OUTFIT,
                        name = fallbackPlan.outfitName
                    )
                )
                fallbackPlan.linksToCreate.forEachIndexed { idx, link ->
                    openSimUdpFrames += WorldManagementPackets.framePacket(
                        sequenceNumber = idx + 2,
                        reliable = true,
                        messageId = WorldManagementPackets.MSG_LINK_INVENTORY_ITEM,
                        body = WorldManagementPackets.buildLinkInventoryItem(
                            agentId = UUID.randomUUID(),
                            sessionId = UUID.randomUUID(),
                            callbackId = idx + 1,
                            folderId = link.targetFolderId,
                            oldItemId = link.oldItemId,
                            assetType = link.assetType,
                            invType = link.inventoryType,
                            name = link.name,
                            description = link.description
                        )
                    )
                }
            }
        )
        assertTrue(openSimRes.success)
        assertFalse(openSimRes.usedAis3)
        assertEquals(1 + plan.linksToCreate.size, openSimUdpFrames.size)
        assertEquals(0, openSimUdpFrames.first()[0].toInt() and LludpPackets.FLAG_ZEROCODED)
        assertTrue((openSimUdpFrames[1][0].toInt() and LludpPackets.FLAG_ZEROCODED) != 0)

        // Case C: OpenSim returning HTTP 404 on InventoryAPIv3 -> clean fallback to UDP
        val openSim404Http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("Not Found".toResponseBody("text/plain".toMediaType()))
                    .build()
            })
            .build()
        val openSim404CapMgr = CapabilityManager(httpClient = openSim404Http, seedHttpClient = openSim404Http)
        openSim404CapMgr.registerCapabilityUrl(Ais3OutfitProtocol.CAP_INVENTORY_API_V3, "http://opensim.local:9000/CAPS/ais")
        var fallbackOn404Invoked = false
        val fallback404Res = OutfitSaveCoordinator.executeSaveOutfit(
            plan = plan,
            capabilityManager = openSim404CapMgr,
            onUdpFallback = { fallbackOn404Invoked = true }
        )
        assertTrue(fallbackOn404Invoked)
        assertTrue(fallback404Res.success)
        assertFalse(fallback404Res.usedAis3)
    }
}
