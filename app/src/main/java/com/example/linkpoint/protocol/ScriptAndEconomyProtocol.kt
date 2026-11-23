package com.example.linkpoint.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

/**
 * Modularized Second Life & OpenSimulator LSL Script Permission, `llTextBox`, and `L$` Economy
 * protocol codecs, constants, and bitmask helpers.
 */
object ScriptAndEconomyProtocol {

    // Canonical LSL Permission Bitmask Flags (`secondlife/lsl-definitions` `lsl_definitions.yaml` / `ScriptQuestion`)
    const val PERMISSION_DEBIT: Int = 0x0002
    const val PERMISSION_TAKE_CONTROLS: Int = 0x0004
    const val PERMISSION_REMAP_CONTROLS: Int = 0x0008
    const val PERMISSION_TRIGGER_ANIMATION: Int = 0x0010
    const val PERMISSION_ATTACH: Int = 0x0020
    const val PERMISSION_RELEASE_OWNERSHIP: Int = 0x0040
    const val PERMISSION_CHANGE_LINKS: Int = 0x0080
    const val PERMISSION_CHANGE_JOINTS: Int = 0x0100
    const val PERMISSION_CHANGE_PERMISSIONS: Int = 0x0200
    const val PERMISSION_TRACK_CAMERA: Int = 0x0400
    const val PERMISSION_CONTROL_CAMERA: Int = 0x0800
    const val PERMISSION_TELEPORT: Int = 0x1000
    const val PERMISSION_EXPERIENCE: Int = 0x2000
    const val PERMISSION_SILENT_ESTATE_MANAGEMENT: Int = 0x4000
    const val PERMISSION_OVERRIDE_ANIMATIONS: Int = 0x8000
    const val PERMISSION_RETURN_OBJECTS: Int = 0x10000
    const val PERMISSION_CONTROL_MUTE_LIST: Int = 0x20000

    const val ALL_LSL_DEFINITIONS_PERMISSIONS_MASK: Int =
        PERMISSION_DEBIT or
            PERMISSION_TAKE_CONTROLS or
            PERMISSION_REMAP_CONTROLS or
            PERMISSION_TRIGGER_ANIMATION or
            PERMISSION_ATTACH or
            PERMISSION_RELEASE_OWNERSHIP or
            PERMISSION_CHANGE_LINKS or
            PERMISSION_CHANGE_JOINTS or
            PERMISSION_CHANGE_PERMISSIONS or
            PERMISSION_TRACK_CAMERA or
            PERMISSION_CONTROL_CAMERA or
            PERMISSION_TELEPORT or
            PERMISSION_EXPERIENCE or
            PERMISSION_SILENT_ESTATE_MANAGEMENT or
            PERMISSION_OVERRIDE_ANIMATIONS or
            PERMISSION_RETURN_OBJECTS or
            PERMISSION_CONTROL_MUTE_LIST

    // Canonical Second Life Economy Transaction Types (MoneyTransferRequest)
    const val TRANS_PAY_GIFT: Int = 5001
    const val TRANS_PAY_OBJECT: Int = 5008

    // Offline LocalSandbox starting balance (L$ 1,250)
    const val SANDBOX_DEFAULT_BALANCE: Int = 1250

    // Second Life simulator sentinel button label indicating an `llTextBox` prompt
    const val LL_TEXT_BOX_SENTINEL: String = "!!llTextBox!!"

