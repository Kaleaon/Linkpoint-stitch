package com.example

import com.example.linkpoint.rlv.RlvChatOutcome
import com.example.linkpoint.rlv.RlvConstants
import com.example.linkpoint.rlv.RlvEngine
import com.example.linkpoint.rlv.RlvHostCallbacks
import com.example.linkpoint.rlv.RlvUiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
    fun `intercepts only object llOwnerSay packets starting with at-sign when enabled`() {
        // Public normal chat (chatType = 1) must NOT be intercepted
        assertFalse(
            engine.onIncomingChat(
                sourceId = collarId,
                fromName = "Collar",
                chatType = RlvConstants.CHAT_TYPE_NORMAL,
                message = "@sendchat=n",
                sourceType = RlvConstants.SOURCE_TYPE_OBJECT
            )
        )
        assertFalse(host.lastSnapshot.sendChatRestricted)

        // Agent sourceType = 1 must NOT be intercepted even if chatType == 8
        assertFalse(
            engine.onIncomingChat(
                sourceId = trustedAvatarId,
                fromName = "Griefer",
                chatType = RlvConstants.CHAT_TYPE_OWNER,
                message = "@sendchat=n",
                sourceType = 1
            )
        )
        assertFalse(host.lastSnapshot.sendChatRestricted)

        // Non-@ llOwnerSay text must pass through to normal chat
        assertFalse(ownerSay(collarId, "Collar ready for commands."))

        // Valid object llOwnerSay starting with '@' IS intercepted and applied
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
        // Zero and negative channels MUST be ignored (prevents spoofing public chat on ch 0)
        assertTrue(ownerSay(collarId, "@version=0,versionnew=-42,versionnum=0,getstatus=-1,getoutfit=0,getattach=-9,getsitid=0"))
        assertTrue(host.channelReplies.isEmpty())

        // Positive channels receive canonical replies
        assertTrue(ownerSay(collarId, "@version=4711,versionnew=4712,versionnum=4713"))
        assertEquals(
            listOf(
                4711 to RlvConstants.RLV_VERSION_REPLY,
                4712 to RlvConstants.RLV_VERSION_NEW_REPLY,
                4713 to RlvConstants.RLV_VERSION_NUM_REPLY
            ),
            host.channelReplies
        )

        host.channelReplies.clear()
        ownerSay(collarId, "@tploc=n,tplm=n,sendchat=n")
        ownerSay(relayId, "@unsit=n", name = "Relay")

        // @getstatus returns ONLY rules held by the querying object, prefixed by '/'
        ownerSay(collarId, "@getstatus=5000,getstatus:tp=5001,getstatus:tp;|=5002")
        assertEquals(5000 to "/tploc/tplm/sendchat", host.channelReplies[0])
        assertEquals(5001 to "/tploc/tplm", host.channelReplies[1])
        assertEquals(5002 to "|tploc|tplm", host.channelReplies[2])

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

        // @accepttp uses =add / =y to enable auto-accept, and =rem / =n to disable
        assertFalse(engine.shouldAutoAcceptTeleport(trustedAvatarId))
        ownerSay(collarId, "@accepttp:$trustedAvatarId=add")
        assertTrue(engine.shouldAutoAcceptTeleport(trustedAvatarId))
        assertFalse(engine.shouldAutoAcceptTeleport(strangerAvatarId))
        ownerSay(collarId, "@accepttp:$trustedAvatarId=rem")
        assertFalse(engine.shouldAutoAcceptTeleport(trustedAvatarId))
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
}
