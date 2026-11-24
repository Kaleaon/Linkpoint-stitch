package com.example

import com.example.linkpoint.protocol.CapabilityManager
import com.example.linkpoint.protocol.GridAssetManager
import com.example.linkpoint.protocol.LlMeshDecoder
import com.example.linkpoint.protocol.LlsdCodec
import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.SceneObjectDecoder
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.protocol.SlAssetType
import com.example.linkpoint.protocol.SlInventoryType
import com.example.linkpoint.render.AnimJointMotion
import com.example.linkpoint.render.AnimPosKeyframe
import com.example.linkpoint.render.AnimRotKeyframe
import com.example.linkpoint.render.ExtraParamTypes
import com.example.linkpoint.render.ExtraParamsAndSculptGenerator
import com.example.linkpoint.render.FlexibleParams
import com.example.linkpoint.render.FlexiblePathSimulator
import com.example.linkpoint.render.LindenAnimBinaryDecoder
import com.example.linkpoint.render.LindenAnimData
import com.example.linkpoint.render.LinkpointGlRenderer
import com.example.linkpoint.render.PartDataBinaryCodec
import com.example.linkpoint.render.PartDataConfig
import com.example.linkpoint.render.ParticleBlendFunc
import com.example.linkpoint.render.ParticleFlags
import com.example.linkpoint.render.ParticlePattern
import com.example.linkpoint.render.ParticleSystemEngine
import com.example.linkpoint.render.PbrMaterialChannelTransforms
import com.example.linkpoint.render.PlayingAvatarAnimation
import com.example.linkpoint.render.SculptParams
import com.example.linkpoint.scene.AvatarEntity
import com.example.linkpoint.scene.AvatarSkeletonHierarchy
import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.LocalSandboxBuilder
import com.example.linkpoint.scene.PrimShape
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.Transform3D
import com.example.linkpoint.scene.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import kotlin.math.sqrt