    /**
     * Returns whether [messageId] is marked `Zerocoded` (`true`) or `Unencoded` (`false`)
     * in canonical `indra/llmessage/message_template.msg`:
     * - `ChatFromViewer` (`Low 80 = 0xFFFF0050`): `NotTrusted Zerocoded` (`true`)
     * - `ScriptAnswerYes` (`Low 132 = 0xFFFF0084`): `NotTrusted Unencoded` (`false`)
     * - `ScriptQuestion` (`Low 188 = 0xFFFF00BC`): `Trusted Unencoded` (`false`)
     * - `ScriptDialog` (`Low 190 = 0xFFFF00BE`): `Trusted Zerocoded` (`true`)
     * - `MoneyTransferRequest` (`Low 311 = 0xFFFF0137`): `NotTrusted Zerocoded` (`true`)
     * - `MoneyBalanceRequest` (`Low 313 = 0xFFFF0139`): `NotTrusted Zerocoded` (`true`)
     * - `MoneyBalanceReply` (`Low 314 = 0xFFFF013A`): `Trusted Zerocoded` (`true`)
     * - `RoutedMoneyBalanceReply` (`Low 315 = 0xFFFF013B`): `Trusted Zerocoded` (`true`)
     */
    fun isMessageZeroCoded(messageId: Int): Boolean = when (messageId) {
        LludpPackets.MSG_SCRIPT_ANSWER_YES,
        LludpPackets.MSG_SCRIPT_QUESTION -> false
        LludpPackets.MSG_CHAT_FROM_VIEWER,
        LludpPackets.MSG_SCRIPT_DIALOG,
        LludpPackets.MSG_MONEY_TRANSFER_REQUEST,
        LludpPackets.MSG_MONEY_BALANCE_REQUEST,
        LludpPackets.MSG_MONEY_BALANCE_REPLY,
        LludpPackets.MSG_ROUTED_MONEY_BALANCE_REPLY -> true
        else -> false
    }

    data class LslPermissionInfo(
        val mask: Int,
        val constantName: String,
        val label: String,
        val detail: String,
        val isDebitWarning: Boolean = false,
        val isCaution: Boolean = false,
        val badgeLabel: String? = null
    )

    private val KNOWN_PERMISSIONS: List<LslPermissionInfo> = listOf(
        LslPermissionInfo(
            mask = PERMISSION_DEBIT,
            constantName = "PERMISSION_DEBIT",
            label = "Debit L$ from your account",
            detail = "Allows this script to take L$ from your balance without asking again until reset.",
            isDebitWarning = true,
            isCaution = true,
            badgeLabel = "DEBIT L$"
        ),
        LslPermissionInfo(
            mask = PERMISSION_TAKE_CONTROLS,
            constantName = "PERMISSION_TAKE_CONTROLS",
            label = "Take movement & keyboard controls",
            detail = "Allows vehicle, HUD, or combat scripts to read or override movement keys."
        ),
        LslPermissionInfo(
            mask = PERMISSION_REMAP_CONTROLS,
            constantName = "PERMISSION_REMAP_CONTROLS",
            label = "Remap keyboard controls",
            detail = "Allows this script to remap viewer control bindings."
        ),
        LslPermissionInfo(
            mask = PERMISSION_TRIGGER_ANIMATION,
            constantName = "PERMISSION_TRIGGER_ANIMATION",
            label = "Animate your avatar",
            detail = "Allows furniture, dances, or poseballs to start and stop animations on your avatar."
        ),
        LslPermissionInfo(
            mask = PERMISSION_ATTACH,
            constantName = "PERMISSION_ATTACH",
            label = "Attach or detach from your avatar",
            detail = "Allows scripted props or HUDs to attach to or detach from your avatar."
        ),
        LslPermissionInfo(
            mask = PERMISSION_RELEASE_OWNERSHIP,
            constantName = "PERMISSION_RELEASE_OWNERSHIP",
            label = "Release ownership to public",
            detail = "Allows the object to relinquish owner permissions."
        ),
        LslPermissionInfo(
            mask = PERMISSION_CHANGE_LINKS,
            constantName = "PERMISSION_CHANGE_LINKS",
            label = "Link and delink objects",
            detail = "Allows the script to link or unlink prims in its linkset."
        ),
        LslPermissionInfo(
            mask = PERMISSION_CHANGE_JOINTS,
            constantName = "PERMISSION_CHANGE_JOINTS",
            label = "Modify object joints",
            detail = "Allows the script to alter physical joint parameters."
        ),
        LslPermissionInfo(
            mask = PERMISSION_CHANGE_PERMISSIONS,
            constantName = "PERMISSION_CHANGE_PERMISSIONS",
            label = "Modify object permissions",
            detail = "Allows the script to alter object or inventory permissions.",
            isCaution = true
        ),
        LslPermissionInfo(
            mask = PERMISSION_TRACK_CAMERA,
            constantName = "PERMISSION_TRACK_CAMERA",
            label = "Track your camera position",
            detail = "Allows the script to read where your camera is looking."
        ),
        LslPermissionInfo(
            mask = PERMISSION_CONTROL_CAMERA,
            constantName = "PERMISSION_CONTROL_CAMERA",
            label = "Control your camera",
            detail = "Allows the script to move or lock your camera focus while seated or attached."
        ),
        LslPermissionInfo(
            mask = PERMISSION_TELEPORT,
            constantName = "PERMISSION_TELEPORT",
            label = "Teleport your avatar",
            detail = "Allows portals, Experiences, or HUDs to teleport your avatar directly."
        ),
        LslPermissionInfo(
            mask = PERMISSION_EXPERIENCE,
            constantName = "PERMISSION_EXPERIENCE",
            label = "Act via region Experience",
            detail = "Grants region Experience runtime permissions to this script.",
            badgeLabel = "EXPERIENCE"
        ),
        LslPermissionInfo(
            mask = PERMISSION_SILENT_ESTATE_MANAGEMENT,
            constantName = "PERMISSION_SILENT_ESTATE_MANAGEMENT",
            label = "Manage estate settings silently",
            detail = "Allows automated estate management operations without per-action prompts.",
            isCaution = true,
            badgeLabel = "ESTATE"
        ),
        LslPermissionInfo(
            mask = PERMISSION_OVERRIDE_ANIMATIONS,
            constantName = "PERMISSION_OVERRIDE_ANIMATIONS",
            label = "Override avatar animations (AO)",
            detail = "Allows Animation Overriders (AOs) to replace default walk, stand, and fly animations.",
            badgeLabel = "AO"
        ),
        LslPermissionInfo(
            mask = PERMISSION_RETURN_OBJECTS,
            constantName = "PERMISSION_RETURN_OBJECTS",
            label = "Return objects from land",
            detail = "Allows automated parcel/estate security orbs to return objects from land you manage.",
            isCaution = true,
            badgeLabel = "LAND"
        ),
        LslPermissionInfo(
            mask = PERMISSION_CONTROL_MUTE_LIST,
            constantName = "PERMISSION_CONTROL_MUTE_LIST",
            label = "Manage your mute/block list",
            detail = "Allows viewer-managed or Experience scripts to update mute list entries.",
            isCaution = true,
            badgeLabel = "MUTE LIST"
        )
    )

