package com.example.linkpoint.rlv

import java.util.UUID

/**
 * Constants, data models, canonical LSL/RLVa tables, and host callback contracts
 * for the Linkpoint RLV / RLVa behavioral restriction engine.
 */
object RlvConstants {
    /** Second Life ChatFromSimulator ChatType for llOwnerSay(). */
    const val CHAT_TYPE_OWNER: Int = 8

    /** Second Life ChatFromSimulator SourceType for in-world scripted objects / attachments. */
    const val SOURCE_TYPE_OBJECT: Int = 2

    /** Second Life ChatFromViewer ChatType values. */
    const val CHAT_TYPE_WHISPER: Int = 0
    const val CHAT_TYPE_NORMAL: Int = 1
    const val CHAT_TYPE_SHOUT: Int = 2

    /** Standard RLV / RLVa handshake version strings & numeric version (MNNPPBB). */
    const val RLV_VERSION_REPLY: String = "RestrainedLife viewer v2.8.0 (RLVa 2.4.2 / Linkpoint 3.4.3)"
    const val RLV_VERSION_NEW_REPLY: String = "RestrainedLove viewer v2.8.0 (RLVa 2.4.2 / Linkpoint 3.4.3)"
    const val RLV_VERSION_NUM_REPLY: String = "2080000"

    /** Null UUID returned by @getsitid when not seated on an object. */
    val ZERO_UUID: UUID = UUID(0L, 0L)

    /** Redacted placeholder displayed in UI when @shownames=n or @showloc=n is active. */
    const val REDACTED_TEXT: String = "(Hidden)"

    /**
     * Canonical Second Life wearable layers in exact RLV @getoutfit bitmask order (15 layers).
     * Matches Marine Kelley RLV & Firestorm RLVa specification:
     * gloves, jacket, pants, shirt, shoes, skirt, socks, underpants, undershirt,
     * skin, eyes, hair, shape, alpha, tattoo (plus physics optional in newer extensions;
     * classic 15-char bitmask is expected by OpenCollar & standard RLV scripts, with skin/eyes/hair/shape
     * excluded or included? Wait: check RLVCmdGetOutfit: bodyparts skin/eyes/hair/shape are skipped when isBodyPart()!
     * Wait — in RLV spec, @getoutfit returns 15 digits:
     * gloves,jacket,pants,shirt,shoes,skirt,socks,underpants,undershirt,skin,eyes,hair,shape,alpha,tattoo).
     */
    val OUTFIT_LAYERS: List<String> = listOf(
        "gloves",
        "jacket",
        "pants",
        "shirt",
        "shoes",
        "skirt",
        "socks",
        "underpants",
        "undershirt",
        "skin",
        "eyes",
        "hair",
        "shape",
        "alpha",
        "tattoo"
    )

    /** Body-part layers that cannot be completely removed (only replaced) in Second Life. */
    val BODY_PART_LAYERS: Set<String> = setOf("skin", "eyes", "hair", "shape")

    /**
     * Canonical LSL / RLV attachment point names indexed 0..55.
     * Indices 0..40 form the classic 41-character @getattach bitmask;
     * indices 41..55 cover Bento attachment points for @getattach:<pt> and @detach:<pt>.
     */
    val ATTACHMENT_POINT_NAMES: Array<String> = arrayOf(
        "none",               // 0
        "chest",              // 1
        "skull",              // 2
        "left shoulder",      // 3
        "right shoulder",     // 4
        "left hand",          // 5
        "right hand",         // 6
        "left foot",          // 7
        "right foot",         // 8
        "spine",              // 9
        "pelvis",             // 10
        "mouth",              // 11
        "chin",               // 12
        "left ear",           // 13
        "right ear",          // 14
        "left eyeball",       // 15
        "right eyeball",      // 16
        "nose",               // 17
        "r upper arm",        // 18
        "r forearm",          // 19
        "l upper arm",        // 20
        "l forearm",          // 21
        "right hip",          // 22
        "r upper leg",        // 23
        "r lower leg",        // 24
        "left hip",           // 25
        "l upper leg",        // 26
        "l lower leg",        // 27
        "stomach",            // 28
        "left pec",           // 29
        "right pec",          // 30
        "center 2",           // 31 (HUD)
        "top right",          // 32 (HUD)
        "top",                // 33 (HUD)
        "top left",           // 34 (HUD)
        "center",             // 35 (HUD)
        "bottom left",        // 36 (HUD)
        "bottom",             // 37 (HUD)
        "bottom right",       // 38 (HUD)
        "neck",               // 39
        "root",               // 40 (Avatar Center / Root in RLV)
        "left ring finger",   // 41 (Bento)
        "right ring finger",  // 42
        "tail base",          // 43
        "tail tip",           // 44
        "left wing",          // 45
        "right wing",         // 46
        "jaw",                // 47
        "alt left ear",       // 48
        "alt right ear",      // 49
        "alt left eye",       // 50
        "alt right eye",      // 51
        "tongue",             // 52
        "groin",              // 53
        "left hind foot",     // 54
        "right hind foot"     // 55
    )