/**
 * Phase 9 Cross-Repo Golden Test Vector Suite:
 * Verifies exact parity across `Kaleaon/Linkpoint-stitch`, `Kaleaon/Linkpoint`, and `Kaleaon/react-linkpoint`
 * for 3D quaternion/matrix math, `.llmesh` binary LLSD + zlib & `.llm` decoding, J2K codestream headers,
 * `PartData` (`llParticleSystem`) decoding & simulation, `ExtraParams` (`0x10` flexi, `0x20` light, `0x30` sculpt),
 * per-channel glTF PBR `KHR_texture_transform`, and Linden `.anim` binary keyframe decoding + IK constraints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GoldenTestVectorsParityTest {

    private fun loadResourceJson(resourcePath: String): JSONObject {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(resourcePath)) {
            "Missing test-vector resource: $resourcePath"
        }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(text)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val out = ByteArray(clean.length / 2)
        for (i in clean.indices step 2) {
            out[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
        }
        return out
    }

    @Test
    fun `verifies cross-repo SL quaternion, matrix, Euler, and AABB golden vectors`() {
        val root = loadResourceJson("test-vectors/math/quaternion_matrix_transform_vectors.json")
        assertEquals("1.0", root.getString("version"))

        val quats = root.getJSONArray("quaternions")
        for (i in 0 until quats.length()) {
            val c = quats.getJSONObject(i)
            val name = c.getString("name")
            val op = c.getString("operation")
            val input = c.getJSONObject("input")
            val expected = c.getJSONObject("expected")

            when (op) {
                "identity" -> {
                    val q = Quat.IDENTITY
                    assertEquals(expected.getDouble("x").toFloat(), q.x, 1e-5f)
                    assertEquals(expected.getDouble("y").toFloat(), q.y, 1e-5f)
                    assertEquals(expected.getDouble("z").toFloat(), q.z, 1e-5f)
                    assertEquals(expected.getDouble("w").toFloat(), q.w, 1e-5f)
                }
                "multiply" -> {
                    val a = input.getJSONArray("q1")
                    val b = input.getJSONArray("q2")
                    val q1 = Quat(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
                    val q2 = Quat(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat())
                    val prod = q1.multiplyLinden(q2)
                    val exp = expected.getJSONArray("q")
                    assertEquals("$name x", exp.getDouble(0).toFloat(), prod.x, 1e-4f)
                    assertEquals("$name y", exp.getDouble(1).toFloat(), prod.y, 1e-4f)
                    assertEquals("$name z", exp.getDouble(2).toFloat(), prod.z, 1e-4f)
                    assertEquals("$name w", exp.getDouble(3).toFloat(), prod.w, 1e-4f)
                }
                "normalize" -> {
                    val a = input.getJSONArray("q")
                    val raw = Quat(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
                    val mag = sqrt(raw.x * raw.x + raw.y * raw.y + raw.z * raw.z + raw.w * raw.w)
                    assertEquals("$name mag", expected.getDouble("magnitude").toFloat(), mag, 1e-4f)
                    val norm = raw.normalized()
                    val exp = expected.getJSONArray("q")
                    assertEquals(exp.getDouble(0).toFloat(), norm.x, 1e-4f)
                    assertEquals(exp.getDouble(1).toFloat(), norm.y, 1e-4f)
                    assertEquals(exp.getDouble(2).toFloat(), norm.z, 1e-4f)
                    assertEquals(exp.getDouble(3).toFloat(), norm.w, 1e-4f)
                }
                "rotate_vector" -> {
                    val qa = input.getJSONArray("q")
                    val va = input.getJSONArray("v")
                    val q = Quat(qa.getDouble(0).toFloat(), qa.getDouble(1).toFloat(), qa.getDouble(2).toFloat(), qa.getDouble(3).toFloat())
                    val v = Vec3(va.getDouble(0).toFloat(), va.getDouble(1).toFloat(), va.getDouble(2).toFloat())
                    val rv = q.rotate(v)
                    val exp = expected.getJSONArray("v")
                    assertEquals("$name vx", exp.getDouble(0).toFloat(), rv.x, 1e-4f)
                    assertEquals("$name vy", exp.getDouble(1).toFloat(), rv.y, 1e-4f)
                    assertEquals("$name vz", exp.getDouble(2).toFloat(), rv.z, 1e-4f)
                }
                "slerp" -> {
                    val a = input.getJSONArray("q1")
                    val b = input.getJSONArray("q2")
                    val t = input.getDouble("t").toFloat()
                    val q1 = Quat(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
                    val q2 = Quat(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat())
                    val res = LindenAnimBinaryDecoder.slerpQuat(q1, q2, t)
                    val exp = expected.getJSONArray("q")
                    assertEquals("$name sx", exp.getDouble(0).toFloat(), res.x, 1e-4f)
                    assertEquals("$name sy", exp.getDouble(1).toFloat(), res.y, 1e-4f)
                    assertEquals("$name sz", exp.getDouble(2).toFloat(), res.z, 1e-4f)
                    assertEquals("$name sw", exp.getDouble(3).toFloat(), res.w, 1e-4f)
                }
            }
        }

        val matrices = root.getJSONArray("matrices")
        for (i in 0 until matrices.length()) {
            val mCase = matrices.getJSONObject(i)
            val op = mCase.getString("operation")
            val input = mCase.getJSONObject("input")
            val expected = mCase.getJSONObject("expected")
            when (op) {
                "multiply" -> {
                    val m1Json = input.getJSONArray("m1")
                    val m2Json = input.getJSONArray("m2")
                    val m1 = FloatArray(16) { m1Json.getDouble(it).toFloat() }
                    val m2 = FloatArray(16) { m2Json.getDouble(it).toFloat() }
                    val prod = Transform3D.multiplyMat4(m1, m2)
                    val exp = expected.getJSONArray("m")
                    for (k in 0 until 16) {
                        assertEquals(exp.getDouble(k).toFloat(), prod[k], 1e-5f)
                    }
                }
                "transform_point" -> {
                    val tr = input.getJSONArray("translation")
                    val pt = input.getJSONArray("v")
                    val xf = Transform3D(Vec3(tr.getDouble(0).toFloat(), tr.getDouble(1).toFloat(), tr.getDouble(2).toFloat()), Quat.IDENTITY)
                    val out = xf.transformPoint(Vec3(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat(), pt.getDouble(2).toFloat()))
                    val exp = expected.getJSONArray("v")
                    assertEquals(exp.getDouble(0).toFloat(), out.x, 1e-5f)
                    assertEquals(exp.getDouble(1).toFloat(), out.y, 1e-5f)
                    assertEquals(exp.getDouble(2).toFloat(), out.z, 1e-5f)
                }
            }
        }

        val eulers = root.getJSONArray("euler_angles")
        for (i in 0 until eulers.length()) {
            val eCase = eulers.getJSONObject(i)
            val radToDeg = (180.0 / Math.PI).toFloat()
            val q = Quat.fromEulerDeg(
                eCase.getDouble("roll").toFloat() * radToDeg,
                eCase.getDouble("pitch").toFloat() * radToDeg,
                eCase.getDouble("yaw").toFloat() * radToDeg
            )
            val exp = eCase.getJSONArray("expected_quaternion")
            assertEquals(exp.getDouble(0).toFloat(), q.x, 1e-4f)
            assertEquals(exp.getDouble(1).toFloat(), q.y, 1e-4f)
            assertEquals(exp.getDouble(2).toFloat(), q.z, 1e-4f)
            assertEquals(exp.getDouble(3).toFloat(), q.w, 1e-4f)
        }
    }

    @Test
    fun `verifies cross-repo LLMesh decompression, multi-LOD pixel selection, and binary LLSD + zlib vectors`() {
        val meshJson = loadResourceJson("test-vectors/mesh/llmesh_decompress_vectors.json")
        val preJson = loadResourceJson("test-vectors/mesh/preprocessed_mesh_vectors.json")
        val phase9Json = loadResourceJson("test-vectors/phase9/phase9_sl_binary_vectors.json")

        // 1. Canonical Linden Binary Mesh 1.0 (.llm) triangle & multi-submesh partition vectors
        val llmVectors = meshJson.getJSONArray("llmesh_vectors")
        for (i in 0 until llmVectors.length()) {
            val c = llmVectors.getJSONObject(i)
            val decoded = LlMeshDecoder.decode(hexToBytes(c.getString("hex_bytes")))
            assertNotNull(decoded)
            val sub = decoded!!.submeshes.first()
            val exp = c.getJSONObject("expected")
            assertEquals(exp.getInt("vertex_count"), sub.vertexCount)
            assertEquals(exp.getInt("index_count"), sub.indexCount)
            val expPos = exp.getJSONArray("positions")
            for (v in 0 until sub.vertexCount) {
                assertEquals(expPos.getDouble(v * 3).toFloat(), sub.interleavedVertices[v * 8], 1e-4f)
                assertEquals(expPos.getDouble(v * 3 + 1).toFloat(), sub.interleavedVertices[v * 8 + 1], 1e-4f)
                assertEquals(expPos.getDouble(v * 3 + 2).toFloat(), sub.interleavedVertices[v * 8 + 2], 1e-4f)
            }
        }

        val subVectors = meshJson.getJSONArray("submesh_vectors")
        for (i in 0 until subVectors.length()) {
            val c = subVectors.getJSONObject(i)
            val decoded = LlMeshDecoder.decode(hexToBytes(c.getString("hex_bytes")))
            assertNotNull(decoded)
            val exp = c.getJSONObject("expected")
            assertEquals(exp.getInt("submesh_count"), decoded!!.submeshes.size)
            val expSubs = exp.getJSONArray("submeshes")
            for (s in 0 until expSubs.length()) {
                val es = expSubs.getJSONObject(s)
                assertEquals(es.getInt("material_index"), decoded.submeshes[s].faceIndex)
                assertEquals(es.getInt("vertex_count"), decoded.submeshes[s].vertexCount)
                assertEquals(es.getInt("index_count"), decoded.submeshes[s].indexCount)
            }
        }

        // 2. Multi-LOD projected pixel coverage & LOD bucket selection
        val lodVectors = meshJson.getJSONArray("multi_lod_vectors")
        for (i in 0 until lodVectors.length()) {
            val c = lodVectors.getJSONObject(i)
            val px = LlMeshDecoder.calculateProjectedPixelCoverage(
                boundingRadius = c.getDouble("bounding_radius").toFloat(),
                distanceMeters = c.getDouble("distance_meters").toFloat(),
                fovRad = c.getDouble("fov_rad").toFloat(),
                screenHeightPx = c.getDouble("screen_height_px").toFloat()
            )
            val exp = c.getJSONObject("expected")
            assertEquals(exp.getDouble("projected_pixel_coverage").toFloat(), px, 0.25f)
            assertEquals(exp.getString("selected_lod"), LlMeshDecoder.selectLodByPixelCoverage(px))
        }

        // 3. Preprocessed Unified-LLSD schema sanity check
        val preVectors = preJson.getJSONArray("preprocessed_mesh_vectors")
        assertEquals(1, preVectors.length())
        assertEquals("unified-llsd-mesh-v1", preVectors.getJSONObject(0).getJSONObject("expected").getString("format"))

        // 4. Streamed binary LLSD + zlib multi-face .llmesh golden vector
        val zlibMeshArr = phase9Json.getJSONArray("llmesh_binary_zlib_vectors")
        for (i in 0 until zlibMeshArr.length()) {
            val c = zlibMeshArr.getJSONObject(i)
            val decoded = LlMeshDecoder.decode(hexToBytes(c.getString("hex_bytes")))
            assertNotNull(decoded)
            val exp = c.getJSONObject("expected")
            assertEquals(exp.getString("lod_name"), decoded!!.lodName)
            assertEquals(exp.getInt("submesh_count"), decoded.submeshes.size)
            assertEquals(exp.getInt("total_triangles"), decoded.totalTriangles)

            val face0 = decoded.submeshes[0].interleavedVertices
            val v0 = exp.getJSONArray("face0_vertex0_pos")
            val v1 = exp.getJSONArray("face0_vertex1_pos")
            val v2 = exp.getJSONArray("face0_vertex2_pos")
            assertEquals(v0.getDouble(0).toFloat(), face0[0], 1e-4f)
            assertEquals(v0.getDouble(1).toFloat(), face0[1], 1e-4f)
            assertEquals(v0.getDouble(2).toFloat(), face0[2], 1e-4f)
            assertEquals(v1.getDouble(0).toFloat(), face0[8], 1e-4f)
            assertEquals(v2.getDouble(1).toFloat(), face0[17], 1e-4f)
            assertEquals(v2.getDouble(2).toFloat(), face0[18], 1e-4f)
            // Normal +Z = (0, 0, 1)
            assertEquals(0f, face0[3], 1e-3f)
            assertEquals(0f, face0[4], 1e-3f)
            assertEquals(1f, face0[5], 1e-3f)
        }
    }

    @Test
    fun `verifies cross-repo JPEG2000 codestream header and real JP2 and J2K vectors`() {
        val j2kJson = loadResourceJson("test-vectors/textures/j2k_texture_decoder_vectors.json")
        val vectors = j2kJson.getJSONArray("j2k_vectors")
        for (i in 0 until vectors.length()) {
            val c = vectors.getJSONObject(i)
            val info = SceneObjectDecoder.inspectJ2kCodestream(hexToBytes(c.getString("hex_bytes")))
            val exp = c.getJSONObject("expected")
            if (exp.getString("status") == "success") {
                assertTrue(info.valid)
                assertEquals("jp2", info.format)
                assertEquals(exp.getInt("width"), info.width)
                assertEquals(exp.getInt("height"), info.height)
            } else {
                assertFalse(info.valid)
            }
        }

        val phase9Json = loadResourceJson("test-vectors/phase9/phase9_sl_binary_vectors.json")
        val realJ2k = phase9Json.getJSONArray("j2k_real_codestream_vectors")
        for (i in 0 until realJ2k.length()) {
            val c = realJ2k.getJSONObject(i)
            val info = SceneObjectDecoder.inspectJ2kCodestream(hexToBytes(c.getString("hex_bytes")))
            val exp = c.getJSONObject("expected")
            assertTrue(info.valid)
            assertEquals(exp.getString("format"), info.format)
            assertEquals(exp.getInt("width"), info.width)
            assertEquals(exp.getInt("height"), info.height)
            assertEquals(exp.getInt("components"), info.components)
        }
    }

    @Test
    fun `verifies PartData binary decoding, ParticleSystemEngine simulation, MAX_PARTICLES cap, billboard batching, and LocalSandbox emitters`() {
        val phase9Json = loadResourceJson("test-vectors/phase9/phase9_sl_binary_vectors.json")
        val pdVectors = phase9Json.getJSONArray("part_data_vectors")

        // 1. Golden vector #0: 86-byte unframed legacy PartData
        val v0 = pdVectors.getJSONObject(0)
        val exp0 = v0.getJSONObject("expected")
        val cfg0 = PartDataBinaryCodec.decodePartData(hexToBytes(v0.getString("hex_bytes")))
        assertNotNull(cfg0)
        assertEquals(exp0.getInt("crc"), cfg0!!.crc)
        assertEquals(exp0.getInt("pattern"), cfg0.pattern)
        assertEquals(exp0.getDouble("max_age").toFloat(), cfg0.maxAge, 1e-3f)
        assertEquals(exp0.getDouble("start_age").toFloat(), cfg0.startAge, 1e-3f)
        assertEquals(exp0.getDouble("inner_angle").toFloat(), cfg0.innerAngle, 1e-3f)
        assertEquals(exp0.getDouble("outer_angle").toFloat(), cfg0.outerAngle, 1e-3f)
        assertEquals(exp0.getDouble("burst_rate").toFloat(), cfg0.burstRate, 1e-3f)
        assertEquals(exp0.getDouble("burst_radius").toFloat(), cfg0.burstRadius, 1e-3f)
        assertEquals(exp0.getDouble("burst_speed_min").toFloat(), cfg0.burstSpeedMin, 1e-3f)
        assertEquals(exp0.getDouble("burst_speed_max").toFloat(), cfg0.burstSpeedMax, 1e-3f)
        assertEquals(exp0.getInt("burst_part_count"), cfg0.burstPartCount)
        assertEquals(1.5f, cfg0.omega.z, 0.02f)
        assertEquals(-4.0f, cfg0.acceleration.z, 0.02f)
        assertEquals(UUID.fromString(exp0.getString("texture_uuid")), cfg0.textureId)
        assertEquals(UUID.fromString(exp0.getString("target_uuid")), cfg0.targetId)
        assertEquals(exp0.getInt("data_flags"), cfg0.dataFlags)
        assertEquals(exp0.getDouble("part_max_age").toFloat(), cfg0.partMaxAge, 1e-3f)
        assertEquals(0.25f, cfg0.startScaleX, 1e-3f)
        assertEquals(0.375f, cfg0.startScaleY, 1e-3f)
        assertEquals(1.0f, cfg0.endScaleX, 1e-3f)
        assertEquals(1.5f, cfg0.endScaleY, 1e-3f)

        // Round-trip encode/decode unframed
        val reEncoded0 = PartDataBinaryCodec.decodePartData(PartDataBinaryCodec.encodePartData(cfg0, framedBlock = false))
        assertEquals(cfg0, reEncoded0)

        // 2. Golden vector #1: 98-byte block-framed PartData with DATA_GLOW | DATA_BLEND (additive)
        val v1 = pdVectors.getJSONObject(1)
        val exp1 = v1.getJSONObject("expected")
        val raw1 = hexToBytes(v1.getString("hex_bytes"))
        val cfg1 = PartDataBinaryCodec.decodePartData(raw1)
        assertNotNull(cfg1)
        assertEquals(exp1.getInt("data_flags"), cfg1!!.dataFlags)
        assertEquals(exp1.getDouble("start_glow").toFloat(), cfg1.startGlow, 0.01f)
        assertEquals(exp1.getDouble("end_glow").toFloat(), cfg1.endGlow, 0.01f)
        assertEquals(ParticleBlendFunc.SOURCE_ALPHA, cfg1.blendFuncSource)
        assertEquals(ParticleBlendFunc.ONE, cfg1.blendFuncDest)

        // Verify ObjectUpdateCompressed trailer decoding with compFlags = 0x08 (legacy 86B) & 0x400 (new framed)
        val compBuf = ByteBuffer.allocate(raw1.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(raw1)
            position(0)
        }
        val decodedComp = SceneObjectDecoder.readCompressedParticleAndExtraParams(
            buf = compBuf,
            compFlags = SceneObjectDecoder.COMP_FLAG_PARTICLES_NEW,
            blockEnd = raw1.size
        )
        assertEquals(cfg1, decodedComp.partData)

        // 3. ParticleSystemEngine simulation: bursts, color/scale/glow lerp, bounce, TARGET_LINEAR, FOLLOW_SRC, MAX_PARTICLES = 4096
        var rngCounter = 0
        val engine = ParticleSystemEngine(randomFloat = {
            rngCounter = (rngCounter + 1) % 8
            0.5f // deterministic midpoint sampling
        })
        val emitterA = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001")
        val targetB = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002")
        val bounceCfg = PartDataConfig(
            crc = 101,
            pattern = ParticlePattern.DROP,
            burstRate = 0.05f,
            burstPartCount = 4,
            acceleration = Vec3(0f, 0f, -20f),
            dataFlags = ParticleFlags.INTERP_COLOR or ParticleFlags.INTERP_SCALE or ParticleFlags.BOUNCE or ParticleFlags.DATA_GLOW,
            partMaxAge = 2.0f,
            startColor = ColorRgba(1f, 0f, 0f, 1f),
            endColor = ColorRgba(0f, 0f, 1f, 0f),
            startScaleX = 0.2f,
            startScaleY = 0.2f,
            endScaleX = 1.2f,
            endScaleY = 1.2f,
            startGlow = 0.6f,
            endGlow = 0.0f
        )
        engine.setEmitter(emitterA, bounceCfg, nowSec = 0f)
        val posMap = mutableMapOf(
            emitterA to Vec3(10f, 10f, 20.5f),
            targetB to Vec3(20f, 10f, 25.0f)
        )
        engine.update(0.1f, posMap, waterHeight = 20.0f) // spawns burst at z=20.5 (bounce plane = max(20.5, 20.0) = 20.5)
        var frames = emptyList<com.example.linkpoint.render.ParticleFrame>()
        repeat(8) {
            frames = engine.update(0.1f, posMap, waterHeight = 20.0f)
        }
        assertTrue("Expected active simulated particles", frames.isNotEmpty())
        // Bounce keeps every particle at or above bouncePlaneZ (20.5m) despite -20 m/s^2 downward acceleration!
        assertTrue(frames.all { it.position.z >= 20.49f })
        // Mid-life color/scale/glow interpolated smoothly between start & end
        val sample = frames.first()
        assertTrue(sample.color.b > 0.1f && sample.color.r < 0.9f)
        assertTrue(sample.scaleX > 0.25f && sample.glow < 0.6f)

        // TARGET_LINEAR exact interpolation between emitterA and targetB
        val linearId = UUID.fromString("cccccccc-0000-4000-8000-000000000003")
        val linearCfg = PartDataConfig(
            crc = 202,
            pattern = ParticlePattern.DROP,
            burstRate = 5.0f,
            burstPartCount = 1,
            targetId = targetB,
            dataFlags = ParticleFlags.TARGET_LINEAR or ParticleFlags.EMISSIVE or ParticleFlags.DATA_BLEND,
            partMaxAge = 2.0f,
            blendFuncSource = ParticleBlendFunc.SOURCE_ALPHA,
            blendFuncDest = ParticleBlendFunc.ONE
        )
        engine.clear()
        engine.setEmitter(linearId, linearCfg, nowSec = 0f)
        posMap[linearId] = Vec3(0f, 0f, 10f)
        posMap[targetB] = Vec3(10f, 0f, 20f)
        engine.update(0.01f, posMap) // spawn at t=0.01
        var midFrames = emptyList<com.example.linkpoint.render.ParticleFrame>()
        repeat(4) {
            midFrames = engine.update(0.2475f, posMap) // total age = 1.0 / 2.0 -> t = 0.5 -> midpoint (5, 0, 15)!
        }
        assertEquals(1, midFrames.size)
        assertEquals(5.0f, midFrames[0].position.x, 0.15f)
        assertEquals(15.0f, midFrames[0].position.z, 0.15f)
        assertTrue(midFrames[0].additiveBlend)

        // Camera-facing billboard quad batching
        val scratch = FloatArray(ParticleSystemEngine.MAX_PARTICLES * ParticleSystemEngine.VERTICES_PER_PARTICLE * ParticleSystemEngine.FLOATS_PER_VERTEX)
        val batches = engine.buildBillboardGeometry(
            frames = midFrames,
            cameraEye = Vec3(5f, -5f, 15f),
            cameraForward = Vec3(0f, 1f, 0f),
            outVertices = scratch
        )
        assertEquals(1, batches.size)
        assertEquals(1, batches[0].quadCount)
        assertTrue(batches[0].additiveBlend)

        // Enforce MAX_PARTICLES = 4096 hard ceiling
        val floodId = UUID.fromString("dddddddd-0000-4000-8000-000000000004")
        posMap[floodId] = Vec3(0f, 0f, 30f)
        engine.setEmitter(
            floodId,
            PartDataConfig(crc = 303, pattern = ParticlePattern.EXPLODE, burstRate = 0.01f, burstPartCount = 255, partMaxAge = 10f)
        )
        repeat(30) { engine.update(0.02f, posMap) }
        assertEquals(ParticleSystemEngine.MAX_PARTICLES, engine.liveParticleCount)
        engine.removeEmitter(floodId)
        assertTrue(engine.liveParticleCount < ParticleSystemEngine.MAX_PARTICLES)

        // 4. Local Sandbox includes live fountain + beacon emitters and flexible prim
        val sandboxTerrain = LocalSandboxBuilder.buildTerrain()
        val sandboxObjects = LocalSandboxBuilder.buildWorldObjects(sandboxTerrain)
        val emittersInSandbox = sandboxObjects.filter { it.partData?.isActiveEmitter == true }
        assertTrue("Expected live fountain & beacon particle emitters in LocalSandboxBuilder", emittersInSandbox.size >= 2)
        assertTrue(sandboxObjects.any { it.flexibleParams != null })
    }

    @Test
    fun `verifies ExtraParams 0x10 Flexible, 0x20 Light, 0x30 Sculpt meshes, Flexi sag and wind, and per-channel glTF PBR UV transforms`() {
        val phase9Json = loadResourceJson("test-vectors/phase9/phase9_sl_binary_vectors.json")
        val epVectors = phase9Json.getJSONArray("extra_params_vectors")

        // 1. Combined 0x10 Flexible + 0x20 Light + 0x30 Sculpt (Torus + Invert + Mirror)
        val ep0 = epVectors.getJSONObject(0)
        val exp0 = ep0.getJSONObject("expected")
        val parsed0 = ExtraParamsAndSculptGenerator.parseExtraParams(hexToBytes(ep0.getString("hex_bytes")))
        assertNotNull(parsed0.flexibleParams)
        assertNotNull(parsed0.lightParams)
        assertNotNull(parsed0.sculptParams)
        assertFalse(parsed0.isMesh)
        assertTrue(parsed0.isSculptedPrim)

        val flex = parsed0.flexibleParams!!
        assertEquals(exp0.getInt("flexi_softness"), flex.softness)
        assertEquals(exp0.getInt("flexi_sections"), flex.numFlexiSections)
        assertEquals(exp0.getDouble("flexi_tension").toFloat(), flex.tension, 1e-3f)
        assertEquals(exp0.getDouble("flexi_air_friction").toFloat(), flex.airFriction, 1e-3f)
        assertEquals(exp0.getDouble("flexi_gravity").toFloat(), flex.gravity, 1e-3f)
        assertEquals(exp0.getDouble("flexi_wind").toFloat(), flex.windSensitivity, 1e-3f)
        assertEquals(0.5f, flex.userForce.x, 1e-4f)

        val light = parsed0.lightParams!!
        assertEquals(exp0.getDouble("light_intensity").toFloat(), light.intensity, 1e-3f)
        assertEquals(exp0.getDouble("light_radius").toFloat(), light.radius, 1e-3f)
        assertEquals(exp0.getDouble("light_falloff").toFloat(), light.falloff, 1e-3f)

        val sculpt = parsed0.sculptParams!!
        assertEquals(UUID.fromString(exp0.getString("sculpt_uuid")), sculpt.sculptTextureId)
        assertEquals(SculptParams.TYPE_TORUS, sculpt.stitchType)
        assertTrue(sculpt.invert)
        assertTrue(sculpt.mirror)

        // Round-trip encodeExtraParams -> parseExtraParams
        val roundTrip = ExtraParamsAndSculptGenerator.parseExtraParams(
            ExtraParamsAndSculptGenerator.encodeExtraParams(flex, light, sculpt)
        )
        assertEquals(flex.softness, roundTrip.flexibleParams?.softness)
        assertEquals(sculpt, roundTrip.sculptParams)

        // Mesh stitchType = 5 preserved as streamed mesh (NOT sculpted prim!)
        val epMesh = epVectors.getJSONObject(1)
        val parsedMesh = ExtraParamsAndSculptGenerator.parseExtraParams(hexToBytes(epMesh.getString("hex_bytes")))
        assertTrue(parsedMesh.isMesh)
        assertFalse(parsedMesh.isSculptedPrim)
        assertEquals(UUID.fromString(epMesh.getJSONObject("expected").getString("mesh_uuid")), parsedMesh.meshAssetId)

        // 2. Sculpted Prim UV mesh generation across Sphere(1), Torus(2), Plane(3), Cylinder(4) + Mirror/Invert
        val w = 16; val h = 16
        val argb = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            val theta = (x.toDouble() / w) * 2.0 * Math.PI
            val phi = (y.toDouble() / (h - 1)) * Math.PI
            val rx = ((Math.sin(phi) * Math.cos(theta) * 0.5 + 0.5) * 255.0).toInt().coerceIn(0, 255)
            val gy = ((Math.sin(phi) * Math.sin(theta) * 0.5 + 0.5) * 255.0).toInt().coerceIn(0, 255)
            val bz = ((Math.cos(phi) * 0.5 + 0.5) * 255.0).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (rx shl 16) or (gy shl 8) or bz
        }
        val sculptTexId = UUID.randomUUID()
        for (stitchType in listOf(SculptParams.TYPE_SPHERE, SculptParams.TYPE_TORUS, SculptParams.TYPE_PLANE, SculptParams.TYPE_CYLINDER)) {
            val mesh = ExtraParamsAndSculptGenerator.generateSculptMeshFromArgb(
                argbPixels = argb,
                width = w,
                height = h,
                sculptParams = SculptParams(sculptTexId, stitchType),
                gridDivisions = 16
            )
            assertNotNull("Expected valid sculpt mesh for stitchType=$stitchType", mesh)
            val sub = mesh!!.submeshes.single()
            assertEquals(17 * 17, sub.vertexCount)
            assertEquals(16 * 16 * 6, sub.indexCount)
        }
        val normalPlane = ExtraParamsAndSculptGenerator.generateSculptMeshFromArgb(
            argb, w, h, SculptParams(sculptTexId, SculptParams.TYPE_PLANE), gridDivisions = 8
        )!!.submeshes.single()
        val invertedPlane = ExtraParamsAndSculptGenerator.generateSculptMeshFromArgb(
            argb, w, h, SculptParams(sculptTexId, SculptParams.TYPE_PLANE or SculptParams.FLAG_INVERT), gridDivisions = 8
        )!!.submeshes.single()
        // FLAG_INVERT flips surface normals & triangle winding across interior and pole vertices
        val midVertOffset = (4 * 9 + 4) * 8
        assertEquals(-normalPlane.interleavedVertices[midVertOffset + 3], invertedPlane.interleavedVertices[midVertOffset + 3], 1e-4f)
        assertEquals(-normalPlane.interleavedVertices[midVertOffset + 4], invertedPlane.interleavedVertices[midVertOffset + 4], 1e-4f)
        assertEquals(-normalPlane.interleavedVertices[midVertOffset + 5], invertedPlane.interleavedVertices[midVertOffset + 5], 1e-4f)
        val normIndices = checkNotNull(normalPlane.indices)
        val invIndices = checkNotNull(invertedPlane.indices)
        assertEquals(normIndices[1], invIndices[2])
        assertEquals(normIndices[2], invIndices[1])

        // 3. FlexiblePathSimulator gravity sag & wind sway
        val ropeFlex = FlexibleParams(
            softness = 2,
            numFlexiSections = 5,
            tension = 1.2f,
            airFriction = 1.5f,
            gravity = 2.5f, // downward pull in SL flexi convention: -gravity along Z
            windSensitivity = 3.0f
        )
        val sim = FlexiblePathSimulator(ropeFlex)
        val anchorOrigin = Vec3(100f, 100f, 30f)
        val primScale = Vec3(0.2f, 0.2f, 4.0f)
        repeat(40) {
            sim.step(anchorOrigin, Quat.IDENTITY, primScale, dtSec = 0.025f, windVector = Vec3(2.0f, 0f, 0f))
        }
        val chainPts = sim.currentCenterline()
        assertEquals(5, chainPts.size)
        // Base anchor [0] stays fixed at bottom of prim (z = 30 - 2 = 28); free tip [4] sways in +X wind and sags below rigid top (32.0)!
        assertEquals(28.0f, chainPts.first().z, 1e-3f)
        assertTrue("Expected wind sway in +X on free tip, got ${chainPts.last().x}", chainPts.last().x > 100.15f)
        assertTrue("Expected gravity sag below rigid top z=32, got ${chainPts.last().z}", chainPts.last().z < 31.95f)

        // 4. Per-channel glTF PBR KHR_texture_transform
        val pbrVec = phase9Json.getJSONArray("pbr_uv_transform_vectors").getJSONObject(0)
        val pbrExp = pbrVec.getJSONObject("expected")
        val pbr = PbrMaterialChannelTransforms.fromGltfJson(pbrVec.getString("gltf_json"))
        assertEquals(UUID.fromString(pbrExp.getString("base_color_uuid")), pbr.baseColorTextureId)
        assertEquals(UUID.fromString(pbrExp.getString("normal_uuid")), pbr.normalTextureId)
        assertEquals(UUID.fromString(pbrExp.getString("mr_uuid")), pbr.metallicRoughnessTextureId)
        assertEquals(UUID.fromString(pbrExp.getString("emissive_uuid")), pbr.emissiveTextureId)

        val sampleUv = pbrVec.getJSONArray("sample_uv")
        val u = sampleUv.getDouble(0).toFloat()
        val v = sampleUv.getDouble(1).toFloat()
        val (bcU, bcV) = pbr.baseColor.apply(u, v)
        val expBc = pbrExp.getJSONArray("base_color_transformed_uv")
        assertEquals(expBc.getDouble(0).toFloat(), bcU, 1e-4f)
        assertEquals(expBc.getDouble(1).toFloat(), bcV, 1e-4f)

        val (mrU, mrV) = pbr.metallicRoughness.apply(u, v)
        val expMr = pbrExp.getJSONArray("mr_transformed_uv")
        assertEquals(expMr.getDouble(0).toFloat(), mrU, 1e-4f)
        assertEquals(expMr.getDouble(1).toFloat(), mrV, 1e-4f)

        // Prove all 4 channels evaluate distinct UV coordinates!
        val (nmU, nmV) = pbr.normal.apply(u, v)
        val (emU, emV) = pbr.emissive.apply(u, v)
        assertFalse(kotlin.math.abs(bcU - nmU) < 1e-3f && kotlin.math.abs(bcV - nmV) < 1e-3f)
        assertFalse(kotlin.math.abs(mrU - emU) < 1e-3f && kotlin.math.abs(mrV - emV) < 1e-3f)
    }

    @Test
    fun `verifies Linden binary anim keyframe decoding, 86-byte IK constraints, priority blending, Packet High 20, and skeleton pose driving`() {
        val phase9Json = loadResourceJson("test-vectors/phase9/phase9_sl_binary_vectors.json")
        val animVectors = phase9Json.getJSONArray("linden_anim_vectors")

        // 1. Real SL Express_Laugh_Emote .anim asset with 12 joints and 1 trailing 86-byte IK constraint
        val laughCase = animVectors.getJSONObject(0)
        val laughExp = laughCase.getJSONObject("expected")
        val laughBytes = Base64.getMimeDecoder().decode(laughCase.getString("base64_bytes"))
        val laughAnim = LindenAnimBinaryDecoder.decode(laughBytes)
        assertNotNull(laughAnim)
        assertEquals(laughExp.getInt("version"), laughAnim!!.version)
        assertEquals(laughExp.getInt("sub_version"), laughAnim.subVersion)
        assertEquals(laughExp.getInt("base_priority"), laughAnim.basePriority)
        assertEquals(laughExp.getDouble("duration").toFloat(), laughAnim.duration, 1e-4f)
        assertEquals(laughExp.getString("emote_name"), laughAnim.emoteName)
        assertEquals(laughExp.getBoolean("loop"), laughAnim.loop)
        assertEquals(laughExp.getDouble("ease_in_time").toFloat(), laughAnim.easeInTime, 1e-4f)
        assertEquals(laughExp.getDouble("ease_out_time").toFloat(), laughAnim.easeOutTime, 1e-4f)
        assertEquals(laughExp.getInt("hand_pose"), laughAnim.handPose)
        assertEquals(laughExp.getInt("joint_count"), laughAnim.joints.size)
        assertEquals(laughExp.getInt("mTorso_rot_keys"), laughAnim.joints["mTorso"]?.rotKeyframes?.size)
        assertEquals(laughExp.getInt("constraint_count"), laughAnim.constraints.size)
        assertEquals(laughExp.getInt("constraint0_chain_length"), laughAnim.constraints[0].chainLength)
        assertEquals(laughExp.getString("constraint0_source_volume"), laughAnim.constraints[0].sourceVolume)
        assertEquals(laughExp.getString("constraint0_target_volume"), laughAnim.constraints[0].targetVolume)

        // Round-trip encode -> decode preserves joints, keyframes, and 86-byte IK constraints
        val reDecodedLaugh = LindenAnimBinaryDecoder.decode(LindenAnimBinaryDecoder.encode(laughAnim))
        assertNotNull(reDecodedLaugh)
        assertEquals(laughAnim.emoteName, reDecodedLaugh!!.emoteName)
        assertEquals(laughAnim.joints.size, reDecodedLaugh.joints.size)
        assertEquals(laughAnim.constraints.size, reDecodedLaugh.constraints.size)
        assertEquals("R_HAND", reDecodedLaugh.constraints[0].sourceVolume)
        assertEquals("HEAD", reDecodedLaugh.constraints[0].targetVolume)

        // Second real SL .anim vector
        val secondCase = animVectors.getJSONObject(1)
        val secondAnim = LindenAnimBinaryDecoder.decode(Base64.getMimeDecoder().decode(secondCase.getString("base64_bytes")))
        assertNotNull(secondAnim)
        assertEquals(0.9333f, secondAnim!!.duration, 1e-3f)

        // 2. Exact u16 midpoint zero-snap (32767 & 32768 -> 0f, matching LLQuantize.h)
        assertEquals(0f, LindenAnimBinaryDecoder.u16ToFloat(32767, -1f, 1f), 0f)
        assertEquals(0f, LindenAnimBinaryDecoder.u16ToFloat(32768, -5f, 5f), 0f)
        assertEquals(-1f, LindenAnimBinaryDecoder.u16ToFloat(0, -1f, 1f), 1e-5f)
        assertEquals(1f, LindenAnimBinaryDecoder.u16ToFloat(65535, -1f, 1f), 1e-5f)

        // 3. Priority-weighted multi-animation blending + AvatarSkeletonHierarchy joint driving
        val lowPrioId = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val highPrioId = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val rotZ90 = Quat.fromAxisAngle(Vec3(0f, 0f, 1f), (Math.PI / 2.0).toFloat())
        val rotX90 = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), (Math.PI / 2.0).toFloat())

        val lowAnim = LindenAnimData(
            basePriority = 2,
            duration = 2.0f,
            emoteName = "",
            loopInPoint = 0f,
            loopOutPoint = 2.0f,
            loop = true,
            easeInTime = 0f,
            easeOutTime = 0f,
            handPose = 0,
            joints = mapOf(
                "mPelvis" to AnimJointMotion(
                    jointName = "mPelvis",
                    priority = 2,
                    rotKeyframes = emptyList(),
                    posKeyframes = listOf(AnimPosKeyframe(0f, Vec3(0f, 0f, -0.25f)), AnimPosKeyframe(2f, Vec3(0f, 0f, -0.25f)))
                ),
                "mShoulderRight" to AnimJointMotion(
                    jointName = "mShoulderRight",
                    priority = 2,
                    rotKeyframes = listOf(AnimRotKeyframe(0f, rotX90), AnimRotKeyframe(2f, rotX90)),
                    posKeyframes = emptyList()
                ),
                "mHead" to AnimJointMotion(
                    jointName = "mHead",
                    priority = 2,
                    rotKeyframes = listOf(AnimRotKeyframe(0f, rotZ90), AnimRotKeyframe(2f, rotZ90)),
                    posKeyframes = emptyList()
                )
            )
        )
        val highAnim = LindenAnimData(
            basePriority = 4,
            duration = 2.0f,
            emoteName = "",
            loopInPoint = 0f,
            loopOutPoint = 2.0f,
            loop = true,
            easeInTime = 0f,
            easeOutTime = 0f,
            handPose = 0,
            joints = mapOf(
                "mShoulderRight" to AnimJointMotion(
                    jointName = "mShoulderRight",
                    priority = 4,
                    rotKeyframes = listOf(AnimRotKeyframe(0f, rotZ90), AnimRotKeyframe(2f, rotZ90)),
                    posKeyframes = emptyList()
                )
            )
        )

        val blended = LindenAnimBinaryDecoder.blendAnimations(
            playing = listOf(
                PlayingAvatarAnimation(lowPrioId, sequenceId = 1, startedAtSec = 0f, skipEaseIn = true),
                PlayingAvatarAnimation(highPrioId, sequenceId = 2, startedAtSec = 0.1f, skipEaseIn = true)
            ),
            assets = mapOf(lowPrioId to lowAnim, highPrioId to highAnim),
            nowSec = 1.0f
        )
        // High-priority animation (4) overrides mShoulderRight, while low-priority animation (2) still drives mHead & mPelvis!
        assertEquals(rotZ90.z, blended["mShoulderRight"]!!.rotation.z, 1e-3f)
        assertEquals(rotZ90.z, blended["mHead"]!!.rotation.z, 1e-3f)
        assertEquals(-0.25f, blended["mPelvis"]!!.positionOffset!!.z, 1e-3f)

        // Verify AvatarSkeletonHierarchy propagates jointPoses down the bone chain (mShoulderRight -> mElbowRight -> mWristRight)
        val restAvatar = AvatarEntity(
            agentId = UUID.randomUUID(),
            localId = 77,
            displayName = "Anim Tester",
            position = Vec3(128f, 128f, 23f)
        )
        val posedAvatar = restAvatar.copy(jointPoses = blended)
        val restHand = AvatarSkeletonHierarchy.resolveAttachmentPointPelvis(6, restAvatar)
        val posedHand = AvatarSkeletonHierarchy.resolveAttachmentPointPelvis(6, posedAvatar)
        assertTrue(
            "Expected mShoulderRight + mPelvis .anim keyframes to move Right Hand (point 6) in pelvis space",
            restHand.position.distanceTo(posedHand.position) > 0.20f
        )

        // 4. Verify AvatarAnimation (High 20) LLUDP packet decoder
        fun putSlUuid(buf: ByteBuffer, id: UUID) {
            val prev = buf.order()
            buf.order(ByteOrder.BIG_ENDIAN)
            buf.putLong(id.mostSignificantBits)
            buf.putLong(id.leastSignificantBits)
            buf.order(prev)
        }
        val senderUuid = restAvatar.agentId
        val sourceObjUuid = UUID.randomUUID()
        val pktBuf = ByteBuffer.allocate(16 + 1 + 40 + 1 + 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putSlUuid(this, senderUuid)
            put(2.toByte())
            putSlUuid(this, lowPrioId)
            putInt(11)
            putSlUuid(this, highPrioId)
            putInt(12)
            put(1.toByte())
            putSlUuid(this, sourceObjUuid)
            position(0)
        }
        val decodedPkt = SceneObjectDecoder.decodeAvatarAnimationPacket(pktBuf)
        assertNotNull(decodedPkt)
        assertEquals(senderUuid, decodedPkt!!.senderId)
        assertEquals(2, decodedPkt.animations.size)
        assertEquals(lowPrioId, decodedPkt.animations[0].animId)
        assertEquals(11, decodedPkt.animations[0].sequenceId)
        assertEquals(highPrioId, decodedPkt.animations[1].animId)
        assertEquals(12, decodedPkt.animations[1].sequenceId)
        assertEquals(listOf(sourceObjUuid), decodedPkt.objectSourceIds)
    }

    @Test
    fun `verifies ObjectUpdateCached High 14 unencoded decoding and RequestMultipleObjects Medium 3 zerocoded cache-miss fallback`() {
        val phase9Json = loadResourceJson("test-vectors/phase9/phase9_sl_binary_vectors.json")
        val vec = phase9Json.getJSONArray("object_update_cached_vectors").getJSONObject(0)
        val exp = vec.getJSONObject("expected")

        // Canonical message IDs per secondlife_protocol (message_template.msg)
        assertEquals(14, LludpPackets.MSG_OBJECT_UPDATE_CACHED)
        assertEquals(0xFF03, LludpPackets.MSG_REQUEST_MULTIPLE_OBJECTS)
        assertEquals(30, LludpPackets.MSG_OBJECT_ANIMATION)

        // 1. Decode golden Unencoded ObjectUpdateCached (0x0E) datagram containing consecutive zero runs
        val rawCachedPkt = hexToBytes(vec.getString("framed_packet_hex"))
        val decodedHeader = LludpPackets.decodePacket(rawCachedPkt, rawCachedPkt.size)
        assertNotNull(decodedHeader)
        assertEquals("ObjectUpdateCached must be Unencoded (FLAG_ZEROCODED unset)", exp.getInt("flags"), decodedHeader!!.flags)
        assertEquals(0, decodedHeader.flags and LludpPackets.FLAG_ZEROCODED)
        assertEquals(exp.getInt("sequence_number"), decodedHeader.sequenceNumber)
        assertEquals(exp.getInt("message_id"), decodedHeader.messageId)

        val decodedCached = LludpPackets.parseObjectUpdateCached(decodedHeader.payload)
        assertNotNull(decodedCached)
        assertEquals(exp.getLong("region_handle"), decodedCached!!.regionHandle)
        assertEquals(exp.getInt("time_dilation"), decodedCached.timeDilation)

        val expEntries = exp.getJSONArray("entries")
        assertEquals(expEntries.length(), decodedCached.objects.size)
        for (i in 0 until expEntries.length()) {
            val ee = expEntries.getJSONObject(i)
            assertEquals(ee.getInt("local_id"), decodedCached.objects[i].localId)
            assertEquals(ee.getInt("crc"), decodedCached.objects[i].crc)
            assertEquals(ee.getInt("update_flags"), decodedCached.objects[i].updateFlags)
        }

        // Verify frameObjectUpdateCachedPacket reproduces exact Unencoded wire bytes byte-for-byte
        val reFramedCached = LludpPackets.frameObjectUpdateCachedPacket(
            sequenceNumber = decodedHeader.sequenceNumber,
            regionHandle = decodedCached.regionHandle,
            timeDilation = decodedCached.timeDilation,
            objects = decodedCached.objects
        )
        assertArrayEquals(rawCachedPkt, reFramedCached)

        // 2. End-to-end LludpCircuitEngine RegionObjectCache hit + RequestMultipleObjects (0xFF03 Zerocoded) miss dispatch
        val agentId = UUID.fromString(vec.getString("agent_id"))
        val sessionId = UUID.fromString(vec.getString("session_id"))
        val regionHandle = decodedCached.regionHandle

        val circuit = LludpCircuitEngine()
        circuit.configureIdentityForTest(agentId, sessionId)
        val prepop = vec.getJSONArray("prepopulated_cache")
        for (i in 0 until prepop.length()) {
            val item = prepop.getJSONObject(i)
            val lid = item.getInt("local_id")
            circuit.objectCache.put(
                regionHandle,
                SceneObject(
                    localId = lid,
                    uuid = UUID.fromString(item.getString("uuid")),
                    name = "Cached Prim #$lid",
                    shape = PrimShape.BOX,
                    position = Vec3(128f, 128f, 25f),
                    crc = item.getInt("crc"),
                    updateFlags = item.getInt("update_flags")
                )
            )
        }

        val collectedEvents = mutableListOf<SimEvent>()
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
            circuit.events.collect { collectedEvents.add(it) }
        }
        try {
            circuit.processRawDatagramForTest(rawCachedPkt)
        } finally {
            collectJob.cancel()
        }

        val updatedEvents = collectedEvents.filterIsInstance<SimEvent.ObjectUpdated>()
        assertEquals(1, updatedEvents.size)
        val hitObj = updatedEvents.single().sceneObject
        assertEquals(exp.getJSONArray("hit_local_ids").getInt(0), hitObj.localId)
        assertEquals(exp.getBoolean("refreshed_hit_is_physical"), hitObj.isPhysical)
        assertEquals(exp.getBoolean("refreshed_hit_is_scripted"), hitObj.isScripted)

        val missEvents = collectedEvents.filterIsInstance<SimEvent.ObjectCacheMissRequested>()
        assertEquals(1, missEvents.size)
        val missEvt = missEvents.single()
        assertEquals(regionHandle, missEvt.regionHandle)

        val expMisses = exp.getJSONArray("misses")
        assertEquals(expMisses.length(), missEvt.misses.size)
        for (i in 0 until expMisses.length()) {
            val em = expMisses.getJSONObject(i)
            assertEquals(em.getInt("local_id"), missEvt.misses[i].localId)
            assertEquals(em.getInt("cache_miss_type"), missEvt.misses[i].cacheMissType)
        }

        // Inspect framed RequestMultipleObjects (Medium 3 = 0xFF03) packet: MUST be Reliable + Zerocoded (0xC0) with 2-byte Medium ID (0xFF, 0x03)
        assertEquals(1, missEvt.framedPackets.size)
        val rmoWire = missEvt.framedPackets.single()
        val rmoFlags = rmoWire[0].toInt() and 0xFF
        assertEquals(exp.getInt("request_multiple_objects_flags"), rmoFlags)
        assertTrue("RequestMultipleObjects must set FLAG_ZEROCODED (0x80)", (rmoFlags and LludpPackets.FLAG_ZEROCODED) != 0)
        assertTrue("RequestMultipleObjects must set FLAG_RELIABLE (0x40)", (rmoFlags and LludpPackets.FLAG_RELIABLE) != 0)
        assertEquals("Medium frequency prefix byte must be 0xFF", 0xFF, rmoWire[6].toInt() and 0xFF)
        assertEquals("Medium frequency message number must be 0x03", 0x03, rmoWire[7].toInt() and 0xFF)
        // Unencoded size would be 6B header + 2B Medium ID + 33B Agent/Count + 10B ObjectData = 51 bytes; zero-coding shrinks zero runs
        assertTrue("Zerocoded RequestMultipleObjects wire packet (${rmoWire.size}B) must compress zero runs (< 51B)", rmoWire.size < 51)

        val decodedRmoPkt = LludpPackets.decodePacket(rmoWire, rmoWire.size)
        assertNotNull(decodedRmoPkt)
        assertEquals(exp.getInt("request_multiple_objects_message_id"), decodedRmoPkt!!.messageId)
        val parsedRmo = LludpPackets.parseRequestMultipleObjects(decodedRmoPkt.payload)
        assertNotNull(parsedRmo)
        assertEquals(agentId, parsedRmo!!.agentId)
        assertEquals(sessionId, parsedRmo.sessionId)
        assertEquals(
            listOf(
                LludpPackets.ObjectCacheMissRequest(localId = 202, cacheMissType = LludpPackets.CACHE_MISS_TYPE_FULL),
                LludpPackets.ObjectCacheMissRequest(localId = 1002, cacheMissType = LludpPackets.CACHE_MISS_TYPE_CRC)
            ),
            parsedRmo.requests
        )

        // 3. KillObject (High 16) evicts cached object so stale entries never linger
        val killBody = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1.toByte())
            putInt(1001)
        }.array()
        val killPkt = LludpPackets.framePacket(
            sequenceNumber = 43,
            reliable = false,
            messageId = LludpPackets.MSG_KILL_OBJECT,
            body = killBody,
            useZeroCoding = false
        )
        circuit.processRawDatagramForTest(killPkt)
        assertNull(circuit.objectCache.get(regionHandle, 1001))
    }

    @Test
    fun `verifies SL glTF 2_0 PBR ORM channel mapping and strict asset_id vs item_id separation across sculpt, particle, llmesh, and anim fetches`() {
        val phase9Json = loadResourceJson("test-vectors/phase9/phase9_sl_binary_vectors.json")
        val vec = phase9Json.getJSONArray("gltf_orm_and_asset_identity_vectors").getJSONObject(0)
        val expOrm = vec.getJSONObject("expected_orm")

        // 1. SL glTF 2.0 PBR ORM convention: R = Occlusion, G = Roughness, B = Metalness
        val mat = PbrMaterialChannelTransforms.fromGltfJson(vec.getString("gltf_json"))
        assertEquals(expOrm.getString("alpha_mode"), mat.alphaMode)
        assertEquals(expOrm.getDouble("alpha_cutoff").toFloat(), mat.alphaCutoff, 1e-4f)
        assertEquals(expOrm.getBoolean("double_sided"), mat.doubleSided)
        assertEquals(0.8f, mat.metallicFactor, 1e-4f)
        assertEquals(0.75f, mat.roughnessFactor, 1e-4f)
        assertEquals(0.6f, mat.occlusionStrength, 1e-4f)

        // Verify independent KHR_texture_transform on baseColor, metallicRoughness, and occlusion
        val (occU, occV) = mat.occlusion.apply(0.4f, 0.2f)
        assertEquals(0.4f * 1.25f + 0.05f, occU, 1e-4f)
        assertEquals(0.2f * 1.25f - 0.05f, occV, 1e-4f)
        val (mrU, mrV) = mat.metallicRoughness.apply(0.4f, 0.2f)
        assertFalse("occlusion and metallicRoughness UV transforms must remain independent", kotlin.math.abs(occU - mrU) < 1e-3f)

        val ormPixel = java.lang.Long.parseLong(vec.getString("orm_pixel_argb_hex"), 16).toInt()
        val unpackedOrm = mat.unpackOrmArgb(ormPixel)
        assertEquals("SL ORM R channel must unpack as ambient occlusion", expOrm.getDouble("occlusion").toFloat(), unpackedOrm.occlusion, 1e-4f)
        assertEquals("SL ORM G channel must unpack as perceptual roughness", expOrm.getDouble("roughness").toFloat(), unpackedOrm.roughness, 1e-4f)
        assertEquals("SL ORM B channel must unpack as metalness", expOrm.getDouble("metalness").toFloat(), unpackedOrm.metalness, 1e-4f)

        // Verify LinkpointGlRenderer.PRIM_FS shader binds R -> occlusion, G -> roughness, B -> metallic
        assertTrue(LinkpointGlRenderer.PRIM_FS.contains("occlusion = mix(1.0, clamp(mrSample.r, 0.0, 1.0), uOrmFactors.x)"))
        assertTrue(LinkpointGlRenderer.PRIM_FS.contains("roughness = clamp(mrSample.g * uOrmFactors.y, 0.04, 1.0)"))
        assertTrue(LinkpointGlRenderer.PRIM_FS.contains("metallic = clamp(mrSample.b * uOrmFactors.z, 0.0, 1.0)"))

        // Verify ExtraParams 0x0070 (RENDER_MATERIAL) & 0x0080 (EXTENDED_MESH) per-face glTF material asset IDs
        val ids = vec.getJSONObject("inventory_and_caps_assets")
        val face0MatAssetId = UUID.fromString(ids.getString("material_asset_id"))
        val extraMatBuf = ByteBuffer.allocate(1 + 6 + 1 + 17).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1.toByte())
            putShort(ExtraParamTypes.RENDER_MATERIAL.toShort())
            putInt(18)
            put(1.toByte()) // 1 face entry
            put(0.toByte()) // face index 0
            LludpPackets.putUuid(this, face0MatAssetId)
        }.array()
        val parsedMatExtra = ExtraParamsAndSculptGenerator.parseExtraParams(extraMatBuf)
        assertEquals(face0MatAssetId, parsedMatExtra.renderMaterialIds[0])

        // 2. SlAssetType vs SlInventoryType integer enum codes are distinct (secondlife_assets_mesh_texture)
        assertNotEquals(SlAssetType.MESH.code, SlInventoryType.MESH.code)
        assertEquals(49, SlAssetType.MESH.code)
        assertEquals(22, SlInventoryType.MESH.code)
        assertNotEquals(SlAssetType.ANIMATION.code, SlInventoryType.ANIMATION.code)
        assertEquals(20, SlAssetType.ANIMATION.code)
        assertEquals(19, SlInventoryType.ANIMATION.code)
        assertNotEquals(SlAssetType.MATERIAL.code, SlInventoryType.MATERIAL.code)
        assertEquals(57, SlAssetType.MATERIAL.code)
        assertEquals(26, SlInventoryType.MATERIAL.code)

        // 3. Strict asset_id vs item_id separation across Inventory CAPS, Attachment CAPS, and GridAssetManager URLs
        val folderId = UUID.fromString(ids.getString("folder_id"))
        val objectId = UUID.fromString(ids.getString("object_id"))
        val meshItemId = UUID.fromString(ids.getString("mesh_item_id"))
        val meshAssetId = UUID.fromString(ids.getString("mesh_asset_id"))
        val sculptItemId = UUID.fromString(ids.getString("sculpt_item_id"))
        val sculptAssetId = UUID.fromString(ids.getString("sculpt_asset_id"))
        val particleItemId = UUID.fromString(ids.getString("particle_item_id"))
        val particleAssetId = UUID.fromString(ids.getString("particle_asset_id"))
        val animItemId = UUID.fromString(ids.getString("anim_item_id"))
        val animAssetId = UUID.fromString(ids.getString("anim_asset_id"))
        val materialItemId = UUID.fromString(ids.getString("material_item_id"))

        val invXml = """
            <llsd><map>
              <key>folders</key><array>
                <map>
                  <key>folder_id</key><uuid>$folderId</uuid>
                  <key>items</key><array>
                    <map>
                      <key>item_id</key><uuid>$meshItemId</uuid>
                      <key>parent_id</key><uuid>$folderId</uuid>
                      <key>asset_id</key><uuid>$meshAssetId</uuid>
                      <key>type</key><integer>49</integer>
                      <key>inv_type</key><integer>22</integer>
                      <key>name</key><string>Cybernetic Visor Mesh</string>
                      <key>desc</key><string>Rigged Mesh</string>
                    </map>
                    <map>
                      <key>item_id</key><uuid>$animItemId</uuid>
                      <key>parent_id</key><uuid>$folderId</uuid>
                      <key>asset_id</key><uuid>$animItemId</uuid>
                      <key>type</key><integer>20</integer>
                      <key>inv_type</key><integer>19</integer>
                      <key>name</key><string>Conflated No-Mod Anim</string>
                      <key>desc</key><string>Bogus asset_id == item_id</string>
                    </map>
                  </array>
                </map>
              </array>
            </map></llsd>
        """.trimIndent()
        val parsedInv = CapabilityManager.parseFetchInventoryDescendentsResponse(invXml, folderId)
        assertEquals(2, parsedInv.size)
        assertEquals(meshItemId, parsedInv[0].itemId)
        assertEquals(folderId, parsedInv[0].parentId)
        assertEquals(meshAssetId, parsedInv[0].assetId)
        assertEquals(49, parsedInv[0].assetType)
        assertEquals(22, parsedInv[0].inventoryType)
        // Conflated asset_id == item_id is stripped to null rather than poisoning asset fetches!
        assertEquals(animItemId, parsedInv[1].itemId)
        assertNull("Inventory parser must strip conflated asset_id == item_id", parsedInv[1].assetId)

        val attachCapsXml = """
            <llsd><map>
              <key>attachments</key><array>
                <map>
                  <key>object_id</key><uuid>$objectId</uuid>
                  <key>item_id</key><uuid>$meshItemId</uuid>
                  <key>attachment_point</key><integer>6</integer>
                  <key>mesh_id</key><uuid>$meshAssetId</uuid>
                  <key>sculpt_texture_id</key><uuid>$sculptAssetId</uuid>
                  <key>particle_texture_id</key><uuid>$particleAssetId</uuid>
                  <key>anim_id</key><uuid>$animAssetId</uuid>
                </map>
              </array>
            </map></llsd>
        """.trimIndent()
        val parsedAttach = CapabilityManager.parseCapsAttachmentUpdates(LlsdCodec.parseXml(attachCapsXml))
        assertEquals(1, parsedAttach.size)
        val att = parsedAttach.single()
        assertEquals(objectId, att.objectId)
        assertEquals(meshItemId, att.itemId)
        assertEquals(meshAssetId, att.meshAssetId)
        assertEquals(sculptAssetId, att.sculptTextureId)
        assertEquals(particleAssetId, att.particleTextureId)
        assertEquals(animAssetId, att.animAssetId)

        // Verify GridAssetManager capability priority order & asset_id vs item_id guard across all 5 asset kinds
        val assetMgr = GridAssetManager()
        assetMgr.configureCapabilities(
            mapOf(
                "GetMesh2" to "https://sim.agni.lindenlab.com:12043/cap/get_mesh2",
                "GetMesh" to "https://sim.agni.lindenlab.com:12043/cap/get_mesh",
                "GetTexture" to "https://sim.agni.lindenlab.com:12043/cap/get_texture",
                "ViewerAsset" to "https://asset-cdn.glb.agni.lindenlab.com/cap/viewer_asset",
                "RenderMaterials" to "https://sim.agni.lindenlab.com:12043/cap/render_materials"
            )
        )

        val meshUrls = assetMgr.resolveMeshUrls(meshAssetId, itemId = meshItemId)
        assertEquals(
            listOf(
                "https://sim.agni.lindenlab.com:12043/cap/get_mesh2?mesh_id=$meshAssetId",
                "https://sim.agni.lindenlab.com:12043/cap/get_mesh?mesh_id=$meshAssetId",
                "https://asset-cdn.glb.agni.lindenlab.com/cap/viewer_asset?mesh_id=$meshAssetId"
            ),
            meshUrls
        )
        assertTrue(meshUrls.none { it.contains(meshItemId.toString()) })

        val sculptUrls = assetMgr.resolveSculptMapUrls(sculptAssetId, itemId = sculptItemId)
        assertEquals(
            listOf(
                "https://sim.agni.lindenlab.com:12043/cap/get_texture?texture_id=$sculptAssetId",
                "https://asset-cdn.glb.agni.lindenlab.com/cap/viewer_asset?texture_id=$sculptAssetId"
            ),
            sculptUrls
        )
        assertTrue(sculptUrls.none { it.contains(sculptItemId.toString()) })

        val particleUrls = assetMgr.resolveParticleTextureUrls(particleAssetId, itemId = particleItemId)
        assertEquals(
            listOf(
                "https://sim.agni.lindenlab.com:12043/cap/get_texture?texture_id=$particleAssetId",
                "https://asset-cdn.glb.agni.lindenlab.com/cap/viewer_asset?texture_id=$particleAssetId"
            ),
            particleUrls
        )
        assertTrue(particleUrls.none { it.contains(particleItemId.toString()) })

        val animUrls = assetMgr.resolveAnimationUrls(animAssetId, itemId = animItemId)
        assertEquals(
            listOf("https://asset-cdn.glb.agni.lindenlab.com/cap/viewer_asset?animatn_id=$animAssetId"),
            animUrls
        )
        assertTrue(animUrls.none { it.contains(animItemId.toString()) })

        val matUrls = assetMgr.resolveGltfMaterialUrls(face0MatAssetId, itemId = materialItemId)
        assertEquals(
            listOf("https://asset-cdn.glb.agni.lindenlab.com/cap/viewer_asset?material_id=$face0MatAssetId"),
            matUrls
        )
        assertTrue(matUrls.none { it.contains(materialItemId.toString()) })

        // Reject any attempt to fetch using item_id in place of asset_id across all asset types
        assertTrue(assetMgr.resolveMeshUrls(meshItemId, itemId = meshItemId).isEmpty())
        assertTrue(assetMgr.resolveSculptMapUrls(sculptItemId, itemId = sculptItemId).isEmpty())
        assertTrue(assetMgr.resolveParticleTextureUrls(particleItemId, itemId = particleItemId).isEmpty())
        assertTrue(assetMgr.resolveAnimationUrls(animItemId, itemId = animItemId).isEmpty())
        assertTrue(assetMgr.resolveGltfMaterialUrls(materialItemId, itemId = materialItemId).isEmpty())
        assertFalse(assetMgr.requestInventoryItemAsset(itemId = animItemId, assetId = animItemId, assetTypeCode = SlAssetType.ANIMATION.code))
        assertFalse(assetMgr.requestInventoryItemAsset(itemId = animItemId, assetId = null, assetTypeCode = SlAssetType.ANIMATION.code))
    }
}