    fun formatPermissionBitmask(mask: Int): String =
        "0x" + Integer.toUnsignedString(mask, 16).uppercase(Locale.US)

    fun hasDebitPermission(questions: Int): Boolean =
        (questions and PERMISSION_DEBIT) != 0

    /**
     * Resolves the exact `Questions` S32 bitmask to transmit in `MSG_SCRIPT_ANSWER_YES` (`0xFFFF0084`).
     * When [grant] is `true`, echoes [requestedQuestions] verbatim (including any new or unknown higher
     * Agni bits so future simulator permissions are never silently dropped). When `false`, returns `0`.
     */
    fun resolveScriptAnswerQuestions(requestedQuestions: Int, grant: Boolean): Int =
        if (grant) requestedQuestions else 0

    fun decodePermissionFlags(questions: Int): List<LslPermissionInfo> {
        if (questions == 0) return emptyList()
        val matched = KNOWN_PERMISSIONS.filter { (questions and it.mask) != 0 }.toMutableList()
        var unknownBits = questions and ALL_LSL_DEFINITIONS_PERMISSIONS_MASK.inv()
        while (unknownBits != 0) {
            val singleBit = Integer.lowestOneBit(unknownBits)
            val hex = formatPermissionBitmask(singleBit)
            matched.add(
                LslPermissionInfo(
                    mask = singleBit,
                    constantName = "PERMISSION_UNKNOWN_$hex",
                    label = "Extended script permission ($hex)",
                    detail = "Unrecognized Agni/OpenSim runtime permission bit ($hex); preserved verbatim if granted.",
                    isCaution = true,
                    badgeLabel = "EXTENDED"
                )
            )
            unknownBits = unknownBits xor singleBit
        }
        return matched
    }