    /** Number of classic LSL attachment slots returned by bare @getattach=<chan>. */
    const val CLASSIC_GETATTACH_COUNT: Int = 41

    private val attachmentAliasToIndex: Map<String, Int> by lazy {
        buildMap {
            ATTACHMENT_POINT_NAMES.forEachIndexed { idx, canonical ->
                put(canonical, idx)
            }
            put("avatar center", 40)
            put("l shoulder", 3)
            put("r shoulder", 4)
            put("l hand", 5)
            put("r hand", 6)
            put("lhand", 5)
            put("rhand", 6)
            put("l foot", 7)
            put("r foot", 8)
            put("lfoot", 7)
            put("rfoot", 8)
            put("l ear", 13)
            put("r ear", 14)
            put("l eye", 15)
            put("r eye", 16)
            put("l eyeball", 15)
            put("r eyeball", 16)
            put("right upper arm", 18)
            put("right forearm", 19)
            put("left upper arm", 20)
            put("left forearm", 21)
            put("r hip", 22)
            put("right upper leg", 23)
            put("right lower leg", 24)
            put("l hip", 25)
            put("left upper leg", 26)
            put("left lower leg", 27)
            put("belly", 28)
            put("l pec", 29)
            put("r pec", 30)
            put("hud center 2", 31)
            put("hud top right", 32)
            put("hud top", 33)
            put("hud top left", 34)
            put("hud center", 35)
            put("hud bottom left", 36)
            put("hud bottom", 37)
            put("hud bottom right", 38)
            put("l ring finger", 41)
            put("r ring finger", 42)
            put("l wing", 45)
            put("r wing", 46)
            put("l hind foot", 54)
            put("r hind foot", 55)
        }
    }

    /** Resolves an RLV attachment point name or alias to its SL attachment point ID (0..55), or null. */
    fun resolveAttachmentPoint(name: String): Int? {
        val normalized = name.trim().lowercase().replace('_', ' ')
        if (normalized.isEmpty()) return null
        return attachmentAliasToIndex[normalized]
    }

    /** Returns the canonical lowercase RLV attachment point name for a point ID, or null if out of range. */
    fun canonicalAttachmentPointName(pointId: Int): String? {
        return ATTACHMENT_POINT_NAMES.getOrNull(pointId)
    }

    /** Infers a canonical wearable layer name from an inventory item's name and assetType. */
    fun inferWearableLayer(itemName: String, assetType: Int): String? {
        if (assetType != 5 && assetType != 13) return null
        val lower = itemName.lowercase()
        return when {
            "glove" in lower -> "gloves"
            "undershirt" in lower -> "undershirt"
            "underpant" in lower || "brief" in lower || "lingerie" in lower -> "underpants"
            "jacket" in lower || "coat" in lower || "hoodie" in lower -> "jacket"
            "shirt" in lower || "top" in lower || "blouse" in lower || "sweater" in lower -> "shirt"
            "pant" in lower || "jean" in lower || "trouser" in lower || "short" in lower -> "pants"
            "skirt" in lower || "dress" in lower -> "skirt"
            "shoe" in lower || "boot" in lower || "heel" in lower || "sneaker" in lower -> "shoes"
            "sock" in lower || "stocking" in lower -> "socks"
            "alpha" in lower -> "alpha"
            "tattoo" in lower || "makeup" in lower -> "tattoo"
            "skin" in lower -> "skin"
            "eye" in lower -> "eyes"
            "hair" in lower || "brow" in lower -> "hair"
            "shape" in lower || "body" in lower -> "shape"
            assetType == 13 -> "shape"
            else -> "jacket"
        }
    }

    /** Human-readable explanation for blocked actions. */
    fun reasonForBehavior(command: String): String = when (command) {
        "detach", "remattach" -> "Locked by RLVa — this attachment cannot be detached."
        "addattach" -> "Blocked by RLVa — attaching items is restricted."
        "sendchat" -> "Restricted by RLVa — public chat is garbled."
        "recvchat" -> "Blocked by RLVa — incoming local chat is restricted."
        "sendchannel" -> "Blocked by RLVa — chatting on script channels is restricted."
        "redirchat" -> "Redirected by RLVa — public chat is routed to a relay channel."
        "sendim" -> "Blocked by RLVa — sending instant messages is restricted."
        "recvim" -> "Blocked by RLVa — receiving instant messages is restricted."
        "tploc" -> "Blocked by RLVa — location teleports are restricted (@tploc=n)."
        "tplm" -> "Blocked by RLVa — landmark teleports are restricted (@tplm=n)."
        "tplure" -> "Blocked by RLVa — teleport lures are restricted (@tplure=n)."
        "sittp" -> "Blocked by RLVa — distant sit-teleports are restricted (@sittp=n)."
        "unsit" -> "Locked by RLVa — standing up is restricted (@unsit=n)."
        "sit" -> "Blocked by RLVa — sitting is restricted (@sit=n)."
        "addoutfit" -> "Blocked by RLVa — wearing clothing layers is restricted (@addoutfit=n)."
        "remoutfit" -> "Locked by RLVa — removing clothing layers is restricted (@remoutfit=n)."
        "rez" -> "Blocked by RLVa — rezzing objects is restricted (@rez=n)."
        "edit" -> "Blocked by RLVa — editing objects is restricted (@edit=n)."
        "showinv" -> "Hidden by RLVa — browsing inventory is restricted (@showinv=n)."
        "viewnote" -> "Blocked by RLVa — reading notecards is restricted (@viewnote=n)."
        "shownames" -> "Hidden by RLVa — nearby resident names are censored (@shownames=n)."
        "showloc" -> "Hidden by RLVa — current location is censored (@showloc=n)."
        else -> "Restricted by RLVa (@$command)."
    }
}

