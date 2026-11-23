package com.example.linkpoint.rlv

import java.util.UUID

/**
 * Standalone Second Life RLV / Firestorm RLVa Command Parser, Query Responder,
 * and Behavioral Restriction Engine.
 *
 * ### Provenance & Open-Source Licensing
 * - **`LibreMetaverse.RLV` (BSD 3-Clause License, Sjofn LLC / LibreMetaverse contributors)**:
 *   Cross-checked for canonical per-object rule tracking, `@version` / `@versionnew` / `@versionnum` /
 *   `@getstatus[:filter[;separator]]` positive-channel reply semantics, target-exception evaluation,
 *   and 15-layer `@getoutfit` / 41-slot `@getattach` bitmask ordering.
 * - **`react-linkpoint` / Linkpoint (MIT License)**:
 *   Adapted for the reactive [RlvUiSnapshot] state contract, mobile inspector controller grouping,
 *   and offline sandbox RLVa relay presets.
 * - **Public RLV / Firestorm Release-Branch RLVa Protocol Specification**:
 *   Interoperates with Marine Kelley's RLV (`v2.8.0`, `MNNPPBB = 2080000`) and Kitty Barnett's
 *   Firestorm release-branch RLVa (`RLVa 2.4.2`, `RestrainedLove viewer v...`) LSL wire conventions.
 *   Clean-room Kotlin implementation; zero LGPL viewer C++ source code is copied.
 *
 * ### Linden Lab Third-Party Viewer (TPV) Policy & Safety Invariants
 * - **Explicit User Opt-In (`initialEnabled = false`)**: RLVa is disabled by default until the user
 *   explicitly enables it via the persisted **Enable RLVa** toggle (`RlvStatusPanel`). Turning RLVa
 *   off immediately purges all active restrictions and ignores subsequent `@` commands.
 * - **Anti-Spoofing `llOwnerSay` Gate**: Processes `ChatFromSimulator` messages **only** when
 *   `enabled == true`, `sourceType == SOURCE_TYPE_OBJECT (2)`, `chatType == CHAT_TYPE_OWNER (8)`
 *   (`llOwnerSay`), and the message starts with `@`. Public (`1`), whisper (`0`), shout (`2`),
 *   debug (`6`), `llRegionSayTo` (`7`), direct (`9`), and avatar (`sourceType == 1`) chat are strictly
 *   ignored so spoofed public `@` chat can never trigger restrictions or channel replies.
 * - **Positive-Channel-Only Replies (`channel > 0`)**: Handshake and status queries (`@version`,
 *   `@versionnew`, `@versionnum`, `@versionnumbl`, `@getstatus[:filter[;sep]]`, `@getstatusall`,
 *   `@getoutfit`, `@getattach`, and `@getsitid`) reply exclusively on positive `> 0` script channels
 *   via `ChatFromViewer` (`Low 80 = 0xFFFF0050`, **`Zerocoded`**), never on channel `0` or negative channels.
 * - **Creator & User Safety Controls**: Preserves Second Life server-enforced content permissions and
 *   creator attribution while surfacing a transparent active-restrictions inspector ([getSnapshot])
 *   grouped by holding object name & UUID, per-object release ([onObjectDetached]), and one-tap
 *   **Emergency Release** ([clearAllRestrictions]).
 */
