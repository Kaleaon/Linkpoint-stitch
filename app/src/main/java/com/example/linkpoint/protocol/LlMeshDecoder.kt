package com.example.linkpoint.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decoded single submesh (material face) from a Second Life / OpenSimulator binary `.llmesh` asset.
 * Vertices are interleaved [px, py, pz, nx, ny, nz, u, v] (8 floats per vertex, ready for OpenGL ES 3.0).
 */
data class DecodedSubmesh(
    val faceIndex: Int,
    val interleavedVertices: FloatArray,
    val vertexCount: Int,
    val indices: ShortArray? = null,
    val indexCount: Int = 0
)

/**
 * Complete decoded Second Life binary mesh asset (`.llmesh`), including skinning/bind-pose alignment
 * and body-coverage flags so rigged mesh bodies & heads cleanly replace classic base geometry.
 */
data class DecodedMeshAsset(
    val lodName: String,
    val submeshes: List<DecodedSubmesh>,
    val isRigged: Boolean = false,
    val jointNames: List<String> = emptyList(),
    val totalTriangles: Int = 0,
    val coversBody: Boolean = false,
    val coversLower: Boolean = false,
    val coversHead: Boolean = false,
    val minZ: Float = -0.5f,
    val maxZ: Float = 0.5f
)

/**
 * Authentic Linden Lab Binary Mesh (`.llmesh`) decoder modeled on React-Linkpoint / LibreMetaverse:
 * 1. Parses the uncompressed LLSD Binary header map to find `medium_lod` / `high_lod` / `low_lod` / `skin` byte ranges.
 * 2. Decompresses the `skin` block to extract `joint_names`, `bind_shape_matrix`, `inverse_bind_matrix`, and `pelvis_offset`.
 * 3. Decompresses the selected LOD chunk using zlib (`Inflater`).
 * 4. For rigged meshes:
 *    - Transforms dequantized `Position` & `Normal` vertices through `bind_shape_matrix` into metric avatar skeleton bind space.
 *    - Resolves the mesh's exact `mPelvis` origin from `inverse_bind_matrix` so rigged bodies, heads, hair, outfits, and shoes
 *      all snap seamlessly onto the same pelvis-centered skeleton regardless of Blender/Maya/Avastar export origin.
 *    - Applies gentle T-pose arm relaxation from per-vertex `Weights` around the mesh's own shoulder pivots.
 *    - Computes `coversBody`, `coversLower`, and `coversHead` so loaded rigged meshes automatically hide classic fallback parts.
 */
object LlMeshDecoder {

    private const val MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024 // 16 MB safety guard
    private const val MAX_TRIANGLES_PER_FACE = 40_000          // Mobile GPU vertex budget guard

    // Canonical SL skeleton joint heights relative to mPelvis = (0, 0, 0) in meters (avatar_skeleton.xml)
    private val CANONICAL_JOINT_Z_FROM_PELVIS = mapOf(
        "mPelvis" to 0.00f,
        "PELVIS" to 0.00f,
        "mTorso" to 0.084f,
        "BELLY" to 0.10f,
        "mChest" to 0.29f,
        "CHEST" to 0.30f,
        "mNeck" to 0.54f,
        "NECK" to 0.54f,
        "mHead" to 0.62f,
        "HEAD" to 0.62f,
        "mSkull" to 0.70f,
        "mCollarLeft" to 0.455f,
        "mCollarRight" to 0.455f,
        "mShoulderLeft" to 0.43f,
        "mShoulderRight" to 0.43f,
        "mElbowLeft" to 0.43f,
        "mElbowRight" to 0.43f,
        "mWristLeft" to 0.43f,
        "mWristRight" to 0.43f,
        "mHipLeft" to -0.04f,
        "mHipRight" to -0.04f,
        "mKneeLeft" to -0.49f,
        "mKneeRight" to -0.49f,
        "mAnkleLeft" to -0.92f,
        "mAnkleRight" to -0.92f,
        "mFootLeft" to -0.98f,
        "mFootRight" to -0.98f
    )

    private val LEFT_ARM_JOINTS = setOf(
        "mShoulderLeft", "mElbowLeft", "mWristLeft", "L_UPPER_ARM", "L_LOWER_ARM", "L_HAND",
        "mHandThumb1Left", "mHandThumb2Left", "mHandThumb3Left",
        "mHandIndex1Left", "mHandIndex2Left", "mHandIndex3Left",
        "mHandMiddle1Left", "mHandMiddle2Left", "mHandMiddle3Left",
        "mHandRing1Left", "mHandRing2Left", "mHandRing3Left",
        "mHandPinky1Left", "mHandPinky2Left", "mHandPinky3Left"
    )