/** Single parsed token from a comma-separated `@cmd[:option]=param` message. */
data class ParsedRlvCommand(
    val command: String,
    val option: String,
    val param: String
)

/** Active per-object RLVa rule (`@command` or `@command:option`). */
data class RlvRule(
    val command: String,
    val option: String = ""
) {
    fun toRuleString(): String = if (option.isEmpty()) command else "$command:$option"
}

/** UI-friendly view of all active rules owned by a single in-world object or HUD. */
data class RlvObjectController(
    val objectId: UUID,
    val objectName: String,
    val rules: List<String>
)

/** Immutable snapshot of RLVa engine state exposed in `ViewerState` for Compose UI rendering. */
data class RlvUiSnapshot(
    val enabled: Boolean = true,
    val activeRuleCount: Int = 0,
    val controllers: List<RlvObjectController> = emptyList(),
    val activeBehaviors: Set<String> = emptySet(),
    val redirChatChannels: Set<Int> = emptySet(),
    val showNamesRestricted: Boolean = false,
    val showLocRestricted: Boolean = false,
    val showInvRestricted: Boolean = false,
    val viewNoteRestricted: Boolean = false,
    val unsitRestricted: Boolean = false,
    val tplocRestricted: Boolean = false,
    val tplmRestricted: Boolean = false,
    val editRestricted: Boolean = false,
    val rezRestricted: Boolean = false,
    val sendChatRestricted: Boolean = false,
    val recentLog: List<String> = emptyList()
)

/** Result of evaluating an outgoing local chat line through RLVa rules. */
sealed class RlvChatOutcome {
    /** Transmit public chat on channel 0 (possibly garbled to "..." or volume-downgraded). */
    data class SendPublic(
        val text: String,
        val chatType: Int,
        val wasGarbled: Boolean = false
    ) : RlvChatOutcome()

    /** Redirect public non-emote chat to one or more positive script channels instead of channel 0. */
    data class RedirectToChannels(
        val channels: List<Int>,
        val text: String,
        val chatType: Int = RlvConstants.CHAT_TYPE_NORMAL
    ) : RlvChatOutcome()

    /** Transmit `/channel message` on an explicit non-zero script channel. */
    data class SendOnChannel(
        val channel: Int,
        val text: String,
        val chatType: Int = RlvConstants.CHAT_TYPE_NORMAL
    ) : RlvChatOutcome()

    /** Outgoing message is completely blocked (e.g. `@sendchannel=n` without exception). */
    data class Blocked(val reason: String) : RlvChatOutcome()
}

/**
 * Host adapter interface implemented by `ViewerSessionViewModel` (and test harnesses)
 * so `RlvEngine` stays pure Kotlin with zero Android/network dependencies.
 */
interface RlvHostCallbacks {
    /** Send a reply string on a positive script channel via `ChatFromViewer` (`chatType = 1`). */
    fun sayOnChannel(channel: Int, message: String)

    /** Execute `@tpto:[region/]x/y/z=force`. */
    fun forceTeleportTo(x: Float, y: Float, z: Float, regionName: String?, sourceId: UUID) {}

    /** Execute `@sit:<uuid>=force`. */
    fun forceSitOnObject(targetId: UUID, sourceId: UUID) {}

    /** Execute `@unsit=force`. */
    fun forceUnsit(sourceId: UUID) {}

    /**
     * Execute `@detach[:attachpt]=force`.
     * When [attachmentPointId] is null, detach all unlocked worn attachments;
     * otherwise detach unlocked attachment(s) at [attachmentPointId].
     */
    fun forceDetachAttachment(attachmentPointId: Int?, sourceId: UUID) {}

    /**
     * Execute `@remoutfit[:layer]=force`.
     * When [layerName] is null, remove all removable unlocked clothing layers.
     */
    fun forceRemoveOutfit(layerName: String?, sourceId: UUID) {}

    /** Query whether a canonical clothing/bodypart layer (e.g. `"shirt"`) is currently worn. */
    fun isWearableLayerWorn(layerName: String): Boolean = false

    /** Query whether any attachment is currently worn at [pointId] (`0..55`). */
    fun isAttachmentPointOccupied(pointId: Int): Boolean = false

    /** Return the UUID of the currently sat-upon scene object, or null if not seated on an object. */
    fun getCurrentSitTargetId(): UUID? = null

    /** Notified whenever the engine's active restriction state or enable flag changes. */
    fun onStateChanged(snapshot: RlvUiSnapshot) {}
}
