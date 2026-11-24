package com.example.linkpoint.render

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.example.linkpoint.protocol.DecodedMeshAsset
import com.example.linkpoint.protocol.DecodedSubmesh
import com.example.linkpoint.protocol.SlBinaryParsers
import com.example.linkpoint.scene.AvatarEntity
import com.example.linkpoint.scene.AvatarHudSlotState
import com.example.linkpoint.scene.AvatarSkeletonHierarchy
import com.example.linkpoint.scene.CameraMode
import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.EnvironmentPreset
import com.example.linkpoint.scene.HudAttachmentPoint
import com.example.linkpoint.scene.HudHitResult
import com.example.linkpoint.scene.HudSafeRect
import com.example.linkpoint.scene.PrimShape
import com.example.linkpoint.scene.ProjectedNametag
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.TerrainGrid
import com.example.linkpoint.scene.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thread-safe snapshot consumed by the OpenGL ES 3.0 render thread each frame.
 */
data class RenderSceneSnapshot(
    val terrain: TerrainGrid = TerrainGrid(),
    val worldObjects: List<SceneObject> = emptyList(),
    val hudAttachments: List<SceneObject> = emptyList(),
    val avatars: List<AvatarEntity> = emptyList(),
    val selectedLocalId: Int? = null,
    val selectedAvatarId: UUID? = null,
    val environment: EnvironmentPreset = EnvironmentPreset.TWILIGHT,
    val cameraMode: CameraMode = CameraMode.FOLLOW,
    val drawDistance: Float = 128f,
    val hudSafeRect: HudSafeRect = HudSafeRect(),
    val hudSlotStates: Map<Int, AvatarHudSlotState> = emptyMap(),
    val hudGlobalScale: Float = 1.0f,
    val hudTouchFlashLocalId: Int? = null,
    val hudTouchFlashUntilMs: Long = 0L
)

/**
 * High-Performance Native OpenGL ES 3.0 Dual-Pass Renderer for Linkpoint:
 * - GPU-resident VBOs & Indexed IBOs (`GL_ARRAY_BUFFER` / `GL_ELEMENT_ARRAY_BUFFER`) for all
 *   procedural primitives, terrain, avatars, and streamed `.llmesh` submeshes.
 * - Cached shader uniform/attribute locations (zero `glGetUniformLocation` per frame),
 *   CPU-computed `mat3 uNormalMat` (no per-vertex `inverse()`), redundant GL state filtering,
 *   and 6-plane frustum + draw-distance sphere culling.
 * - Two-sided hemisphere-lit shading for crisp mesh hair, clothing, and architecture.
 * - Frame-rate-independent damped orbit/pan/zoom/inertia camera via [OrbitCameraRig].
 */
