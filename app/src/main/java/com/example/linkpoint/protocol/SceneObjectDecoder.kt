package com.example.linkpoint.protocol

import com.example.linkpoint.render.ExtraParamsAndSculptGenerator
import com.example.linkpoint.render.FullExtraParamsResult
import com.example.linkpoint.render.PartDataBinaryCodec
import com.example.linkpoint.render.PartDataConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Decoded animation sequence entry from `AvatarAnimation` (`High 20`) or `ObjectAnimation` (`High 21`).
 */
data class SignaledAnimation(
    val animId: UUID,
    val sequenceId: Int
)

/**
 * Decoded `AvatarAnimation` / `ObjectAnimation` packet payload.
 */
data class DecodedAvatarAnimationPacket(
    val senderId: UUID,
    val animations: List<SignaledAnimation>,
    val objectSourceIds: List<UUID> = emptyList()
)

/**
 * Combined decoded `PartData` + `ExtraParams` block for a Second Life `SceneObject`.
 */
data class DecodedObjectBlocks(
    val partData: PartDataConfig? = null,
    val extraParams: FullExtraParamsResult = FullExtraParamsResult()
)

/**
 * Standalone protocol decoder for Phase 9 `PartData`, `ExtraParams` (`0x10` Flexi, `0x20` Light, `0x30` Sculpt, `0x60` Mesh),
 * and `AvatarAnimation` / `ObjectAnimation` UDP packets.
 */
object SceneObjectDecoder {

    /** Compressed update flag: legacy 86-byte `LLPartSysData` block is present. */
    const val COMP_FLAG_PARTICLES_LEGACY = 0x00000008

    /** Compressed update flag: modern block-framed `LLPartSysData::unpackBlock` is present. */
    const val COMP_FLAG_PARTICLES_NEW = 0x00000400

    /**
     * Decodes `PSBlock` and `ExtraParams` from an uncompressed `ObjectUpdate` block.
     */
    fun decodeUncompressedBlocks(psBlockBytes: ByteArray, extraParamBytes: ByteArray): DecodedObjectBlocks {
        val partData = if (psBlockBytes.isNotEmpty()) {
            PartDataBinaryCodec.decodePartData(psBlockBytes)
        } else {
            null
        }
        val extra = ExtraParamsAndSculptGenerator.parseExtraParams(extraParamBytes)
        return DecodedObjectBlocks(partData = partData, extraParams = extra)
    }

    /**
     * Reads optional `PartData` (`0x08` legacy 86-byte and/or `0x400` block-framed) followed by `ExtraParams`
     * from an `ObjectUpdateCompressed` Data buffer bounded by [blockEnd].
     */
    fun readCompressedParticleAndExtraParams(
        buf: ByteBuffer,
        compFlags: Int,
        blockEnd: Int
    ): DecodedObjectBlocks {
        var partData: PartDataConfig? = null

        if ((compFlags and COMP_FLAG_PARTICLES_LEGACY) != 0 && blockEnd - buf.position() >= PartDataBinaryCodec.LEGACY_TOTAL_BYTES) {
            val raw = ByteArray(PartDataBinaryCodec.LEGACY_TOTAL_BYTES)
            buf.get(raw)
            partData = PartDataBinaryCodec.decodePartData(raw)
        }

        if ((compFlags and COMP_FLAG_PARTICLES_NEW) != 0 && blockEnd - buf.position() >= 8) {
            val modern = PartDataBinaryCodec.readFromPacketBuffer(
                buf = buf,
                maxBytes = blockEnd - buf.position(),
                framedBlock = true
            )
            if (modern != null) {
                partData = modern
            }
        }

        var extra = FullExtraParamsResult()
        if (blockEnd - buf.position() >= 1) {
            val epStart = buf.position()
            val epCount = buf.get().toInt() and 0xFF
            var validEp = true
            repeat(epCount.coerceAtMost(32)) {
                if (blockEnd - buf.position() < 6) {
                    validEp = false
                    return@repeat
                }
                buf.short // param type
                val szBytes = buf.int
                if (szBytes < 0 || szBytes > blockEnd - buf.position()) {
                    validEp = false
                    return@repeat
                }
                buf.position(buf.position() + szBytes)
            }
            if (validEp) {
                val epLen = buf.position() - epStart
                val epBytes = ByteArray(epLen)
                val savedPos = buf.position()
                buf.position(epStart)
                buf.get(epBytes)
                buf.position(savedPos)
                extra = ExtraParamsAndSculptGenerator.parseExtraParams(epBytes)
            }
        }

        return DecodedObjectBlocks(partData = partData, extraParams = extra)
    }

