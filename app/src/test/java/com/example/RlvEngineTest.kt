package com.example

import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.rlv.RlvChatOutcome
import com.example.linkpoint.rlv.RlvConstants
import com.example.linkpoint.rlv.RlvEngine
import com.example.linkpoint.rlv.RlvHostCallbacks
import com.example.linkpoint.rlv.RlvUiSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class RlvEngineTest {

    private class FakeRlvHost : RlvHostCallbacks {
        val channelReplies = mutableListOf<Pair<Int, String>>()
        val forcedTeleports = mutableListOf<Triple<String?, Triple<Float, Float, Float>, UUID>>()
        val forcedSits = mutableListOf<Pair<UUID, UUID>>()
        var forcedUnsitCount = 0
        val forcedDetaches = mutableListOf<Pair<Int?, UUID>>()
        val forcedRemOutfits = mutableListOf<Pair<String?, UUID>>()
        val wornLayers = linkedSetOf("jacket", "shirt", "pants", "shoes", "skin", "hair", "eyes", "shape")
        val occupiedAttachmentPoints = linkedSetOf(2, 32, 35) // skull (2), top right HUD (32), center HUD (35)
        var currentSitId: UUID? = null
        var lastSnapshot: RlvUiSnapshot = RlvUiSnapshot()

        override fun sayOnChannel(channel: Int, message: String) {
            channelReplies += channel to message
        }

        override fun forceTeleportTo(x: Float, y: Float, z: Float, regionName: String?, sourceId: UUID) {
            forcedTeleports += Triple(regionName, Triple(x, y, z), sourceId)
        }

        override fun forceSitOnObject(targetId: UUID, sourceId: UUID) {
            currentSitId = targetId
            forcedSits += targetId to sourceId
        }

        override fun forceUnsit(sourceId: UUID) {
            currentSitId = null
            forcedUnsitCount++
        }

        override fun forceDetachAttachment(attachmentPointId: Int?, sourceId: UUID) {
            forcedDetaches += attachmentPointId to sourceId
        }

        override fun forceRemoveOutfit(layerName: String?, sourceId: UUID) {
            forcedRemOutfits += layerName to sourceId
        }

        override fun isWearableLayerWorn(layerName: String): Boolean = layerName in wornLayers

        override fun isAttachmentPointOccupied(pointId: Int): Boolean = pointId in occupiedAttachmentPoints

        override fun getCurrentSitTargetId(): UUID? = currentSitId

        override fun onStateChanged(snapshot: RlvUiSnapshot) {
            lastSnapshot = snapshot
        }
    }

    private lateinit var host: FakeRlvHost
    private lateinit var engine: RlvEngine

    private val collarId = UUID.fromString("11111111-2222-4333-8444-aaaaaaaa0001")
    private val relayId = UUID.fromString("22222222-3333-4444-8555-bbbbbbbb0002")
    private val trustedAvatarId = UUID.fromString("33333333-4444-4555-8666-cccccccc0003")
    private val strangerAvatarId = UUID.fromString("44444444-5555-4666-8777-dddddddd0004")
    private val chairId = UUID.fromString("55555555-6666-4777-8888-eeeeeeee0005")

    @Before
    fun setUp() {
        host = FakeRlvHost()
        engine = RlvEngine(host = host, initialEnabled = true)
    }

    private fun ownerSay(sourceId: UUID, message: String, name: String = "Collar"): Boolean {
        return engine.onIncomingChat(
            sourceId = sourceId,
            fromName = name,
            chatType = RlvConstants.CHAT_TYPE_OWNER,
            message = message,
            sourceType = RlvConstants.SOURCE_TYPE_OBJECT
        )
    }

    @Test
    fun `defaults to opt-in disabled state and rejects spoofed public, whisper, shout, region, and agent at-chat`() {
        // Default constructor starts explicitly disabled (TPV / Firestorm RLVa / LibreMetaverse.RLV opt-in safety)
        val optInEngine = RlvEngine(host = host)
        assertFalse(optInEngine.isEnabled())
        assertFalse(
            optInEngine.onIncomingChat(
                sourceId = collarId,
                fromName = "Collar",
                chatType = RlvConstants.CHAT_TYPE_OWNER,
                message = "@version=4711,sendchat=n,tploc=n,detach=n",
                sourceType = RlvConstants.SOURCE_TYPE_OBJECT
            )
        )
        assertTrue(host.channelReplies.isEmpty())
        assertEquals(0, optInEngine.getSnapshot().activeRuleCount)

        // All non-owner ChatTypes (whisper=0, normal=1, shout=2, start=4, stop=5, debug=6, regionSayTo=7, direct=9)
        // MUST be ignored even when RLVa is enabled and sourceType == OBJECT
        val spoofedChatTypes = listOf(
            RlvConstants.CHAT_TYPE_WHISPER,
            RlvConstants.CHAT_TYPE_NORMAL,
            RlvConstants.CHAT_TYPE_SHOUT,
            RlvConstants.CHAT_TYPE_START,
            RlvConstants.CHAT_TYPE_STOP,
            RlvConstants.CHAT_TYPE_DEBUG_MSG,
            RlvConstants.CHAT_TYPE_REGION,
            RlvConstants.CHAT_TYPE_DIRECT
        )
        for (spoofedType in spoofedChatTypes) {
            assertFalse(
                "chatType=$spoofedType must never trigger RLVa commands",
                engine.onIncomingChat(
                    sourceId = collarId,
                    fromName = "SpoofPrim",
                    chatType = spoofedType,
                    message = "@version=4711,sendchat=n,tploc=n,detach=n",
                    sourceType = RlvConstants.SOURCE_TYPE_OBJECT
                )
            )
        }
        assertTrue(host.channelReplies.isEmpty())
        assertFalse(host.lastSnapshot.sendChatRestricted)
        assertEquals(0, engine.getSnapshot().activeRuleCount)

        // System (0) and Agent (1) sourceTypes must NOT be intercepted even if chatType == CHAT_TYPE_OWNER (8)
        for (nonObjectSource in listOf(RlvConstants.SOURCE_TYPE_SYSTEM, RlvConstants.SOURCE_TYPE_AGENT)) {
            assertFalse(
                engine.onIncomingChat(
                    sourceId = trustedAvatarId,
                    fromName = "Griefer",
                    chatType = RlvConstants.CHAT_TYPE_OWNER,
                    message = "@version=4711,sendchat=n",
                    sourceType = nonObjectSource
                )
            )
        }
        assertTrue(host.channelReplies.isEmpty())
        assertFalse(host.lastSnapshot.sendChatRestricted)

        // Non-@ llOwnerSay text and leading-whitespace " @..." text must pass through to normal chat (never RLV)
        assertFalse(ownerSay(collarId, "Collar ready for commands."))
        assertFalse(ownerSay(collarId, " @version=4711,sendchat=n"))
        assertTrue(RlvEngine.parseCommandLine(" @version=4711").isEmpty())

        // Omitted sourceType (defaults fail-closed to SOURCE_TYPE_SYSTEM) and non-fully-audible packets must be ignored
        assertFalse(
            engine.onIncomingChat(
                sourceId = collarId,
                fromName = "Collar",
                chatType = RlvConstants.CHAT_TYPE_OWNER,
                message = "@sendchat=n"
            )
        )
        for (inaudible in listOf(RlvConstants.CHAT_AUDIBLE_NOT, RlvConstants.CHAT_AUDIBLE_BARELY)) {
            assertFalse(
                engine.onIncomingChat(
                    sourceId = collarId,
                    fromName = "Collar",
                    chatType = RlvConstants.CHAT_TYPE_OWNER,
                    message = "@sendchat=n",
                    sourceType = RlvConstants.SOURCE_TYPE_OBJECT,
                    audibleLevel = inaudible
                )
            )
        }
        assertFalse(host.lastSnapshot.sendChatRestricted)

        // Valid object llOwnerSay (chatType == 8, sourceType == 2) starting with '@' IS intercepted and applied
        assertTrue(ownerSay(collarId, "@sendchat=n"))
        assertTrue(host.lastSnapshot.sendChatRestricted)

        // Disabling RLVa clears restrictions and ignores subsequent @commands
        engine.setEnabled(false)
        assertFalse(engine.isEnabled())
        assertEquals(0, engine.getSnapshot().activeRuleCount)
        assertFalse(ownerSay(collarId, "@tploc=n"))
        assertTrue(engine.canTeleportLocation())
    }

    @Test
    fun `replies to version, status, outfit, attach, and sitid strictly on positive channels`() {
        // Zero, negative, and malformed channels MUST be ignored (prevents spoofing public chat on ch 0)
        assertTrue(
            ownerSay(
                collarId,
                "@version=0,versionnew=-42,versionnum=0,versionnumbl=-1812221819," +
                    "getstatus=-1,getstatus:tp=0,getoutfit=0,getattach=-9,getsitid=0,version=nan"
            )
        )
        assertTrue(host.channelReplies.isEmpty())

        // Positive channels receive canonical LibreMetaverse.RLV / Firestorm RLVa replies
        assertTrue(ownerSay(collarId, "@version=4711,versionnew=4712,versionnum=4713,versionnumbl=4714,getstatus=4715"))
        assertTrue(RlvConstants.RLV_VERSION_REPLY.startsWith("RestrainedLife viewer v"))
        assertTrue(RlvConstants.RLV_VERSION_NEW_REPLY.startsWith("RestrainedLove viewer v"))
        assertTrue(RlvConstants.RLV_VERSION_NEW_REPLY.contains("RLVa "))
        assertEquals(
            listOf(
                4711 to RlvConstants.RLV_VERSION_REPLY,
                4712 to RlvConstants.RLV_VERSION_NEW_REPLY,
                4713 to RlvConstants.RLV_VERSION_NUM_REPLY,
                4714 to RlvConstants.RLV_VERSION_NUM_REPLY,
                4715 to ""
            ),
            host.channelReplies
        )

        host.channelReplies.clear()
        ownerSay(collarId, "@tploc=n,tplm=n,sendchat=n")
        ownerSay(relayId, "@unsit=n", name = "Relay")

        // @getstatus[:filter[;sep]] returns ONLY rules held by the querying object, prefixed by separator
        ownerSay(
            collarId,
            "@getstatus=5000,getstatus:tp=5001,getstatus:tp;|=5002," +
                "getstatus:TP=5003,getstatus:nomatch=5004,getstatus:;|=5005,getstatus:tp; =5006"
        )
        assertEquals(5000 to "/tploc/tplm/sendchat", host.channelReplies[0])
        assertEquals(5001 to "/tploc/tplm", host.channelReplies[1])
        assertEquals(5002 to "|tploc|tplm", host.channelReplies[2])
        assertEquals(5003 to "/tploc/tplm", host.channelReplies[3])
        assertEquals(5004 to "", host.channelReplies[4])
        assertEquals(5005 to "|tploc|tplm|sendchat", host.channelReplies[5])
        assertEquals(5006 to " tploc tplm", host.channelReplies[6])

        // @getoutfit returns 15-char bitmask matching RlvConstants.OUTFIT_LAYERS
        // Worn: jacket(1), pants(2), shirt(3), shoes(4), skin(9), eyes(10), hair(11), shape(12)
        host.channelReplies.clear()
        ownerSay(collarId, "@getoutfit=6000,getoutfit:jacket=6001,getoutfit:gloves=6002")
        assertEquals(6000 to "011110000111100", host.channelReplies[0])
        assertEquals(6001 to "1", host.channelReplies[1])
        assertEquals(6002 to "0", host.channelReplies[2])

        // @getattach returns 41-char bitmask (index 0 always '0', skull=2 -> '1', top right=32 -> '1', center=35 -> '1')
        host.channelReplies.clear()
        ownerSay(collarId, "@getattach=7000,getattach:skull=7001,getattach:chest=7002")
        val attachMask = host.channelReplies[0].second
        assertEquals(41, attachMask.length)
        assertEquals('0', attachMask[0])
        assertEquals('1', attachMask[2])
        assertEquals('1', attachMask[32])
        assertEquals('1', attachMask[35])
        assertEquals('0', attachMask[1])
        assertEquals(7001 to "1", host.channelReplies[1])
        assertEquals(7002 to "0", host.channelReplies[2])

        // @getsitid returns ZERO_UUID when standing, and sat-upon object UUID when seated
        host.channelReplies.clear()
        ownerSay(collarId, "@getsitid=8000")
        assertEquals(8000 to "00000000-0000-0000-0000-000000000000", host.channelReplies[0])

        host.currentSitId = chairId
        host.channelReplies.clear()
        ownerSay(collarId, "@getsitid=8001")
        assertEquals(8001 to chairId.toString(), host.channelReplies[0])
    }

    @Test
    fun `stacks restrictions per source object and isolates clear and detach garbage collection`() {
        ownerSay(collarId, "@tploc=n,tplm=n,sendchat=n", name = "Collar")
        ownerSay(relayId, "@tploc=n,unsit=n", name = "Relay")

        assertFalse(engine.canTeleportLocation())
        assertFalse(engine.canTeleportLandmark())
        assertFalse(engine.canUnsit())

        // Filtered @clear=tp on Collar removes Collar's tploc & tplm, keeping Collar's sendchat AND Relay's tploc!
        ownerSay(collarId, "@clear=tp")
        assertFalse("Relay still holds @tploc=n", engine.canTeleportLocation())
        assertTrue("Collar released @tplm=n", engine.canTeleportLandmark())
        assertTrue("Collar still holds @sendchat=n", engine.getSnapshot().sendChatRestricted)

        // Detaching Relay garbage-collects Relay's @tploc=n and @unsit=n without touching Collar
        engine.onObjectDetached(relayId)
        assertTrue("All @tploc=n holders cleared", engine.canTeleportLocation())
        assertTrue("Relay's @unsit=n cleared", engine.canUnsit())
        assertTrue("Collar's @sendchat=n remains active", engine.getSnapshot().sendChatRestricted)

        // Full @clear on Collar clears remaining rules
        ownerSay(collarId, "@clear")
        assertEquals(0, engine.getSnapshot().activeRuleCount)
        assertFalse(engine.getSnapshot().sendChatRestricted)
    }

    @Test
    fun `enforces chat garbling, emotes, volume limits, redirchat, and sendchannel exceptions`() {
        // @sendchat=n garbles public chat to "..." while preserving /me emotes
        ownerSay(collarId, "@sendchat=n")
        val garbled = engine.processOutgoingChat("Secret message!", RlvConstants.CHAT_TYPE_NORMAL)
        assertTrue(garbled is RlvChatOutcome.SendPublic)
        assertEquals("...", (garbled as RlvChatOutcome.SendPublic).text)
        assertTrue(garbled.wasGarbled)

        val emote = engine.processOutgoingChat("/me nods quietly.", RlvConstants.CHAT_TYPE_NORMAL)
        assertTrue(emote is RlvChatOutcome.SendPublic)
        assertEquals("/me nods quietly.", (emote as RlvChatOutcome.SendPublic).text)
        assertFalse(emote.wasGarbled)

        // @redirchat:<chan>=add redirects public non-emote chat to channel and suppresses "..." on channel 0
        ownerSay(collarId, "@redirchat:324=add")
        val redirected = engine.processOutgoingChat("Muffled speech", RlvConstants.CHAT_TYPE_SHOUT)
        assertTrue(redirected is RlvChatOutcome.RedirectToChannels)
        assertEquals(listOf(324), (redirected as RlvChatOutcome.RedirectToChannels).channels)
        assertEquals("Muffled speech", redirected.text)

        // Invalid @redirchat:0=add or negative channel is ignored
        ownerSay(collarId, "@redirchat:324=rem,sendchat=y,redirchat:0=add,redirchat:-10=add")
        val normalAfterRem = engine.processOutgoingChat("Clear voice", RlvConstants.CHAT_TYPE_NORMAL)
        assertEquals(RlvChatOutcome.SendPublic("Clear voice", RlvConstants.CHAT_TYPE_NORMAL, false), normalAfterRem)

        // Volume limits: @chatshout=n downgrades shout -> normal; @chatnormal=n downgrades normal -> whisper
        ownerSay(collarId, "@chatshout=n")
        assertEquals(
            RlvChatOutcome.SendPublic("Hello", RlvConstants.CHAT_TYPE_NORMAL, false),
            engine.processOutgoingChat("Hello", RlvConstants.CHAT_TYPE_SHOUT)
        )
        ownerSay(collarId, "@chatnormal=n")
        assertEquals(
            RlvChatOutcome.SendPublic("Hello", RlvConstants.CHAT_TYPE_WHISPER, false),
            engine.processOutgoingChat("Hello", RlvConstants.CHAT_TYPE_SHOUT)
        )

        // @sendchannel=n blocks non-zero channel chat unless @sendchannel:<chan>=add exception is present
        ownerSay(collarId, "@sendchannel=n,sendchannel:99=add")
        assertTrue(engine.processOutgoingChat("/42 blocked") is RlvChatOutcome.Blocked)
        val allowedCh99 = engine.processOutgoingChat("/99 allowed payload")
        assertEquals(RlvChatOutcome.SendOnChannel(99, "allowed payload", RlvConstants.CHAT_TYPE_NORMAL), allowedCh99)
    }

    @Test
    fun `enforces UUID exceptions for IM, recvchat, tplure, edit, and accepttp allowance`() {
        ownerSay(
            collarId,
            "@sendim=n,sendim:$trustedAvatarId=add," +
                "recvim=n,recvim:$trustedAvatarId=add," +
                "recvchat=n,recvchat:$trustedAvatarId=add," +
                "tplure=n,tplure:$trustedAvatarId=add," +
                "edit=n,edit:$chairId=add"
        )

        assertTrue(engine.canSendIm(trustedAvatarId))
        assertFalse(engine.canSendIm(strangerAvatarId))

        assertTrue(engine.canReceiveIm(trustedAvatarId))
        assertFalse(engine.canReceiveIm(strangerAvatarId))

        assertTrue(engine.canReceiveChat("Hello there", trustedAvatarId))
        assertFalse(engine.canReceiveChat("Hello there", strangerAvatarId))
        assertTrue("Emotes remain visible under @recvchat=n", engine.canReceiveChat("/me waves", strangerAvatarId))

        assertTrue(engine.canAcceptLure(trustedAvatarId))
        assertFalse(engine.canAcceptLure(strangerAvatarId))

        assertTrue(engine.canEdit(chairId))
        assertFalse(engine.canEdit(collarId))
        assertFalse(engine.canEdit(null))

        // @accepttp uses =n / =add to add auto-accept rule, and =y / =rem to remove per RLVa & LibreMetaverse.RLV
        assertFalse(engine.shouldAutoAcceptTeleport(trustedAvatarId))
        ownerSay(collarId, "@accepttp:$trustedAvatarId=add")
        assertTrue(engine.shouldAutoAcceptTeleport(trustedAvatarId))
        assertFalse(engine.shouldAutoAcceptTeleport(strangerAvatarId))
        ownerSay(collarId, "@accepttp:$trustedAvatarId=rem")
        assertFalse(engine.shouldAutoAcceptTeleport(trustedAvatarId))

        ownerSay(collarId, "@accepttp=n")
        assertTrue(engine.shouldAutoAcceptTeleport(strangerAvatarId))
        ownerSay(collarId, "@accepttp=y")
        assertFalse(engine.shouldAutoAcceptTeleport(strangerAvatarId))
    }

    @Test
    fun `enforces teleport, sit, detach, outfit, world locks, and force commands`() {
        // @detach=n locks ONLY the issuing object, while other attachments remain detachable
        ownerSay(collarId, "@detach=n")
        assertFalse(engine.canDetach(collarId, 39))
        assertTrue(engine.canDetach(relayId, 35))

        // @detach:skull=n locks the skull slot (point 2) for any object
        ownerSay(collarId, "@detach:skull=n")
        assertFalse(engine.canDetach(UUID.randomUUID(), 2))

        // @detach:top right=force triggers host callback for point 32
        ownerSay(collarId, "@detach:top right=force")
        assertEquals(listOf(32 to collarId), host.forcedDetaches)

        // @addoutfit / @remoutfit & @remoutfit:jacket=force
        ownerSay(collarId, "@addoutfit:gloves=n,remoutfit:shirt=n,remoutfit:jacket=force")
        assertFalse(engine.canAddOutfit("gloves"))
        assertTrue(engine.canAddOutfit("jacket"))
        assertFalse(engine.canRemoveOutfit("shirt"))
        assertEquals(listOf("jacket" to collarId), host.forcedRemOutfits)

        // @sit:<uuid>=force & @unsit=n & @unsit=force
        ownerSay(chairId, "@sit:$chairId=force,unsit=n", name = "Chair")
        assertEquals(chairId, host.currentSitId)
        assertFalse(engine.canUnsit())

        // Another object cannot @unsit=force while Chair holds @unsit=n
        ownerSay(relayId, "@unsit=force", name = "Relay")
        assertEquals(0, host.forcedUnsitCount)
        assertNotNull(host.currentSitId)

        // Chair itself CAN @unsit=force (or @unsit=y then force)
        ownerSay(chairId, "@unsit=force", name = "Chair")
        assertEquals(1, host.forcedUnsitCount)
        assertNull(host.currentSitId)

        // @tpto:<x>/<y>/<z>=force succeeds unless ANOTHER object holds @tploc=n
        ownerSay(relayId, "@tpto:140/155/28=force", name = "Relay")
        assertEquals(1, host.forcedTeleports.size)
        assertEquals(Triple(140f, 155f, 28f), host.forcedTeleports[0].second)

        ownerSay(collarId, "@tploc=n")
        ownerSay(relayId, "@tpto:200/210/30=force", name = "Relay")
        assertEquals("Blocked because Collar holds @tploc=n", 1, host.forcedTeleports.size)

        // @sittp=n blocks distant (>1.5m) sit-teleports while allowing close sits
        ownerSay(collarId, "@sittp=n,rez=n,showinv=n,viewnote=n,shownames=n,showloc=n")
        assertTrue(engine.canSitTeleport(1.2f))
        assertFalse(engine.canSitTeleport(4.0f))
        assertFalse(engine.canRez())
        assertFalse(engine.canShowInventory())
        assertFalse(engine.canViewNotecard())
        assertFalse(engine.canShowNames())
        assertFalse(engine.canShowLocation())

        // Emergency clearAllRestrictions restores full avatar freedom
        engine.clearAllRestrictions()
        assertTrue(engine.canRez())
        assertTrue(engine.canShowInventory())
        assertTrue(engine.canViewNotecard())
        assertTrue(engine.canShowNames())
        assertTrue(engine.canShowLocation())
        assertTrue(engine.canDetach(collarId, 39))
    }

    @Test
    fun `transmits RLVa replies as Zerocoded ChatFromViewer Low 80 packets and ignores spoofed ChatFromSimulator packets`() {
        val circuit = LludpCircuitEngine()
        val capturedWirePackets = mutableListOf<ByteArray>()
        circuit.outboundPacketObserverForTesting = { raw ->
            capturedWirePackets += raw
        }

        val wireEngine = RlvEngine(
            host = object : RlvHostCallbacks {
                override fun sayOnChannel(channel: Int, message: String) {
                    if (channel > 0) {
                        circuit.sendChat(message, RlvConstants.CHAT_TYPE_NORMAL.toByte(), channel)
                    }
                }
            },
            initialEnabled = true
        )

        val collectorScope = CoroutineScope(Dispatchers.Unconfined + Job())
        collectorScope.launch {
            circuit.events.collect { evt ->
                if (evt is SimEvent.ChatReceived) {
                    wireEngine.onIncomingChat(
                        sourceId = evt.sourceId,
                        fromName = evt.fromName,
                        chatType = evt.chatType,
                        message = evt.message,
                        sourceType = evt.sourceType,
                        audibleLevel = evt.audible
                    )
                }
            }
        }

        try {
            // 1. Spoofed public/whisper/shout/region/agent/inaudible ChatFromSimulator packets MUST NOT trigger replies or rules
            for ((srcType, chType) in listOf(
                RlvConstants.SOURCE_TYPE_OBJECT to RlvConstants.CHAT_TYPE_WHISPER,
                RlvConstants.SOURCE_TYPE_OBJECT to RlvConstants.CHAT_TYPE_NORMAL,
                RlvConstants.SOURCE_TYPE_OBJECT to RlvConstants.CHAT_TYPE_SHOUT,
                RlvConstants.SOURCE_TYPE_OBJECT to RlvConstants.CHAT_TYPE_REGION,
                RlvConstants.SOURCE_TYPE_AGENT to RlvConstants.CHAT_TYPE_OWNER
            )) {
                val spoofed = buildChatFromSimulatorDecodedPacket(
                    fromName = "Spoofer",
                    sourceId = collarId,
                    ownerId = trustedAvatarId,
                    sourceType = srcType,
                    chatType = chType,
                    message = "@version=4711,tploc=n,sendchat=n"
                )
                circuit.decodeAndDispatchForTesting(spoofed)
            }
            val inaudibleOwnerSay = buildChatFromSimulatorDecodedPacket(
                fromName = "MutedObject",
                sourceId = collarId,
                ownerId = trustedAvatarId,
                sourceType = RlvConstants.SOURCE_TYPE_OBJECT,
                chatType = RlvConstants.CHAT_TYPE_OWNER,
                message = "@version=4711,tploc=n",
                audible = RlvConstants.CHAT_AUDIBLE_NOT
            )
            circuit.decodeAndDispatchForTesting(inaudibleOwnerSay)
            assertTrue("Spoofed ChatFromSimulator packets must never emit ChatFromViewer replies", capturedWirePackets.isEmpty())
            assertEquals(0, wireEngine.getSnapshot().activeRuleCount)

            // 2. Authentic object llOwnerSay (sourceType=2, chatType=8) applies rules & replies ONLY on channels > 0
            val ownerCmd = buildChatFromSimulatorDecodedPacket(
                fromName = "OpenCollar Relay",
                sourceId = collarId,
                ownerId = trustedAvatarId,
                sourceType = RlvConstants.SOURCE_TYPE_OBJECT,
                chatType = RlvConstants.CHAT_TYPE_OWNER,
                message = "@tploc=n,sendchat=n,version=4711,versionnew=4712,versionnum=4713,getstatus:tp=4714,version=0,getstatus=-5"
            )
            circuit.decodeAndDispatchForTesting(ownerCmd)

            assertEquals(4, capturedWirePackets.size)

            val decodedReplies = capturedWirePackets.map { raw ->
                val flags = raw[0].toInt() and 0xFF
                assertTrue("ChatFromViewer packet must set FLAG_ZEROCODED (0x80)", (flags and LludpPackets.FLAG_ZEROCODED) != 0)
                assertTrue("ChatFromViewer packet must set FLAG_RELIABLE (0x40)", (flags and LludpPackets.FLAG_RELIABLE) != 0)
                // Verify raw wire header uses zero-encoded Low 80 (0xFF 0xFF 0x00 -> 0x00 0x01, 0x50)
                assertEquals(0xFF, raw[6].toInt() and 0xFF)
                assertEquals(0xFF, raw[7].toInt() and 0xFF)
                assertEquals(0x00, raw[8].toInt() and 0xFF)
                assertEquals(0x01, raw[9].toInt() and 0xFF)
                assertEquals(0x50, raw[10].toInt() and 0xFF)

                val pkt = LludpPackets.decodePacket(raw, raw.size)
                assertNotNull(pkt)
                assertEquals(LludpPackets.MSG_CHAT_FROM_VIEWER, pkt!!.messageId)

                val payload = pkt.payload
                LludpPackets.readUuid(payload) // AgentID
                LludpPackets.readUuid(payload) // SessionID
                val msgLen = payload.short.toInt() and 0xFFFF
                val msgText = LludpPackets.readCString(payload, msgLen)
                val outChatType = payload.get().toInt() and 0xFF
                val outChannel = payload.int
                assertEquals(RlvConstants.CHAT_TYPE_NORMAL, outChatType)
                assertTrue("Reply channel must be strictly > 0", outChannel > 0)
                outChannel to msgText
            }

            assertEquals(
                listOf(
                    4711 to RlvConstants.RLV_VERSION_REPLY,
                    4712 to RlvConstants.RLV_VERSION_NEW_REPLY,
                    4713 to RlvConstants.RLV_VERSION_NUM_REPLY,
                    4714 to "/tploc"
                ),
                decodedReplies
            )
        } finally {
            collectorScope.cancel()
        }
    }

    private fun buildChatFromSimulatorDecodedPacket(
        fromName: String,
        sourceId: UUID,
        ownerId: UUID,
        sourceType: Int,
        chatType: Int,
        message: String,
        audible: Int = RlvConstants.CHAT_AUDIBLE_FULLY
    ): LludpPackets.DecodedPacket {
        val nameBytes = (fromName + "\u0000").toByteArray(Charsets.UTF_8)
        val msgBytes = (message + "\u0000").toByteArray(Charsets.UTF_8)
        val body = ByteBuffer.allocate(1 + nameBytes.size + 16 + 16 + 3 + 12 + 2 + msgBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        body.put(nameBytes.size.toByte())
        body.put(nameBytes)
        LludpPackets.putUuid(body, sourceId)
        LludpPackets.putUuid(body, ownerId)
        body.put(sourceType.toByte())
        body.put(chatType.toByte())
        body.put(audible.toByte())
        body.putFloat(128f)
        body.putFloat(128f)
        body.putFloat(25f)
        body.putShort(msgBytes.size.toShort())
        body.put(msgBytes)

        val raw = LludpPackets.framePacket(
            sequenceNumber = 1,
            reliable = true,
            messageId = LludpPackets.MSG_CHAT_FROM_SIMULATOR,
            body = body.array(),
            useZeroCoding = true
        )
        return LludpPackets.decodePacket(raw, raw.size)!!
    }
}
