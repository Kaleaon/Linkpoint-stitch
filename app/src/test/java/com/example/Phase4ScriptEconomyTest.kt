package com.example

import com.example.linkpoint.protocol.CapabilityManager
import com.example.linkpoint.protocol.CapsEvent
import com.example.linkpoint.protocol.LlsdCodec
import com.example.linkpoint.protocol.LlsdValue
import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.ScriptAndEconomyProtocol
import com.example.linkpoint.protocol.SimEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class Phase4ScriptEconomyTest {

    @Test
    fun `decodes ScriptQuestion bitmask flags and serializes ScriptAnswerYes grant and deny packets`() = runBlocking {
        val agentId = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val sessionId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val taskId = UUID.fromString("22222222-3333-4444-8555-666666666666")
        val itemId = UUID.fromString("33333333-4444-4555-8666-777777777777")

        val allStandardFlags =
            LludpPackets.PERMISSION_DEBIT or
                LludpPackets.PERMISSION_TAKE_CONTROLS or
                LludpPackets.PERMISSION_TRIGGER_ANIMATION or
                LludpPackets.PERMISSION_ATTACH or
                LludpPackets.PERMISSION_TRACK_CAMERA or
                LludpPackets.PERMISSION_CONTROL_CAMERA or
                LludpPackets.PERMISSION_TELEPORT

        assertEquals(0x02, LludpPackets.PERMISSION_DEBIT)
        assertEquals(0x04, LludpPackets.PERMISSION_TAKE_CONTROLS)
        assertEquals(0x10, LludpPackets.PERMISSION_TRIGGER_ANIMATION)
        assertEquals(0x20, LludpPackets.PERMISSION_ATTACH)
        assertEquals(0x400, LludpPackets.PERMISSION_TRACK_CAMERA)
        assertEquals(0x800, LludpPackets.PERMISSION_CONTROL_CAMERA)
        assertEquals(0x1000, LludpPackets.PERMISSION_TELEPORT)

        // Build inbound MSG_SCRIPT_QUESTION (Low 188 / 0xFFFF00BC) binary payload
        val objNameBytes = "Tip & Dance Vendor\u0000".toByteArray(Charsets.UTF_8)
        val ownerBytes = "Kaleaon Resident\u0000".toByteArray(Charsets.UTF_8)
        val questionBitmask = LludpPackets.PERMISSION_DEBIT or LludpPackets.PERMISSION_TRIGGER_ANIMATION

        val payloadBuf = ByteBuffer.allocate(16 + 16 + 1 + objNameBytes.size + 1 + ownerBytes.size + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(payloadBuf, taskId)
        LludpPackets.putUuid(payloadBuf, itemId)
        payloadBuf.put(objNameBytes.size.toByte())
        payloadBuf.put(objNameBytes)
        payloadBuf.put(ownerBytes.size.toByte())
        payloadBuf.put(ownerBytes)
        payloadBuf.putInt(questionBitmask)

        val framedQuestion = LludpPackets.framePacket(
            sequenceNumber = 42,
            reliable = true,
            messageId = LludpPackets.MSG_SCRIPT_QUESTION,
            body = payloadBuf.array(),
            useZeroCoding = false
        )

        val engine = LludpCircuitEngine()
        val deferredEvt = async {
            withTimeout(2000L) {
                engine.events.first { it is SimEvent.ScriptQuestionReceived } as SimEvent.ScriptQuestionReceived
            }
        }
        // Yield briefly so SharedFlow collector subscribes before synchronous packet dispatch
        kotlinx.coroutines.yield()
        assertTrue(engine.decodeAndDispatchForTesting(framedQuestion))

        val received = deferredEvt.await()
        assertEquals(taskId, received.taskId)
        assertEquals(itemId, received.itemId)
        assertEquals("Tip & Dance Vendor", received.objectName)
        assertEquals("Kaleaon Resident", received.objectOwner)
        assertEquals(questionBitmask, received.questions)
        assertTrue("PERMISSION_DEBIT must set hasDebitPermission=true", received.hasDebitPermission)
        assertEquals(2, received.permissions.size)
        assertTrue(received.permissions.any { it.mask == LludpPackets.PERMISSION_DEBIT && it.isDebitWarning })
        assertTrue(received.permissions.any { it.mask == LludpPackets.PERMISSION_TRIGGER_ANIMATION && !it.isDebitWarning })

        // Verify all 7 standard flags decode into distinct human-readable entries
        val decodedAll = ScriptAndEconomyProtocol.decodePermissionFlags(allStandardFlags)
        assertEquals(7, decodedAll.size)
        assertFalse(
            "Pure animation/camera permissions should NOT flag debit warning",
            ScriptAndEconomyProtocol.hasDebitPermission(
                LludpPackets.PERMISSION_TRIGGER_ANIMATION or LludpPackets.PERMISSION_CONTROL_CAMERA
            )
        )

        // Verify outbound MSG_SCRIPT_ANSWER_YES (Low 132 / 0xFFFF0084) 68-byte body for Grant and Deny (0)
        val grantBody = LludpPackets.buildScriptAnswerYes(
            agentId = agentId,
            sessionId = sessionId,
            taskId = taskId,
            itemId = itemId,
            questions = questionBitmask
        )
        assertEquals(68, grantBody.size)
        val framedGrant = LludpPackets.framePacket(
            sequenceNumber = 43,
            reliable = true,
            messageId = LludpPackets.MSG_SCRIPT_ANSWER_YES,
            body = grantBody,
            useZeroCoding = false
        )
        val decodedGrant = LludpPackets.decodePacket(framedGrant, framedGrant.size)
        assertNotNull(decodedGrant)
        assertEquals(LludpPackets.MSG_SCRIPT_ANSWER_YES, decodedGrant!!.messageId)
        assertEquals(68, decodedGrant.payload.remaining())
        assertEquals(agentId, LludpPackets.readUuid(decodedGrant.payload))
        assertEquals(sessionId, LludpPackets.readUuid(decodedGrant.payload))
        assertEquals(taskId, LludpPackets.readUuid(decodedGrant.payload))
        assertEquals(itemId, LludpPackets.readUuid(decodedGrant.payload))
        assertEquals(questionBitmask, decodedGrant.payload.int)

        val denyBody = LludpPackets.buildScriptAnswerYes(agentId, sessionId, taskId, itemId, 0)
        val denyBuf = ByteBuffer.wrap(denyBody).order(ByteOrder.LITTLE_ENDIAN)
        denyBuf.position(64)
        assertEquals(0, denyBuf.int)
    }

    @Test
    fun `detects llTextBox sentinel in CapabilityManager ScriptDialog LLSD and UDP ScriptDialog fallback`() = runBlocking {
        val capsMgr = CapabilityManager()
        val objectUuid = UUID.fromString("77777777-8888-4999-8aaa-bbbbbbbbbbbb")

        // 1. CAPS EventQueueGet LLSD ScriptDialog with !!llTextBox!! sentinel
        val textBoxXml = """
            <?xml version="1.0"?>
            <llsd>
              <map>
                <key>Data</key>
                <array>
                  <map>
                    <key>ObjectID</key><uuid>$objectUuid</uuid>
                    <key>FirstName</key><string>Ada</string>
                    <key>LastName</key><string>Lovelace</string>
                    <key>ObjectName</key><string>Visitor Guestbook</string>
                    <key>Message</key><string>Enter your custom greeting note:</string>
                    <key>ChatChannel</key><integer>-88421</integer>
                  </map>
                </array>
                <key>Buttons</key>
                <array>
                  <map>
                    <key>ButtonLabel</key><string>!!llTextBox!!</string>
                  </map>
                </array>
              </map>
            </llsd>
        """.trimIndent()

        val deferredTextBox = async {
            withTimeout(2000L) {
                capsMgr.capsEvents.first { it is CapsEvent.ScriptDialog } as CapsEvent.ScriptDialog
            }
        }
        kotlinx.coroutines.yield()
        capsMgr.dispatchCapsEvent("ScriptDialog", LlsdCodec.parseXml(textBoxXml))

        val tbEvt = deferredTextBox.await()
        assertEquals("Visitor Guestbook", tbEvt.objectName)
        assertEquals("Enter your custom greeting note:", tbEvt.message)
        assertEquals(-88421, tbEvt.channel)
        assertEquals(objectUuid, tbEvt.objectId)
        assertEquals("Ada Lovelace", tbEvt.ownerName)
        assertTrue("!!llTextBox!! must set isTextBox=true", tbEvt.isTextBox)
        assertTrue("Sentinel must not leak as a clickable button", tbEvt.buttons.isEmpty())

        // 2. Classic multi-button ScriptDialog over CAPS must keep isTextBox=false and preserve button labels
        val classicBody = LlsdValue.MapVal(
            mapOf(
                "Data" to LlsdValue.ArrayVal(
                    listOf(
                        LlsdValue.MapVal(
                            mapOf(
                                "ObjectName" to LlsdValue.Str("Security Orb"),
                                "Message" to LlsdValue.Str("Choose security mode:"),
                                "ChatChannel" to LlsdValue.IntVal(912)
                            )
                        )
                    )
                ),
                "Buttons" to LlsdValue.ArrayVal(
                    listOf(
                        LlsdValue.MapVal(mapOf("ButtonLabel" to LlsdValue.Str("Active"))),
                        LlsdValue.MapVal(mapOf("ButtonLabel" to LlsdValue.Str("Passive"))),
                        LlsdValue.MapVal(mapOf("ButtonLabel" to LlsdValue.Str("Off")))
                    )
                )
            )
        )
        val deferredClassic = async {
            withTimeout(2000L) {
                capsMgr.capsEvents.first { it is CapsEvent.ScriptDialog } as CapsEvent.ScriptDialog
            }
        }
        kotlinx.coroutines.yield()
        capsMgr.dispatchCapsEvent("ScriptDialog", classicBody)

        val classicEvt = deferredClassic.await()
        assertFalse(classicEvt.isTextBox)
        assertEquals(listOf("Active", "Passive", "Off"), classicEvt.buttons)
        assertEquals(912, classicEvt.channel)

        // 3. UDP MSG_SCRIPT_DIALOG (0xFFFF00BE) fallback with !!llTextBox!! sentinel and zero-coding
        val firstBytes = "Grace\u0000".toByteArray(Charsets.UTF_8)
        val lastBytes = "Hopper\u0000".toByteArray(Charsets.UTF_8)
        val objBytes = "Search Terminal\u0000".toByteArray(Charsets.UTF_8)
        val msgBytes = "Type destination region:\u0000".toByteArray(Charsets.UTF_8)
        val btnBytes = "!!llTextBox!!\u0000".toByteArray(Charsets.UTF_8)

        val udpBuf = ByteBuffer.allocate(
            16 + 1 + firstBytes.size + 1 + lastBytes.size + 1 + objBytes.size +
                2 + msgBytes.size + 4 + 16 + 1 + 1 + btnBytes.size + 1
        ).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(udpBuf, objectUuid)
        udpBuf.put(firstBytes.size.toByte()); udpBuf.put(firstBytes)
        udpBuf.put(lastBytes.size.toByte()); udpBuf.put(lastBytes)
        udpBuf.put(objBytes.size.toByte()); udpBuf.put(objBytes)
        udpBuf.putShort(msgBytes.size.toShort()); udpBuf.put(msgBytes)
        udpBuf.putInt(-4200)
        LludpPackets.putUuid(udpBuf, UUID(0L, 0L)) // ImageID
        udpBuf.put(1.toByte()) // 1 button
        udpBuf.put(btnBytes.size.toByte()); udpBuf.put(btnBytes)
        udpBuf.put(0.toByte()) // 0 OwnerData

        val framedUdpDialog = LludpPackets.framePacket(
            sequenceNumber = 77,
            reliable = true,
            messageId = LludpPackets.MSG_SCRIPT_DIALOG,
            body = udpBuf.array(),
            useZeroCoding = true
        )
        val engine = LludpCircuitEngine()
        val deferredUdp = async {
            withTimeout(2000L) {
                engine.events.first { it is SimEvent.ScriptDialogReceived } as SimEvent.ScriptDialogReceived
            }
        }
        kotlinx.coroutines.yield()
        assertTrue(engine.decodeAndDispatchForTesting(framedUdpDialog))
        val udpEvt = deferredUdp.await()
        assertTrue(udpEvt.isTextBox)
        assertEquals("Search Terminal", udpEvt.objectName)
        assertEquals("Grace Hopper", udpEvt.ownerName)
        assertEquals(-4200, udpEvt.channel)
        assertTrue(udpEvt.buttons.isEmpty())
    }

    @Test
    fun `frames and decodes MoneyBalanceRequest MoneyBalanceReply and MoneyTransferRequest binary packets`() = runBlocking {
        val agentId = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val sessionId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val txId = UUID.fromString("99999999-8888-4777-8666-555555555555")
        val targetAvatarId = UUID.fromString("44444444-5555-4666-8777-888888888888")
        val targetVendorId = UUID.fromString("55555555-6666-4777-8888-999999999999")

        // 1. MoneyBalanceRequest (0xFFFF0139): exact 48-byte zero-coded payload
        val balReqBody = LludpPackets.buildMoneyBalanceRequest(agentId, sessionId, txId)
        assertEquals(48, balReqBody.size)
        val framedBalReq = LludpPackets.framePacket(
            sequenceNumber = 10,
            reliable = true,
            messageId = LludpPackets.MSG_MONEY_BALANCE_REQUEST,
            body = balReqBody,
            useZeroCoding = true
        )
        val decodedBalReq = LludpPackets.decodePacket(framedBalReq, framedBalReq.size)
        assertNotNull(decodedBalReq)
        assertEquals(LludpPackets.MSG_MONEY_BALANCE_REQUEST, decodedBalReq!!.messageId)
        assertEquals(48, decodedBalReq.payload.remaining())
        assertEquals(agentId, LludpPackets.readUuid(decodedBalReq.payload))
        assertEquals(sessionId, LludpPackets.readUuid(decodedBalReq.payload))
        assertEquals(txId, LludpPackets.readUuid(decodedBalReq.payload))

        // 2. MoneyBalanceReply (0xFFFF013A): MoneyData + TransactionInfo with Description toast
        val descBytes = "You paid Kaleaon Resident L$250.\u0000".toByteArray(Charsets.UTF_8)
        val itemDescBytes = "DJ Tip Jar\u0000".toByteArray(Charsets.UTF_8)
        val replyBuf = ByteBuffer.allocate(46 + descBytes.size + 43 + itemDescBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(replyBuf, agentId)
        LludpPackets.putUuid(replyBuf, txId)
        replyBuf.put(1.toByte()) // TransactionSuccess = true
        replyBuf.putInt(4825) // MoneyBalance = L$ 4,825
        replyBuf.putInt(1024) // SquareMetersCredit
        replyBuf.putInt(512) // SquareMetersCommitted
        replyBuf.put(descBytes.size.toByte())
        replyBuf.put(descBytes)
        // TransactionInfo block
        replyBuf.putInt(LludpPackets.TRANS_PAY_GIFT)
        LludpPackets.putUuid(replyBuf, agentId)
        replyBuf.put(0.toByte()) // IsSourceGroup
        LludpPackets.putUuid(replyBuf, targetAvatarId)
        replyBuf.put(0.toByte()) // IsDestGroup
        replyBuf.putInt(250) // Amount
        replyBuf.put(itemDescBytes.size.toByte())
        replyBuf.put(itemDescBytes)

        val framedReply = LludpPackets.framePacket(
            sequenceNumber = 11,
            reliable = true,
            messageId = LludpPackets.MSG_MONEY_BALANCE_REPLY,
            body = replyBuf.array(),
            useZeroCoding = true
        )
        val engine = LludpCircuitEngine()
        val deferredBalance = async {
            withTimeout(2000L) {
                engine.events.first { it is SimEvent.MoneyBalanceReceived } as SimEvent.MoneyBalanceReceived
            }
        }
        kotlinx.coroutines.yield()
        assertTrue(engine.decodeAndDispatchForTesting(framedReply))
        val balEvt = deferredBalance.await()
        assertEquals(4825, balEvt.moneyBalance)
        assertTrue(balEvt.transactionSuccess)
        assertEquals("You paid Kaleaon Resident L$250.", balEvt.description)
        assertEquals(250, balEvt.amount)
        assertEquals(5001, balEvt.transactionType)

        // 3. MoneyTransferRequest (0xFFFF0137) for both PayGift (5001) and PayObject (5008)
        val giftBody = LludpPackets.buildMoneyTransferRequest(
            agentId = agentId,
            sessionId = sessionId,
            sourceId = agentId,
            destId = targetAvatarId,
            amount = 100,
            transactionType = LludpPackets.TRANS_PAY_GIFT,
            flags = 0,
            aggregatePermNextOwner = 0,
            description = "Great live set!"
        )
        val framedGift = LludpPackets.framePacket(
            sequenceNumber = 12,
            reliable = true,
            messageId = LludpPackets.MSG_MONEY_TRANSFER_REQUEST,
            body = giftBody,
            useZeroCoding = true
        )
        val decodedGiftPkt = LludpPackets.decodePacket(framedGift, framedGift.size)
        assertNotNull(decodedGiftPkt)
        assertEquals(LludpPackets.MSG_MONEY_TRANSFER_REQUEST, decodedGiftPkt!!.messageId)
        val parsedGift = ScriptAndEconomyProtocol.parseMoneyTransferRequest(decodedGiftPkt.payload)
        assertNotNull(parsedGift)
        assertEquals(agentId, parsedGift!!.agentId)
        assertEquals(sessionId, parsedGift.sessionId)
        assertEquals(agentId, parsedGift.sourceId)
        assertEquals(targetAvatarId, parsedGift.destId)
        assertEquals(100, parsedGift.amount)
        assertEquals(5001, parsedGift.transactionType)
        assertEquals("Great live set!", parsedGift.description)

        // Verify PayObject (5008) + overlong UTF-8 description clamping (never overflows 1-byte Variable 1 length)
        val overlongNote = "TipJar-" + "★".repeat(140)
        val objectPayBody = LludpPackets.buildMoneyTransferRequest(
            agentId = agentId,
            sessionId = sessionId,
            sourceId = agentId,
            destId = targetVendorId,
            amount = 500,
            transactionType = LludpPackets.TRANS_PAY_OBJECT,
            flags = 1,
            aggregatePermNextOwner = 0x0E,
            description = overlongNote
        )
        val framedObjPay = LludpPackets.framePacket(
            sequenceNumber = 13,
            reliable = true,
            messageId = LludpPackets.MSG_MONEY_TRANSFER_REQUEST,
            body = objectPayBody,
            useZeroCoding = true
        )
        val decodedObjPkt = LludpPackets.decodePacket(framedObjPay, framedObjPay.size)
        assertNotNull(decodedObjPkt)
        val parsedObjPay = ScriptAndEconomyProtocol.parseMoneyTransferRequest(decodedObjPkt!!.payload)
        assertNotNull(parsedObjPay)
        assertEquals(targetVendorId, parsedObjPay!!.destId)
        assertEquals(500, parsedObjPay.amount)
        assertEquals(1, parsedObjPay.flags)
        assertEquals(0x0E, parsedObjPay.aggregatePermNextOwner)
        assertEquals(5008, parsedObjPay.transactionType)
        assertTrue(parsedObjPay.description.startsWith("TipJar-★"))
        assertTrue(parsedObjPay.description.toByteArray(Charsets.UTF_8).size <= 254)
    }

    @Test
    fun `decodes full lsl_definitions runtime permission catalog 0x02 through 0x20000 and preserves unknown Agni bits in ScriptAnswerYes`() = runBlocking {
        val agentId = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val sessionId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val taskId = UUID.fromString("22222222-3333-4444-8555-666666666666")
        val itemId = UUID.fromString("33333333-4444-4555-8666-777777777777")
        val experienceId = UUID.fromString("44444444-5555-4666-8777-888888888888")

        // Verify canonical secondlife/lsl-definitions hex constants on LludpPackets & ScriptAndEconomyProtocol
        assertEquals(0x0002, LludpPackets.PERMISSION_DEBIT)
        assertEquals(0x0004, LludpPackets.PERMISSION_TAKE_CONTROLS)
        assertEquals(0x0008, LludpPackets.PERMISSION_REMAP_CONTROLS)
        assertEquals(0x0010, LludpPackets.PERMISSION_TRIGGER_ANIMATION)
        assertEquals(0x0020, LludpPackets.PERMISSION_ATTACH)
        assertEquals(0x0040, LludpPackets.PERMISSION_RELEASE_OWNERSHIP)
        assertEquals(0x0080, LludpPackets.PERMISSION_CHANGE_LINKS)
        assertEquals(0x0100, LludpPackets.PERMISSION_CHANGE_JOINTS)
        assertEquals(0x0200, LludpPackets.PERMISSION_CHANGE_PERMISSIONS)
        assertEquals(0x0400, LludpPackets.PERMISSION_TRACK_CAMERA)
        assertEquals(0x0800, LludpPackets.PERMISSION_CONTROL_CAMERA)
        assertEquals(0x1000, LludpPackets.PERMISSION_TELEPORT)
        assertEquals(0x2000, LludpPackets.PERMISSION_EXPERIENCE)
        assertEquals(0x4000, LludpPackets.PERMISSION_SILENT_ESTATE_MANAGEMENT)
        assertEquals(0x8000, LludpPackets.PERMISSION_OVERRIDE_ANIMATIONS)
        assertEquals(0x10000, LludpPackets.PERMISSION_RETURN_OBJECTS)
        assertEquals(0x20000, LludpPackets.PERMISSION_CONTROL_MUTE_LIST)

        // Verify full 0x02..0x10000 catalog (16 bits) + 0x20000 (17th canonical bit)
        val mask02To10000 =
            LludpPackets.PERMISSION_DEBIT or
                LludpPackets.PERMISSION_TAKE_CONTROLS or
                LludpPackets.PERMISSION_REMAP_CONTROLS or
                LludpPackets.PERMISSION_TRIGGER_ANIMATION or
                LludpPackets.PERMISSION_ATTACH or
                LludpPackets.PERMISSION_RELEASE_OWNERSHIP or
                LludpPackets.PERMISSION_CHANGE_LINKS or
                LludpPackets.PERMISSION_CHANGE_JOINTS or
                LludpPackets.PERMISSION_CHANGE_PERMISSIONS or
                LludpPackets.PERMISSION_TRACK_CAMERA or
                LludpPackets.PERMISSION_CONTROL_CAMERA or
                LludpPackets.PERMISSION_TELEPORT or
                LludpPackets.PERMISSION_EXPERIENCE or
                LludpPackets.PERMISSION_SILENT_ESTATE_MANAGEMENT or
                LludpPackets.PERMISSION_OVERRIDE_ANIMATIONS or
                LludpPackets.PERMISSION_RETURN_OBJECTS
        assertEquals(0x1FFFE, mask02To10000)

        val decodedCatalog = ScriptAndEconomyProtocol.decodePermissionFlags(mask02To10000)
        assertEquals(16, decodedCatalog.size)
        assertEquals(mask02To10000, decodedCatalog.fold(0) { acc, info -> acc or info.mask })

        val byMask = decodedCatalog.associateBy { it.mask }
        assertEquals("Link and delink objects", byMask.getValue(LludpPackets.PERMISSION_CHANGE_LINKS).label)
        assertEquals("Modify object joints", byMask.getValue(LludpPackets.PERMISSION_CHANGE_JOINTS).label)
        assertEquals("Modify object permissions", byMask.getValue(LludpPackets.PERMISSION_CHANGE_PERMISSIONS).label)
        assertEquals("Act via region Experience", byMask.getValue(LludpPackets.PERMISSION_EXPERIENCE).label)
        assertEquals("Manage estate settings silently", byMask.getValue(LludpPackets.PERMISSION_SILENT_ESTATE_MANAGEMENT).label)
        assertEquals("Override avatar animations (AO)", byMask.getValue(LludpPackets.PERMISSION_OVERRIDE_ANIMATIONS).label)
        assertEquals("Return objects from land", byMask.getValue(LludpPackets.PERMISSION_RETURN_OBJECTS).label)

        // Test AO + Linkset + Experience ScriptQuestion packet (including trailing 16B ExperienceID block)
        val aoAndLinkMask =
            LludpPackets.PERMISSION_OVERRIDE_ANIMATIONS or
                LludpPackets.PERMISSION_TRIGGER_ANIMATION or
                LludpPackets.PERMISSION_TAKE_CONTROLS or
                LludpPackets.PERMISSION_CHANGE_LINKS or
                LludpPackets.PERMISSION_EXPERIENCE
        val objNameBytes = "Vista Bento AO HUD\u0000".toByteArray(Charsets.UTF_8)
        val ownerBytes = "Lyra Resident\u0000".toByteArray(Charsets.UTF_8)
        val aoBuf = ByteBuffer.allocate(16 + 16 + 1 + objNameBytes.size + 1 + ownerBytes.size + 4 + 16)
            .order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(aoBuf, taskId)
        LludpPackets.putUuid(aoBuf, itemId)
        aoBuf.put(objNameBytes.size.toByte()); aoBuf.put(objNameBytes)
        aoBuf.put(ownerBytes.size.toByte()); aoBuf.put(ownerBytes)
        aoBuf.putInt(aoAndLinkMask)
        LludpPackets.putUuid(aoBuf, experienceId)

        val framedAoQuestion = LludpPackets.framePacket(
            sequenceNumber = 88,
            reliable = true,
            messageId = LludpPackets.MSG_SCRIPT_QUESTION,
            body = aoBuf.array(),
            useZeroCoding = LludpPackets.isMessageZeroCoded(LludpPackets.MSG_SCRIPT_QUESTION)
        )
        val engine = LludpCircuitEngine()
        val deferredAo = async {
            withTimeout(2000L) {
                engine.events.first { it is SimEvent.ScriptQuestionReceived } as SimEvent.ScriptQuestionReceived
            }
        }
        kotlinx.coroutines.yield()
        assertTrue(engine.decodeAndDispatchForTesting(framedAoQuestion))
        val aoEvt = deferredAo.await()
        assertFalse("AO + Linkset permissions must not trigger debit warning", aoEvt.hasDebitPermission)
        assertEquals(experienceId, aoEvt.experienceId)
        assertEquals(5, aoEvt.permissions.size)
        assertTrue(aoEvt.permissions.any { it.mask == LludpPackets.PERMISSION_OVERRIDE_ANIMATIONS && it.badgeLabel == "AO" })
        assertTrue(aoEvt.permissions.any { it.mask == LludpPackets.PERMISSION_CHANGE_LINKS && it.label == "Link and delink objects" })

        // Verify PERMISSION_CONTROL_MUTE_LIST (0x20000) + unknown future Agni bits (0x40000, 0x80000000)
        // are formatted gracefully and echoed verbatim without dropping bits in ScriptAnswerYes
        val futureAgniMask =
            LludpPackets.PERMISSION_OVERRIDE_ANIMATIONS or
                LludpPackets.PERMISSION_CONTROL_MUTE_LIST or
                0x40000 or
                0x80000000.toInt()
        val decodedFuture = ScriptAndEconomyProtocol.decodePermissionFlags(futureAgniMask)
        assertEquals(4, decodedFuture.size)
        assertEquals(futureAgniMask, decodedFuture.fold(0) { acc, info -> acc or info.mask })
        assertTrue(decodedFuture.any { it.mask == LludpPackets.PERMISSION_CONTROL_MUTE_LIST && it.constantName == "PERMISSION_CONTROL_MUTE_LIST" })
        assertTrue(decodedFuture.any { it.mask == 0x40000 && it.label.contains("0x40000") })
        assertTrue(decodedFuture.any { it.mask == 0x80000000.toInt() && it.label.contains("0x80000000") })

        val resolvedGrantMask = ScriptAndEconomyProtocol.resolveScriptAnswerQuestions(futureAgniMask, grant = true)
        assertEquals(futureAgniMask, resolvedGrantMask)
        val answerBody = LludpPackets.buildScriptAnswerYes(agentId, sessionId, taskId, itemId, resolvedGrantMask)
        val framedAnswer = LludpPackets.framePacket(
            sequenceNumber = 89,
            reliable = true,
            messageId = LludpPackets.MSG_SCRIPT_ANSWER_YES,
            body = answerBody,
            useZeroCoding = LludpPackets.isMessageZeroCoded(LludpPackets.MSG_SCRIPT_ANSWER_YES)
        )
        val decodedAnswer = LludpPackets.decodePacket(framedAnswer, framedAnswer.size)
        assertNotNull(decodedAnswer)
        assertEquals(agentId, LludpPackets.readUuid(decodedAnswer!!.payload))
        assertEquals(sessionId, LludpPackets.readUuid(decodedAnswer.payload))
        assertEquals(taskId, LludpPackets.readUuid(decodedAnswer.payload))
        assertEquals(itemId, LludpPackets.readUuid(decodedAnswer.payload))
        assertEquals("Unknown higher Agni bits must never be dropped in ScriptAnswerYes", futureAgniMask, decodedAnswer.payload.int)
        assertEquals(0, ScriptAndEconomyProtocol.resolveScriptAnswerQuestions(futureAgniMask, grant = false))
    }

    @Test
    fun `verifies message_template zero-coding flags for ScriptAnswerYes MoneyBalanceRequest and MoneyTransferRequest`() {
        // Canonical message_template.msg encoding check:
        // - ScriptAnswerYes (Low 132 = 0xFFFF0084): NotTrusted Unencoded (false)
        // - ScriptQuestion  (Low 188 = 0xFFFF00BC): Trusted Unencoded (false)
        // - ScriptDialog    (Low 190 = 0xFFFF00BE): Trusted Zerocoded (true)
        // - ChatFromViewer  (Low 80  = 0xFFFF0050): NotTrusted Zerocoded (true)
        // - MoneyTransferRequest (Low 311 = 0xFFFF0137): NotTrusted Zerocoded (true)
        // - MoneyBalanceRequest  (Low 313 = 0xFFFF0139): NotTrusted Zerocoded (true)
        // - MoneyBalanceReply    (Low 314 = 0xFFFF013A): Trusted Zerocoded (true)
        assertEquals(0xFFFF0084.toInt(), LludpPackets.MSG_SCRIPT_ANSWER_YES)
        assertEquals(0xFFFF0139.toInt(), LludpPackets.MSG_MONEY_BALANCE_REQUEST)
        assertEquals(0xFFFF0137.toInt(), LludpPackets.MSG_MONEY_TRANSFER_REQUEST)

        assertFalse("ScriptAnswerYes is Unencoded in message_template.msg", LludpPackets.isMessageZeroCoded(LludpPackets.MSG_SCRIPT_ANSWER_YES))
        assertFalse("ScriptQuestion is Unencoded in message_template.msg", LludpPackets.isMessageZeroCoded(LludpPackets.MSG_SCRIPT_QUESTION))
        assertTrue("ScriptDialog is Zerocoded in message_template.msg", LludpPackets.isMessageZeroCoded(LludpPackets.MSG_SCRIPT_DIALOG))
        assertTrue("ChatFromViewer is Zerocoded in message_template.msg", LludpPackets.isMessageZeroCoded(LludpPackets.MSG_CHAT_FROM_VIEWER))
        assertTrue("MoneyBalanceRequest is Zerocoded in message_template.msg", LludpPackets.isMessageZeroCoded(LludpPackets.MSG_MONEY_BALANCE_REQUEST))
        assertTrue("MoneyTransferRequest is Zerocoded in message_template.msg", LludpPackets.isMessageZeroCoded(LludpPackets.MSG_MONEY_TRANSFER_REQUEST))
        assertTrue("MoneyBalanceReply is Zerocoded in message_template.msg", LludpPackets.isMessageZeroCoded(LludpPackets.MSG_MONEY_BALANCE_REPLY))

        val zeroHeavyUuid = UUID(0L, 1L)

        // 1. ScriptAnswerYes (Unencoded): even with zero-heavy UUIDs + Questions=0, FLAG_ZEROCODED (0x80) must NOT be set
        val answerBody = LludpPackets.buildScriptAnswerYes(zeroHeavyUuid, zeroHeavyUuid, zeroHeavyUuid, zeroHeavyUuid, 0)
        val framedAnswer = LludpPackets.framePacket(
            sequenceNumber = 1,
            reliable = true,
            messageId = LludpPackets.MSG_SCRIPT_ANSWER_YES,
            body = answerBody,
            useZeroCoding = LludpPackets.isMessageZeroCoded(LludpPackets.MSG_SCRIPT_ANSWER_YES)
        )
        assertEquals("Unencoded ScriptAnswerYes must only set FLAG_RELIABLE (0x40)", LludpPackets.FLAG_RELIABLE, framedAnswer[0].toInt() and 0xFF)
        assertEquals(6 + 4 + 68, framedAnswer.size)

        // 2. MoneyBalanceRequest (Zerocoded): zero-heavy TransactionID(0,0) must compress and set FLAG_ZEROCODED (0x80)
        val balReqBody = LludpPackets.buildMoneyBalanceRequest(zeroHeavyUuid, zeroHeavyUuid, UUID(0L, 0L))
        val framedBalReq = LludpPackets.framePacket(
            sequenceNumber = 2,
            reliable = true,
            messageId = LludpPackets.MSG_MONEY_BALANCE_REQUEST,
            body = balReqBody,
            useZeroCoding = LludpPackets.isMessageZeroCoded(LludpPackets.MSG_MONEY_BALANCE_REQUEST)
        )
        assertTrue("Zerocoded MoneyBalanceRequest must set FLAG_ZEROCODED", (framedBalReq[0].toInt() and LludpPackets.FLAG_ZEROCODED) != 0)
        assertTrue("Zerocoded MoneyBalanceRequest must compress zero runs", framedBalReq.size < 6 + 4 + 48)
        val decodedBalReq = LludpPackets.decodePacket(framedBalReq, framedBalReq.size)
        assertNotNull(decodedBalReq)
        assertEquals(LludpPackets.MSG_MONEY_BALANCE_REQUEST, decodedBalReq!!.messageId)
        assertEquals(48, decodedBalReq.payload.remaining())

        // 3. MoneyTransferRequest (Zerocoded): zero-run fields compress and set FLAG_ZEROCODED (0x80)
        val transferBody = LludpPackets.buildMoneyTransferRequest(
            agentId = zeroHeavyUuid,
            sessionId = zeroHeavyUuid,
            sourceId = zeroHeavyUuid,
            destId = zeroHeavyUuid,
            amount = 50,
            transactionType = LludpPackets.TRANS_PAY_OBJECT,
            description = ""
        )
        val framedTransfer = LludpPackets.framePacket(
            sequenceNumber = 3,
            reliable = true,
            messageId = LludpPackets.MSG_MONEY_TRANSFER_REQUEST,
            body = transferBody,
            useZeroCoding = LludpPackets.isMessageZeroCoded(LludpPackets.MSG_MONEY_TRANSFER_REQUEST)
        )
        assertTrue("Zerocoded MoneyTransferRequest must set FLAG_ZEROCODED", (framedTransfer[0].toInt() and LludpPackets.FLAG_ZEROCODED) != 0)
        assertTrue("Zerocoded MoneyTransferRequest must compress zero runs", framedTransfer.size < 6 + 4 + transferBody.size)
        val decodedTransfer = LludpPackets.decodePacket(framedTransfer, framedTransfer.size)
        assertNotNull(decodedTransfer)
        val parsedTransfer = ScriptAndEconomyProtocol.parseMoneyTransferRequest(decodedTransfer!!.payload)
        assertNotNull(parsedTransfer)
        assertEquals(50, parsedTransfer!!.amount)
        assertEquals(LludpPackets.TRANS_PAY_OBJECT, parsedTransfer.transactionType)
    }
}