    /**
     * Decodes `AvatarAnimation` (`High 20`) or `ObjectAnimation` (`High 21`) packet body:
     * - `Sender` block: `ID` (16B UUID)
     * - `AnimationList` variable count (1B): `[AnimID (16B UUID), AnimSequenceID (4B S32 LE)] * count`
     * - `AnimationSourceList` variable count (1B, optional): `[ObjectID (16B UUID)] * count`
     */
    fun decodeAvatarAnimationPacket(buf: ByteBuffer): DecodedAvatarAnimationPacket? {
        if (buf.remaining() < 17) return null
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val senderId = LludpPackets.readUuid(buf)
        val animCount = buf.get().toInt() and 0xFF
        val anims = ArrayList<SignaledAnimation>(animCount)
        repeat(animCount) {
            if (buf.remaining() < 20) return null
            val animId = LludpPackets.readUuid(buf)
            val seqId = buf.int
            anims.add(SignaledAnimation(animId, seqId))
        }
        val sources = ArrayList<UUID>()
        if (buf.remaining() >= 1) {
            val sourceCount = buf.get().toInt() and 0xFF
            repeat(sourceCount) {
                if (buf.remaining() >= 16) {
                    sources.add(LludpPackets.readUuid(buf))
                }
            }
        }
        return DecodedAvatarAnimationPacket(
            senderId = senderId,
            animations = anims,
            objectSourceIds = sources
        )
    }

    data class J2kCodestreamHeader(
        val valid: Boolean,
        val format: String, // "jp2", "j2k", or "invalid"
        val width: Int,
        val height: Int,
        val components: Int
    )

    /**
     * Inspects Second Life JPEG2000 (`.jp2` box container or raw `.j2c` / `.j2k` SOC/SIZ codestream)
     * headers to extract dimensions and channel count without native JNI overhead.
     */
    fun inspectJ2kCodestream(bytes: ByteArray): J2kCodestreamHeader {
        if (bytes.size >= 24 &&
            bytes[4] == 'j'.code.toByte() &&
            bytes[5] == 'P'.code.toByte() &&
            bytes[6] == ' '.code.toByte() &&
            bytes[7] == ' '.code.toByte()
        ) {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            for (offset in 8..bytes.size - 14) {
                if (bytes[offset] == 'i'.code.toByte() &&
                    bytes[offset + 1] == 'h'.code.toByte() &&
                    bytes[offset + 2] == 'd'.code.toByte() &&
                    bytes[offset + 3] == 'r'.code.toByte()
                ) {
                    bb.position(offset + 4)
                    val height = bb.int
                    val width = bb.int
                    val comps = if (bb.remaining() >= 2) (bb.short.toInt() and 0xFFFF).let { if (it in 1..4) it else 4 } else 4
                    if (width > 0 && height > 0) {
                        return J2kCodestreamHeader(true, "jp2", width, height, comps)
                    }
                }
            }
        }
        // Raw J2K codestream: SOC (0xFF4F) + SIZ (0xFF51)
        if (bytes.size >= 42 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0x4F.toByte() &&
            bytes[2] == 0xFF.toByte() && bytes[3] == 0x51.toByte()
        ) {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            bb.position(8) // skip SOC (2) + SIZ (2) + Lsiz (2) + Rsiz (2)
            val xSiz = bb.int
            val ySiz = bb.int
            val xOsiz = bb.int
            val yOsiz = bb.int
            bb.position(40)
            val csiz = bb.short.toInt() and 0xFFFF
            val width = xSiz - xOsiz
            val height = ySiz - yOsiz
            if (width > 0 && height > 0) {
                return J2kCodestreamHeader(true, "j2k", width, height, csiz.coerceIn(1, 4))
            }
        }
        return J2kCodestreamHeader(false, "invalid", 0, 0, 0)
    }
}