    private val RIGHT_ARM_JOINTS = setOf(
        "mShoulderRight", "mElbowRight", "mWristRight", "R_UPPER_ARM", "R_LOWER_ARM", "R_HAND",
        "mHandThumb1Right", "mHandThumb2Right", "mHandThumb3Right",
        "mHandIndex1Right", "mHandIndex2Right", "mHandIndex3Right",
        "mHandMiddle1Right", "mHandMiddle2Right", "mHandMiddle3Right",
        "mHandRing1Right", "mHandRing2Right", "mHandRing3Right",
        "mHandPinky1Right", "mHandPinky2Right", "mHandPinky3Right"
    )

    private data class ParsedSkinData(
        val jointNames: List<String>,
        val bindShapeMatrix: FloatArray,
        val inverseBindMatrices: List<FloatArray>,
        val pelvisOffset: Float,
        val pelvisOriginX: Float,
        val pelvisOriginY: Float,
        val pelvisOriginZ: Float,
        val leftShoulderPivot: FloatArray?,
        val rightShoulderPivot: FloatArray?
    )

    fun decode(rawBytes: ByteArray): DecodedMeshAsset? {
        if (rawBytes.size < 16) return null
        return try {
            val (header, headerEndOffset) = parseLlsdBinaryWithOffset(rawBytes) ?: return null
            val headerMap = header.asMap()
            if (headerMap.isEmpty()) return null

            // Parse optional skin block first so LOD selection knows whether this is a rigged avatar mesh
            var isRigged = false
            var skinData: ParsedSkinData? = null
            val skinEntry = headerMap["skin"]?.asMap()
            if (skinEntry != null) {
                val sOff = skinEntry["offset"]?.asInt(-1) ?: -1
                val sSz = skinEntry["size"]?.asInt(-1) ?: -1
                if (sOff >= 0 && sSz > 0 && headerEndOffset + sOff + sSz <= rawBytes.size) {
                    isRigged = true
                    skinData = runCatching {
                        val skinRaw = zlibDecompress(rawBytes, headerEndOffset + sOff, sSz)
                        parseSkinBlock(LlsdCodec.parseBinary(skinRaw))
                    }.getOrNull()
                }
            }

            // Prefer high_lod whenever it fits mobile GPU budget (prevents broken/spiky creator medium_lod imposters),
            // falling back to medium_lod -> low_lod -> lowest_lod for oversized multi-megabyte scenery meshes.
            val maxHighLodCompressedBytes = if (isRigged) 512 * 1024 else 384 * 1024
            val highEntry = headerMap["high_lod"]?.asMap()
            val highSz = highEntry?.get("size")?.asInt(-1) ?: -1
            val lodPriority = if (highSz in 1..maxHighLodCompressedBytes) {
                listOf("high_lod", "medium_lod", "low_lod", "lowest_lod")
            } else {
                listOf("medium_lod", "high_lod", "low_lod", "lowest_lod")
            }

            var selectedLodName: String? = null
            var lodOffset = -1
            var lodSize = -1

            for (lodKey in lodPriority) {
                val entry = headerMap[lodKey]?.asMap() ?: continue
                val off = entry["offset"]?.asInt(-1) ?: -1
                val sz = entry["size"]?.asInt(-1) ?: -1
                if (off >= 0 && sz > 0 && headerEndOffset + off + sz <= rawBytes.size) {
                    selectedLodName = lodKey
                    lodOffset = headerEndOffset + off
                    lodSize = sz
                    break
                }
            }
            if (selectedLodName == null || lodOffset < 0 || lodSize <= 0) return null

            val decompressedLod = zlibDecompress(rawBytes, lodOffset, lodSize)
            if (decompressedLod.isEmpty()) return null

            val lodLlsd = LlsdCodec.parseBinary(decompressedLod)
            val faceEntries = lodLlsd.asList()
            if (faceEntries.isEmpty()) return null

            val submeshes = mutableListOf<DecodedSubmesh>()
            var totalTris = 0
            var globalMinZ = Float.POSITIVE_INFINITY
            var globalMaxZ = Float.NEGATIVE_INFINITY

            faceEntries.forEachIndexed { faceIdx, faceVal ->
                val faceMap = faceVal.asMap()
                if (faceMap.isEmpty() || faceMap.containsKey("NoGeometry")) return@forEachIndexed

                val posBytes = (faceMap["Position"] as? LlsdValue.Binary)?.bytes ?: return@forEachIndexed
                val triBytes = (faceMap["TriangleList"] as? LlsdValue.Binary)?.bytes ?: return@forEachIndexed
                if (posBytes.size < 6 || triBytes.size < 6) return@forEachIndexed

                val normBytes = (faceMap["Normal"] as? LlsdValue.Binary)?.bytes
                val uvBytes = (faceMap["TexCoord0"] as? LlsdValue.Binary)?.bytes
                val weightBytes = (faceMap["Weights"] as? LlsdValue.Binary)?.bytes

                val posDomain = faceMap["PositionDomain"]?.asMap()
                val posMin = readVec3Domain(posDomain?.get("Min"), -0.5f, -0.5f, -0.5f)
                val posMax = readVec3Domain(posDomain?.get("Max"), 0.5f, 0.5f, 0.5f)

                val uvDomain = faceMap["TexCoord0Domain"]?.asMap()
                val uvMin = readVec2Domain(uvDomain?.get("Min"), 0f, 0f)
                val uvMax = readVec2Domain(uvDomain?.get("Max"), 1f, 1f)

                val vertCount = posBytes.size / 6
                val positions = FloatArray(vertCount * 3)
                val pBuf = ByteBuffer.wrap(posBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until vertCount) {
                    val ux = (pBuf.short.toInt() and 0xFFFF) / 65535f
                    val uy = (pBuf.short.toInt() and 0xFFFF) / 65535f
                    val uz = (pBuf.short.toInt() and 0xFFFF) / 65535f
                    positions[i * 3] = posMin[0] + ux * (posMax[0] - posMin[0])
                    positions[i * 3 + 1] = posMin[1] + uy * (posMax[1] - posMin[1])
                    positions[i * 3 + 2] = posMin[2] + uz * (posMax[2] - posMin[2])
                }

                val normals: FloatArray? = if (normBytes != null && normBytes.size >= vertCount * 6) {
                    val arr = FloatArray(vertCount * 3)
                    val nBuf = ByteBuffer.wrap(normBytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until vertCount) {
                        val nx = ((nBuf.short.toInt() and 0xFFFF) / 65535f) * 2f - 1f
                        val ny = ((nBuf.short.toInt() and 0xFFFF) / 65535f) * 2f - 1f
                        val nz = ((nBuf.short.toInt() and 0xFFFF) / 65535f) * 2f - 1f
                        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
                        arr[i * 3] = nx / len
                        arr[i * 3 + 1] = ny / len
                        arr[i * 3 + 2] = nz / len
                    }
                    arr
                } else null

                // Transform rigged vertices into canonical SL pelvis-centered bind pose (+ gentle A-pose arm relax)
                if (isRigged && skinData != null) {
                    applyBindShapeAndRelaxPose(positions, normals, vertCount, skinData, weightBytes)
                }

                for (i in 0 until vertCount) {
                    val z = positions[i * 3 + 2]
                    if (z < globalMinZ) globalMinZ = z
                    if (z > globalMaxZ) globalMaxZ = z
                }

                val uvs: FloatArray? = if (uvBytes != null && uvBytes.size >= vertCount * 4) {
                    val arr = FloatArray(vertCount * 2)
                    val uBuf = ByteBuffer.wrap(uvBytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until vertCount) {
                        val uu = (uBuf.short.toInt() and 0xFFFF) / 65535f
                        val uv = (uBuf.short.toInt() and 0xFFFF) / 65535f
                        arr[i * 2] = uvMin[0] + uu * (uvMax[0] - uvMin[0])
                        arr[i * 2 + 1] = 1f - (uvMin[1] + uv * (uvMax[1] - uvMin[1])) // Flip V for OpenGL texture space
                    }
                    arr
                } else null

                val triCount = (triBytes.size / 6).coerceAtMost(MAX_TRIANGLES_PER_FACE)
                val tBuf = ByteBuffer.wrap(triBytes).order(ByteOrder.LITTLE_ENDIAN)
                val validIndices = ShortArray(triCount * 3)
                var idxOut = 0
                var validTris = 0

                // Compute smooth area-weighted vertex normals whenever Normal stream is absent
                val computedNormals: FloatArray = normals ?: FloatArray(vertCount * 3)
                val needSmoothNormals = (normals == null)

                for (t in 0 until triCount) {
                    val i0 = tBuf.short.toInt() and 0xFFFF
                    val i1 = tBuf.short.toInt() and 0xFFFF
                    val i2 = tBuf.short.toInt() and 0xFFFF
                    if (i0 >= vertCount || i1 >= vertCount || i2 >= vertCount) continue
                    // Skip degenerate zero-area triangles
                    if (i0 == i1 || i1 == i2 || i2 == i0) continue

                    if (needSmoothNormals) {
                        val p0x = positions[i0 * 3]; val p0y = positions[i0 * 3 + 1]; val p0z = positions[i0 * 3 + 2]
                        val p1x = positions[i1 * 3]; val p1y = positions[i1 * 3 + 1]; val p1z = positions[i1 * 3 + 2]
                        val p2x = positions[i2 * 3]; val p2y = positions[i2 * 3 + 1]; val p2z = positions[i2 * 3 + 2]
                        val e1x = p1x - p0x; val e1y = p1y - p0y; val e1z = p1z - p0z
                        val e2x = p2x - p0x; val e2y = p2y - p0y; val e2z = p2z - p0z
                        // Area-weighted face normal (unnormalized cross product weights larger faces proportionally)
                        val cx = e1y * e2z - e1z * e2y
                        val cy = e1z * e2x - e1x * e2z
                        val cz = e1x * e2y - e1y * e2x
                        computedNormals[i0 * 3] += cx; computedNormals[i0 * 3 + 1] += cy; computedNormals[i0 * 3 + 2] += cz
                        computedNormals[i1 * 3] += cx; computedNormals[i1 * 3 + 1] += cy; computedNormals[i1 * 3 + 2] += cz
                        computedNormals[i2 * 3] += cx; computedNormals[i2 * 3 + 1] += cy; computedNormals[i2 * 3 + 2] += cz
                    }

                    validIndices[idxOut++] = i0.toShort()
                    validIndices[idxOut++] = i1.toShort()
                    validIndices[idxOut++] = i2.toShort()
                    validTris++
                }

                if (validTris > 0) {
                    if (needSmoothNormals) {
                        for (v in 0 until vertCount) {
                            val nx = computedNormals[v * 3]
                            val ny = computedNormals[v * 3 + 1]
                            val nz = computedNormals[v * 3 + 2]
                            val len = sqrt(nx * nx + ny * ny + nz * nz)
                            if (len > 1e-6f) {
                                computedNormals[v * 3] = nx / len
                                computedNormals[v * 3 + 1] = ny / len
                                computedNormals[v * 3 + 2] = nz / len
                            } else {
                                computedNormals[v * 3] = 0f
                                computedNormals[v * 3 + 1] = 0f
                                computedNormals[v * 3 + 2] = 1f
                            }
                        }
                    }

                    val interleaved = FloatArray(vertCount * 8)
                    var vOut = 0
                    for (v in 0 until vertCount) {
                        interleaved[vOut++] = positions[v * 3]
                        interleaved[vOut++] = positions[v * 3 + 1]
                        interleaved[vOut++] = positions[v * 3 + 2]
                        interleaved[vOut++] = computedNormals[v * 3]
                        interleaved[vOut++] = computedNormals[v * 3 + 1]
                        interleaved[vOut++] = computedNormals[v * 3 + 2]
                        if (uvs != null) {
                            interleaved[vOut++] = uvs[v * 2]
                            interleaved[vOut++] = uvs[v * 2 + 1]
                        } else {
                            interleaved[vOut++] = 0.5f
                            interleaved[vOut++] = 0.5f
                        }
                    }

                    val trimmedIndices = if (idxOut == validIndices.size) validIndices else validIndices.copyOf(idxOut)
                    submeshes.add(
                        DecodedSubmesh(
                            faceIndex = faceIdx,
                            interleavedVertices = interleaved,
                            vertexCount = vertCount,
                            indices = trimmedIndices,
                            indexCount = idxOut
                        )
                    )
                    totalTris += validTris
                }
            }

            if (submeshes.isEmpty()) return null

            val jointNames = skinData?.jointNames.orEmpty()
            val finalMinZ = if (globalMinZ.isFinite()) globalMinZ else -0.5f
            val finalMaxZ = if (globalMaxZ.isFinite()) globalMaxZ else 0.5f
            val zSpan = finalMaxZ - finalMinZ

            val jointSet = jointNames.toSet()
            val hasTorsoJoints = jointSet.any {
                it in setOf("mChest", "mTorso", "CHEST", "BELLY", "UPPER_BACK", "LOWER_BACK", "mSpine2", "mSpine3")
            }
            val hasLegJoints = jointSet.any {
                it in setOf("mHipLeft", "mHipRight", "mKneeLeft", "mKneeRight", "L_UPPER_LEG", "R_UPPER_LEG")
            }
            val hasHeadJoints = jointSet.any {
                it in setOf("mHead", "HEAD", "mFaceJaw", "mFaceRoot", "mFaceLipLowerCenter", "mEyeLeft", "mEyeRight")
            }

            // Classify which classic body zones this rigged mesh replaces
            val coversBody = isRigged && (hasTorsoJoints && zSpan > 0.35f)
            val coversLower = isRigged && (hasLegJoints && zSpan > 0.45f)
            val coversHead = isRigged && (hasHeadJoints && finalMaxZ > 0.50f && (zSpan > 0.16f || jointSet.any { it.startsWith("mFace") }))

            DecodedMeshAsset(
                lodName = selectedLodName,
                submeshes = submeshes,
                isRigged = isRigged,
                jointNames = jointNames,
                totalTriangles = totalTris,
                coversBody = coversBody,
                coversLower = coversLower,
                coversHead = coversHead,
                minZ = finalMinZ,
                maxZ = finalMaxZ
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSkinBlock(skinLlsd: LlsdValue): ParsedSkinData {
        val jointNames = skinLlsd["joint_names"].asList().map { it.asString() }
        val bindShape = readMat4(skinLlsd["bind_shape_matrix"]) ?: identityMat4()
        val invBindList = skinLlsd["inverse_bind_matrix"].asList().mapNotNull { readMat4(it) }
        val pelvisOffset = skinLlsd["pelvis_offset"].asDouble(0.0).toFloat().coerceIn(-1.5f, 1.5f)

        // Extract world-space bind positions of joints by inverting their inverse_bind_matrix
        var pelvisX = 0f
        var pelvisY = 0f
        var pelvisZ: Float? = null
        var leftShoulderPivot: FloatArray? = null
        var rightShoulderPivot: FloatArray? = null

        val jointBindPos = HashMap<String, FloatArray>()
        for (idx in jointNames.indices) {
            if (idx >= invBindList.size) break
            val pos = extractJointPositionFromInverseBind(invBindList[idx])
            if (pos != null) {
                jointBindPos[jointNames[idx]] = pos
            }
        }

        // Priority 1: Direct mPelvis / PELVIS joint position in bind space
        val directPelvis = jointBindPos["mPelvis"] ?: jointBindPos["PELVIS"]
        if (directPelvis != null) {
            pelvisX = directPelvis[0]
            pelvisY = directPelvis[1]
            pelvisZ = directPelvis[2]
        } else {
            // Priority 2: Infer pelvis origin from any known canonical joint (e.g. rigged mesh head -> mHead - 0.62m)
            for ((jName, canonicalZ) in CANONICAL_JOINT_Z_FROM_PELVIS) {
                val jPos = jointBindPos[jName]
                if (jPos != null) {
                    pelvisZ = jPos[2] - canonicalZ
                    // Keep X/Y centered on skeleton vertical axis for symmetric head/body joints
                    if (jName == "mTorso" || jName == "mChest" || jName == "mNeck" || jName == "mHead" || jName == "mSkull") {
                        pelvisX = jPos[0]
                        pelvisY = jPos[1]
                    }
                    break
                }
            }
        }

        leftShoulderPivot = jointBindPos["mShoulderLeft"]
        rightShoulderPivot = jointBindPos["mShoulderRight"]

        return ParsedSkinData(
            jointNames = jointNames,
            bindShapeMatrix = bindShape,
            inverseBindMatrices = invBindList,
            pelvisOffset = pelvisOffset,
            pelvisOriginX = pelvisX,
            pelvisOriginY = pelvisY,
            pelvisOriginZ = pelvisZ ?: 0f,
            leftShoulderPivot = leftShoulderPivot,
            rightShoulderPivot = rightShoulderPivot
        )
    }

    /**
     * Transforms rigged mesh vertices via `bind_shape_matrix`, centers them onto the canonical
     * Second Life `mPelvis = (0, 0, 0)` origin, and gently relaxes T-pose arms (~48 deg down)
     * around the mesh's own shoulder pivots when vertex skinning weights are present.
     */
    private fun applyBindShapeAndRelaxPose(
        positions: FloatArray,
        normals: FloatArray?,
        vertCount: Int,
        skin: ParsedSkinData,
        weightBytes: ByteArray?
    ) {
        val bsm = skin.bindShapeMatrix
        val ox = skin.pelvisOriginX
        val oy = skin.pelvisOriginY
        val oz = skin.pelvisOriginZ - skin.pelvisOffset

        // Precompute arm relaxation weights per vertex if Weights + shoulder pivots exist
        var leftArmWeights: FloatArray? = null
        var rightArmWeights: FloatArray? = null
        if (weightBytes != null && weightBytes.isNotEmpty() && skin.jointNames.isNotEmpty() &&
            (skin.leftShoulderPivot != null || skin.rightShoulderPivot != null)
        ) {
            val lFlags = BooleanArray(skin.jointNames.size) { skin.jointNames[it] in LEFT_ARM_JOINTS }
            val rFlags = BooleanArray(skin.jointNames.size) { skin.jointNames[it] in RIGHT_ARM_JOINTS }
            if (lFlags.any { it } || rFlags.any { it }) {
                val lw = FloatArray(vertCount)
                val rw = FloatArray(vertCount)
                decodeArmInfluenceWeights(weightBytes, vertCount, lFlags, rFlags, lw, rw)
                leftArmWeights = lw
                rightArmWeights = rw
            }
        }

        // 48-degree downward shoulder drop around +X axis (left arm rotates -48 deg around +X, right arm +48 deg)
        val relaxAngle = Math.toRadians(46.0).toFloat()
        val cosL = cos(-relaxAngle); val sinL = sin(-relaxAngle)
        val cosR = cos(relaxAngle); val sinR = sin(relaxAngle)
        val lPivot = skin.leftShoulderPivot?.let { floatArrayOf(it[0] - ox, it[1] - oy, it[2] - oz) }
        val rPivot = skin.rightShoulderPivot?.let { floatArrayOf(it[0] - ox, it[1] - oy, it[2] - oz) }

        for (i in 0 until vertCount) {
            val px = positions[i * 3]
            val py = positions[i * 3 + 1]
            val pz = positions[i * 3 + 2]

            // Apply column-major bind_shape_matrix & shift to mPelvis = (0, 0, 0)
            var bx = bsm[0] * px + bsm[4] * py + bsm[8] * pz + bsm[12] - ox
            var by = bsm[1] * px + bsm[5] * py + bsm[9] * pz + bsm[13] - oy
            var bz = bsm[2] * px + bsm[6] * py + bsm[10] * pz + bsm[14] - oz

            // Transform normal through upper-left 3x3 of bind_shape_matrix
            var nx = 0f; var ny = 0f; var nz = 1f
            if (normals != null) {
                val onx = normals[i * 3]
                val ony = normals[i * 3 + 1]
                val onz = normals[i * 3 + 2]
                nx = bsm[0] * onx + bsm[4] * ony + bsm[8] * onz
                ny = bsm[1] * onx + bsm[5] * ony + bsm[9] * onz
                nz = bsm[2] * onx + bsm[6] * ony + bsm[10] * onz
            }

            // Gently blend left/right arm T-pose relaxation using vertex weights
            val wL = leftArmWeights?.get(i) ?: 0f
            val wR = rightArmWeights?.get(i) ?: 0f
            if (wL > 0.01f && lPivot != null) {
                val dy = by - lPivot[1]
                val dz = bz - lPivot[2]
                val ry = lPivot[1] + dy * cosL - dz * sinL
                val rz = lPivot[2] + dy * sinL + dz * cosL
                by = by * (1f - wL) + ry * wL
                bz = bz * (1f - wL) + rz * wL
                if (normals != null) {
                    val rny = ny * cosL - nz * sinL
                    val rnz = ny * sinL + nz * cosL
                    ny = ny * (1f - wL) + rny * wL
                    nz = nz * (1f - wL) + rnz * wL
                }
            } else if (wR > 0.01f && rPivot != null) {
                val dy = by - rPivot[1]
                val dz = bz - rPivot[2]
                val ry = rPivot[1] + dy * cosR - dz * sinR
                val rz = rPivot[2] + dy * sinR + dz * cosR
                by = by * (1f - wR) + ry * wR
                bz = bz * (1f - wR) + rz * wR
                if (normals != null) {
                    val rny = ny * cosR - nz * sinR
                    val rnz = ny * sinR + nz * cosR
                    ny = ny * (1f - wR) + rny * wR
                    nz = nz * (1f - wR) + rnz * wR
                }
            }

            positions[i * 3] = bx
            positions[i * 3 + 1] = by
            positions[i * 3 + 2] = bz

            if (normals != null) {
                val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
                normals[i * 3] = nx / nLen
                normals[i * 3 + 1] = ny / nLen
                normals[i * 3 + 2] = nz / nLen
            }
        }
    }

    /**
     * Parses Second Life's compact binary `Weights` list (up to 4 joint influences per vertex;
     * byte 0xFF terminates a vertex's weight list early, otherwise uint8 jointIndex + uint16 LE weight).
     */
    private fun decodeArmInfluenceWeights(
        weightBytes: ByteArray,
        vertCount: Int,
        leftArmJoints: BooleanArray,
        rightArmJoints: BooleanArray,
        outLeft: FloatArray,
        outRight: FloatArray
    ) {
        val buf = ByteBuffer.wrap(weightBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (v in 0 until vertCount) {
            var count = 0
            var lSum = 0f
            var rSum = 0f
            while (count < 4 && buf.hasRemaining()) {
                val jointIdx = buf.get().toInt() and 0xFF
                if (jointIdx == 0xFF) break
                if (buf.remaining() < 2) return
                val w = (buf.short.toInt() and 0xFFFF) / 65535f
                if (jointIdx < leftArmJoints.size) {
                    if (leftArmJoints[jointIdx]) lSum += w
                    if (rightArmJoints[jointIdx]) rSum += w
                }
                count++
            }
            outLeft[v] = lSum.coerceIn(0f, 1f)
            outRight[v] = rSum.coerceIn(0f, 1f)
        }
    }

    /**
     * Extracts a joint's bind-pose world position from its 4x4 `inverse_bind_matrix` (`M_inv`):
     * For rigid/affine `M_inv = [R^T, -R^T * t; 0, 1]`, `t = -R * (M_inv[12..14])`.
     */
    private fun extractJointPositionFromInverseBind(m: FloatArray): FloatArray? {
        if (m.size < 16) return null
        val tx = m[12]; val ty = m[13]; val tz = m[14]
        // Compute inverse of upper 3x3 (handles both pure rotation and non-uniform collision bone scale)
        val a00 = m[0]; val a01 = m[4]; val a02 = m[8]
        val a10 = m[1]; val a11 = m[5]; val a12 = m[9]
        val a20 = m[2]; val a21 = m[6]; val a22 = m[10]

        val c00 = a11 * a22 - a12 * a21
        val c01 = -(a10 * a22 - a12 * a20)
        val c02 = a10 * a21 - a11 * a20
        val det = a00 * c00 + a01 * c01 + a02 * c02
        if (abs(det) < 1e-6f) return floatArrayOf(-tx, -ty, -tz)

        val invDet = 1f / det
        val c10 = -(a01 * a22 - a02 * a21)
        val c11 = a00 * a22 - a02 * a20
        val c12 = -(a00 * a21 - a01 * a20)
        val c20 = a01 * a12 - a02 * a11
        val c21 = -(a00 * a12 - a02 * a10)
        val c22 = a00 * a11 - a01 * a10

        val px = -(c00 * tx + c10 * ty + c20 * tz) * invDet
        val py = -(c01 * tx + c11 * ty + c21 * tz) * invDet
        val pz = -(c02 * tx + c12 * ty + c22 * tz) * invDet
        return if (px.isFinite() && py.isFinite() && pz.isFinite()) floatArrayOf(px, py, pz) else null
    }

    /**
     * Reads a 4x4 float matrix from LLSD Array (16 reals) or 64-byte Binary, normalizing
     * to OpenGL column-major order (translation in indices [12, 13, 14]).
     */
    private fun readMat4(value: LlsdValue?): FloatArray? {
        if (value == null) return null
        val m = when (value) {
            is LlsdValue.ArrayVal -> {
                if (value.items.size < 16) return null
                FloatArray(16) { i -> value.items[i].asDouble(if (i % 5 == 0) 1.0 else 0.0).toFloat() }
            }
            is LlsdValue.Binary -> {
                if (value.bytes.size < 64) return null
                val bb = ByteBuffer.wrap(value.bytes).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(16) { bb.float }
            }
            else -> return null
        }
        // Defensive check: if translation sits in row-major slots [3, 7, 11] while [12, 13, 14] are zero, transpose it
        val colTransMag = abs(m[12]) + abs(m[13]) + abs(m[14])
        val rowTransMag = abs(m[3]) + abs(m[7]) + abs(m[11])
        if (colTransMag < 1e-4f && rowTransMag > 1e-3f) {
            return floatArrayOf(
                m[0], m[4], m[8], m[12],
                m[1], m[5], m[9], m[13],
                m[2], m[6], m[10], m[14],
                m[3], m[7], m[11], m[15]
            )
        }
        return m
    }

    private fun identityMat4(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    private fun readVec3Domain(value: LlsdValue?, dx: Float, dy: Float, dz: Float): FloatArray {
        val list = value?.asList().orEmpty()
        if (list.size < 3) return floatArrayOf(dx, dy, dz)
        return floatArrayOf(
            list[0].asDouble(dx.toDouble()).toFloat(),
            list[1].asDouble(dy.toDouble()).toFloat(),
            list[2].asDouble(dz.toDouble()).toFloat()
        )
    }

    private fun readVec2Domain(value: LlsdValue?, du: Float, dv: Float): FloatArray {
        val list = value?.asList().orEmpty()
        if (list.size < 2) return floatArrayOf(du, dv)
        return floatArrayOf(
            list[0].asDouble(du.toDouble()).toFloat(),
            list[1].asDouble(dv.toDouble()).toFloat()
        )
    }

    private fun zlibDecompress(src: ByteArray, offset: Int, length: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(src, offset, length)
            val out = ByteArrayOutputStream(length * 3)
            val chunk = ByteArray(8192)
            while (!inflater.finished() && out.size() < MAX_DECOMPRESSED_BYTES) {
                val n = inflater.inflate(chunk)
                if (n <= 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(chunk, 0, n)
                }
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /**
     * Parses the leading LLSD Binary header map and returns both the parsed value
     * and the exact byte offset immediately following the header (where LOD offsets begin).
     */
    private fun parseLlsdBinaryWithOffset(data: ByteArray): Pair<LlsdValue, Int>? {
        val headerPrefix = "<? llsd/binary ?>\n".toByteArray(Charsets.UTF_8)
        val startOffset = if (data.size >= headerPrefix.size &&
            data.sliceArray(0 until headerPrefix.size).contentEquals(headerPrefix)
        ) headerPrefix.size else 0
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.position(startOffset)
        val value = readBinaryNodeTracked(buf) ?: return null
        return value to buf.position()
    }

    private fun readBinaryNodeTracked(buf: ByteBuffer): LlsdValue? {
        if (!buf.hasRemaining()) return null
        return when (buf.get().toInt().toChar()) {
            '!' -> LlsdValue.Undef
            '1' -> LlsdValue.Bool(true)
            '0' -> LlsdValue.Bool(false)
            'i' -> if (buf.remaining() >= 4) LlsdValue.IntVal(buf.int) else null
            'r' -> if (buf.remaining() >= 8) LlsdValue.Real(buf.double) else null
            'u' -> if (buf.remaining() >= 16) LlsdValue.Uuid(java.util.UUID(buf.long, buf.long)) else null
            'b' -> {
                if (buf.remaining() < 4) return null
                val len = buf.int
                if (len < 0 || len > buf.remaining()) return null
                val bytes = ByteArray(len)
                buf.get(bytes)
                LlsdValue.Binary(bytes)
            }
            's', 'l' -> {
                if (buf.remaining() < 4) return null
                val len = buf.int
                if (len < 0 || len > buf.remaining()) return null
                val bytes = ByteArray(len)
                buf.get(bytes)
                LlsdValue.Str(String(bytes, Charsets.UTF_8))
            }
            'd' -> if (buf.remaining() >= 8) LlsdValue.Date(buf.double.toString()) else null
            '[' -> {
                if (buf.remaining() < 4) return null
                val count = buf.int.coerceIn(0, 65536)
                val items = ArrayList<LlsdValue>(count)
                repeat(count) {
                    val item = readBinaryNodeTracked(buf) ?: return null
                    items.add(item)
                }
                if (buf.hasRemaining() && buf.get().toInt().toChar() == ']') Unit
                LlsdValue.ArrayVal(items)
            }
            '{' -> {
                if (buf.remaining() < 4) return null
                val count = buf.int.coerceIn(0, 65536)
                val map = LinkedHashMap<String, LlsdValue>(count)
                repeat(count) {
                    if (!buf.hasRemaining() || buf.get().toInt().toChar() != 'k') return null
                    if (buf.remaining() < 4) return null
                    val kLen = buf.int
                    if (kLen < 0 || kLen > buf.remaining()) return null
                    val kBytes = ByteArray(kLen)
                    buf.get(kBytes)
                    val key = String(kBytes, Charsets.UTF_8)
                    val v = readBinaryNodeTracked(buf) ?: return null
                    map[key] = v
                }
                if (buf.hasRemaining() && buf.get().toInt().toChar() == '}') Unit
                LlsdValue.MapVal(map)
            }
            else -> null
        }
    }
}
