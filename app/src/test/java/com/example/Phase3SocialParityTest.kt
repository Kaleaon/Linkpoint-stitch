package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.linkpoint.client.ViewerSessionViewModel
import com.example.linkpoint.designsystem.LinkpointTheme
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.designsystem.PersonalDesignState
import com.example.linkpoint.protocol.CapabilityManager
import com.example.linkpoint.protocol.CapsEvent
import com.example.linkpoint.protocol.ChatEmoteFormatter
import com.example.linkpoint.protocol.LlsdCodec
import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.LoginRequestParams
import com.example.linkpoint.protocol.LoginSessionResponse
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.protocol.SocialProtocolPackets
import com.example.linkpoint.protocol.XmlRpcLoginClient
import com.example.linkpoint.scene.AvatarEntity
import com.example.linkpoint.scene.Vec3
import com.example.linkpoint.store.ChatMessage
import com.example.linkpoint.store.ChatRange
import com.example.linkpoint.store.FriendEntry
import com.example.linkpoint.store.FriendshipOfferPrompt
import com.example.linkpoint.store.GroupChatSession
import com.example.linkpoint.store.ImConversation
import com.example.linkpoint.store.TeleportOfferPrompt
import com.example.linkpoint.store.ViewerState
import com.example.linkpoint.ui.AvatarProfileSheetContent
import com.example.linkpoint.ui.ChatFloaterContent
import com.example.linkpoint.ui.InWorldCockpitScreen
import com.example.linkpoint.ui.RadarFloaterContent
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit and Robolectric Compose UI verification suite for Phase 3 Second Life social parity:
 * - XML-RPC & LLSD `buddy-list` login parsing
 * - `OnlineNotification` / `OfflineNotification` binary framing & real-time presence updates
 * - `StartLure` (`MSG_START_LURE`) & `TeleportLureRequest` (`MSG_TELEPORT_LURE_REQUEST`) framing
 * - `ImprovedInstantMessage` dialog routing (1:1 IM, typing start/stop, teleport lures/requests,
 *   friendship offers/responses, group chat & notices)
 * - `/me` emote formatting across Local Chat, Group Chat, and IMs + `mutedAgentIds` filtering
 * - `ChatSessionRequest` (`StartSession` & `AcceptInvitation`) LLSD request & EventQueue parsing
 * - Compose UI controls on `RadarFloaterContent`, `ChatFloaterContent`, `AvatarProfileSheetContent`,
 *   and `InWorldCockpitScreen` social offer banners
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w440dp-h920dp-xxhdpi")
class Phase3SocialParityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val loginClient = XmlRpcLoginClient()

    @Test
    fun `parses buddy-list from XML-RPC and LLSD login responses with rights flags and deduplication`() {
        val friendA = UUID.fromString("aaaaaaaa-1111-4222-8333-444455556666")
        val friendB = UUID.fromString("bbbbbbbb-2222-4333-8444-555566667777")
        val friendC = UUID.fromString("cccccccc-3333-4444-8555-666677778888")

        val xmlRpcResponse = """
            <?xml version="1.0"?>
            <methodResponse>
              <params>
                <param>
                  <value>
                    <struct>
                      <member><name>login</name><value><string>true</string></value></member>
                      <member><name>agent_id</name><value><string>11111111-2222-4333-8444-555555555555</string></value></member>
                      <member><name>session_id</name><value><string>aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee</string></value></member>
                      <member><name>secure_session_id</name><value><string>99999999-8888-4777-8666-555555555555</string></value></member>
                      <member><name>circuit_code</name><value><i4>12345678</i4></value></member>
                      <member><name>sim_ip</name><value><string>216.82.49.10</string></value></member>
                      <member><name>sim_port</name><value><i4>13004</i4></value></member>
                      <member><name>seed_capability</name><value><string>https://simhost.agni.secondlife.io:12043/cap/abc</string></value></member>
                      <member>
                        <name>buddy-list</name>
                        <value>
                          <array>
                            <data>
                              <value>
                                <struct>
                                  <member><name>buddy_id</name><value><string>$friendA</string></value></member>
                                  <member><name>buddy_rights_given</name><value><i4>3</i4></value></member>
                                  <member><name>buddy_rights_has</name><value><int>7</int></value></member>
                                </struct>
                              </value>
                              <value>
                                <struct>
                                  <member><name>buddy_id</name><value><string>$friendB</string></value></member>
                                  <member><name>buddy_rights_given</name><value><boolean>1</boolean></value></member>
                                  <member><name>buddy_rights_has</name><value><boolean>0</boolean></value></member>
                                </struct>
                              </value>
                              <value>
                                <struct>
                                  <member><name>buddy_id</name><value><string>$friendA</string></value></member>
                                  <member><name>buddy_rights_given</name><value><i4>1</i4></value></member>
                                  <member><name>buddy_rights_has</name><value><i4>1</i4></value></member>
                                </struct>
                              </value>
                              <value>
                                <struct>
                                  <member><name>buddy_id</name><value><string>00000000-0000-0000-0000-000000000000</string></value></member>
                                </struct>
                              </value>
                            </data>
                          </array>
                        </value>
                      </member>
                    </struct>
                  </value>
                </param>
              </params>
            </methodResponse>
        """.trimIndent()

        val parsed = loginClient.parseXmlRpcForTesting(xmlRpcResponse)
        val params = LoginRequestParams(
            gridName = "Second Life",
            loginUri = "https://login.agni.lindenlab.com/cgi-bin/login.cgi",
            firstName = "Kaleaon",
            lastName = "Resident",
            passwordOrMd5Hash = "secret"
        )
        val session = loginClient.buildSessionResponseForTesting(parsed, params)

        assertTrue(session.success)
        assertEquals(2, session.buddyList.size)
        assertEquals(friendA, session.buddyList[0].buddyId)
        assertEquals(3, session.buddyList[0].rightsGiven)
        assertEquals(7, session.buddyList[0].rightsHas)
        assertEquals(friendB, session.buddyList[1].buddyId)
        assertEquals(1, session.buddyList[1].rightsGiven)
        assertEquals(0, session.buddyList[1].rightsHas)

        // Also verify LLSD login map buddy-list fallback
        val llsdLoginXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <llsd><map>
              <key>login</key><string>true</string>
              <key>agent_id</key><uuid>11111111-2222-4333-8444-555555555555</uuid>
              <key>session_id</key><uuid>aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee</uuid>
              <key>circuit_code</key><integer>98765</integer>
              <key>sim_ip</key><string>216.82.49.12</string>
              <key>sim_port</key><integer>13005</integer>
              <key>buddy-list</key>
              <array>
                <map>
                  <key>buddy_id</key><uuid>$friendC</uuid>
                  <key>buddy_rights_given</key><integer>1</integer>
                  <key>buddy_rights_has</key><integer>3</integer>
                </map>
              </array>
            </map></llsd>
        """.trimIndent()
        val parsedLlsd = loginClient.parseXmlRpcForTesting(llsdLoginXml)
        val llsdSession = loginClient.buildSessionResponseForTesting(parsedLlsd, params)
        assertTrue(llsdSession.success)
        assertEquals(1, llsdSession.buddyList.size)
        assertEquals(friendC, llsdSession.buddyList[0].buddyId)
        assertEquals(1, llsdSession.buddyList[0].rightsGiven)
        assertEquals(3, llsdSession.buddyList[0].rightsHas)
    }

    @Test
    fun `decodes OnlineNotification and OfflineNotification and frames StartLure and TeleportLureRequest`() = runBlocking {
        val engine = LludpCircuitEngine()
        val buddy1 = UUID.fromString("12345678-1111-4111-8111-111111111111")
        val buddy2 = UUID.fromString("12345678-2222-4222-8222-222222222222")

        // 1. Frame and decode OnlineNotification (0xFFFF0142) & OfflineNotification (0xFFFF0143)
        // Both are declared Trusted Unencoded in message_template.msg
        val onlinePayload = SocialProtocolPackets.buildPresenceNotification(listOf(buddy1, buddy2))
        val framedOnline = LludpPackets.framePacket(
            sequenceNumber = 10,
            reliable = true,
            messageId = LludpPackets.MSG_ONLINE_NOTIFICATION,
            body = onlinePayload,
            useZeroCoding = false
        )
        assertEquals(0, framedOnline[0].toInt() and LludpPackets.FLAG_ZEROCODED)
        val decodedOnlinePkt = LludpPackets.decodePacket(framedOnline, framedOnline.size)
        assertNotNull(decodedOnlinePkt)
        assertEquals(LludpPackets.MSG_ONLINE_NOTIFICATION, decodedOnlinePkt!!.messageId)

        var onlineEvt: SimEvent.FriendOnlineStatusChanged? = null
        val onlineJob = launch {
            onlineEvt = engine.events.first { it is SimEvent.FriendOnlineStatusChanged } as SimEvent.FriendOnlineStatusChanged
        }
        yield()
        engine.decodeAndDispatchForTesting(decodedOnlinePkt)
        onlineJob.join()
        val verifiedOnline = onlineEvt
        assertNotNull(verifiedOnline)
        assertTrue(verifiedOnline!!.isOnline)
        assertEquals(listOf(buddy1, buddy2), verifiedOnline.agentIds)

        val offlinePayload = SocialProtocolPackets.buildPresenceNotification(listOf(buddy2))
        val framedOffline = LludpPackets.framePacket(
            sequenceNumber = 11,
            reliable = true,
            messageId = LludpPackets.MSG_OFFLINE_NOTIFICATION,
            body = offlinePayload,
            useZeroCoding = false
        )
        assertEquals(0, framedOffline[0].toInt() and LludpPackets.FLAG_ZEROCODED)
        val decodedOfflinePkt = LludpPackets.decodePacket(framedOffline, framedOffline.size)!!
        var offlineEvt: SimEvent.FriendOnlineStatusChanged? = null
        val offlineJob = launch {
            offlineEvt = engine.events.first { it is SimEvent.FriendOnlineStatusChanged } as SimEvent.FriendOnlineStatusChanged
        }
        yield()
        engine.decodeAndDispatchForTesting(decodedOfflinePkt)
        offlineJob.join()
        val verifiedOffline = offlineEvt
        assertNotNull(verifiedOffline)
        assertFalse(verifiedOffline!!.isOnline)
        assertEquals(listOf(buddy2), verifiedOffline.agentIds)

        // 2. Binary packet framing & round-trip verification for StartLure (0xFFFF0046, Unencoded)
        val selfAgent = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val selfSession = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        val targetFriend = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        val lureMsg = "Join me at Linkpoint Plaza!"

        val startLureBytes = LludpPackets.buildStartLure(
            agentId = selfAgent,
            sessionId = selfSession,
            targetAgentId = targetFriend,
            message = lureMsg,
            lureType = 0
        )
        val framedStartLure = LludpPackets.framePacket(
            sequenceNumber = 12,
            reliable = true,
            messageId = LludpPackets.MSG_START_LURE,
            body = startLureBytes,
            useZeroCoding = false
        )
        assertEquals(0, framedStartLure[0].toInt() and LludpPackets.FLAG_ZEROCODED)
        val decodedStartLurePkt = LludpPackets.decodePacket(framedStartLure, framedStartLure.size)!!
        assertEquals(LludpPackets.MSG_START_LURE, decodedStartLurePkt.messageId)
        val parsedStartLure = SocialProtocolPackets.parseStartLure(decodedStartLurePkt.payload)
        assertNotNull(parsedStartLure)
        assertEquals(selfAgent, parsedStartLure!!.agentId)
        assertEquals(selfSession, parsedStartLure.sessionId)
        assertEquals(0, parsedStartLure.lureType)
        assertEquals(lureMsg, parsedStartLure.message)
        assertEquals(listOf(targetFriend), parsedStartLure.targetIds)

        // 3. Binary packet framing & round-trip verification for TeleportLureRequest (0xFFFF0047, Unencoded)
        val lureId = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
        val tpLureReqBytes = LludpPackets.buildTeleportLureRequest(
            agentId = selfAgent,
            sessionId = selfSession,
            lureId = lureId
        )
        assertEquals(52, tpLureReqBytes.size) // 16 + 16 + 16 + 4 bytes
        val framedTpReq = LludpPackets.framePacket(
            sequenceNumber = 13,
            reliable = true,
            messageId = LludpPackets.MSG_TELEPORT_LURE_REQUEST,
            body = tpLureReqBytes,
            useZeroCoding = false
        )
        assertEquals(0, framedTpReq[0].toInt() and LludpPackets.FLAG_ZEROCODED)
        val decodedTpReqPkt = LludpPackets.decodePacket(framedTpReq, framedTpReq.size)!!
        assertEquals(LludpPackets.MSG_TELEPORT_LURE_REQUEST, decodedTpReqPkt.messageId)
        val parsedTpReq = SocialProtocolPackets.parseTeleportLureRequest(decodedTpReqPkt.payload)
        assertNotNull(parsedTpReq)
        assertEquals(selfAgent, parsedTpReq!!.agentId)
        assertEquals(selfSession, parsedTpReq.sessionId)
        assertEquals(lureId, parsedTpReq.lureId)
        assertEquals(SocialProtocolPackets.TELEPORT_FLAGS_VIA_LURE, parsedTpReq.teleportFlags)

        // 4. Verify AcceptFriendship (65B), DeclineFriendship (48B), TerminateFriendship (48B) & Unencoded enforcement
        val txId = UUID.randomUUID()
        val folderId = UUID(0L, 0L) // All-zero UUID ensures zero-run bytes exist in payload
        val acceptBytes = SocialProtocolPackets.buildAcceptFriendship(selfAgent, selfSession, txId, folderId)
        val declineBytes = SocialProtocolPackets.buildDeclineFriendship(selfAgent, selfSession, txId)
        val terminateBytes = SocialProtocolPackets.buildTerminateFriendship(selfAgent, selfSession, targetFriend)
        assertEquals(65, acceptBytes.size)
        assertEquals(48, declineBytes.size)
        assertEquals(48, terminateBytes.size)

        val unencodedCases = listOf(
            LludpPackets.MSG_ONLINE_NOTIFICATION to SocialProtocolPackets.buildPresenceNotification(listOf(UUID(0L, 0L))),
            LludpPackets.MSG_OFFLINE_NOTIFICATION to SocialProtocolPackets.buildPresenceNotification(listOf(UUID(0L, 0L))),
            LludpPackets.MSG_ACCEPT_FRIENDSHIP to acceptBytes,
            LludpPackets.MSG_DECLINE_FRIENDSHIP to declineBytes,
            LludpPackets.MSG_TERMINATE_FRIENDSHIP to terminateBytes,
            LludpPackets.MSG_START_LURE to SocialProtocolPackets.buildStartLure(UUID(0L, 0L), UUID(0L, 0L), UUID(0L, 0L), "Hi"),
            LludpPackets.MSG_TELEPORT_LURE_REQUEST to SocialProtocolPackets.buildTeleportLureRequest(UUID(0L, 0L), UUID(0L, 0L), UUID(0L, 0L))
        )
        for ((msgId, bodyBytes) in unencodedCases) {
            assertFalse("Message 0x${msgId.toUInt().toString(16)} must be Unencoded per message_template.msg", LludpPackets.isZeroCodedMessage(msgId))
            assertFalse(SocialProtocolPackets.isTemplateZeroCoded(msgId))
            val framedEvenWhenRequested = LludpPackets.framePacket(
                sequenceNumber = 99,
                reliable = true,
                messageId = msgId,
                body = bodyBytes,
                useZeroCoding = true
            )
            assertEquals(
                "FLAG_ZEROCODED must never be set on Unencoded message 0x${msgId.toUInt().toString(16)}",
                0,
                framedEvenWhenRequested[0].toInt() and LludpPackets.FLAG_ZEROCODED
            )
        }
        assertTrue(LludpPackets.isZeroCodedMessage(LludpPackets.MSG_IMPROVED_INSTANT_MESSAGE))
        assertTrue(LludpPackets.isZeroCodedMessage(LludpPackets.MSG_CHAT_FROM_VIEWER))
    }

    @Test
    fun `routes ImprovedInstantMessage dialogs across IMs typing indicators lures friendship offers and group chat`() = runBlocking {
        val engine = LludpCircuitEngine()
        val peerId = UUID.fromString("22222222-3333-4444-8555-666666666666")
        val selfId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val sessionId = UUID.randomUUID()
        val imSessionId = SocialProtocolPackets.computeDirectImSessionId(selfId, peerId)

        suspend fun dispatchIm(
            dialog: Int,
            message: String,
            customImSessionId: UUID = imSessionId,
            fromName: String = "Torley Navigator",
            binaryBucket: ByteArray = ByteArray(0)
        ): SimEvent {
            val body = SocialProtocolPackets.buildImprovedInstantMessage(
                agentId = peerId,
                sessionId = sessionId,
                toAgentId = selfId,
                imSessionId = customImSessionId,
                fromName = fromName,
                message = message,
                dialog = dialog,
                binaryBucket = binaryBucket
            )
            val framed = LludpPackets.framePacket(
                sequenceNumber = 42,
                reliable = true,
                messageId = LludpPackets.MSG_IMPROVED_INSTANT_MESSAGE,
                body = body,
                useZeroCoding = true
            )
            val pkt = LludpPackets.decodePacket(framed, framed.size)!!
            var received: SimEvent? = null
            val job = launch { received = engine.events.first() }
            yield()
            engine.decodeAndDispatchForTesting(pkt)
            job.join()
            return received!!
        }

        // 1. IM_MESSAGE_FROM_AGENT (0) & IM_BUSY_AUTO_RESPONSE (20)
        val dm = dispatchIm(SocialProtocolPackets.IM_MESSAGE_FROM_AGENT, "Hey there!") as SimEvent.InstantMessageReceived
        assertEquals(peerId, dm.fromAgentId)
        assertEquals("Torley Navigator", dm.fromName)
        assertEquals("Hey there!", dm.message)
        assertEquals(imSessionId, dm.imSessionId)

        val autoResp = dispatchIm(SocialProtocolPackets.IM_BUSY_AUTO_RESPONSE, "Currently away building.") as SimEvent.InstantMessageReceived
        assertEquals(SocialProtocolPackets.IM_BUSY_AUTO_RESPONSE, autoResp.dialog)
        assertEquals("Currently away building.", autoResp.message)
        assertTrue(autoResp.isAutoResponse)

        // 2. IM_TYPING_START (41) & IM_TYPING_STOP (42) — even with empty message body!
        val typingStart = dispatchIm(SocialProtocolPackets.IM_TYPING_START, "") as SimEvent.PeerTypingStateChanged
        assertTrue(typingStart.isTyping)
        assertEquals(peerId, typingStart.fromAgentId)
        assertEquals(imSessionId, typingStart.imSessionId)

        val typingStop = dispatchIm(SocialProtocolPackets.IM_TYPING_STOP, "") as SimEvent.PeerTypingStateChanged
        assertFalse(typingStop.isTyping)

        // 3. IM_LURE_USER (22) & IM_REQUEST_TELEPORT (26)
        val lureId = UUID.fromString("77777777-8888-4999-8aaa-bbbbbbbbbbbb")
        val lure = dispatchIm(SocialProtocolPackets.IM_LURE_USER, "Check out this skybox!", customImSessionId = lureId) as SimEvent.TeleportLureReceived
        assertEquals(lureId, lure.lureId)
        assertEquals("Check out this skybox!", lure.message)
        assertFalse(lure.isTeleportRequest)

        val tpReq = dispatchIm(SocialProtocolPackets.IM_REQUEST_TELEPORT, "May I teleport to you?", customImSessionId = lureId) as SimEvent.TeleportLureReceived
        assertTrue(tpReq.isTeleportRequest)

        // 4. IM_FRIENDSHIP_OFFERED (38), IM_FRIENDSHIP_ACCEPTED (39), IM_FRIENDSHIP_DECLINED (40)
        val offerTxId = UUID.fromString("99999999-aaaa-4bbb-8ccc-dddddddddddd")
        val friendOffer = dispatchIm(
            SocialProtocolPackets.IM_FRIENDSHIP_OFFERED,
            "Let's be friends in SL!",
            customImSessionId = offerTxId
        ) as SimEvent.FriendshipOfferReceived
        assertEquals(offerTxId, friendOffer.transactionId)
        assertEquals("Let's be friends in SL!", friendOffer.message)

        val friendAccepted = dispatchIm(SocialProtocolPackets.IM_FRIENDSHIP_ACCEPTED, "") as SimEvent.FriendshipResponseReceived
        assertTrue(friendAccepted.accepted)

        val friendDeclined = dispatchIm(SocialProtocolPackets.IM_FRIENDSHIP_DECLINED, "") as SimEvent.FriendshipResponseReceived
        assertFalse(friendDeclined.accepted)

        // 5. IM_SESSION_SEND (17) & IM_GROUP_NOTICE (32)
        val groupSessionId = UUID.fromString("fedcba98-7654-4321-89ab-cdef01234567")
        val groupBucket = "Builders Brewery\u0000".toByteArray(Charsets.UTF_8)
        val groupMsg = dispatchIm(
            SocialProtocolPackets.IM_SESSION_SEND,
            "/me shares a new mesh blueprint",
            customImSessionId = groupSessionId,
            binaryBucket = groupBucket
        ) as SimEvent.GroupChatReceived
        assertEquals(groupSessionId, groupMsg.sessionId)
        assertEquals("Builders Brewery", groupMsg.groupName)
        assertEquals("/me shares a new mesh blueprint", groupMsg.message)
        assertFalse(groupMsg.isNotice)

        val noticeBucket = ByteArrayOutputStream().apply {
            write(1) // hasInventory = true
            write(7) // Notecard assetType
            val b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            b.putLong(groupSessionId.mostSignificantBits)
            b.putLong(groupSessionId.leastSignificantBits)
            write(b.array())
            write("Class Schedule\u0000".toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val groupNotice = dispatchIm(
            SocialProtocolPackets.IM_GROUP_NOTICE,
            "Weekly Mesh Class|Starts at 12pm SLT in the main hall!",
            customImSessionId = UUID.randomUUID(),
            binaryBucket = noticeBucket
        ) as SimEvent.GroupChatReceived
        assertTrue(groupNotice.isNotice)
        assertEquals(groupSessionId, groupNotice.sessionId)
        assertTrue(groupNotice.message.contains("[Notice: Weekly Mesh Class]"))
        assertTrue(groupNotice.message.contains("Starts at 12pm SLT"))
        assertTrue(groupNotice.message.contains("Class Schedule"))
    }

    @Test
    fun `serializes ChatSessionRequest LLSD and parses ChatterBoxInvitation and ChatterBoxSessionEventReply`() = runBlocking {
        val groupSessionId = UUID.fromString("55555555-6666-4777-8888-999999999999")

        // 1. Verify StartSession & AcceptInvitation LLSD request maps
        val startLlsd = SocialProtocolPackets.buildChatSessionRequestLlsd(
            SocialProtocolPackets.ChatSessionRequestMethod.StartSession,
            groupSessionId
        )
        assertEquals("start session", startLlsd["method"].asString())
        assertEquals(groupSessionId.toString(), startLlsd["session-id"].asString())
        assertEquals(groupSessionId.toString(), startLlsd["params"].asString())

        val acceptLlsd = SocialProtocolPackets.buildChatSessionRequestLlsd(
            SocialProtocolPackets.ChatSessionRequestMethod.AcceptInvitation,
            groupSessionId
        )
        assertEquals("accept invitation", acceptLlsd["method"].asString())
        assertEquals(groupSessionId.toString(), acceptLlsd["session-id"].asString())

        // 2. Verify CapabilityManager HTTP round-trip for ChatSessionRequest
        val capturedRequestXml = AtomicReference("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cap/chat-session") { exchange ->
            val reqBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            capturedRequestXml.set(reqBody)
            val replyXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <llsd><map>
                  <key>session_id</key><uuid>$groupSessionId</uuid>
                  <key>success</key><boolean>1</boolean>
                  <key>session_info</key>
                  <map>
                    <key>session_name</key><string>Linkpoint Pioneers</string>
                  </map>
                </map></llsd>
            """.trimIndent()
            val bytes = replyXml.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/llsd+xml")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val port = server.address.port
            val capsManager = CapabilityManager()
            capsManager.setCapabilityUrlForTesting("ChatSessionRequest", "http://127.0.0.1:$port/cap/chat-session")

            var replyEvent: CapsEvent.ChatterBoxSessionEventReceived? = null
            val replyJob = launch {
                replyEvent = capsManager.capsEvents.first {
                    it is CapsEvent.ChatterBoxSessionEventReceived
                } as CapsEvent.ChatterBoxSessionEventReceived
            }
            yield()

            val ok = capsManager.acceptGroupChatInvitation(groupSessionId)
            replyJob.join()

            assertTrue(ok)
            val sentLlsd = LlsdCodec.parseXml(capturedRequestXml.get())
            assertEquals("accept invitation", sentLlsd["method"].asString())
            assertEquals(groupSessionId.toString(), sentLlsd["session-id"].asString())
            val verifiedReply = replyEvent
            assertNotNull(verifiedReply)
            assertEquals(groupSessionId, verifiedReply!!.sessionId)
            assertEquals("Linkpoint Pioneers", verifiedReply.sessionName)
            assertTrue(verifiedReply.success)

            // 3. Verify EventQueueGet ChatterBoxInvitation parsing
            val inviterId = UUID.fromString("aaaa1111-bbbb-4ccc-8ddd-eeeeffff0001")
            val inviteXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <llsd><map>
                  <key>session_id</key><uuid>$groupSessionId</uuid>
                  <key>session_name</key><string>SL Scripters</string>
                  <key>instantmessage</key>
                  <map>
                    <key>message_params</key>
                    <map>
                      <key>from_id</key><uuid>$inviterId</uuid>
                      <key>from_name</key><string>Lyra Architect</string>
                      <key>message</key><string>Join our live LSL Q&amp;A!</string>
                    </map>
                  </map>
                </map></llsd>
            """.trimIndent()

            var inviteEvent: CapsEvent.ChatterBoxInvitationReceived? = null
            val inviteJob = launch {
                inviteEvent = capsManager.capsEvents.first {
                    it is CapsEvent.ChatterBoxInvitationReceived
                } as CapsEvent.ChatterBoxInvitationReceived
            }
            yield()
            capsManager.dispatchCapsEventForTesting("ChatterBoxInvitation", LlsdCodec.parseXml(inviteXml))
            inviteJob.join()

            val verifiedInvite = inviteEvent
            assertNotNull(verifiedInvite)
            assertEquals(groupSessionId, verifiedInvite!!.sessionId)
            assertEquals("SL Scripters", verifiedInvite.sessionName)
            assertEquals(inviterId, verifiedInvite.fromAgentId)
            assertEquals("Lyra Architect", verifiedInvite.fromName)
            assertEquals("Join our live LSL Q&A!", verifiedInvite.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `routes AcceptFriendship and DeclineFriendship via Agni HTTP capabilities when granted and falls back to Unencoded UDP on OpenSim`() = runBlocking {
        // 1. Verify CapabilityManager.REQUESTED_CAPS includes Agni social & group capabilities
        assertTrue(CapabilityManager.REQUESTED_CAPS.contains("AcceptFriendship"))
        assertTrue(CapabilityManager.REQUESTED_CAPS.contains("DeclineFriendship"))
        assertTrue(CapabilityManager.REQUESTED_CAPS.contains("GroupAPIv1"))
        assertTrue(CapabilityManager.REQUESTED_CAPS.contains("GroupMemberData"))

        data class RecordedHttpCall(val method: String, val pathAndQuery: String, val body: String)
        val recordedCalls = CopyOnWriteArrayList<RecordedHttpCall>()
        val seedRequestedCaps = CopyOnWriteArrayList<String>()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val udpSimSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 350
        }

        val selfAgent = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val selfSession = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val callingCardFolder = UUID.fromString("22222222-3333-4444-8555-666677778888")
        val offerFromA = UUID.fromString("77777777-1111-4222-8333-444444444444")
        val offerFromB = UUID.fromString("88888888-2222-4333-8444-555555555555")
        val txAcceptAgni = UUID.fromString("99990001-aaaa-4bbb-8ccc-dddddddddddd")
        val txDeclineAgni = UUID.fromString("99990002-aaaa-4bbb-8ccc-dddddddddddd")
        val txAcceptOpenSim = UUID.fromString("99990003-aaaa-4bbb-8ccc-dddddddddddd")
        val txDeclineOpenSim = UUID.fromString("99990004-aaaa-4bbb-8ccc-dddddddddddd")

        server.createContext("/cap/seed") { exchange ->
            val reqXml = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            LlsdCodec.parseXml(reqXml).asList().forEach { seedRequestedCaps.add(it.asString()) }
            val port = server.address.port
            val replyXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <llsd><map>
                  <key>AcceptFriendship</key><uri>http://127.0.0.1:$port/cap/accept-friendship</uri>
                  <key>DeclineFriendship</key><uri>http://127.0.0.1:$port/cap/decline-friendship</uri>
                  <key>GroupAPIv1</key><uri>http://127.0.0.1:$port/cap/group-api-v1</uri>
                  <key>GroupMemberData</key><uri>http://127.0.0.1:$port/cap/group-member-data</uri>
                </map></llsd>
            """.trimIndent()
            val bytes = replyXml.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/llsd+xml")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/cap/accept-friendship") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            recordedCalls.add(RecordedHttpCall(exchange.requestMethod, exchange.requestURI.toString(), body))
            val bytes = """<?xml version="1.0" encoding="UTF-8"?><llsd><map><key>success</key><boolean>1</boolean></map></llsd>"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/llsd+xml")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/cap/decline-friendship") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            recordedCalls.add(RecordedHttpCall(exchange.requestMethod, exchange.requestURI.toString(), body))
            val bytes = """<?xml version="1.0" encoding="UTF-8"?><llsd><map><key>success</key><boolean>1</boolean></map></llsd>"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/llsd+xml")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ViewerSessionViewModel(app)
        try {
            val httpPort = server.address.port
            val udpPort = udpSimSocket.localPort
            val testSession = LoginSessionResponse(
                success = true,
                message = "Welcome",
                reason = "",
                agentId = selfAgent,
                sessionId = selfSession,
                secureSessionId = UUID.randomUUID(),
                circuitCode = 424242,
                simIp = "127.0.0.1",
                simPort = udpPort,
                regionX = 256000,
                regionY = 256000,
                seedCapability = "http://127.0.0.1:$httpPort/cap/seed",
                firstName = "Kaleaon",
                lastName = "Resident",
                inventoryRootId = UUID.randomUUID(),
                inventorySkeleton = emptyList(),
                buddyList = emptyList(),
                lookAt = Triple(1f, 0f, 0f),
                homeRegionName = "Agni Sandbox"
            )

            // Negotiate Agni Seed CAPS
            val seedRes = vm.capabilityManager.requestSeedCapabilities(testSession.seedCapability)
            assertTrue(seedRequestedCaps.containsAll(listOf("AcceptFriendship", "DeclineFriendship", "GroupAPIv1", "GroupMemberData")))
            assertTrue(seedRes.capabilities.containsKey("AcceptFriendship"))
            assertTrue(seedRes.capabilities.containsKey("DeclineFriendship"))

            vm.attachConnectedSessionForTesting(testSession, gridName = "Second Life", callingCardFolder = callingCardFolder)

            fun drainUdpPackets(waitMs: Long = 250L): List<Pair<Int, LludpPackets.DecodedPacket>> {
                val collected = mutableListOf<Pair<Int, LludpPackets.DecodedPacket>>()
                val deadline = System.currentTimeMillis() + waitMs
                val rawBuf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(25)
                    udpSimSocket.soTimeout = remaining
                    try {
                        val dp = DatagramPacket(rawBuf, rawBuf.size)
                        udpSimSocket.receive(dp)
                        val rawCopy = dp.data.copyOf(dp.length)
                        val decoded = LludpPackets.decodePacket(rawCopy, rawCopy.size)
                        if (decoded != null) {
                            collected.add((rawCopy[0].toInt() and 0xFF) to decoded)
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
                return collected
            }

            // Drain initial circuit handshake packets
            drainUdpPackets(260L)

            // 2. Agni HTTP Capability Path: AcceptFriendship & DeclineFriendship granted
            fun dispatchIncomingFriendshipOffer(seq: Int, fromId: UUID, fromName: String, txId: UUID) {
                val pktBytes = SocialProtocolPackets.buildImprovedInstantMessage(
                    agentId = fromId,
                    sessionId = UUID.randomUUID(),
                    toAgentId = selfAgent,
                    imSessionId = txId,
                    fromName = fromName,
                    message = "Let's be friends!",
                    dialog = SocialProtocolPackets.IM_FRIENDSHIP_OFFERED
                )
                vm.lludpEngine.decodeAndDispatchForTesting(
                    LludpPackets.DecodedPacket(
                        flags = 0,
                        sequenceNumber = seq,
                        messageId = LludpPackets.MSG_IMPROVED_INSTANT_MESSAGE,
                        payload = ByteBuffer.wrap(pktBytes).order(ByteOrder.LITTLE_ENDIAN),
                        acks = emptyList()
                    )
                )
                shadowOf(Looper.getMainLooper()).idle()
            }

            dispatchIncomingFriendshipOffer(70, offerFromA, "Aria Agni", txAcceptAgni)
            assertNotNull(vm.state.value.incomingFriendshipOffer)

            // Exercise ViewerClient.acceptFriendshipOffer() and verify HTTP POST with ?transaction=<imSessionId>
            vm.acceptFriendshipOffer()
            val deadlineAcceptHttp = System.currentTimeMillis() + 1500L
            while (recordedCalls.none { it.pathAndQuery.startsWith("/cap/accept-friendship") } && System.currentTimeMillis() < deadlineAcceptHttp) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            val acceptHttp = recordedCalls.firstOrNull { it.pathAndQuery.startsWith("/cap/accept-friendship") }
            assertNotNull("Expected HTTP POST to AcceptFriendship capability", acceptHttp)
            assertEquals("POST", acceptHttp!!.method)
            assertTrue(acceptHttp.pathAndQuery.contains("transaction=$txAcceptAgni"))
            assertTrue(acceptHttp.pathAndQuery.contains("from=$offerFromA"))
            val acceptLlsd = LlsdCodec.parseXml(acceptHttp.body)
            assertEquals(txAcceptAgni.toString(), acceptLlsd["transaction_id"].asString())
            assertEquals(callingCardFolder.toString(), acceptLlsd["folder_id"].asString())
            assertTrue(vm.state.value.friends.any { it.agentId == offerFromA && it.isOnline })
            val udpDuringAgniAccept = drainUdpPackets(180L)
            assertFalse(
                "Must not send duplicate UDP AcceptFriendship when Agni HTTP capability succeeds",
                udpDuringAgniAccept.any { it.second.messageId == LludpPackets.MSG_ACCEPT_FRIENDSHIP }
            )

            // Exercise ViewerClient.declineFriendshipOffer() on Agni HTTP capability
            dispatchIncomingFriendshipOffer(71, offerFromB, "Bram Agni", txDeclineAgni)
            assertNotNull(vm.state.value.incomingFriendshipOffer)
            vm.declineFriendshipOffer()
            val deadlineDeclineHttp = System.currentTimeMillis() + 1500L
            while (recordedCalls.none { it.pathAndQuery.startsWith("/cap/decline-friendship") } && System.currentTimeMillis() < deadlineDeclineHttp) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            val declineHttp = recordedCalls.firstOrNull { it.pathAndQuery.startsWith("/cap/decline-friendship") }
            assertNotNull("Expected HTTP POST to DeclineFriendship capability", declineHttp)
            assertEquals("POST", declineHttp!!.method)
            assertTrue(declineHttp.pathAndQuery.contains("transaction=$txDeclineAgni"))
            assertTrue(declineHttp.pathAndQuery.contains("from=$offerFromB"))
            val declineLlsd = LlsdCodec.parseXml(declineHttp.body)
            assertEquals(txDeclineAgni.toString(), declineLlsd["transaction_id"].asString())
            val udpDuringAgniDecline = drainUdpPackets(180L)
            assertFalse(
                "Must not send duplicate UDP DeclineFriendship when Agni HTTP capability succeeds",
                udpDuringAgniDecline.any { it.second.messageId == LludpPackets.MSG_DECLINE_FRIENDSHIP }
            )

            // 3. OpenSim Fallback Path: Clear capabilities (simulating OpenSimulator region without AcceptFriendship/DeclineFriendship caps)
            vm.capabilityManager.shutdown()
            assertNull(vm.capabilityManager.getCapabilityUrl("AcceptFriendship"))
            assertNull(vm.capabilityManager.getCapabilityUrl("DeclineFriendship"))

            val openSimFriend = UUID.fromString("66666666-3333-4444-8555-777777777777")
            dispatchIncomingFriendshipOffer(72, openSimFriend, "Orion OpenSim", txAcceptOpenSim)
            vm.acceptFriendshipOffer()
            shadowOf(Looper.getMainLooper()).idle()

            var udpAcceptPkt: Pair<Int, LludpPackets.DecodedPacket>? = null
            val acceptUdpDeadline = System.currentTimeMillis() + 1500L
            while (udpAcceptPkt == null && System.currentTimeMillis() < acceptUdpDeadline) {
                udpAcceptPkt = drainUdpPackets(200L).firstOrNull {
                    it.second.messageId == LludpPackets.MSG_ACCEPT_FRIENDSHIP
                }
            }
            assertNotNull("OpenSim without AcceptFriendship cap must fall back to UDP 0xFFFF0129", udpAcceptPkt)
            assertEquals(0, udpAcceptPkt!!.first and LludpPackets.FLAG_ZEROCODED)
            assertTrue((udpAcceptPkt.first and LludpPackets.FLAG_RELIABLE) != 0)
            val parsedUdpAccept = SocialProtocolPackets.parseAcceptFriendship(udpAcceptPkt.second.payload)
            assertNotNull(parsedUdpAccept)
            assertEquals(selfAgent, parsedUdpAccept!!.agentId)
            assertEquals(selfSession, parsedUdpAccept.sessionId)
            assertEquals(txAcceptOpenSim, parsedUdpAccept.transactionId)
            assertEquals(listOf(callingCardFolder), parsedUdpAccept.folderIds)

            val openSimDeclinePeer = UUID.fromString("55555555-4444-4555-8666-888888888888")
            dispatchIncomingFriendshipOffer(73, openSimDeclinePeer, "Daria OpenSim", txDeclineOpenSim)
            vm.declineFriendshipOffer()
            shadowOf(Looper.getMainLooper()).idle()

            var udpDeclinePkt: Pair<Int, LludpPackets.DecodedPacket>? = null
            val declineUdpDeadline = System.currentTimeMillis() + 1500L
            while (udpDeclinePkt == null && System.currentTimeMillis() < declineUdpDeadline) {
                udpDeclinePkt = drainUdpPackets(200L).firstOrNull {
                    it.second.messageId == LludpPackets.MSG_DECLINE_FRIENDSHIP
                }
            }
            assertNotNull("OpenSim without DeclineFriendship cap must fall back to UDP 0xFFFF012A", udpDeclinePkt)
            assertEquals(0, udpDeclinePkt!!.first and LludpPackets.FLAG_ZEROCODED)
            assertTrue((udpDeclinePkt.first and LludpPackets.FLAG_RELIABLE) != 0)
            val parsedUdpDecline = SocialProtocolPackets.parseDeclineFriendship(udpDeclinePkt.second.payload)
            assertNotNull(parsedUdpDecline)
            assertEquals(selfAgent, parsedUdpDecline!!.agentId)
            assertEquals(selfSession, parsedUdpDecline.sessionId)
            assertEquals(txDeclineOpenSim, parsedUdpDecline.transactionId)
        } finally {
            vm.disconnect()
            udpSimSocket.close()
            server.stop(0)
        }
    }

    @Test
    fun `formats slash-me emotes without colon and filters muted agents across chat IMs and offers`() = runBlocking {
        // 1. Direct formatter checks
        val emote = ChatEmoteFormatter.analyze("/me waves enthusiastically")
        assertTrue(emote.isEmote)
        assertEquals("/me waves enthusiastically", emote.wireMessage)
        assertEquals("waves enthusiastically", emote.displayBody)
        assertEquals("* Resident waves enthusiastically", ChatEmoteFormatter.formatLine("Resident", emote.displayBody, isEmote = true))
        assertFalse(ChatEmoteFormatter.formatLine("Resident", emote.displayBody, isEmote = true).contains(":"))

        val normal = ChatEmoteFormatter.analyze("Hello world")
        assertFalse(normal.isEmote)
        assertEquals("Resident: Hello world", ChatEmoteFormatter.formatLine("Resident", normal.displayBody, isEmote = false))

        // 2. End-to-end ViewModel state, sandbox defaults, /me emotes, and mute filtering
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ViewerSessionViewModel(app)
        try {
            vm.enterLocalSandbox()
            shadowOf(Looper.getMainLooper()).idle()

            val sandboxState = vm.state.value
            assertTrue("enterLocalSandbox() must populate sample friends", sandboxState.friends.size >= 3)
            assertTrue("enterLocalSandbox() must populate sample group chat", sandboxState.groupChats.isNotEmpty())

            // Test /me emote in Local Chat, Group Chat, and IM
            vm.sendLocalChat("/me dances on the plaza", ChatRange.SAY)
            val lastLocal = vm.state.value.localChat.last()
            assertTrue(lastLocal.isEmote)
            assertEquals("* Sandbox Explorer (You) dances on the plaza", lastLocal.formattedLine())

            val groupId = vm.state.value.groupChats.first().sessionId
            vm.sendGroupChatMessage(groupId, "/me cheers for Phase 3")
            val lastGroup = vm.state.value.groupChats.first { it.sessionId == groupId }.messages.last()
            assertTrue(lastGroup.isEmote)
            assertEquals("* Sandbox Explorer (You) cheers for Phase 3", lastGroup.formattedLine())

            val friend = vm.state.value.friends.first()
            vm.openImWith(friend.agentId, friend.name)
            val imSessionId = vm.state.value.activeImSessionId!!
            vm.sendInstantMessage(imSessionId, "/me sends a friendly wave")
            val lastIm = vm.state.value.imConversations.first { it.sessionId == imSessionId }.messages.last()
            assertTrue(lastIm.isEmote)
            assertEquals("* Sandbox Explorer (You) sends a friendly wave", lastIm.formattedLine())

            // Test muting an agent filters their Local Chat, Group Chat, IMs, Teleport Lures, and Friendship Offers
            val trollId = UUID.fromString("deadbeef-0000-4000-8000-000000000099")
            vm.toggleMuteAvatar(trollId, "Griefer Bot")
            assertTrue(trollId in vm.state.value.mutedAgentIds)

            val localCountBefore = vm.state.value.localChat.size
            val groupCountBefore = vm.state.value.groupChats.first { it.sessionId == groupId }.messages.size

            // Dispatch muted Local Chat + IM + Lure + Friendship Offer via lludpEngine
            val chatBody = ByteArrayOutputStream().apply {
                val nameB = "Griefer Bot\u0000".toByteArray(Charsets.UTF_8)
                write(nameB.size); write(nameB)
                val u = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                u.putLong(trollId.mostSignificantBits); u.putLong(trollId.leastSignificantBits)
                write(u.array()) // SourceID
                write(u.array()) // OwnerID
                write(1) // SourceType = Agent
                write(1) // ChatType = Normal
                write(1) // Audible
                write(ByteArray(12)) // Position
                val msgB = "Spam local chat\u0000".toByteArray(Charsets.UTF_8)
                write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(msgB.size.toShort()).array())
                write(msgB)
            }.toByteArray()
            vm.lludpEngine.decodeAndDispatchForTesting(
                LludpPackets.DecodedPacket(0, 50, LludpPackets.MSG_CHAT_FROM_SIMULATOR, ByteBuffer.wrap(chatBody).order(ByteOrder.LITTLE_ENDIAN), emptyList())
            )

            fun buildImPkt(seq: Int, dialog: Int, msg: String, session: UUID = UUID.randomUUID()): LludpPackets.DecodedPacket {
                val b = SocialProtocolPackets.buildImprovedInstantMessage(
                    agentId = trollId,
                    sessionId = UUID.randomUUID(),
                    toAgentId = UUID.randomUUID(),
                    imSessionId = session,
                    fromName = "Griefer Bot",
                    message = msg,
                    dialog = dialog,
                    binaryBucket = "Linkpoint Pioneers\u0000".toByteArray(Charsets.UTF_8)
                )
                return LludpPackets.DecodedPacket(0, seq, LludpPackets.MSG_IMPROVED_INSTANT_MESSAGE, ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN), emptyList())
            }

            vm.lludpEngine.decodeAndDispatchForTesting(buildImPkt(51, SocialProtocolPackets.IM_MESSAGE_FROM_AGENT, "Spam IM"))
            vm.lludpEngine.decodeAndDispatchForTesting(buildImPkt(52, SocialProtocolPackets.IM_SESSION_SEND, "Spam Group", session = groupId))
            vm.lludpEngine.decodeAndDispatchForTesting(buildImPkt(53, SocialProtocolPackets.IM_LURE_USER, "Spam Lure"))
            vm.lludpEngine.decodeAndDispatchForTesting(buildImPkt(54, SocialProtocolPackets.IM_FRIENDSHIP_OFFERED, "Spam Friend"))

            yield()
            shadowOf(Looper.getMainLooper()).idle()

            val afterState = vm.state.value
            assertEquals("Muted agent local chat must be dropped", localCountBefore, afterState.localChat.size)
            assertEquals(
                "Muted agent group chat must be dropped",
                groupCountBefore,
                afterState.groupChats.first { it.sessionId == groupId }.messages.size
            )
            assertFalse("Muted agent IM must not open conversation", afterState.imConversations.any { it.peerAgentId == trollId })
            assertNull("Muted agent teleport lure must be ignored", afterState.incomingTeleportOffer)
            assertNull("Muted agent friendship offer must be ignored", afterState.incomingFriendshipOffer)

            // Unmute & verify state updates cleanly
            vm.toggleMuteAvatar(trollId, "Griefer Bot")
            assertFalse(trollId in vm.state.value.mutedAgentIds)
        } finally {
            vm.disconnect()
        }
    }

    @Test
    fun `renders Phase 3 social UI controls and captures verification screenshots`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ViewerSessionViewModel(app)
        try {
            vm.enterLocalSandbox()
            shadowOf(Looper.getMainLooper()).idle()

            val friendOnlineId = UUID.fromString("00000000-0000-4000-8000-000000000003")
            val friendOfflineId = UUID.fromString("00000000-0000-4000-8000-000000000004")
            val strangerId = UUID.fromString("00000000-0000-4000-8000-000000000077")
            val groupSessionId = UUID.fromString("00000000-0000-4000-9000-000000000101")
            val imSessionId = UUID.fromString("00000000-0000-4000-9000-000000000202")
            val lureId = UUID.fromString("00000000-0000-4000-9000-000000000303")
            val txId = UUID.fromString("00000000-0000-4000-9000-000000000404")

            val strangerAvatar = AvatarEntity(
                agentId = strangerId,
                localId = 808,
                displayName = "Aria Skybuilder",
                legacyName = "Aria Resident",
                position = Vec3(132f, 129f, 23f),
                isSelf = false,
                isOnline = true,
                profileLoaded = true,
                bornOn = "06/14/2019",
                aboutText = "Creator of modular skyboxes & kinetic sculptures across Second Life."
            )

            val richState = ViewerState(
                friends = listOf(
                    FriendEntry(friendOnlineId, "Torley Navigator", isOnline = true, rightsGiven = 3, rightsHas = 3),
                    FriendEntry(UUID.fromString("00000000-0000-4000-8000-000000000002"), "Grid Greeter Bot", isOnline = true),
                    FriendEntry(friendOfflineId, "Lyra Architect", isOnline = false)
                ),
                groupChats = listOf(
                    GroupChatSession(
                        sessionId = groupSessionId,
                        groupName = "Linkpoint Pioneers",
                        isJoined = true,
                        messages = listOf(
                            ChatMessage(speaker = "Torley Navigator", text = "Welcome to the group session!", sourceAgentId = friendOnlineId),
                            ChatMessage(speaker = "Lyra Architect", text = "/me waves from the sandbox plaza!", isEmote = true, sourceAgentId = friendOfflineId)
                        )
                    )
                ),
                imConversations = listOf(
                    ImConversation(
                        sessionId = imSessionId,
                        peerAgentId = friendOnlineId,
                        peerName = "Torley Navigator",
                        messages = listOf(
                            ChatMessage(speaker = "Torley Navigator", text = "Ready to check out the new build?", sourceAgentId = friendOnlineId),
                            ChatMessage(speaker = "Explorer", text = "/me puts on jetpack boots", isEmote = true)
                        ),
                        isPeerTyping = true
                    )
                ),
                activeImSessionId = imSessionId,
                incomingTeleportOffer = TeleportOfferPrompt(
                    fromAgentId = friendOnlineId,
                    fromName = "Torley Navigator",
                    lureId = lureId,
                    message = "Join me at the skybox amphitheater!"
                ),
                incomingFriendshipOffer = FriendshipOfferPrompt(
                    fromAgentId = strangerId,
                    fromName = "Aria Skybuilder",
                    sessionId = txId,
                    message = "Would love to connect and collaborate on builds!"
                ),
                design = PersonalDesignState()
            )

            // 1. Verify RadarFloaterContent Friends Tab + 1-tap IM & Offer Teleport + Screenshot
            var activeSurface by mutableIntStateOf(0)
            composeRule.setContent {
                LinkpointTheme(designState = richState.design) {
                    val tokens = LocalLinkpointDesign.current
                    Box(
                        modifier = Modifier
                            .width(412.dp)
                            .background(tokens.canvasVoid)
                            .padding(16.dp)
                    ) {
                        when (activeSurface) {
                            0 -> RadarFloaterContent(state = richState, client = vm, onClose = {})
                            1 -> ChatFloaterContent(state = richState, client = vm, onClose = {})
                            2 -> ChatFloaterContent(
                                state = richState.copy(activeGroupSessionId = groupSessionId, activeImSessionId = null),
                                client = vm,
                                onClose = {}
                            )
                            3 -> AvatarProfileSheetContent(
                                avatar = strangerAvatar,
                                state = richState,
                                client = vm,
                                onClose = {}
                            )
                        }
                    }
                }
            }

            composeRule.onNodeWithTag("radar_tab_friends").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Torley Navigator").assertIsDisplayed()
            composeRule.onNodeWithText("Lyra Architect").assertIsDisplayed()
            composeRule.onNodeWithTag("friend_im_$friendOnlineId").assertIsDisplayed()
            composeRule.onNodeWithTag("friend_lure_$friendOnlineId").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            assertEquals("Teleport offer sent to Torley Navigator", vm.state.value.toastMessage)
            saveDecorScreenshot("/tmp/phase3_friends_radar.png")

            // 2. Verify ChatFloaterContent Typing Indicator + Group Chat Tab + /me Emotes + Screenshot
            activeSurface = 1
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat_typing_indicator").assertIsDisplayed()
            composeRule.onNodeWithTag("chat_tab_group_$groupSessionId").assertIsDisplayed()
            composeRule.onNodeWithText("* Explorer puts on jetpack boots").assertIsDisplayed()
            composeRule.onNodeWithTag("chat_input_field").performTextInput("On my way!")
            composeRule.waitForIdle()
            saveDecorScreenshot("/tmp/phase3_im_typing_and_groups.png")

            // Switch to Group Chat tab view and verify group /me emote rendering without colon
            activeSurface = 2
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Group • Linkpoint Pioneers").assertIsDisplayed()
            composeRule.onNodeWithText("* Lyra Architect waves from the sandbox plaza!").assertIsDisplayed()
            saveDecorScreenshot("/tmp/phase3_group_chat_emotes.png")

            // 3. Verify AvatarProfileSheetContent social buttons (Add Friend, Offer Teleport, Mute) + Screenshot
            activeSurface = 3
            composeRule.waitForIdle()
            saveDecorScreenshot("/tmp/phase3_avatar_profile_social.png")
            composeRule.onNodeWithTag("avatar_profile_add_friend_button").assertIsDisplayed().performClick()
            assertTrue(vm.state.value.friends.any { it.agentId == strangerId })
            assertEquals("Added Aria Skybuilder to Friends", vm.state.value.toastMessage)
            composeRule.onNodeWithTag("avatar_profile_offer_teleport_button").assertIsDisplayed().performClick()
            assertEquals("Teleport offer sent to Aria Skybuilder", vm.state.value.toastMessage)
            composeRule.onNodeWithTag("avatar_profile_mute_button").assertIsDisplayed().performClick()
            assertTrue(strangerId in vm.state.value.mutedAgentIds)
        } finally {
            vm.disconnect()
        }
    }

    @Test
    fun `renders incoming Teleport Offer and Friendship Offer banners in InWorldCockpitScreen`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ViewerSessionViewModel(app)
        try {
            vm.enterLocalSandbox()
            shadowOf(Looper.getMainLooper()).idle()

            val friendId = UUID.fromString("00000000-0000-4000-8000-000000000003")
            val strangerId = UUID.fromString("00000000-0000-4000-8000-000000000088")
            val lurePkt = SocialProtocolPackets.buildImprovedInstantMessage(
                agentId = friendId,
                sessionId = UUID.randomUUID(),
                toAgentId = UUID.randomUUID(),
                imSessionId = UUID.randomUUID(),
                fromName = "Torley Navigator",
                message = "Come see the sunset build at 250m!",
                dialog = SocialProtocolPackets.IM_LURE_USER
            )
            val friendOfferPkt = SocialProtocolPackets.buildImprovedInstantMessage(
                agentId = strangerId,
                sessionId = UUID.randomUUID(),
                toAgentId = UUID.randomUUID(),
                imSessionId = UUID.randomUUID(),
                fromName = "Aria Skybuilder",
                message = "Let's exchange landmarks & builds!",
                dialog = SocialProtocolPackets.IM_FRIENDSHIP_OFFERED
            )

            vm.lludpEngine.decodeAndDispatchForTesting(
                LludpPackets.DecodedPacket(0, 60, LludpPackets.MSG_IMPROVED_INSTANT_MESSAGE, ByteBuffer.wrap(lurePkt).order(ByteOrder.LITTLE_ENDIAN), emptyList())
            )
            vm.lludpEngine.decodeAndDispatchForTesting(
                LludpPackets.DecodedPacket(0, 61, LludpPackets.MSG_IMPROVED_INSTANT_MESSAGE, ByteBuffer.wrap(friendOfferPkt).order(ByteOrder.LITTLE_ENDIAN), emptyList())
            )
            shadowOf(Looper.getMainLooper()).idle()

            composeRule.setContent {
                val currentState = vm.state.value
                LinkpointTheme(designState = currentState.design) {
                    InWorldCockpitScreen(
                        state = currentState,
                        client = vm,
                        renderer = vm.renderer
                    )
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("teleport_offer_banner").assertIsDisplayed()
            composeRule.onNodeWithTag("friendship_offer_banner").assertIsDisplayed()
            saveDecorScreenshot("/tmp/phase3_social_offer_banners.png")

            // Accept friendship -> adds Aria Skybuilder to friends & clears prompt
            composeRule.onNodeWithTag("friendship_offer_accept_button").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(vm.state.value.incomingFriendshipOffer)
            assertTrue(vm.state.value.friends.any { it.agentId == strangerId && it.isOnline })

            // Decline teleport offer -> clears teleport prompt cleanly
            composeRule.onNodeWithTag("teleport_offer_decline_button").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(vm.state.value.incomingTeleportOffer)
        } finally {
            vm.disconnect()
        }
    }

    private fun saveDecorScreenshot(path: String) {
        runCatching {
            shadowOf(Looper.getMainLooper()).idle()
            val decor = composeRule.activity.window.decorView
            val w = decor.width.coerceAtLeast(480)
            val h = decor.height.coerceAtLeast(640)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            decor.draw(canvas)
            val file = File(path)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }
}