    fun extractScriptDialogButtons(rawButtons: List<String>): Pair<List<String>, Boolean> {
        val cleaned = rawButtons.map { it.trim() }.filter { it.isNotEmpty() }
        val isTextBox = cleaned.any { it.equals(LL_TEXT_BOX_SENTINEL, ignoreCase = true) }
        val visibleButtons = if (isTextBox) {
            cleaned.filterNot { it.equals(LL_TEXT_BOX_SENTINEL, ignoreCase = true) }
        } else {
            cleaned
        }
        return visibleButtons to isTextBox
    }

    fun formatLindenAmount(amount: Int): String {
        val formatted = NumberFormat.getIntegerInstance(Locale.US).format(amount)
        return "L$ $formatted"
    }

    data class ParsedScriptQuestion(
        val taskId: UUID,
        val itemId: UUID,
        val objectName: String,
        val objectOwner: String,
        val questions: Int,
        val experienceId: UUID = UUID(0L, 0L),
        val permissions: List<LslPermissionInfo> = decodePermissionFlags(questions),
        val hasDebitPermission: Boolean = hasDebitPermission(questions)
    )

    data class ParsedScriptDialog(
        val objectId: UUID,
        val firstName: String,
        val lastName: String,
        val objectName: String,
        val message: String,
        val chatChannel: Int,
        val imageId: UUID,
        val buttons: List<String>,
        val isTextBox: Boolean
    ) {
        val ownerName: String
            get() = "$firstName $lastName".trim()
    }

    data class ParsedMoneyBalanceReply(
        val agentId: UUID,
        val transactionId: UUID,
        val transactionSuccess: Boolean,
        val moneyBalance: Int,
        val squareMetersCredit: Int,
        val squareMetersCommitted: Int,
        val description: String,
        val transactionType: Int = 0,
        val sourceId: UUID = UUID(0L, 0L),
        val isSourceGroup: Boolean = false,
        val destId: UUID = UUID(0L, 0L),
        val isDestGroup: Boolean = false,
        val amount: Int = 0,
        val itemDescription: String = ""
    )

    data class ParsedMoneyTransferRequest(
        val agentId: UUID,
        val sessionId: UUID,
        val sourceId: UUID,
        val destId: UUID,
        val flags: Int,
        val amount: Int,
        val aggregatePermNextOwner: Int,
        val aggregatePermInventory: Int,
        val transactionType: Int,
        val description: String
    )