class LinkpointGlRenderer(
    private val onFpsMeasured: (Int) -> Unit = {},
    private val onNametagsProjected: (List<ProjectedNametag>) -> Unit = {}
) : GLSurfaceView.Renderer {

    @Volatile var sceneSnapshot: RenderSceneSnapshot = RenderSceneSnapshot()
    @Volatile var focusOverride: Vec3? = null

    val orbitCamera = OrbitCameraRig()

    var cameraYaw: Float
        get() = orbitCamera.yaw
        set(value) { orbitCamera.yaw = value }

    var cameraPitch: Float
        get() = orbitCamera.pitch
        set(value) { orbitCamera.pitch = value }

    var cameraDistance: Float
        get() = orbitCamera.distance
        set(value) { orbitCamera.distance = value }

    fun applyOrbitDrag(deltaYawRad: Float, deltaPitchRad: Float, dtSec: Float) {
        orbitCamera.applyOrbitDrag(deltaYawRad, deltaPitchRad, dtSec)
    }

    fun releaseOrbitDrag() {
        orbitCamera.releaseOrbitDrag()
    }

    fun applyZoomScale(scaleFactor: Float) {
        orbitCamera.applyZoomScale(scaleFactor)
    }

    fun applyTwoFingerPan(dxNorm: Float, dyNorm: Float) {
        orbitCamera.applyTwoFingerPan(dxNorm, dyNorm)
    }

    fun resetOrbitView(alignYaw: Float? = null) {
        focusOverride = null
        val targetYaw = alignYaw ?: sceneSnapshot.avatars.firstOrNull { it.isSelf }?.yaw
        orbitCamera.resetOrbit(alignYaw = targetYaw)
    }

    fun nudgeFollowCamera(deltaAvatarYaw: Float, avatarYaw: Float, movingForward: Boolean, dtSec: Float) {
        orbitCamera.nudgeFollowCamera(deltaAvatarYaw, avatarYaw, movingForward, dtSec)
    }

    private var viewportWidth = 1
    private var viewportHeight = 1

    private var skyProgram = 0
    private var terrainProgram = 0
    private var primProgram = 0
    private var waterProgram = 0
    private var hudProgram = 0

    // Cached GL uniform & attribute locations (resolved once in onSurfaceCreated)
    private data class SkyLocs(
        val aPos: Int,
        val uZenith: Int,
        val uHorizon: Int,
        val uSunColor: Int,
        val uTime: Int,
        val uPitch: Int
    )

    private data class TerrainLocs(
        val aPos: Int,
        val aNormal: Int,
        val aUv: Int,
        val uMvp: Int,
        val uModel: Int,
        val uNormalMat: Int,
        val uSunDir: Int,
        val uSunColor: Int,
        val uFogColor: Int,
        val uEyePos: Int,
        val uAmbient: Int
    )

    private data class PrimLocs(
        val aPos: Int,
        val aNormal: Int,
        val aUv: Int,
        val uMvp: Int,
        val uModel: Int,
        val uNormalMat: Int,
        val uSunDir: Int,
        val uSunColor: Int,
        val uFogColor: Int,
        val uEyePos: Int,
        val uAmbient: Int,
        val uColor: Int,
        val uFullbright: Int,
        val uGlow: Int,
        val uSelectedPulse: Int,
        val uTex: Int,
        val uUseTex: Int
    )

    private data class WaterLocs(
        val aPos: Int,
        val aUv: Int,
        val uMvp: Int,
        val uTime: Int,
        val uWaterTint: Int,
        val uHorizon: Int,
        val uEyePos: Int
    )

    private data class HudLocs(
        val aPos: Int,
        val aUv: Int,
        val uRect: Int,
        val uColor: Int,
        val uBorderColor: Int,
        val uFlash: Int,
        val uTex: Int,
        val uUseTex: Int
    )

    private lateinit var skyLocs: SkyLocs
    private lateinit var terrainLocs: TerrainLocs
    private lateinit var primLocs: PrimLocs
    private lateinit var waterLocs: WaterLocs
    private lateinit var hudLocs: HudLocs

    private data class GpuMeshBuffer(
        val vboId: Int,
        val iboId: Int,
        val vertexCount: Int,
        val indexCount: Int
    )

    private lateinit var boxMesh: GpuMeshBuffer
    private lateinit var cylinderMesh: GpuMeshBuffer
    private lateinit var sphereMesh: GpuMeshBuffer
    private lateinit var torusMesh: GpuMeshBuffer
    private lateinit var prismMesh: GpuMeshBuffer
    private lateinit var tubeMesh: GpuMeshBuffer
    private lateinit var ringMesh: GpuMeshBuffer
    private lateinit var avatarLowerMesh: GpuMeshBuffer
    private lateinit var avatarUpperMesh: GpuMeshBuffer
    private lateinit var avatarHeadMesh: GpuMeshBuffer
    private lateinit var avatarHairCapMesh: GpuMeshBuffer
    private lateinit var waterMesh: GpuMeshBuffer
    private lateinit var hudQuadMesh: GpuMeshBuffer
    private var skyQuadVbo = 0
    private var terrainMesh: GpuMeshBuffer? = null
    private var uploadedTerrainVersion = -1

    // Real streamed grid assets (thread-safe handoff -> GL thread GPU upload)
    private val retainedMeshes = ConcurrentHashMap<UUID, DecodedMeshAsset>()
    private val retainedTextures = ConcurrentHashMap<UUID, Bitmap>()
    private val pendingMeshUploads = ConcurrentLinkedQueue<Pair<UUID, DecodedMeshAsset>>()
    private val pendingTextureUploads = ConcurrentLinkedQueue<Pair<UUID, Bitmap>>()
    @Volatile private var clearGpuCachesRequested = false

    // GL-thread-only GPU resource maps (rebuilt safely on EGL context recreate)
    private data class GpuMeshAsset(
        val submeshes: List<Pair<Int, GpuMeshBuffer>>,
        val isRigged: Boolean,
        val coversBody: Boolean,
        val coversLower: Boolean,
        val coversHead: Boolean
    )
    private val gpuMeshCache = LinkedHashMap<UUID, GpuMeshAsset>(256, 0.75f, true)
    private val gpuTextureCache = LinkedHashMap<UUID, Int>(192, 0.75f, true)

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val invVpMatrix = FloatArray(16)
    private val invVpCopyLock = Any()
    private val invVpReadable = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val normalMatrix3 = FloatArray(9)
    private val rotMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    // 6 normalized frustum planes: [a, b, c, d] * 6
    private val frustumPlanes = FloatArray(24)

    // Per-frame GL state caches to avoid redundant binds
    private var activeBoundTextureId = -1
    private var activeBoundVboId = -1
    private var activeBoundIboId = -1

    @Volatile var lastCameraEye = Vec3(120f, 120f, 30f)
    @Volatile var lastCameraAt = Vec3(1f, 0f, 0f)

    private var startTimeNs = System.nanoTime()
    private var lastFrameNs = System.nanoTime()
    private var frameCount = 0
    private var fpsLastCheckMs = System.currentTimeMillis()
    private var lastNametagEmitMs = 0L
    private val clipTmp = FloatArray(4)
    private val worldTmp = FloatArray(4)
    private val glIdScratch = IntArray(2)

    private data class ComputedHudRect(
        val obj: SceneObject,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    fun putStreamedMesh(meshId: UUID, asset: DecodedMeshAsset) {
        if (retainedMeshes.size > MAX_GPU_MESHES) {
            retainedMeshes.keys.firstOrNull()?.let { retainedMeshes.remove(it) }
        }
        retainedMeshes[meshId] = asset
        pendingMeshUploads.add(meshId to asset)
    }

    fun putStreamedTexture(textureId: UUID, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        if (retainedTextures.size > MAX_GPU_TEXTURES) {
            retainedTextures.keys.firstOrNull()?.let { retainedTextures.remove(it) }
        }
        retainedTextures[textureId] = bitmap
        pendingTextureUploads.add(textureId to bitmap)
    }

    fun clearStreamedAssets() {
        retainedMeshes.clear()
        retainedTextures.clear()
        pendingMeshUploads.clear()
        pendingTextureUploads.clear()
        clearGpuCachesRequested = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glClearColor(0.04f, 0.06f, 0.10f, 1f)

        skyProgram = compileProgram(SKY_VS, SKY_FS)
        terrainProgram = compileProgram(WORLD_VS, TERRAIN_FS)
        primProgram = compileProgram(WORLD_VS, PRIM_FS)
        waterProgram = compileProgram(WATER_VS, WATER_FS)
        hudProgram = compileProgram(HUD_VS, HUD_FS)

        cacheShaderLocations()

        // Upload all procedural geometry once into GPU VRAM VBOs
        boxMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildUnitBox())
        cylinderMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildUnitCylinder())
        sphereMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildUnitSphere())
        torusMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildUnitTorus())
        prismMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildUnitPrism())
        tubeMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildUnitTube())
        ringMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildUnitRing())
        avatarLowerMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildAvatarLowerMesh())
        avatarUpperMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildAvatarUpperMesh())
        avatarHeadMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildAvatarHeadMesh())
        avatarHairCapMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildAvatarHairCapMesh())
        waterMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildWaterPlane())
        hudQuadMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildHudQuad())

        val skyVerts = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
        val skyBuf = ByteBuffer.allocateDirect(skyVerts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(skyVerts); position(0)
            }
        GLES30.glGenBuffers(1, glIdScratch, 0)
        skyQuadVbo = glIdScratch[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, skyQuadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, skyVerts.size * 4, skyBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        // EGL context was created/recreated: clear stale GL IDs and re-queue retained assets
        terrainMesh = null
        uploadedTerrainVersion = -1
        gpuMeshCache.clear()
        gpuTextureCache.clear()
        pendingMeshUploads.clear()
        pendingTextureUploads.clear()
        retainedMeshes.forEach { (id, asset) -> pendingMeshUploads.add(id to asset) }
        retainedTextures.forEach { (id, bmp) -> if (!bmp.isRecycled) pendingTextureUploads.add(id to bmp) }
        startTimeNs = System.nanoTime()
        lastFrameNs = startTimeNs
    }

    private fun cacheShaderLocations() {
        skyLocs = SkyLocs(
            aPos = GLES30.glGetAttribLocation(skyProgram, "aPos"),
            uZenith = GLES30.glGetUniformLocation(skyProgram, "uZenith"),
            uHorizon = GLES30.glGetUniformLocation(skyProgram, "uHorizon"),
            uSunColor = GLES30.glGetUniformLocation(skyProgram, "uSunColor"),
            uTime = GLES30.glGetUniformLocation(skyProgram, "uTime"),
            uPitch = GLES30.glGetUniformLocation(skyProgram, "uPitch")
        )
        terrainLocs = TerrainLocs(
            aPos = GLES30.glGetAttribLocation(terrainProgram, "aPos"),
            aNormal = GLES30.glGetAttribLocation(terrainProgram, "aNormal"),
            aUv = GLES30.glGetAttribLocation(terrainProgram, "aUv"),
            uMvp = GLES30.glGetUniformLocation(terrainProgram, "uMvp"),
            uModel = GLES30.glGetUniformLocation(terrainProgram, "uModel"),
            uNormalMat = GLES30.glGetUniformLocation(terrainProgram, "uNormalMat"),
            uSunDir = GLES30.glGetUniformLocation(terrainProgram, "uSunDir"),
            uSunColor = GLES30.glGetUniformLocation(terrainProgram, "uSunColor"),
            uFogColor = GLES30.glGetUniformLocation(terrainProgram, "uFogColor"),
            uEyePos = GLES30.glGetUniformLocation(terrainProgram, "uEyePos"),
            uAmbient = GLES30.glGetUniformLocation(terrainProgram, "uAmbient")
        )
        primLocs = PrimLocs(
            aPos = GLES30.glGetAttribLocation(primProgram, "aPos"),
            aNormal = GLES30.glGetAttribLocation(primProgram, "aNormal"),
            aUv = GLES30.glGetAttribLocation(primProgram, "aUv"),
            uMvp = GLES30.glGetUniformLocation(primProgram, "uMvp"),
            uModel = GLES30.glGetUniformLocation(primProgram, "uModel"),
            uNormalMat = GLES30.glGetUniformLocation(primProgram, "uNormalMat"),
            uSunDir = GLES30.glGetUniformLocation(primProgram, "uSunDir"),
            uSunColor = GLES30.glGetUniformLocation(primProgram, "uSunColor"),
            uFogColor = GLES30.glGetUniformLocation(primProgram, "uFogColor"),
            uEyePos = GLES30.glGetUniformLocation(primProgram, "uEyePos"),
            uAmbient = GLES30.glGetUniformLocation(primProgram, "uAmbient"),
            uColor = GLES30.glGetUniformLocation(primProgram, "uColor"),
            uFullbright = GLES30.glGetUniformLocation(primProgram, "uFullbright"),
            uGlow = GLES30.glGetUniformLocation(primProgram, "uGlow"),
            uSelectedPulse = GLES30.glGetUniformLocation(primProgram, "uSelectedPulse"),
            uTex = GLES30.glGetUniformLocation(primProgram, "uTex"),
            uUseTex = GLES30.glGetUniformLocation(primProgram, "uUseTex")
        )
        waterLocs = WaterLocs(
            aPos = GLES30.glGetAttribLocation(waterProgram, "aPos"),
            aUv = GLES30.glGetAttribLocation(waterProgram, "aUv"),
            uMvp = GLES30.glGetUniformLocation(waterProgram, "uMvp"),
            uTime = GLES30.glGetUniformLocation(waterProgram, "uTime"),
            uWaterTint = GLES30.glGetUniformLocation(waterProgram, "uWaterTint"),
            uHorizon = GLES30.glGetUniformLocation(waterProgram, "uHorizon"),
            uEyePos = GLES30.glGetUniformLocation(waterProgram, "uEyePos")
        )
        hudLocs = HudLocs(
            aPos = GLES30.glGetAttribLocation(hudProgram, "aPos"),
            aUv = GLES30.glGetAttribLocation(hudProgram, "aUv"),
            uRect = GLES30.glGetUniformLocation(hudProgram, "uRect"),
            uColor = GLES30.glGetUniformLocation(hudProgram, "uColor"),
            uBorderColor = GLES30.glGetUniformLocation(hudProgram, "uBorderColor"),
            uFlash = GLES30.glGetUniformLocation(hudProgram, "uFlash"),
            uTex = GLES30.glGetUniformLocation(hudProgram, "uTex"),
            uUseTex = GLES30.glGetUniformLocation(hudProgram, "uUseTex")
        )
    }

    private fun uploadMeshDataToGpu(mesh: MeshData): GpuMeshBuffer {
        GLES30.glGenBuffers(1, glIdScratch, 0)
        val vbo = glIdScratch[0]
        mesh.vertexBuffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            mesh.vertexBuffer.capacity() * 4,
            mesh.vertexBuffer,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return GpuMeshBuffer(vboId = vbo, iboId = 0, vertexCount = mesh.vertexCount, indexCount = 0)
    }

    private fun uploadSubmeshToGpu(sub: DecodedSubmesh): GpuMeshBuffer? {
        if (sub.vertexCount <= 0 || sub.interleavedVertices.isEmpty()) return null
        val vertBuf = ByteBuffer.allocateDirect(sub.interleavedVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(sub.interleavedVertices)
                position(0)
            }
        val indices = sub.indices
        val useIndexed = indices != null && sub.indexCount > 0

        GLES30.glGenBuffers(if (useIndexed) 2 else 1, glIdScratch, 0)
        val vbo = glIdScratch[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            sub.interleavedVertices.size * 4,
            vertBuf,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        var ibo = 0
        var idxCount = 0
        if (useIndexed && indices != null) {
            ibo = glIdScratch[1]
            idxCount = sub.indexCount
            val idxBuf = ByteBuffer.allocateDirect(idxCount * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .apply {
                    put(indices, 0, idxCount)
                    position(0)
                }
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES30.glBufferData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER,
                idxCount * 2,
                idxBuf,
                GLES30.GL_STATIC_DRAW
            )
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        return GpuMeshBuffer(vboId = vbo, iboId = ibo, vertexCount = sub.vertexCount, indexCount = idxCount)
    }

    private fun deleteGpuBuffer(buf: GpuMeshBuffer?) {
        if (buf == null) return
        var count = 0
        if (buf.vboId != 0) glIdScratch[count++] = buf.vboId
        if (buf.iboId != 0) glIdScratch[count++] = buf.iboId
        if (count > 0) GLES30.glDeleteBuffers(count, glIdScratch, 0)
    }

    private fun deleteGpuMeshAsset(asset: GpuMeshAsset) {
        for ((_, buf) in asset.submeshes) {
            deleteGpuBuffer(buf)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowNs = System.nanoTime()
        val dtSec = ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.08f)
        lastFrameNs = nowNs
        val elapsedSec = ((nowNs - startTimeNs) / 1_000_000_000.0).toFloat()

        val snapshot = sceneSnapshot
        updateFpsCounter()
        processPendingGpuAssets()

        if (terrainMesh == null || uploadedTerrainVersion != snapshot.terrain.version) {
            deleteGpuBuffer(terrainMesh)
            terrainMesh = uploadMeshDataToGpu(PrimMeshGenerator.buildTerrainMesh(snapshot.terrain))
            uploadedTerrainVersion = snapshot.terrain.version
        }

        // Advance silky damped orbit/pan/zoom/inertia camera
        orbitCamera.step(dtSec)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // Compute Camera Matrices
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val drawDist = snapshot.drawDistance.coerceAtLeast(64f)
        Matrix.perspectiveM(projMatrix, 0, 56f, aspect, 0.20f, drawDist * 2.5f)

        val selfAvatar = snapshot.avatars.firstOrNull { it.isSelf }
        // Avatar position is canonical SL pelvis center; focus camera at upper chest / shoulder line
        val baseTarget = focusOverride ?: (selfAvatar?.position?.plus(CAMERA_FOCUS_OFFSET) ?: DEFAULT_CAMERA_TARGET)

        val (eye, lookAtTarget) = orbitCamera.computeEyeAndLookAt(
            baseTarget = baseTarget,
            mode = snapshot.cameraMode
        ) { ex, ey ->
            val terrainZ = snapshot.terrain.sampleWorldHeight(ex, ey) + 0.38f
            val waterZ = snapshot.terrain.waterHeight + 0.45f
            maxOf(terrainZ, waterZ)
        }

        lastCameraEye = eye
        lastCameraAt = (lookAtTarget - eye).normalized()

        Matrix.setLookAtM(
            viewMatrix, 0,
            eye.x, eye.y, eye.z,
            lookAtTarget.x, lookAtTarget.y, lookAtTarget.z,
            0f, 0f, 1f
        )
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
        Matrix.invertM(invVpMatrix, 0, vpMatrix, 0)
        synchronized(invVpCopyLock) {
            System.arraycopy(invVpMatrix, 0, invVpReadable, 0, 16)
        }
        extractFrustumPlanes(vpMatrix, frustumPlanes)

        // PASS 1A: Sky Dome (depth write off)
        drawSkyDome(snapshot.environment, elapsedSec)

        // PASS 1B: 256m Terrain Heightfield
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        terrainMesh?.let { drawTerrain(it, snapshot.environment, eye) }

        // PASS 1C: Opaque World Meshes/Prims & Real Avatars + Worn Attachments
        drawWorldObjects(snapshot, eye, elapsedSec, drawDist, translucentPass = false)
        drawAvatars(snapshot, eye, elapsedSec, drawDist)

        // PASS 1D: Animated Translucent Water Plane
        drawWater(snapshot.terrain.waterHeight, snapshot.environment, eye, elapsedSec)

        // PASS 1E: Translucent World Meshes/Prims
        drawWorldObjects(snapshot, eye, elapsedSec, drawDist, translucentPass = true)

        // PASS 2: Orthographic Avatar Attachment HUDs (SL Slots 31..38) inside Adaptive HudSafeRect
        val nowMs = System.currentTimeMillis()
        drawAvatarHudAttachments(snapshot, nowMs)

        // PASS 3 (Projection): Emit 2D screen-space floating nametag anchors (~15 Hz)
        if (nowMs - lastNametagEmitMs >= 66L) {
            lastNametagEmitMs = nowMs
            emitProjectedNametags(snapshot, eye)
        }
    }

    private fun emitProjectedNametags(snapshot: RenderSceneSnapshot, eye: Vec3) {
        if (snapshot.avatars.isEmpty()) {
            onNametagsProjected(emptyList())
            return
        }
        val selfPos = snapshot.avatars.firstOrNull { it.isSelf }?.position ?: eye
        val maxDist = snapshot.drawDistance.coerceAtLeast(64f)
        val projected = ArrayList<ProjectedNametag>(snapshot.avatars.size)
        for (av in snapshot.avatars) {
            if (av.isSelf && snapshot.cameraMode == CameraMode.MOUSELOOK) continue
            val dist = av.position.distanceTo(selfPos)
            if (dist > maxDist) continue

            worldTmp[0] = av.position.x
            worldTmp[1] = av.position.y
            worldTmp[2] = av.position.z + av.heightMeters * 0.54f + 0.24f
            worldTmp[3] = 1f
            Matrix.multiplyMV(clipTmp, 0, vpMatrix, 0, worldTmp, 0)
            val w = clipTmp[3]
            if (w <= 0.15f) continue
            val ndcX = clipTmp[0] / w
            val ndcY = clipTmp[1] / w
            val ndcZ = clipTmp[2] / w
            if (ndcZ < -1f || ndcZ > 1f) continue
            val normX = (ndcX + 1f) * 0.5f
            val normY = (1f - ndcY) * 0.5f
            if (normX < -0.05f || normX > 1.05f || normY < 0.04f || normY > 0.94f) continue

            val hasPendingBaked =
                (av.bakedHeadTextureId != null && !gpuTextureCache.containsKey(av.bakedHeadTextureId)) ||
                    (av.bakedUpperTextureId != null && !gpuTextureCache.containsKey(av.bakedUpperTextureId)) ||
                    (av.bakedLowerTextureId != null && !gpuTextureCache.containsKey(av.bakedLowerTextureId))
            var hasPendingAttach = false
            for (att in av.attachments) {
                val mid = att.meshAssetId
                if (mid != null && !gpuMeshCache.containsKey(mid)) {
                    hasPendingAttach = true
                    break
                }
            }
            projected.add(
                ProjectedNametag(
                    agentId = av.agentId,
                    displayName = av.displayName,
                    distanceMeters = dist,
                    normX = normX.coerceIn(0.06f, 0.94f),
                    normY = normY.coerceIn(0.06f, 0.90f),
                    isSelf = av.isSelf,
                    isStreaming = hasPendingBaked || hasPendingAttach
                )
            )
        }
        onNametagsProjected(projected)
    }

    private fun processPendingGpuAssets() {
        if (clearGpuCachesRequested) {
            clearGpuCachesRequested = false
            for (asset in gpuMeshCache.values) {
                deleteGpuMeshAsset(asset)
            }
            gpuMeshCache.clear()
            if (gpuTextureCache.isNotEmpty()) {
                val ids = gpuTextureCache.values.toIntArray()
                GLES30.glDeleteTextures(ids.size, ids, 0)
                gpuTextureCache.clear()
            }
        }

        // Upload up to 8 meshes per frame into GPU VBO/IBO VRAM
        var meshBudget = 8
        while (meshBudget > 0) {
            val pair = pendingMeshUploads.poll() ?: break
            val (meshId, asset) = pair
            gpuMeshCache.remove(meshId)?.let { deleteGpuMeshAsset(it) }
            val subMeshes = ArrayList<Pair<Int, GpuMeshBuffer>>(asset.submeshes.size)
            for (sub in asset.submeshes) {
                val gpuBuf = uploadSubmeshToGpu(sub) ?: continue
                subMeshes.add(sub.faceIndex to gpuBuf)
            }
            if (subMeshes.isNotEmpty()) {
                while (gpuMeshCache.size >= MAX_GPU_MESHES) {
                    val oldestKey = gpuMeshCache.keys.firstOrNull() ?: break
                    gpuMeshCache.remove(oldestKey)?.let { deleteGpuMeshAsset(it) }
                }
                gpuMeshCache[meshId] = GpuMeshAsset(
                    submeshes = subMeshes,
                    isRigged = asset.isRigged,
                    coversBody = asset.coversBody,
                    coversLower = asset.coversLower,
                    coversHead = asset.coversHead
                )
            }
            meshBudget--
        }

        // Upload up to 5 textures per frame
        var texBudget = 5
        while (texBudget > 0) {
            val pair = pendingTextureUploads.poll() ?: break
            val (texId, bmp) = pair
            if (bmp.isRecycled) continue
            val existing = gpuTextureCache.remove(texId)
            if (existing != null) {
                glIdScratch[0] = existing
                GLES30.glDeleteTextures(1, glIdScratch, 0)
            }
            while (gpuTextureCache.size >= MAX_GPU_TEXTURES) {
                val oldestKey = gpuTextureCache.keys.firstOrNull() ?: break
                val oldTex = gpuTextureCache.remove(oldestKey)
                if (oldTex != null && oldTex != 0) {
                    glIdScratch[0] = oldTex
                    GLES30.glDeleteTextures(1, glIdScratch, 0)
                }
            }
            GLES30.glGenTextures(1, glIdScratch, 0)
            val glTex = glIdScratch[0]
            if (glTex != 0) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glTex)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                gpuTextureCache[texId] = glTex
            }
            texBudget--
        }
    }

    private fun bindTextureCached(textureId: UUID?, useTexLoc: Int): Boolean {
        val glTex = textureId?.let { gpuTextureCache[it] }
        return if (glTex != null && glTex != 0) {
            if (activeBoundTextureId != glTex) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glTex)
                activeBoundTextureId = glTex
            }
            GLES30.glUniform1f(useTexLoc, 1f)
            true
        } else {
            GLES30.glUniform1f(useTexLoc, 0f)
            false
        }
    }

    private fun resetBatchBindings() {
        activeBoundTextureId = -1
        activeBoundVboId = -1
        activeBoundIboId = -1
    }

    private fun drawSkyDome(env: EnvironmentPreset, timeSec: Float) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(skyProgram)

        GLES30.glUniform3f(skyLocs.uZenith, env.zenith.r, env.zenith.g, env.zenith.b)
        GLES30.glUniform3f(skyLocs.uHorizon, env.horizon.r, env.horizon.g, env.horizon.b)
        GLES30.glUniform3f(skyLocs.uSunColor, env.sunColor.r, env.sunColor.g, env.sunColor.b)
        GLES30.glUniform1f(skyLocs.uTime, timeSec)
        GLES30.glUniform1f(skyLocs.uPitch, orbitCamera.pitch)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, skyQuadVbo)
        GLES30.glEnableVertexAttribArray(skyLocs.aPos)
        GLES30.glVertexAttribPointer(skyLocs.aPos, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(skyLocs.aPos)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawTerrain(mesh: GpuMeshBuffer, env: EnvironmentPreset, eye: Vec3) {
        GLES30.glUseProgram(terrainProgram)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        setIdentityNormalMat3(normalMatrix3)

        GLES30.glUniformMatrix4fv(terrainLocs.uMvp, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(terrainLocs.uModel, 1, false, modelMatrix, 0)
        GLES30.glUniformMatrix3fv(terrainLocs.uNormalMat, 1, false, normalMatrix3, 0)
        val sDir = env.sunDir.normalized()
        GLES30.glUniform3f(terrainLocs.uSunDir, sDir.x, sDir.y, sDir.z)
        GLES30.glUniform3f(terrainLocs.uSunColor, env.sunColor.r, env.sunColor.g, env.sunColor.b)
        GLES30.glUniform3f(terrainLocs.uFogColor, env.horizon.r, env.horizon.g, env.horizon.b)
        GLES30.glUniform3f(terrainLocs.uEyePos, eye.x, eye.y, eye.z)
        GLES30.glUniform1f(terrainLocs.uAmbient, env.ambientStrength)

        resetBatchBindings()
        enableVertexAttribs(terrainLocs.aPos, terrainLocs.aNormal, terrainLocs.aUv)
        drawGpuMesh(terrainLocs.aPos, terrainLocs.aNormal, terrainLocs.aUv, mesh)
        disableVertexAttribs(terrainLocs.aPos, terrainLocs.aNormal, terrainLocs.aUv)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawWorldObjects(
        snapshot: RenderSceneSnapshot,
        eye: Vec3,
        timeSec: Float,
        drawDistance: Float,
        translucentPass: Boolean
    ) {
        GLES30.glUseProgram(primProgram)
        val env = snapshot.environment
        val sDir = env.sunDir.normalized()
        GLES30.glUniform3f(primLocs.uSunDir, sDir.x, sDir.y, sDir.z)
        GLES30.glUniform3f(primLocs.uSunColor, env.sunColor.r, env.sunColor.g, env.sunColor.b)
        GLES30.glUniform3f(primLocs.uFogColor, env.horizon.r, env.horizon.g, env.horizon.b)
        GLES30.glUniform3f(primLocs.uEyePos, eye.x, eye.y, eye.z)
        GLES30.glUniform1f(primLocs.uAmbient, env.ambientStrength)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(primLocs.uTex, 0)

        resetBatchBindings()
        enableVertexAttribs(primLocs.aPos, primLocs.aNormal, primLocs.aUv)

        for (obj in snapshot.worldObjects) {
            if (obj.shape == PrimShape.AVATAR) continue
            val isTranslucent = obj.color.a < 0.98f
            if (isTranslucent != translucentPass) continue

            // Fast draw-distance + 6-plane frustum sphere cull
            val maxDim = maxOf(obj.scale.x, obj.scale.y, obj.scale.z)
            val boundRadius = maxDim * 0.95f + 0.5f
            if (!isSphereVisible(obj.position.x, obj.position.y, obj.position.z, boundRadius, eye, drawDistance)) {
                continue
            }

            val streamedMesh = obj.meshAssetId?.let { gpuMeshCache[it] }
            if (streamedMesh == null && obj.isMesh) continue

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, obj.position.x, obj.position.y, obj.position.z)
            multiplyQuatRotation(modelMatrix, obj.rotation)
            Matrix.scaleM(
                modelMatrix, 0,
                obj.scale.x.coerceAtLeast(0.005f),
                obj.scale.y.coerceAtLeast(0.005f),
                obj.scale.z.coerceAtLeast(0.005f)
            )
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            computeNormalMat3(modelMatrix, normalMatrix3)

            GLES30.glUniformMatrix4fv(primLocs.uMvp, 1, false, mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(primLocs.uModel, 1, false, modelMatrix, 0)
            GLES30.glUniformMatrix3fv(primLocs.uNormalMat, 1, false, normalMatrix3, 0)
            GLES30.glUniform1f(primLocs.uFullbright, if (obj.isFullbright) 1f else 0f)
            GLES30.glUniform1f(primLocs.uGlow, obj.glow.coerceIn(0f, 1f))
            val pulse = if (snapshot.selectedLocalId == obj.localId) 0.55f + 0.45f * sin(timeSec * 5f) else 0f
            GLES30.glUniform1f(primLocs.uSelectedPulse, pulse)

            if (streamedMesh != null) {
                for ((faceIdx, submesh) in streamedMesh.submeshes) {
                    val faceTex = obj.faceTextures[faceIdx] ?: obj.defaultTextureId
                    val faceCol = obj.faceColors[faceIdx] ?: obj.color
                    if (SlBinaryParsers.isTransparentFace(faceTex, faceCol)) continue
                    bindTextureCached(faceTex, primLocs.uUseTex)
                    GLES30.glUniform4f(primLocs.uColor, faceCol.r, faceCol.g, faceCol.b, faceCol.a)
                    drawGpuMesh(primLocs.aPos, primLocs.aNormal, primLocs.aUv, submesh)
                }
            } else {
                if (SlBinaryParsers.isTransparentFace(obj.defaultTextureId, obj.color)) continue
                val primMesh = when (obj.shape) {
                    PrimShape.BOX -> boxMesh
                    PrimShape.CYLINDER -> cylinderMesh
                    PrimShape.SPHERE -> sphereMesh
                    PrimShape.TORUS -> torusMesh
                    PrimShape.PRISM -> prismMesh
                    PrimShape.TUBE -> tubeMesh
                    PrimShape.RING -> ringMesh
                    PrimShape.AVATAR -> continue
                }
                bindTextureCached(obj.defaultTextureId, primLocs.uUseTex)
                GLES30.glUniform4f(primLocs.uColor, obj.color.r, obj.color.g, obj.color.b, obj.color.a)
                drawGpuMesh(primLocs.aPos, primLocs.aNormal, primLocs.aUv, primMesh)
            }
        }

        disableVertexAttribs(primLocs.aPos, primLocs.aNormal, primLocs.aUv)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun drawAvatars(snapshot: RenderSceneSnapshot, eye: Vec3, timeSec: Float, drawDistance: Float) {
        if (snapshot.avatars.isEmpty()) return
        GLES30.glUseProgram(primProgram)
        val env = snapshot.environment
        val sDir = env.sunDir.normalized()
        GLES30.glUniform3f(primLocs.uSunDir, sDir.x, sDir.y, sDir.z)
        GLES30.glUniform3f(primLocs.uSunColor, env.sunColor.r, env.sunColor.g, env.sunColor.b)
        GLES30.glUniform3f(primLocs.uFogColor, env.horizon.r, env.horizon.g, env.horizon.b)
        GLES30.glUniform3f(primLocs.uEyePos, eye.x, eye.y, eye.z)
        GLES30.glUniform1f(primLocs.uAmbient, env.ambientStrength)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(primLocs.uTex, 0)

        resetBatchBindings()
        enableVertexAttribs(primLocs.aPos, primLocs.aNormal, primLocs.aUv)

        for (av in snapshot.avatars) {
            if (av.isSelf && snapshot.cameraMode == CameraMode.MOUSELOOK) continue
            if (!av.isSelf && !isSphereVisible(av.position.x, av.position.y, av.position.z, 3.0f, eye, drawDistance)) {
                continue
            }

            val yawDeg = av.yaw * 180f / PI.toFloat()
            val bob = if (av.isFlying) sin(timeSec * 3.2f) * 0.10f else 0f
            val pelvisZ = av.position.z + bob
            val hScale = (av.heightMeters / 1.82f).coerceIn(0.68f, 1.38f)
            val torsoS = av.torsoScale.coerceIn(0.80f, 1.30f)
            val shoulderS = av.shoulderScale.coerceIn(0.82f, 1.28f)
            val headS = av.headScale.coerceIn(0.85f, 1.22f)
            val legS = av.legScale.coerceIn(0.82f, 1.25f)
            val avPulse = if (snapshot.selectedAvatarId == av.agentId) 0.22f + 0.22f * sin(timeSec * 4.5f) else 0f

            // Inspect loaded rigged meshes so any body zone replaced by real SL rigged meshes hides the classic base completely
            var riggedCoversUpper = false
            var riggedCoversLower = false
            var riggedCoversHead = false
            for (att in av.attachments) {
                val gm = att.meshAssetId?.let { gpuMeshCache[it] } ?: continue
                if (gm.isRigged) {
                    if (gm.coversBody) riggedCoversUpper = true
                    if (gm.coversLower || gm.coversBody) riggedCoversLower = true
                    if (gm.coversHead) riggedCoversHead = true
                }
            }

            fun drawClassicPart(
                mesh: GpuMeshBuffer,
                sx: Float, sy: Float, sz: Float,
                fallbackColor: ColorRgba,
                bakedTexId: UUID? = null
            ) {
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, av.position.x, av.position.y, pelvisZ)
                Matrix.rotateM(modelMatrix, 0, yawDeg, 0f, 0f, 1f)
                Matrix.scaleM(modelMatrix, 0, sx, sy, sz)
                Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
                computeNormalMat3(modelMatrix, normalMatrix3)

                val textured = bindTextureCached(bakedTexId, primLocs.uUseTex)
                val tint = if (textured) COLOR_WHITE else fallbackColor
                GLES30.glUniformMatrix4fv(primLocs.uMvp, 1, false, mvpMatrix, 0)
                GLES30.glUniformMatrix4fv(primLocs.uModel, 1, false, modelMatrix, 0)
                GLES30.glUniformMatrix3fv(primLocs.uNormalMat, 1, false, normalMatrix3, 0)
                GLES30.glUniform4f(primLocs.uColor, tint.r, tint.g, tint.b, tint.a)
                GLES30.glUniform1f(primLocs.uFullbright, 0f)
                GLES30.glUniform1f(primLocs.uGlow, 0f)
                GLES30.glUniform1f(primLocs.uSelectedPulse, avPulse)
                drawGpuMesh(primLocs.aPos, primLocs.aNormal, primLocs.aUv, mesh)
            }

            // 1. Sculpted Organic Classic Humanoid Base (only draws zones NOT covered by loaded rigged meshes)
            if (!riggedCoversLower) {
                drawClassicPart(avatarLowerMesh, torsoS, torsoS, hScale * legS, AVATAR_PANTS_COLOR, av.bakedLowerTextureId)
            }
            if (!riggedCoversUpper) {
                drawClassicPart(avatarUpperMesh, torsoS, shoulderS, hScale, av.shirtColor, av.bakedUpperTextureId)
            }
            if (!riggedCoversHead) {
                drawClassicPart(avatarHeadMesh, headS, headS, hScale * headS, AVATAR_SKIN_COLOR, av.bakedHeadTextureId)
                if (av.bakedHairTextureId != null && gpuTextureCache.containsKey(av.bakedHairTextureId)) {
                    drawClassicPart(avatarHairCapMesh, headS, headS, hScale * headS, COLOR_WHITE, av.bakedHairTextureId)
                }
            }

            // 2. Real Worn Avatar Mesh & Prim Attachments (root attachments AND multi-prim linkset children)
            for (att in av.attachments) {
                val gpuMesh = att.meshAssetId?.let { gpuMeshCache[it] }
                if (gpuMesh == null && att.isMesh) continue
                val isRigged = gpuMesh != null && gpuMesh.isRigged

                AvatarSkeletonHierarchy.buildAttachmentModelMatrix(
                    attachment = att,
                    avatar = av,
                    pelvisBobZ = bob,
                    isRiggedMesh = isRigged,
                    outMatrix = modelMatrix
                )
                Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
                computeNormalMat3(modelMatrix, normalMatrix3)

                GLES30.glUniformMatrix4fv(primLocs.uMvp, 1, false, mvpMatrix, 0)
                GLES30.glUniformMatrix4fv(primLocs.uModel, 1, false, modelMatrix, 0)
                GLES30.glUniformMatrix3fv(primLocs.uNormalMat, 1, false, normalMatrix3, 0)
                GLES30.glUniform1f(primLocs.uFullbright, if (att.isFullbright) 1f else 0f)
                GLES30.glUniform1f(primLocs.uGlow, att.glow.coerceIn(0f, 1f))
                GLES30.glUniform1f(primLocs.uSelectedPulse, avPulse * 0.5f)

                if (gpuMesh != null) {
                    for ((faceIdx, submesh) in gpuMesh.submeshes) {
                        val faceTex = att.faceTextures[faceIdx] ?: att.defaultTextureId
                        val faceCol = att.faceColors[faceIdx] ?: att.color
                        if (SlBinaryParsers.isTransparentFace(faceTex, faceCol)) continue
                        bindTextureCached(faceTex, primLocs.uUseTex)
                        GLES30.glUniform4f(primLocs.uColor, faceCol.r, faceCol.g, faceCol.b, faceCol.a)
                        drawGpuMesh(primLocs.aPos, primLocs.aNormal, primLocs.aUv, submesh)
                    }
                } else {
                    if (SlBinaryParsers.isTransparentFace(att.defaultTextureId, att.color)) continue
                    val fallbackMesh = when (att.shape) {
                        PrimShape.SPHERE -> sphereMesh
                        PrimShape.CYLINDER -> cylinderMesh
                        PrimShape.TORUS -> torusMesh
                        PrimShape.PRISM -> prismMesh
                        PrimShape.TUBE -> tubeMesh
                        PrimShape.RING -> ringMesh
                        else -> boxMesh
                    }
                    bindTextureCached(att.defaultTextureId, primLocs.uUseTex)
                    GLES30.glUniform4f(primLocs.uColor, att.color.r, att.color.g, att.color.b, att.color.a)
                    drawGpuMesh(primLocs.aPos, primLocs.aNormal, primLocs.aUv, fallbackMesh)
                }
            }

            if (av.isFlying) {
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, av.position.x, av.position.y, pelvisZ - av.heightMeters * 0.48f)
                Matrix.scaleM(modelMatrix, 0, 0.78f, 0.78f, 0.16f)
                Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
                computeNormalMat3(modelMatrix, normalMatrix3)
                GLES30.glUniform1f(primLocs.uUseTex, 0f)
                GLES30.glUniformMatrix4fv(primLocs.uMvp, 1, false, mvpMatrix, 0)
                GLES30.glUniformMatrix4fv(primLocs.uModel, 1, false, modelMatrix, 0)
                GLES30.glUniformMatrix3fv(primLocs.uNormalMat, 1, false, normalMatrix3, 0)
                GLES30.glUniform4f(primLocs.uColor, 0.22f, 0.83f, 0.98f, 0.65f)
                GLES30.glUniform1f(primLocs.uFullbright, 1f)
                GLES30.glUniform1f(primLocs.uGlow, 0.35f)
                GLES30.glUniform1f(primLocs.uSelectedPulse, 0f)
                drawGpuMesh(primLocs.aPos, primLocs.aNormal, primLocs.aUv, torusMesh)
            }
        }

        disableVertexAttribs(primLocs.aPos, primLocs.aNormal, primLocs.aUv)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun drawWater(waterHeight: Float, env: EnvironmentPreset, eye: Vec3, timeSec: Float) {
        GLES30.glUseProgram(waterProgram)
        GLES30.glDepthMask(false)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, waterHeight)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES30.glUniformMatrix4fv(waterLocs.uMvp, 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(waterLocs.uTime, timeSec)
        GLES30.glUniform4f(waterLocs.uWaterTint, env.waterTint.r, env.waterTint.g, env.waterTint.b, env.waterTint.a)
        GLES30.glUniform3f(waterLocs.uHorizon, env.horizon.r, env.horizon.g, env.horizon.b)
        GLES30.glUniform3f(waterLocs.uEyePos, eye.x, eye.y, eye.z)

        resetBatchBindings()
        enableVertexAttribs(waterLocs.aPos, -1, waterLocs.aUv)
        drawGpuMesh(waterLocs.aPos, -1, waterLocs.aUv, waterMesh)
        disableVertexAttribs(waterLocs.aPos, -1, waterLocs.aUv)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glDepthMask(true)
    }

    /**
     * Pass 2: Renders all visible, non-minimized Avatar Attachment HUDs (slots 31..38)
     * inside the Adaptive HudSafeRect using orthographic screen-space projection.
     */
    private fun drawAvatarHudAttachments(snapshot: RenderSceneSnapshot, nowMs: Long) {
        val rects = computeActiveHudRects(snapshot)
        if (rects.isEmpty()) return

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(hudProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(hudLocs.uTex, 0)

        resetBatchBindings()
        enableVertexAttribs(hudLocs.aPos, -1, hudLocs.aUv)

        for (hr in rects) {
            val isFlashing = snapshot.hudTouchFlashLocalId == hr.obj.localId && nowMs < snapshot.hudTouchFlashUntilMs
            val flashAmt = if (isFlashing) 0.45f else 0f

            val hasTex = bindTextureCached(hr.obj.defaultTextureId, hudLocs.uUseTex)
            GLES30.glUniform4f(hudLocs.uRect, hr.left, hr.top, hr.right - hr.left, hr.bottom - hr.top)
            if (hasTex) {
                GLES30.glUniform4f(hudLocs.uColor, 1f, 1f, 1f, 0.96f)
            } else {
                GLES30.glUniform4f(hudLocs.uColor, hr.obj.color.r, hr.obj.color.g, hr.obj.color.b, 0.88f)
            }
            GLES30.glUniform4f(hudLocs.uBorderColor, 0.22f, 0.78f, 0.98f, 0.85f)
            GLES30.glUniform1f(hudLocs.uFlash, flashAmt)
            drawGpuMesh(hudLocs.aPos, -1, hudLocs.aUv, hudQuadMesh)

            // Draw individual interactive LSL HUD button cells mapped in UV STCoord space
            GLES30.glUniform1f(hudLocs.uUseTex, 0f)
            val w = hr.right - hr.left
            val h = hr.bottom - hr.top
            for (btn in hr.obj.hudButtons) {
                val padX = w * 0.03f
                val padY = h * 0.06f
                val bx = hr.left + btn.uMin * w + padX
                val by = hr.top + btn.vMin * h + padY
                val bw = ((btn.uMax - btn.uMin) * w - padX * 2f).coerceAtLeast(0.01f)
                val bh = ((btn.vMax - btn.vMin) * h - padY * 2f).coerceAtLeast(0.01f)
                GLES30.glUniform4f(hudLocs.uRect, bx, by, bw, bh)
                GLES30.glUniform4f(hudLocs.uColor, btn.accentColor.r, btn.accentColor.g, btn.accentColor.b, 0.78f)
                GLES30.glUniform4f(hudLocs.uBorderColor, 1f, 1f, 1f, 0.55f)
                GLES30.glUniform1f(hudLocs.uFlash, flashAmt * 0.6f)
                drawGpuMesh(hudLocs.aPos, -1, hudLocs.aUv, hudQuadMesh)
            }
        }
        disableVertexAttribs(hudLocs.aPos, -1, hudLocs.aUv)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
    }

    private fun computeActiveHudRects(snapshot: RenderSceneSnapshot): List<ComputedHudRect> {
        val safe = snapshot.hudSafeRect
        val safeW = (safe.right - safe.left).coerceAtLeast(0.20f)
        val safeH = (safe.bottom - safe.top).coerceAtLeast(0.20f)
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val isCompactPortrait = aspect < 0.85f

        val list = mutableListOf<ComputedHudRect>()
        for (obj in snapshot.hudAttachments) {
            val slot = HudAttachmentPoint.fromSlotId(obj.attachmentPoint) ?: continue
            val override = snapshot.hudSlotStates[slot.slotId] ?: AvatarHudSlotState(slot.slotId)
            if (!override.visible || override.minimized) continue

            val scale = (override.scale * snapshot.hudGlobalScale).coerceIn(0.45f, 1.55f)
            val baseW = (if (isCompactPortrait) 0.34f else 0.19f) * scale
            val baseH = (if (isCompactPortrait) 0.088f else 0.125f) * scale
            val w = baseW.coerceAtMost(safeW * 0.48f)
            val h = baseH.coerceAtMost(safeH * 0.34f)

            val cx = safe.left + slot.anchorX * (safeW - w) + override.offsetX * safeW
            val cy = safe.top + slot.anchorY * (safeH - h) + override.offsetY * safeH
            val clampedX = cx.coerceIn(safe.left, (safe.right - w).coerceAtLeast(safe.left))
            val clampedY = cy.coerceIn(safe.top, (safe.bottom - h).coerceAtLeast(safe.top))

            list.add(ComputedHudRect(obj, clampedX, clampedY, clampedX + w, clampedY + h))
        }
        return list
    }

    /**
     * Orthographic raycast against expanded Avatar Attachment HUDs (Slots 31..38).
     */
    fun pickHudAttachment(normX: Float, normY: Float): HudHitResult? {
        val rects = computeActiveHudRects(sceneSnapshot)
        for (hr in rects.asReversed()) {
            if (normX in hr.left..hr.right && normY in hr.top..hr.bottom) {
                val u = ((normX - hr.left) / (hr.right - hr.left)).coerceIn(0f, 1f)
                val v = ((normY - hr.top) / (hr.bottom - hr.top)).coerceIn(0f, 1f)
                val matched = hr.obj.hudButtons.firstOrNull { u in it.uMin..it.uMax && v in it.vMin..it.vMax }
                return HudHitResult(hr.obj, faceIndex = 4, uvS = u, uvT = 1f - v, matchedButton = matched)
            }
        }
        return null
    }

    private fun unprojectPickRay(normX: Float, normY: Float): Pair<Vec3, Vec3>? {
        val ndcX = normX * 2f - 1f
        val ndcY = 1f - normY * 2f
        val invCopy = FloatArray(16)
        synchronized(invVpCopyLock) {
            System.arraycopy(invVpReadable, 0, invCopy, 0, 16)
        }
        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)
        Matrix.multiplyMV(nearWorld, 0, invCopy, 0, floatArrayOf(ndcX, ndcY, -1f, 1f), 0)
        Matrix.multiplyMV(farWorld, 0, invCopy, 0, floatArrayOf(ndcX, ndcY, 1f, 1f), 0)
        if (abs(nearWorld[3]) < 1e-5f || abs(farWorld[3]) < 1e-5f) return null

        val origin = Vec3(nearWorld[0] / nearWorld[3], nearWorld[1] / nearWorld[3], nearWorld[2] / nearWorld[3])
        val farPt = Vec3(farWorld[0] / farWorld[3], farWorld[1] / farWorld[3], farWorld[2] / farWorld[3])
        return origin to (farPt - origin).normalized()
    }

    /**
     * Perspective raycast against 3D Avatars using inverse VP unprojection against each avatar's
     * vertical bounding capsule (feet..head+0.2m, radius 0.68m) so tapping any avatar opens Profile.
     */
    fun pickAvatar(normX: Float, normY: Float): AvatarEntity? {
        val (origin, dir) = unprojectPickRay(normX, normY) ?: return null

        var bestAv: AvatarEntity? = null
        var bestT = Float.MAX_VALUE
        val radiusSq = 0.68f * 0.68f

        for (av in sceneSnapshot.avatars) {
            if (av.isSelf && sceneSnapshot.cameraMode == CameraMode.MOUSELOOK) continue
            val h = av.heightMeters.coerceIn(1.2f, 2.5f)
            val offsets = floatArrayOf(-h * 0.28f, h * 0.06f, h * 0.36f)
            for (dz in offsets) {
                val cx = av.position.x
                val cy = av.position.y
                val cz = av.position.z + dz
                val ocx = cx - origin.x
                val ocy = cy - origin.y
                val ocz = cz - origin.z
                val tProj = ocx * dir.x + ocy * dir.y + ocz * dir.z
                if (tProj < 0.2f || tProj > bestT) continue
                val dx = cx - (origin.x + dir.x * tProj)
                val dy = cy - (origin.y + dir.y * tProj)
                val dzDist = cz - (origin.z + dir.z * tProj)
                if (dx * dx + dy * dy + dzDist * dzDist <= radiusSq) {
                    bestT = tProj
                    bestAv = av
                }
            }
        }
        return bestAv
    }

    /**
     * Perspective raycast against 3D world prims/meshes using inverse View-Projection unprojection.
     */
    fun pickWorldObject(normX: Float, normY: Float): SceneObject? {
        val (origin, dir) = unprojectPickRay(normX, normY) ?: return null

        var bestObj: SceneObject? = null
        var bestT = Float.MAX_VALUE

        for (obj in sceneSnapshot.worldObjects) {
            val radius = maxOf(obj.scale.x, obj.scale.y, obj.scale.z) * 0.72f
            val ocx = obj.position.x - origin.x
            val ocy = obj.position.y - origin.y
            val ocz = obj.position.z - origin.z
            val tProj = ocx * dir.x + ocy * dir.y + ocz * dir.z
            if (tProj < 0.2f || tProj > bestT) continue
            val dx = obj.position.x - (origin.x + dir.x * tProj)
            val dy = obj.position.y - (origin.y + dir.y * tProj)
            val dz = obj.position.z - (origin.z + dir.z * tProj)
            if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                bestT = tProj
                bestObj = obj
            }
        }
        return bestObj
    }

    private fun enableVertexAttribs(posLoc: Int, normLoc: Int, uvLoc: Int) {
        if (posLoc >= 0) GLES30.glEnableVertexAttribArray(posLoc)
        if (normLoc >= 0) GLES30.glEnableVertexAttribArray(normLoc)
        if (uvLoc >= 0) GLES30.glEnableVertexAttribArray(uvLoc)
    }

    private fun disableVertexAttribs(posLoc: Int, normLoc: Int, uvLoc: Int) {
        if (posLoc >= 0) GLES30.glDisableVertexAttribArray(posLoc)
        if (normLoc >= 0) GLES30.glDisableVertexAttribArray(normLoc)
        if (uvLoc >= 0) GLES30.glDisableVertexAttribArray(uvLoc)
    }

    private fun drawGpuMesh(posLoc: Int, normLoc: Int, uvLoc: Int, mesh: GpuMeshBuffer) {
        if (activeBoundVboId != mesh.vboId) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.vboId)
            activeBoundVboId = mesh.vboId
            if (posLoc >= 0) {
                GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, MeshData.STRIDE_BYTES, 0)
            }
            if (normLoc >= 0) {
                GLES30.glVertexAttribPointer(normLoc, 3, GLES30.GL_FLOAT, false, MeshData.STRIDE_BYTES, 12)
            }
            if (uvLoc >= 0) {
                GLES30.glVertexAttribPointer(uvLoc, 2, GLES30.GL_FLOAT, false, MeshData.STRIDE_BYTES, 24)
            }
        }

        if (mesh.iboId != 0 && mesh.indexCount > 0) {
            if (activeBoundIboId != mesh.iboId) {
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.iboId)
                activeBoundIboId = mesh.iboId
            }
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        } else {
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.vertexCount)
        }
    }

    /**
     * Extracts 6 normalized frustum clip planes from OpenGL column-major [vp] matrix
     * (Gribb-Hartmann method).
     */
    private fun extractFrustumPlanes(vp: FloatArray, out: FloatArray) {
        // Row i of column-major matrix vp is (vp[i], vp[4+i], vp[8+i], vp[12+i])
        setNormalizedPlane(out, 0, vp[3] + vp[0], vp[7] + vp[4], vp[11] + vp[8], vp[15] + vp[12])  // Left
        setNormalizedPlane(out, 4, vp[3] - vp[0], vp[7] - vp[4], vp[11] - vp[8], vp[15] - vp[12])  // Right
        setNormalizedPlane(out, 8, vp[3] + vp[1], vp[7] + vp[5], vp[11] + vp[9], vp[15] + vp[13])  // Bottom
        setNormalizedPlane(out, 12, vp[3] - vp[1], vp[7] - vp[5], vp[11] - vp[9], vp[15] - vp[13]) // Top
        setNormalizedPlane(out, 16, vp[3] + vp[2], vp[7] + vp[6], vp[11] + vp[10], vp[15] + vp[14]) // Near
        setNormalizedPlane(out, 20, vp[3] - vp[2], vp[7] - vp[6], vp[11] - vp[10], vp[15] - vp[14]) // Far
    }

    private fun setNormalizedPlane(out: FloatArray, offset: Int, a: Float, b: Float, c: Float, d: Float) {
        val len = sqrt(a * a + b * b + c * c)
        if (len > 1e-6f) {
            val inv = 1f / len
            out[offset] = a * inv
            out[offset + 1] = b * inv
            out[offset + 2] = c * inv
            out[offset + 3] = d * inv
        } else {
            out[offset] = 0f; out[offset + 1] = 0f; out[offset + 2] = 0f; out[offset + 3] = 1f
        }
    }

    private fun isSphereVisible(
        cx: Float,
        cy: Float,
        cz: Float,
        radius: Float,
        eye: Vec3,
        drawDistance: Float
    ): Boolean {
        val dx = cx - eye.x
        val dy = cy - eye.y
        val dz = cz - eye.z
        val maxDist = drawDistance + radius
        if (dx * dx + dy * dy + dz * dz > maxDist * maxDist) return false

        var i = 0
        while (i < 24) {
            val dist = frustumPlanes[i] * cx + frustumPlanes[i + 1] * cy + frustumPlanes[i + 2] * cz + frustumPlanes[i + 3]
            if (dist < -radius) return false
            i += 4
        }
        return true
    }

    /**
     * Computes inverse-transpose of the upper-left 3x3 of OpenGL column-major [m] into [out]
     * so the vertex shader avoids per-vertex `transpose(inverse(mat3(uModel)))`.
     */
    private fun computeNormalMat3(m: FloatArray, out: FloatArray) {
        val m00 = m[0]; val m10 = m[1]; val m20 = m[2]
        val m01 = m[4]; val m11 = m[5]; val m21 = m[6]
        val m02 = m[8]; val m12 = m[9]; val m22 = m[10]

        val c00 = m11 * m22 - m21 * m12
        val c10 = m21 * m02 - m01 * m22
        val c20 = m01 * m12 - m11 * m02

        val c01 = m12 * m20 - m22 * m10
        val c11 = m22 * m00 - m02 * m20
        val c21 = m02 * m10 - m12 * m00

        val c02 = m10 * m21 - m20 * m11
        val c12 = m20 * m01 - m00 * m21
        val c22 = m00 * m11 - m10 * m01

        val det = m00 * c00 + m10 * c10 + m20 * c20
        if (abs(det) > 1e-10f) {
            val invDet = 1f / det
            out[0] = c00 * invDet; out[1] = c10 * invDet; out[2] = c20 * invDet
            out[3] = c01 * invDet; out[4] = c11 * invDet; out[5] = c21 * invDet
            out[6] = c02 * invDet; out[7] = c12 * invDet; out[8] = c22 * invDet
        } else {
            out[0] = m00; out[1] = m10; out[2] = m20
            out[3] = m01; out[4] = m11; out[5] = m21
            out[6] = m02; out[7] = m12; out[8] = m22
        }
    }

    private fun setIdentityNormalMat3(out: FloatArray) {
        out[0] = 1f; out[1] = 0f; out[2] = 0f
        out[3] = 0f; out[4] = 1f; out[5] = 0f
        out[6] = 0f; out[7] = 0f; out[8] = 1f
    }

    /**
     * Multiplies `target` by the full 3D rotation matrix represented by Quaternion `q`.
     */
    private fun multiplyQuatRotation(target: FloatArray, q: Quat) {
        val len = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
        if (len < 1e-5f) return
        val x = q.x / len
        val y = q.y / len
        val z = q.z / len
        val w = q.w / len

        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z

        rotMatrix[0] = 1f - 2f * (yy + zz)
        rotMatrix[1] = 2f * (xy + wz)
        rotMatrix[2] = 2f * (xz - wy)
        rotMatrix[3] = 0f

        rotMatrix[4] = 2f * (xy - wz)
        rotMatrix[5] = 1f - 2f * (xx + zz)
        rotMatrix[6] = 2f * (yz + wx)
        rotMatrix[7] = 0f

        rotMatrix[8] = 2f * (xz + wy)
        rotMatrix[9] = 2f * (yz - wx)
        rotMatrix[10] = 1f - 2f * (xx + yy)
        rotMatrix[11] = 0f

        rotMatrix[12] = 0f
        rotMatrix[13] = 0f
        rotMatrix[14] = 0f
        rotMatrix[15] = 1f

        Matrix.multiplyMM(tempMatrix, 0, target, 0, rotMatrix, 0)
        System.arraycopy(tempMatrix, 0, target, 0, 16)
    }

    private fun updateFpsCounter() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - fpsLastCheckMs >= 1000L) {
            val fps = (frameCount * 1000L / (now - fpsLastCheckMs).coerceAtLeast(1L)).toInt()
            onFpsMeasured(fps)
            frameCount = 0
            fpsLastCheckMs = now
        }
    }

    private fun compileProgram(vs: String, fs: String): Int {
        val vShader = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also {
            GLES30.glShaderSource(it, vs)
            GLES30.glCompileShader(it)
        }
        val fShader = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also {
            GLES30.glShaderSource(it, fs)
            GLES30.glCompileShader(it)
        }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vShader)
            GLES30.glAttachShader(it, fShader)
            GLES30.glLinkProgram(it)
            GLES30.glDeleteShader(vShader)
            GLES30.glDeleteShader(fShader)
        }
    }

    companion object {
        private const val MAX_GPU_MESHES = 240
        private const val MAX_GPU_TEXTURES = 160

        private val CAMERA_FOCUS_OFFSET = Vec3(0f, 0f, 0.40f)
        private val DEFAULT_CAMERA_TARGET = Vec3(128f, 128f, 25f)
        private val AVATAR_SKIN_COLOR = ColorRgba(0.90f, 0.75f, 0.65f, 1f)
        private val AVATAR_PANTS_COLOR = ColorRgba(0.14f, 0.18f, 0.25f, 1f)
        private val COLOR_WHITE = ColorRgba(1f, 1f, 1f, 1f)

        private const val SKY_VS = """#version 300 es
            in vec2 aPos;
            out vec2 vUv;
            void main() {
                vUv = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.999, 1.0);
            }
        """

        private const val SKY_FS = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform vec3 uZenith;
            uniform vec3 uHorizon;
            uniform vec3 uSunColor;
            uniform float uTime;
            uniform float uPitch;
            out vec4 fragColor;
            void main() {
                float elev = clamp(vUv.y + uPitch * 0.35, 0.0, 1.0);
                vec3 sky = mix(uHorizon, uZenith, pow(elev, 0.65));
                vec2 sunPos = vec2(0.72, 0.42 + uPitch * 0.25);
                float d = length(vUv - sunPos);
                float sunGlow = exp(-d * 9.0) * 0.55 + smoothstep(0.05, 0.015, d) * 0.9;
                float cloud = sin(vUv.x * 11.0 + uTime * 0.08) * cos(vUv.y * 8.0 - uTime * 0.04) * 0.5 + 0.5;
                float cloudBand = smoothstep(0.35, 0.85, elev) * smoothstep(0.95, 0.55, elev) * cloud * 0.14;
                vec3 col = sky + uSunColor * sunGlow + vec3(cloudBand);
                fragColor = vec4(col, 1.0);
            }
        """

        private const val WORLD_VS = """#version 300 es
            in vec3 aPos;
            in vec3 aNormal;
            in vec2 aUv;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform mat3 uNormalMat;
            out vec3 vWorldPos;
            out vec3 vNormal;
            out vec2 vUv;
            void main() {
                vec4 wp = uModel * vec4(aPos, 1.0);
                vWorldPos = wp.xyz;
                vNormal = normalize(uNormalMat * aNormal);
                vUv = aUv;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val TERRAIN_FS = """#version 300 es
            precision mediump float;
            in vec3 vWorldPos;
            in vec3 vNormal;
            in vec2 vUv;
            uniform vec3 uSunDir;
            uniform vec3 uSunColor;
            uniform vec3 uFogColor;
            uniform vec3 uEyePos;
            uniform float uAmbient;
            out vec4 fragColor;
            void main() {
                float h = vWorldPos.z;
                vec3 sand = vec3(0.76, 0.70, 0.54);
                vec3 grass = vec3(0.22, 0.45, 0.26);
                vec3 rock = vec3(0.38, 0.41, 0.46);
                vec3 snow = vec3(0.86, 0.90, 0.95);
                vec3 base = mix(sand, grass, smoothstep(20.2, 23.0, h));
                base = mix(base, rock, smoothstep(28.0, 35.0, h));
                base = mix(base, snow, smoothstep(40.0, 47.0, h));
                float slope = 1.0 - clamp(vNormal.z, 0.0, 1.0);
                base = mix(base, rock, smoothstep(0.25, 0.65, slope));

                vec2 g = abs(fract(vWorldPos.xy / 16.0 - 0.5) - 0.5);
                float grid = smoothstep(0.025, 0.0, min(g.x, g.y)) * 0.06;

                float diff = max(dot(normalize(vNormal), normalize(uSunDir)), 0.0);
                vec3 lit = (base + vec3(grid)) * (uAmbient + diff * uSunColor * 0.85);
                float dist = length(vWorldPos - uEyePos);
                float fog = clamp((dist - 55.0) / 140.0, 0.0, 0.82);
                fragColor = vec4(mix(lit, uFogColor, fog), 1.0);
            }
        """

        private const val PRIM_FS = """#version 300 es
            precision mediump float;
            in vec3 vWorldPos;
            in vec3 vNormal;
            in vec2 vUv;
            uniform vec3 uSunDir;
            uniform vec3 uSunColor;
            uniform vec3 uFogColor;
            uniform vec3 uEyePos;
            uniform float uAmbient;
            uniform vec4 uColor;
            uniform float uFullbright;
            uniform float uGlow;
            uniform float uSelectedPulse;
            uniform sampler2D uTex;
            uniform float uUseTex;
            out vec4 fragColor;
            void main() {
                vec4 baseCol = uColor;
                if (uUseTex > 0.5) {
                    vec4 texCol = texture(uTex, vUv);
                    baseCol *= texCol;
                    if (baseCol.a < 0.08) discard;
                }
                vec3 n = normalize(vNormal);
                if (!gl_FrontFacing) {
                    n = -n;
                }
                vec3 l = normalize(uSunDir);
                vec3 v = normalize(uEyePos - vWorldPos);
                float diff = max(dot(n, l), 0.0);
                float backFill = max(dot(n, -l), 0.0) * 0.22;
                float hemi = mix(0.80, 1.15, n.z * 0.5 + 0.5);
                vec3 hVec = normalize(l + v);
                float spec = pow(max(dot(n, hVec), 0.0), 24.0) * 0.20;

                vec3 lit = baseCol.rgb * (uAmbient * hemi + backFill + diff * uSunColor * 0.85) + uSunColor * spec;
                lit = mix(lit, baseCol.rgb * 1.12, uFullbright);
                lit += baseCol.rgb * (uGlow * 0.85);

                float edge = smoothstep(0.035, 0.0, min(min(vUv.x, 1.0 - vUv.x), min(vUv.y, 1.0 - vUv.y)));
                float untexturedEdge = (1.0 - uUseTex) * 0.08;
                lit = mix(lit, vec3(0.22, 0.86, 1.0), edge * (untexturedEdge + uSelectedPulse * 0.85));

                float dist = length(vWorldPos - uEyePos);
                float fog = clamp((dist - 65.0) / 155.0, 0.0, 0.78);
                fragColor = vec4(mix(lit, uFogColor, fog), baseCol.a);
            }
        """

        private const val WATER_VS = """#version 300 es
            in vec3 aPos;
            in vec2 aUv;
            uniform mat4 uMvp;
            uniform float uTime;
            out vec3 vWorldPos;
            out vec2 vUv;
            void main() {
                vec3 p = aPos;
                p.z += sin(p.x * 0.14 + uTime * 1.6) * 0.12 + cos(p.y * 0.12 + uTime * 1.2) * 0.10;
                vWorldPos = p;
                vUv = aUv;
                gl_Position = uMvp * vec4(p, 1.0);
            }
        """

        private const val WATER_FS = """#version 300 es
            precision mediump float;
            in vec3 vWorldPos;
            in vec2 vUv;
            uniform float uTime;
            uniform vec4 uWaterTint;
            uniform vec3 uHorizon;
            uniform vec3 uEyePos;
            out vec4 fragColor;
            void main() {
                float ripple = sin(vWorldPos.x * 0.65 + uTime * 2.1) * cos(vWorldPos.y * 0.55 - uTime * 1.7) * 0.5 + 0.5;
                vec3 v = normalize(uEyePos - vWorldPos);
                float fresnel = pow(1.0 - clamp(v.z, 0.0, 1.0), 3.0);
                vec3 col = mix(uWaterTint.rgb + vec3(ripple * 0.08), uHorizon, fresnel * 0.58);
                fragColor = vec4(col, uWaterTint.a);
            }
        """

        private const val HUD_VS = """#version 300 es
            in vec3 aPos;
            in vec2 aUv;
            uniform vec4 uRect; // x, y, w, h in [0..1] top-left screen coordinates
            out vec2 vUv;
            void main() {
                float sx = uRect.x + aPos.x * uRect.z;
                float sy = uRect.y + aPos.y * uRect.w;
                vec2 ndc = vec2(sx * 2.0 - 1.0, 1.0 - sy * 2.0);
                vUv = aUv;
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
        """

        private const val HUD_FS = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform vec4 uColor;
            uniform vec4 uBorderColor;
            uniform float uFlash;
            uniform sampler2D uTex;
            uniform float uUseTex;
            out vec4 fragColor;
            void main() {
                vec4 base = uColor;
                if (uUseTex > 0.5) {
                    base *= texture(uTex, vUv);
                }
                float edge = step(vUv.x, 0.025) + step(0.975, vUv.x) + step(vUv.y, 0.04) + step(0.96, vUv.y);
                float grad = uUseTex > 0.5 ? 1.0 : (0.85 + (1.0 - vUv.y) * 0.25);
                vec3 body = base.rgb * grad + vec3(uFlash);
                vec3 col = mix(body, uBorderColor.rgb, clamp(edge, 0.0, 1.0) * (uUseTex > 0.5 ? 0.45 : 1.0));
                fragColor = vec4(col, base.a);
            }
        """
    }
}