class RlvEngine(
    private val host: RlvHostCallbacks,
    initialEnabled: Boolean = false
) {
    private val lock = Any()

    @Volatile
    private var enabled: Boolean = initialEnabled

    private val objectRules = LinkedHashMap<UUID, LinkedHashSet<RlvRule>>()
    private val objectNames = LinkedHashMap<UUID, String>()
    private val recentLog = ArrayDeque<String>()

    fun isEnabled(): Boolean = enabled

    fun setEnabled(newEnabled: Boolean) {
        val snapshot = synchronized(lock) {
            if (enabled == newEnabled) return@synchronized buildSnapshotLocked()
            enabled = newEnabled
            if (!newEnabled) {
                objectRules.clear()
                objectNames.clear()
                appendLogLocked("RLVa disabled — cleared all active restrictions")
            } else {
                appendLogLocked("RLVa enabled (RLVa 2.4.2 / RLV 2.8.0)")
            }
            buildSnapshotLocked()
        }
        host.onStateChanged(snapshot)
    }

    /**
     * Evaluates an incoming `ChatFromSimulator` message.
     *
     * @return `true` if the message was an RLVa `@` owner-say command from an object and was
     *         intercepted (meaning the viewer should suppress displaying it in local chat);
     *         `false` otherwise.
     */
    fun onIncomingChat(
        sourceId: UUID,
        fromName: String,
        chatType: Int,
        message: String,
        sourceType: Int = RlvConstants.SOURCE_TYPE_OBJECT
    ): Boolean {
        if (!enabled) return false
        if (sourceType != RlvConstants.SOURCE_TYPE_OBJECT) return false
        if (chatType != RlvConstants.CHAT_TYPE_OWNER) return false
        if (!message.startsWith("@")) return false

        val commands = parseCommandLine(message)
        if (commands.isEmpty()) return true

        val pendingReplies = mutableListOf<Pair<Int, String>>()
        val pendingSideEffects = mutableListOf<() -> Unit>()

        val snapshot = synchronized(lock) {
            if (!enabled) return false
            if (fromName.isNotBlank()) {
                objectNames[sourceId] = fromName
            } else if (!objectNames.containsKey(sourceId)) {
                objectNames[sourceId] = "Object ${sourceId.toString().take(8)}"
            }

            for (cmd in commands) {
                handleSingleCommandLocked(
                    sourceId = sourceId,
                    sourceName = objectNames[sourceId] ?: fromName,
                    cmd = cmd,
                    pendingReplies = pendingReplies,
                    pendingSideEffects = pendingSideEffects
                )
            }
            buildSnapshotLocked()
        }

        host.onStateChanged(snapshot)
        for ((channel, reply) in pendingReplies) {
            host.sayOnChannel(channel, reply)
        }
        for (effect in pendingSideEffects) {
            effect.invoke()
        }
        return true
    }

    /**
     * Garbage-collects all active restrictions owned by [objectId] (e.g. when an attachment detaches
     * or via per-object release in the RLVa inspector).
     */
    fun onObjectDetached(objectId: UUID) {
        val snapshot = synchronized(lock) {
            val removed = objectRules.remove(objectId)
            val name = objectNames.remove(objectId)
            if (removed == null) return
            appendLogLocked("Released ${removed.size} rule(s) from ${name ?: objectId.toString().take(8)}")
            buildSnapshotLocked()
        }
        host.onStateChanged(snapshot)
    }

    /** Emergency release: clears all active RLVa restrictions across all objects. */
    fun clearAllRestrictions() {
        val snapshot = synchronized(lock) {
            if (objectRules.isEmpty()) {
                appendLogLocked("Emergency release: no active restrictions")
                return@synchronized buildSnapshotLocked()
            }
            val count = objectRules.values.sumOf { it.size }
            objectRules.clear()
            objectNames.clear()
            appendLogLocked("Emergency release: cleared $count restriction(s)")
            buildSnapshotLocked()
        }
        host.onStateChanged(snapshot)
    }

    /** Returns an immutable snapshot of current RLVa state. */
    fun getSnapshot(): RlvUiSnapshot = synchronized(lock) {
        buildSnapshotLocked()
    }

    /** Returns active rules formatted as `/rule1/rule2` for a specific [objectId] (or `""` if none). */
    fun getStatusForObject(objectId: UUID, filter: String = "", separator: String = "/"): String = synchronized(lock) {
        if (!enabled) return@synchronized ""
        formatStatusLocked(objectId, filter, separator)
    }

    // -------------------------------------------------------------------------
    // Behavioral Restriction Evaluators
    // -------------------------------------------------------------------------

    /** `@tploc=n`: Can the resident teleport to an arbitrary location / SLURL / Home? */
    fun canTeleportLocation(exceptSourceObject: UUID? = null): Boolean = synchronized(lock) {
        if (!enabled) return true
        !isRestrictedByOtherThanLocked("tploc", exceptSourceObject)
    }

    /** `@tplm=n`: Can the resident teleport using a landmark? */
    fun canTeleportLandmark(): Boolean = synchronized(lock) {
        if (!enabled) return true
        !hasBlanketRuleLocked("tplm")
    }

    /** `@tplure=n` with `@tplure:<uuid>=add` exceptions: Can the resident accept a teleport lure from [fromAgentId]? */
    fun canAcceptLure(fromAgentId: UUID): Boolean = synchronized(lock) {
        if (!enabled) return true
        isAllowedWithExceptionLocked("tplure", fromAgentId.toString().lowercase())
    }

    /** `@sittp=n`: Can the resident sit on an object (>1.5m sit-teleport)? */
    fun canSitTeleport(distanceMeters: Float = Float.MAX_VALUE): Boolean = synchronized(lock) {
        if (!enabled) return true
        if (distanceMeters <= 1.5f) return true
        !hasBlanketRuleLocked("sittp")
    }

    /** `@accepttp=add` / `@accepttp:<uuid>=add`: Must the viewer auto-accept teleport offers from [fromAgentId]? */
    fun shouldAutoAcceptTeleport(fromAgentId: UUID): Boolean = synchronized(lock) {
        if (!enabled) return false
        hasBlanketRuleLocked("accepttp") || hasOptionRuleLocked("accepttp", fromAgentId.toString().lowercase())
    }

    /** `@sit=n`: Can the resident initiate sitting? */
    fun canSit(exceptSourceObject: UUID? = null): Boolean = synchronized(lock) {
        if (!enabled) return true
        !isRestrictedByOtherThanLocked("sit", exceptSourceObject)
    }

    /** `@unsit=n`: Can the resident stand up from sitting? */
    fun canUnsit(exceptSourceObject: UUID? = null): Boolean = synchronized(lock) {
        if (!enabled) return true
        !isRestrictedByOtherThanLocked("unsit", exceptSourceObject)
    }

    /**
     * `@detach=n` (locks issuing object itself), `@detach:<attachpt>=n`, and `@remattach[:attachpt]=n`:
     * Can the resident detach [objectId] worn at [attachmentPointId]?
     */
    fun canDetach(objectId: UUID?, attachmentPointId: Int): Boolean = synchronized(lock) {
        if (!enabled) return true
        // 1. Blanket @remattach=n locks all attachments
        if (hasBlanketRuleLocked("remattach")) return false

        // 2. @detach=n (empty option) locks the specific object that issued @detach=n
        if (objectId != null) {
            val ownRules = objectRules[objectId]
            if (ownRules != null && ownRules.any { it.command == "detach" && it.option.isEmpty() }) {
                return false
            }
        }

        // 3. Slot-specific @detach:<pt>=n or @remattach:<pt>=n locks that attachment point
        val canonicalPt = RlvConstants.canonicalAttachmentPointName(attachmentPointId)
        if (canonicalPt != null && canonicalPt != "none") {
            if (hasOptionRuleLocked("detach", canonicalPt) || hasOptionRuleLocked("remattach", canonicalPt)) {
                return false
            }
        }
        true
    }

    /** `@addattach[:attachpt]=n`: Can the resident attach an object to [attachmentPointId]? */
    fun canAttach(attachmentPointId: Int): Boolean = synchronized(lock) {
        if (!enabled) return true
        if (hasBlanketRuleLocked("addattach")) return false
        val canonicalPt = RlvConstants.canonicalAttachmentPointName(attachmentPointId)
        if (canonicalPt != null && canonicalPt != "none") {
            if (hasOptionRuleLocked("addattach", canonicalPt)) return false
        }
        true
    }

    /** `@addoutfit[:part]=n`: Can the resident wear clothing on [layerName]? */
    fun canAddOutfit(layerName: String? = null): Boolean = synchronized(lock) {
        if (!enabled) return true
        if (hasBlanketRuleLocked("addoutfit")) return false
        val norm = layerName?.trim()?.lowercase().orEmpty()
        if (norm.isNotEmpty() && hasOptionRuleLocked("addoutfit", norm)) return false
        true
    }

    /** `@remoutfit[:part]=n`: Can the resident take off clothing on [layerName]? */
    fun canRemoveOutfit(layerName: String? = null): Boolean = synchronized(lock) {
        if (!enabled) return true
        if (hasBlanketRuleLocked("remoutfit")) return false
        val norm = layerName?.trim()?.lowercase().orEmpty()
        if (norm.isNotEmpty() && hasOptionRuleLocked("remoutfit", norm)) return false
        true
    }

    /** `@rez=n`: Can the resident rez objects in-world? */
    fun canRez(): Boolean = synchronized(lock) {
        if (!enabled) return true
        !hasBlanketRuleLocked("rez")
    }

    /** `@edit=n` (with optional `@edit:<uuid>=add` exception): Can the resident edit objects? */
    fun canEdit(targetObjectId: UUID? = null): Boolean = synchronized(lock) {
        if (!enabled) return true
        isAllowedWithExceptionLocked("edit", targetObjectId?.toString()?.lowercase().orEmpty())
    }

    /** `@showinv=n`: Can the resident open and browse inventory? */
    fun canShowInventory(): Boolean = synchronized(lock) {
        if (!enabled) return true
        !hasBlanketRuleLocked("showinv")
    }

    /** `@viewnote=n`: Can the resident open and read notecards? */
    fun canViewNotecard(): Boolean = synchronized(lock) {
        if (!enabled) return true
        !hasBlanketRuleLocked("viewnote")
    }

    /** `@shownames=n`: Can the resident see nearby avatar names? */
    fun canShowNames(): Boolean = synchronized(lock) {
        if (!enabled) return true
        !hasBlanketRuleLocked("shownames")
    }

    /** `@showloc=n`: Can the resident see region/parcel/coordinate location info? */
    fun canShowLocation(): Boolean = synchronized(lock) {
        if (!enabled) return true
        !hasBlanketRuleLocked("showloc")
    }

    /**
     * `@recvchat=n` with `@recvchat:<uuid>=add` exceptions:
     * Can the resident receive public chat [message] from [senderId]?
     * Per RLVa spec, `/me` emotes are still visible unless blocked separately,
     * and speakers listed in `@recvchat:<uuid>=add` remain audible.
     */
    fun canReceiveChat(message: String, senderId: UUID): Boolean = synchronized(lock) {
        if (!enabled) return true
        if (isEmote(message)) return true
        isAllowedWithExceptionLocked("recvchat", senderId.toString().lowercase())
    }

    /** `@sendim=n` with `@sendim:<uuid>=add` exceptions: Can the resident send an IM to [recipientId]? */
    fun canSendIm(recipientId: UUID): Boolean = synchronized(lock) {
        if (!enabled) return true
        isAllowedWithExceptionLocked("sendim", recipientId.toString().lowercase())
    }

    /** `@recvim=n` with `@recvim:<uuid>=add` exceptions: Can the resident receive an IM from [senderId]? */
    fun canReceiveIm(senderId: UUID): Boolean = synchronized(lock) {
        if (!enabled) return true
        isAllowedWithExceptionLocked("recvim", senderId.toString().lowercase())
    }

    /** `@sendchannel=n` with `@sendchannel:<chan>=add` exceptions: Can the resident chat on non-zero [channel]? */
    fun canSendOnChannel(channel: Int): Boolean = synchronized(lock) {
        if (!enabled || channel == 0) return true
        isAllowedWithExceptionLocked("sendchannel", channel.toString())
    }

    /**
     * Evaluates an outgoing local chat input string against `@sendchannel`, `@redirchat:<chan>`,
     * `@sendchat`, `@chatshout`, and `@chatnormal`.
     */
    fun processOutgoingChat(
        rawInput: String,
        requestedChatType: Int = RlvConstants.CHAT_TYPE_NORMAL
    ): RlvChatOutcome {
        val trimmed = rawInput.trim()
        val parsedChannelChat = parseExplicitChannelChat(trimmed)

        synchronized(lock) {
            if (!enabled) {
                return if (parsedChannelChat != null && parsedChannelChat.first != 0) {
                    RlvChatOutcome.SendOnChannel(parsedChannelChat.first, parsedChannelChat.second, requestedChatType)
                } else {
                    val publicMsg = parsedChannelChat?.second ?: trimmed
                    RlvChatOutcome.SendPublic(publicMsg, requestedChatType, wasGarbled = false)
                }
            }

            // 1. Explicit non-zero channel chat (e.g. "/42 hello" or "/-99 menu")
            if (parsedChannelChat != null && parsedChannelChat.first != 0) {
                val (chan, payload) = parsedChannelChat
                return if (isAllowedWithExceptionLocked("sendchannel", chan.toString())) {
                    RlvChatOutcome.SendOnChannel(chan, payload, RlvConstants.CHAT_TYPE_NORMAL)
                } else {
                    RlvChatOutcome.Blocked(RlvConstants.reasonForBehavior("sendchannel"))
                }
            }

            val publicText = parsedChannelChat?.second ?: trimmed
            val adjustedChatType = adjustChatVolumeLocked(requestedChatType)

            // 2. Emotes (/me or /me's) bypass @sendchat garbling and @redirchat per RLVa spec
            if (isEmote(publicText)) {
                return RlvChatOutcome.SendPublic(publicText, adjustedChatType, wasGarbled = false)
            }

            // 3. @redirchat:<chan> redirects public non-emote chat to active positive script channel(s)
            //    and suppresses channel 0 output (including suppressing "..." when @sendchat=n is also set).
            val redirChannels = getActiveRedirChannelsLocked()
            if (redirChannels.isNotEmpty()) {
                return RlvChatOutcome.RedirectToChannels(
                    channels = redirChannels,
                    text = publicText,
                    chatType = RlvConstants.CHAT_TYPE_NORMAL
                )
            }

            // 4. @sendchat=n garbles restricted public chat to "..." per RLVa spec
            if (hasBlanketRuleLocked("sendchat")) {
                val garbledType = adjustedChatType.coerceAtMost(RlvConstants.CHAT_TYPE_NORMAL)
                return RlvChatOutcome.SendPublic(
                    text = "...",
                    chatType = garbledType,
                    wasGarbled = true
                )
            }

            return RlvChatOutcome.SendPublic(publicText, adjustedChatType, wasGarbled = false)
        }
    }

    // -------------------------------------------------------------------------
    // Command Parser & Dispatcher
    // -------------------------------------------------------------------------

    companion object {
        private val CHANNEL_CHAT_REGEX = Regex("""^/(-?\d+)(?:\s+(.*))?$""")

        /**
         * Parses an `@`-prefixed RLV line into individual [ParsedRlvCommand] tokens.
         * Supports comma-separated commands (`@detach=n,sendchat=n,redirchat:324=add,@clear=tp`).
         */
        fun parseCommandLine(rawMessage: String): List<ParsedRlvCommand> {
            val trimmed = rawMessage.trim()
            if (!trimmed.startsWith("@")) return emptyList()

            val body = trimmed.substring(1)
            if (body.isBlank()) return emptyList()

            val result = mutableListOf<ParsedRlvCommand>()
            for (token in body.split(',')) {
                var part = token.trim()
                if (part.startsWith("@")) {
                    part = part.substring(1).trim()
                }
                if (part.isEmpty()) continue

                val eqIdx = part.indexOf('=')
                if (eqIdx < 0) {
                    // Handle parameterless @clear or @clear:<filter> (and shorthand @cmd:force)
                    val colonIdx = part.indexOf(':')
                    val cmdName = (if (colonIdx >= 0) part.substring(0, colonIdx) else part).trim().lowercase()
                    val opt = if (colonIdx >= 0) part.substring(colonIdx + 1).trim() else ""
                    if (cmdName == "clear") {
                        result += ParsedRlvCommand(command = "clear", option = opt, param = "")
                    } else if (opt.equals("force", ignoreCase = true)) {
                        result += ParsedRlvCommand(command = cmdName, option = "", param = "force")
                    }
                    continue
                }

                val lhs = part.substring(0, eqIdx).trim()
                val param = part.substring(eqIdx + 1).trim().lowercase()
                if (lhs.isEmpty()) continue

                val colonIdx = lhs.indexOf(':')
                val cmdName = (if (colonIdx >= 0) lhs.substring(0, colonIdx) else lhs).trim().lowercase()
                val opt = if (colonIdx >= 0) {
                    val rawOpt = lhs.substring(colonIdx + 1)
                    if (';' in rawOpt) rawOpt else rawOpt.trim()
                } else {
                    ""
                }
                if (cmdName.isNotEmpty()) {
                    result += ParsedRlvCommand(command = cmdName, option = opt, param = param)
                }
            }
            return result
        }

        private fun isEmote(text: String): Boolean {
            val lower = text.trimStart().lowercase()
            return lower == "/me" || lower.startsWith("/me ") || lower.startsWith("/me'")
        }

        private fun parseExplicitChannelChat(text: String): Pair<Int, String>? {
            if (!text.startsWith("/") || text.startsWith("//") || isEmote(text)) return null
            val match = CHANNEL_CHAT_REGEX.matchEntire(text) ?: return null
            val channel = match.groupValues[1].toIntOrNull() ?: return null
            val body = match.groupValues[2].trim()
            return channel to body
        }
    }

    private fun handleSingleCommandLocked(
        sourceId: UUID,
        sourceName: String,
        cmd: ParsedRlvCommand,
        pendingReplies: MutableList<Pair<Int, String>>,
        pendingSideEffects: MutableList<() -> Unit>
    ) {
        when (cmd.command) {
            // --- Handshake & Status Queries (positive non-zero channel only) ---
            "version" -> {
                val chan = cmd.param.toIntOrNull()
                if (chan != null && chan > 0) {
                    pendingReplies += chan to RlvConstants.RLV_VERSION_REPLY
                    appendLogLocked("$sourceName -> @version=$chan")
                }
            }

            "versionnew" -> {
                val chan = cmd.param.toIntOrNull()
                if (chan != null && chan > 0) {
                    pendingReplies += chan to RlvConstants.RLV_VERSION_NEW_REPLY
                    appendLogLocked("$sourceName -> @versionnew=$chan")
                }
            }

            "versionnum", "versionnumbl" -> {
                val chan = cmd.param.toIntOrNull()
                if (chan != null && chan > 0) {
                    pendingReplies += chan to RlvConstants.RLV_VERSION_NUM_REPLY
                    appendLogLocked("$sourceName -> @${cmd.command}=$chan")
                }
            }

            "getstatus" -> {
                val chan = cmd.param.toIntOrNull()
                if (chan != null && chan > 0) {
                    val (filter, separator) = parseFilterAndSeparator(cmd.option)
                    val reply = formatStatusLocked(sourceId, filter, separator)
                    pendingReplies += chan to reply
                    appendLogLocked("$sourceName -> @getstatus=$chan (\"$reply\")")
                }
            }

            "getstatusall" -> {
                val chan = cmd.param.toIntOrNull()
                if (chan != null && chan > 0) {
                    val (filter, separator) = parseFilterAndSeparator(cmd.option)
                    val reply = formatStatusAllLocked(filter, separator)
                    pendingReplies += chan to reply
                    appendLogLocked("$sourceName -> @getstatusall=$chan")
                }
            }

            "getoutfit" -> {
                val chan = cmd.param.toIntOrNull()
                if (chan != null && chan > 0) {
                    val reply = buildGetOutfitReply(cmd.option)
                    pendingReplies += chan to reply
                    appendLogLocked("$sourceName -> @getoutfit${if (cmd.option.isNotEmpty()) ":${cmd.option}" else ""}=$chan (\"$reply\")")
                }
            }

            "getattach" -> {
                val chan = cmd.param.toIntOrNull()
                if (chan != null && chan > 0) {
                    val reply = buildGetAttachReply(cmd.option)
                    pendingReplies += chan to reply
                    appendLogLocked("$sourceName -> @getattach${if (cmd.option.isNotEmpty()) ":${cmd.option}" else ""}=$chan")
                }
            }

            "getsitid" -> {
                val chan = cmd.param.toIntOrNull()
                if (chan != null && chan > 0) {
                    val sitUuid = host.getCurrentSitTargetId() ?: RlvConstants.ZERO_UUID
                    pendingReplies += chan to sitUuid.toString()
                    appendLogLocked("$sourceName -> @getsitid=$chan ($sitUuid)")
                }
            }

            // --- Clear per-object rules ---
            "clear" -> {
                val filter = (cmd.option.ifEmpty { cmd.param }).trim().lowercase()
                val rules = objectRules[sourceId]
                if (rules != null) {
                    if (filter.isEmpty()) {
                        objectRules.remove(sourceId)
                        appendLogLocked("$sourceName -> @clear (all rules cleared)")
                    } else {
                        rules.removeAll { it.toRuleString().contains(filter) }
                        if (rules.isEmpty()) {
                            objectRules.remove(sourceId)
                        }
                        appendLogLocked("$sourceName -> @clear=$filter")
                    }
                }
            }

            // --- Force-only Teleport command ---
            "tpto" -> {
                if (cmd.param == "force" && cmd.option.isNotEmpty()) {
                    if (!isRestrictedByOtherThanLocked("tploc", sourceId)) {
                        val parsedTp = parseTpToOption(cmd.option)
                        if (parsedTp != null) {
                            val (regionName, x, y, z) = parsedTp
                            appendLogLocked("$sourceName -> @tpto:${cmd.option}=force")
                            pendingSideEffects += {
                                host.forceTeleportTo(x, y, z, regionName, sourceId)
                            }
                        }
                    } else {
                        appendLogLocked("$sourceName -> @tpto blocked by @tploc=n")
                    }
                }
            }

            // --- Allowance command (@accepttp uses =n|=add to add rule, =y|=rem to remove rule per RLVa/LibreMetaverse.RLV) ---
            "accepttp" -> {
                val normalizedOpt = normalizeUuidOrEmptyOption(cmd.option) ?: return
                applyBooleanRuleLocked(sourceId, "accepttp", normalizedOpt, cmd.param)
            }

            // --- Redirchat requires a positive non-zero script channel ---
            "redirchat" -> {
                val chan = cmd.option.trim().toIntOrNull()
                if (chan != null && chan > 0) {
                    val rule = RlvRule("redirchat", chan.toString())
                    if (isAddParam(cmd.param)) {
                        addRuleLocked(sourceId, rule)
                    } else if (isRemParam(cmd.param)) {
                        removeRuleLocked(sourceId, rule)
                    }
                }
            }

            // --- Sit & Unsit (restrictions + =force) ---
            "sit" -> {
                if (cmd.param == "force") {
                    val targetUuid = parseUuidOrNull(cmd.option)
                    if (targetUuid != null) {
                        val blockedBySit = isRestrictedByOtherThanLocked("sit", sourceId)
                        val currentlySeated = host.getCurrentSitTargetId() != null
                        val blockedByUnsit = currentlySeated && isRestrictedByOtherThanLocked("unsit", sourceId)
                        if (!blockedBySit && !blockedByUnsit) {
                            appendLogLocked("$sourceName -> @sit:$targetUuid=force")
                            pendingSideEffects += {
                                host.forceSitOnObject(targetUuid, sourceId)
                            }
                        }
                    }
                } else {
                    applyBooleanRuleLocked(sourceId, "sit", "", cmd.param)
                }
            }

            "unsit" -> {
                if (cmd.param == "force") {
                    if (!isRestrictedByOtherThanLocked("unsit", sourceId)) {
                        appendLogLocked("$sourceName -> @unsit=force")
                        pendingSideEffects += {
                            host.forceUnsit(sourceId)
                        }
                    } else {
                        appendLogLocked("$sourceName -> @unsit=force blocked by @unsit=n")
                    }
                } else {
                    applyBooleanRuleLocked(sourceId, "unsit", "", cmd.param)
                }
            }

            // --- Detach / Remattach / Addattach ---
            "detach", "remattach" -> {
                val rawOpt = cmd.option.trim()
                val resolvedPt = if (rawOpt.isEmpty()) null else RlvConstants.resolveAttachmentPoint(rawOpt)
                if (rawOpt.isNotEmpty() && resolvedPt == null) return

                if (cmd.param == "force") {
                    appendLogLocked("$sourceName -> @${cmd.command}${if (rawOpt.isNotEmpty()) ":$rawOpt" else ""}=force")
                    pendingSideEffects += {
                        host.forceDetachAttachment(resolvedPt, sourceId)
                    }
                } else {
                    val canonicalOpt = if (resolvedPt != null) {
                        RlvConstants.canonicalAttachmentPointName(resolvedPt).orEmpty()
                    } else {
                        ""
                    }
                    applyBooleanRuleLocked(sourceId, cmd.command, canonicalOpt, cmd.param)
                }
            }

            "addattach" -> {
                val rawOpt = cmd.option.trim()
                val resolvedPt = if (rawOpt.isEmpty()) null else RlvConstants.resolveAttachmentPoint(rawOpt)
                if (rawOpt.isNotEmpty() && resolvedPt == null) return
                val canonicalOpt = if (resolvedPt != null) {
                    RlvConstants.canonicalAttachmentPointName(resolvedPt).orEmpty()
                } else {
                    ""
                }
                applyBooleanRuleLocked(sourceId, "addattach", canonicalOpt, cmd.param)
            }

            // --- Addoutfit / Remoutfit ---
            "addoutfit", "remoutfit" -> {
                val normLayer = cmd.option.trim().lowercase()
                if (normLayer.isNotEmpty() && normLayer !in RlvConstants.OUTFIT_LAYERS) return

                if (cmd.command == "remoutfit" && cmd.param == "force") {
                    val targetLayer = normLayer.ifEmpty { null }
                    if (canRemoveOutfit(targetLayer)) {
                        appendLogLocked("$sourceName -> @remoutfit${if (normLayer.isNotEmpty()) ":$normLayer" else ""}=force")
                        pendingSideEffects += {
                            host.forceRemoveOutfit(targetLayer, sourceId)
                        }
                    }
                } else {
                    applyBooleanRuleLocked(sourceId, cmd.command, normLayer, cmd.param)
                }
            }

            // --- Restrictions supporting UUID exceptions ---
            "tplure", "sendim", "recvim", "recvchat", "edit" -> {
                val normalizedOpt = normalizeUuidOrEmptyOption(cmd.option) ?: return
                applyBooleanRuleLocked(sourceId, cmd.command, normalizedOpt, cmd.param)
            }

            // --- Restrictions supporting non-zero channel exceptions ---
            "sendchannel" -> {
                val rawOpt = cmd.option.trim()
                val normalizedOpt = if (rawOpt.isEmpty()) {
                    ""
                } else {
                    val ch = rawOpt.toIntOrNull() ?: return
                    if (ch == 0) return
                    ch.toString()
                }
                applyBooleanRuleLocked(sourceId, "sendchannel", normalizedOpt, cmd.param)
            }

            // --- Blanket behavioral restrictions ---
            "tploc", "tplm", "sittp",
            "sendchat", "chatshout", "chatnormal",
            "rez", "showinv", "viewnote", "shownames", "showloc",
            "showworldmap", "showminimap", "fly" -> {
                applyBooleanRuleLocked(sourceId, cmd.command, "", cmd.param)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal Helpers (caller must hold `lock`)
    // -------------------------------------------------------------------------

    private fun isAddParam(param: String): Boolean = param == "n" || param == "add"
    private fun isRemParam(param: String): Boolean = param == "y" || param == "rem"

    private fun applyBooleanRuleLocked(sourceId: UUID, command: String, option: String, param: String) {
        val rule = RlvRule(command, option)
        if (isAddParam(param)) {
            addRuleLocked(sourceId, rule)
        } else if (isRemParam(param)) {
            removeRuleLocked(sourceId, rule)
        }
    }

    private fun addRuleLocked(sourceId: UUID, rule: RlvRule) {
        val set = objectRules.getOrPut(sourceId) { LinkedHashSet() }
        if (set.add(rule)) {
            val name = objectNames[sourceId] ?: sourceId.toString().take(8)
            appendLogLocked("$name + @${rule.toRuleString()}")
        }
    }

    private fun removeRuleLocked(sourceId: UUID, rule: RlvRule) {
        val set = objectRules[sourceId] ?: return
        if (set.remove(rule)) {
            val name = objectNames[sourceId] ?: sourceId.toString().take(8)
            appendLogLocked("$name - @${rule.toRuleString()}")
            if (set.isEmpty()) {
                objectRules.remove(sourceId)
            }
        }
    }

    private fun hasBlanketRuleLocked(command: String): Boolean {
        return objectRules.values.any { rules ->
            rules.any { it.command == command && it.option.isEmpty() }
        }
    }

    private fun hasOptionRuleLocked(command: String, option: String): Boolean {
        return objectRules.values.any { rules ->
            rules.any { it.command == command && it.option == option }
        }
    }

    private fun isRestrictedByOtherThanLocked(command: String, exceptObject: UUID?): Boolean {
        for ((objId, rules) in objectRules) {
            if (exceptObject != null && objId == exceptObject) continue
            if (rules.any { it.command == command && it.option.isEmpty() }) {
                return true
            }
        }
        return false
    }

    /**
     * Evaluates a `TargetSpecifiesException` restriction (`@tplure`, `@sendim`, `@recvim`, `@recvchat`,
     * `@sendchannel`, `@edit`):
     * - Allowed when no blanket `@cmd=n` rule is active.
     * - If blanket `@cmd=n` IS active, allowed only when [target] is non-empty and an active `@cmd:<target>=add`
     *   exception rule exists.
     */
    private fun isAllowedWithExceptionLocked(command: String, target: String): Boolean {
        if (!hasBlanketRuleLocked(command)) return true
        if (target.isEmpty()) return false
        return hasOptionRuleLocked(command, target)
    }

    private fun getActiveRedirChannelsLocked(): List<Int> {
        val channels = LinkedHashSet<Int>()
        for (rules in objectRules.values) {
            for (rule in rules) {
                if (rule.command == "redirchat") {
                    val ch = rule.option.toIntOrNull()
                    if (ch != null && ch > 0) {
                        channels.add(ch)
                    }
                }
            }
        }
        return channels.toList()
    }

    private fun adjustChatVolumeLocked(requestedChatType: Int): Int {
        var result = requestedChatType
        if (result == RlvConstants.CHAT_TYPE_SHOUT && hasBlanketRuleLocked("chatshout")) {
            result = RlvConstants.CHAT_TYPE_NORMAL
        }
        if (result == RlvConstants.CHAT_TYPE_NORMAL && hasBlanketRuleLocked("chatnormal")) {
            result = RlvConstants.CHAT_TYPE_WHISPER
        }
        return result
    }

    private fun parseFilterAndSeparator(option: String): Pair<String, String> {
        val semiIdx = option.indexOf(';')
        return if (semiIdx >= 0) {
            val filter = option.substring(0, semiIdx).lowercase()
            val sep = option.substring(semiIdx + 1).ifEmpty { "/" }
            filter to sep
        } else {
            option.lowercase() to "/"
        }
    }

    private fun formatStatusLocked(objectId: UUID, filter: String, separator: String): String {
        val rules = objectRules[objectId] ?: return ""
        val matching = rules
            .map { it.toRuleString() }
            .filter { filter.isEmpty() || it.contains(filter) }
        return if (matching.isEmpty()) "" else separator + matching.joinToString(separator)
    }

    private fun formatStatusAllLocked(filter: String, separator: String): String {
        val matching = objectRules.values
            .asSequence()
            .flatten()
            .map { it.toRuleString() }
            .distinct()
            .filter { filter.isEmpty() || it.contains(filter) }
            .toList()
        return if (matching.isEmpty()) "" else separator + matching.joinToString(separator)
    }

    private fun buildGetOutfitReply(option: String): String {
        val targetLayer = option.trim().lowercase()
        if (targetLayer.isNotEmpty()) {
            if (targetLayer !in RlvConstants.OUTFIT_LAYERS) return ""
            return if (host.isWearableLayerWorn(targetLayer)) "1" else "0"
        }
        return buildString(RlvConstants.OUTFIT_LAYERS.size) {
            for (layer in RlvConstants.OUTFIT_LAYERS) {
                append(if (host.isWearableLayerWorn(layer)) '1' else '0')
            }
        }
    }

    private fun buildGetAttachReply(option: String): String {
        val rawPoint = option.trim()
        if (rawPoint.isNotEmpty()) {
            val ptId = RlvConstants.resolveAttachmentPoint(rawPoint) ?: return ""
            return if (ptId > 0 && host.isAttachmentPointOccupied(ptId)) "1" else "0"
        }
        return buildString(RlvConstants.CLASSIC_GETATTACH_COUNT) {
            for (ptId in 0 until RlvConstants.CLASSIC_GETATTACH_COUNT) {
                val occupied = ptId > 0 && host.isAttachmentPointOccupied(ptId)
                append(if (occupied) '1' else '0')
            }
        }
    }

    private data class TpToTarget(
        val regionName: String?,
        val x: Float,
        val y: Float,
        val z: Float
    )

    private fun parseTpToOption(option: String): TpToTarget? {
        // Strip optional ";lookat" suffix per RLVa spec (e.g. @tpto:128/128/25;1.57=force)
        val coordSpec = option.substringBefore(';').trim()
        val parts = coordSpec.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.size == 3 -> {
                val x = parts[0].toFloatOrNull() ?: return null
                val y = parts[1].toFloatOrNull() ?: return null
                val z = parts[2].toFloatOrNull() ?: return null
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
                TpToTarget(null, x, y, z)
            }
            parts.size >= 4 -> {
                val region = parts[0]
                val x = parts[1].toFloatOrNull() ?: return null
                val y = parts[2].toFloatOrNull() ?: return null
                val z = parts[3].toFloatOrNull() ?: return null
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
                TpToTarget(region, x, y, z)
            }
            else -> null
        }
    }

    private fun parseUuidOrNull(raw: String): UUID? {
        val trimmed = raw.trim()
        if (trimmed.length != 36) return null
        return runCatching { UUID.fromString(trimmed) }.getOrNull()
    }

    private fun normalizeUuidOrEmptyOption(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return parseUuidOrNull(trimmed)?.toString()?.lowercase()
    }

    private fun appendLogLocked(entry: String) {
        if (recentLog.size >= 24) {
            recentLog.removeFirst()
        }
        recentLog.addLast(entry)
    }

    private fun buildSnapshotLocked(): RlvUiSnapshot {
        if (!enabled) {
            return RlvUiSnapshot(
                enabled = false,
                recentLog = recentLog.toList()
            )
        }
        val controllers = objectRules.entries.map { (objId, rules) ->
            RlvObjectController(
                objectId = objId,
                objectName = objectNames[objId] ?: "Object ${objId.toString().take(8)}",
                rules = rules.map { "@${it.toRuleString()}" }
            )
        }
        val behaviors = objectRules.values
            .asSequence()
            .flatten()
            .map { it.command }
            .toSet()

        return RlvUiSnapshot(
            enabled = true,
            activeRuleCount = objectRules.values.sumOf { it.size },
            controllers = controllers,
            activeBehaviors = behaviors,
            redirChatChannels = getActiveRedirChannelsLocked().toSet(),
            showNamesRestricted = hasBlanketRuleLocked("shownames"),
            showLocRestricted = hasBlanketRuleLocked("showloc"),
            showInvRestricted = hasBlanketRuleLocked("showinv"),
            viewNoteRestricted = hasBlanketRuleLocked("viewnote"),
            unsitRestricted = hasBlanketRuleLocked("unsit"),
            tplocRestricted = hasBlanketRuleLocked("tploc"),
            tplmRestricted = hasBlanketRuleLocked("tplm"),
            editRestricted = hasBlanketRuleLocked("edit"),
            rezRestricted = hasBlanketRuleLocked("rez"),
            sendChatRestricted = hasBlanketRuleLocked("sendchat"),
            recentLog = recentLog.toList()
        )
    }
}