    /**
     * Encodes a UTF-8 C-string capped safely to 255 total bytes (254 UTF-8 bytes + trailing NUL)
     * so its length fits cleanly in a 1-byte unsigned `Variable 1` length prefix.
     */
    fun encodeVar1CString(text: String): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= 254) {
            return raw + byteArrayOf(0)
        }
        var cut = 254
        while (cut > 0 && (raw[cut].toInt() and 0xC0) == 0x80) {
            cut--
        }
        return raw.copyOf(cut) + byteArrayOf(0)
    }

    /**
     * Decodes `MSG_SCRIPT_QUESTION` (`0xFFFF00BC` Low 188 `Trusted Unencoded`).
     * Wire layout: TaskID (16B), ItemID (16B), ObjectName (Var 1), ObjectOwner (Var 1), Questions (S32 LE),
     * plus optional `Experience` block (`ExperienceID` 16B LLUUID on SL Agni simulators).
     */
    fun parseScriptQuestion(buf: ByteBuffer): ParsedScriptQuestion? {
        if (buf.remaining() < 38) return null
        val prevOrder = buf.order()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return try {
            val taskId = LludpPackets.readUuid(buf)
            val itemId = LludpPackets.readUuid(buf)
            if (buf.remaining() < 1) return null
            val nameLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < nameLen + 1) return null
            val objectName = LludpPackets.readCString(buf, nameLen).ifBlank { "Scripted Object" }
            val ownerLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < ownerLen + 4) return null
            val objectOwner = LludpPackets.readCString(buf, ownerLen)
            val questions = buf.int
            val experienceId = if (buf.remaining() >= 16) LludpPackets.readUuid(buf) else UUID(0L, 0L)
            ParsedScriptQuestion(
                taskId = taskId,
                itemId = itemId,
                objectName = objectName,
                objectOwner = objectOwner,
                questions = questions,
                experienceId = experienceId
            )
        } finally {
            buf.order(prevOrder)
        }
    }

    /**
     * Builds `MSG_SCRIPT_ANSWER_YES` (`0xFFFF0084` Low 132 `NotTrusted Unencoded`).
     * Wire layout (68 bytes): AgentID (16B), SessionID (16B), TaskID (16B), ItemID (16B), Questions (S32 LE).
     * Pass `questions = 0` to explicitly deny requested permissions, or the full `ScriptQuestion.Questions`
     * bitmask to grant without dropping unrecognized higher Agni bits.
     */
    fun buildScriptAnswerYes(
        agentId: UUID,
        sessionId: UUID,
        taskId: UUID,
        itemId: UUID,
        questions: Int
    ): ByteArray {
        val buf = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, taskId)
        LludpPackets.putUuid(buf, itemId)
        buf.putInt(questions)
        return buf.array()
    }

    /**
     * Decodes `MSG_SCRIPT_DIALOG` (`0xFFFF00BE` Low 190) UDP fallback packet, including `!!llTextBox!!` detection.
     */
    fun parseScriptDialog(buf: ByteBuffer): ParsedScriptDialog? {
        if (buf.remaining() < 42) return null
        val prevOrder = buf.order()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return try {
            val objectId = LludpPackets.readUuid(buf)
            if (buf.remaining() < 1) return null
            val firstLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < firstLen + 1) return null
            val firstName = LludpPackets.readCString(buf, firstLen)

            val lastLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < lastLen + 1) return null
            val lastName = LludpPackets.readCString(buf, lastLen)

            val objNameLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < objNameLen + 2) return null
            val objectName = LludpPackets.readCString(buf, objNameLen).ifBlank { "Scripted Object" }

            val msgLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < msgLen + 21) return null
            val message = LludpPackets.readCString(buf, msgLen)

            val chatChannel = buf.int
            val imageId = LludpPackets.readUuid(buf)

            val buttonCount = buf.get().toInt() and 0xFF
            val rawButtons = ArrayList<String>(buttonCount)
            repeat(buttonCount) {
                if (buf.remaining() < 1) return@repeat
                val lblLen = buf.get().toInt() and 0xFF
                if (buf.remaining() < lblLen) return@repeat
                rawButtons.add(LludpPackets.readCString(buf, lblLen))
            }
            val (buttons, isTextBox) = extractScriptDialogButtons(rawButtons)
            ParsedScriptDialog(
                objectId = objectId,
                firstName = firstName,
                lastName = lastName,
                objectName = objectName,
                message = message,
                chatChannel = chatChannel,
                imageId = imageId,
                buttons = buttons,
                isTextBox = isTextBox
            )
        } finally {
            buf.order(prevOrder)
        }
    }

    /**
     * Builds `MSG_MONEY_BALANCE_REQUEST` (`0xFFFF0139` Low 313).
     * Wire layout (48 bytes): AgentID (16B), SessionID (16B), TransactionID (16B).
     */
    fun buildMoneyBalanceRequest(
        agentId: UUID,
        sessionId: UUID,
        transactionId: UUID = UUID(0L, 0L)
    ): ByteArray {
        val buf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, transactionId)
        return buf.array()
    }

    /**
     * Decodes `MSG_MONEY_BALANCE_REPLY` (`0xFFFF013A` Low 314) and `MSG_ROUTED_MONEY_BALANCE_REPLY` (`0xFFFF013B` Low 315).
     */
    fun parseMoneyBalanceReply(buf: ByteBuffer, hasRoutedTargetHeader: Boolean = false): ParsedMoneyBalanceReply? {
        val prevOrder = buf.order()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return try {
            if (hasRoutedTargetHeader) {
                if (buf.remaining() < 6) return null
                buf.int // TargetIP (4B)
                buf.short // TargetPort (2B)
            }
            if (buf.remaining() < 46) return null
            val agentId = LludpPackets.readUuid(buf)
            val transactionId = LludpPackets.readUuid(buf)
            val transactionSuccess = buf.get().toInt() != 0
            val moneyBalance = buf.int
            val squareMetersCredit = buf.int
            val squareMetersCommitted = buf.int
            val descLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < descLen) return null
            val description = LludpPackets.readCString(buf, descLen).trim()

            var transactionType = 0
            var sourceId = UUID(0L, 0L)
            var isSourceGroup = false
            var destId = UUID(0L, 0L)
            var isDestGroup = false
            var amount = 0
            var itemDescription = ""

            if (buf.remaining() >= 43) {
                transactionType = buf.int
                sourceId = LludpPackets.readUuid(buf)
                isSourceGroup = buf.get().toInt() != 0
                destId = LludpPackets.readUuid(buf)
                isDestGroup = buf.get().toInt() != 0
                amount = buf.int
                val itemDescLen = buf.get().toInt() and 0xFF
                if (buf.remaining() >= itemDescLen) {
                    itemDescription = LludpPackets.readCString(buf, itemDescLen).trim()
                }
            }

            ParsedMoneyBalanceReply(
                agentId = agentId,
                transactionId = transactionId,
                transactionSuccess = transactionSuccess,
                moneyBalance = moneyBalance,
                squareMetersCredit = squareMetersCredit,
                squareMetersCommitted = squareMetersCommitted,
                description = description,
                transactionType = transactionType,
                sourceId = sourceId,
                isSourceGroup = isSourceGroup,
                destId = destId,
                isDestGroup = isDestGroup,
                amount = amount,
                itemDescription = itemDescription
            )
        } finally {
            buf.order(prevOrder)
        }
    }

    /**
     * Builds `MSG_MONEY_TRANSFER_REQUEST` (`0xFFFF0137` Low 311).
     * Wire layout: AgentID(16B), SessionID(16B), SourceID(16B), DestID(16B), Flags(1B),
     * Amount(4B LE), AggregatePermNextOwner(1B), AggregatePermInventory(1B),
     * TransactionType(4B LE — e.g. 5001 PayGift, 5008 PayObject), Description(Var 1).
     */
    fun buildMoneyTransferRequest(
        agentId: UUID,
        sessionId: UUID,
        sourceId: UUID = agentId,
        destId: UUID,
        amount: Int,
        transactionType: Int = TRANS_PAY_GIFT,
        flags: Int = 0,
        aggregatePermNextOwner: Int = 0,
        aggregatePermInventory: Int = 0,
        description: String = ""
    ): ByteArray {
        val descBytes = encodeVar1CString(description.trim())
        val buf = ByteBuffer.allocate(16 * 4 + 1 + 4 + 1 + 1 + 4 + 1 + descBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, sourceId)
        LludpPackets.putUuid(buf, destId)
        buf.put((flags and 0xFF).toByte())
        buf.putInt(amount)
        buf.put((aggregatePermNextOwner and 0xFF).toByte())
        buf.put((aggregatePermInventory and 0xFF).toByte())
        buf.putInt(transactionType)
        buf.put((descBytes.size and 0xFF).toByte())
        buf.put(descBytes)
        return buf.array()
    }

    /**
     * Decodes a `MoneyTransferRequest` body for round-trip verification & diagnostics.
     */
    fun parseMoneyTransferRequest(buf: ByteBuffer): ParsedMoneyTransferRequest? {
        if (buf.remaining() < 76) return null
        val prevOrder = buf.order()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return try {
            val agentId = LludpPackets.readUuid(buf)
            val sessionId = LludpPackets.readUuid(buf)
            val sourceId = LludpPackets.readUuid(buf)
            val destId = LludpPackets.readUuid(buf)
            val flags = buf.get().toInt() and 0xFF
            val amount = buf.int
            val aggregatePermNextOwner = buf.get().toInt() and 0xFF
            val aggregatePermInventory = buf.get().toInt() and 0xFF
            val transactionType = buf.int
            val descLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < descLen) return null
            val description = LludpPackets.readCString(buf, descLen)
            ParsedMoneyTransferRequest(
                agentId = agentId,
                sessionId = sessionId,
                sourceId = sourceId,
                destId = destId,
                flags = flags,
                amount = amount,
                aggregatePermNextOwner = aggregatePermNextOwner,
                aggregatePermInventory = aggregatePermInventory,
                transactionType = transactionType,
                description = description
            )
        } finally {
            buf.order(prevOrder)
        }
    }
}
