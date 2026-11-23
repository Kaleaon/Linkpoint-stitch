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
import com.example.linkpoint.management.DirectoryCategory
import com.example.linkpoint.management.DirectorySearchResult
import com.example.linkpoint.management.GroupMembershipInfo
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
import com.example.linkpoint.management.ParcelFlags
import com.example.linkpoint.management.ParcelManagementInfo
import com.example.linkpoint.management.ParcelReturnType
import com.example.linkpoint.management.PayPriceInfo
import com.example.linkpoint.management.WorldManagementPackets
import com.example.linkpoint.protocol.CapabilityManager
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
        val framedFind = LludpPackets.framePacket(11, true, WorldManagementPackets.MSG_DIR_FIND_QUERY, findBytes)
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
}
