package com.example.linkpoint.render

import com.example.linkpoint.scene.TerrainGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Interleaved vertex buffer (pos.xyz, normal.xyz, uv.st) for OpenGL ES 3.0 meshes.
 */
class MeshData(
    val vertexBuffer: FloatBuffer,
    val vertexCount: Int
) {
    companion object {
        const val FLOATS_PER_VERTEX = 8 // 3 pos + 3 normal + 2 uv
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
    }
}

/**
 * Procedural mesh builder for Second Life primitives (Box, Cylinder, Sphere, Torus, Prism),
 * region terrain heightfield, animated water plane, selection wireframe, and HUD faces.
 */
object PrimMeshGenerator {

    private fun toFloatBuffer(list: List<Float>): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(list.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        val arr = FloatArray(list.size) { list[it] }
        fb.put(arr)
        fb.position(0)
        return fb
    }

    private fun pushVert(
        out: MutableList<Float>,
        px: Float, py: Float, pz: Float,
        nx: Float, ny: Float, nz: Float,
        u: Float, v: Float
    ) {
        out.add(px); out.add(py); out.add(pz)
        out.add(nx); out.add(ny); out.add(nz)
        out.add(u); out.add(v)
    }

    private fun pushQuad(
        out: MutableList<Float>,
        p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray,
        nx: Float, ny: Float, nz: Float
    ) {
        pushVert(out, p0[0], p0[1], p0[2], nx, ny, nz, 0f, 0f)
        pushVert(out, p1[0], p1[1], p1[2], nx, ny, nz, 1f, 0f)
        pushVert(out, p2[0], p2[1], p2[2], nx, ny, nz, 1f, 1f)

        pushVert(out, p0[0], p0[1], p0[2], nx, ny, nz, 0f, 0f)
        pushVert(out, p2[0], p2[1], p2[2], nx, ny, nz, 1f, 1f)
        pushVert(out, p3[0], p3[1], p3[2], nx, ny, nz, 0f, 1f)
    }

    fun buildUnitBox(): MeshData {
        val out = ArrayList<Float>(36 * 8)
        val h = 0.5f
        // +Z top
        pushQuad(out, floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h), floatArrayOf(h, h, h), floatArrayOf(-h, h, h), 0f, 0f, 1f)
        // -Z bottom
        pushQuad(out, floatArrayOf(-h, h, -h), floatArrayOf(h, h, -h), floatArrayOf(h, -h, -h), floatArrayOf(-h, -h, -h), 0f, 0f, -1f)
        // +X right
        pushQuad(out, floatArrayOf(h, -h, -h), floatArrayOf(h, h, -h), floatArrayOf(h, h, h), floatArrayOf(h, -h, h), 1f, 0f, 0f)
        // -X left
        pushQuad(out, floatArrayOf(-h, h, -h), floatArrayOf(-h, -h, -h), floatArrayOf(-h, -h, h), floatArrayOf(-h, h, h), -1f, 0f, 0f)
        // +Y back
        pushQuad(out, floatArrayOf(h, h, -h), floatArrayOf(-h, h, -h), floatArrayOf(-h, h, h), floatArrayOf(h, h, h), 0f, 1f, 0f)
        // -Y front
        pushQuad(out, floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h), floatArrayOf(h, -h, h), floatArrayOf(-h, -h, h), 0f, -1f, 0f)
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }

    fun buildUnitCylinder(segments: Int = 20): MeshData {
        val out = ArrayList<Float>(segments * 12 * 8)
        val r = 0.5f
        val h = 0.5f
        for (i in 0 until segments) {
            val a0 = (2.0 * PI * i / segments).toFloat()
            val a1 = (2.0 * PI * (i + 1) / segments).toFloat()
            val c0 = cos(a0); val s0 = sin(a0)
            val c1 = cos(a1); val s1 = sin(a1)
            val u0 = i.toFloat() / segments
            val u1 = (i + 1).toFloat() / segments

            // Side quad
            pushVert(out, r * c0, r * s0, -h, c0, s0, 0f, u0, 0f)
            pushVert(out, r * c1, r * s1, -h, c1, s1, 0f, u1, 0f)
            pushVert(out, r * c1, r * s1, h, c1, s1, 0f, u1, 1f)

            pushVert(out, r * c0, r * s0, -h, c0, s0, 0f, u0, 0f)
            pushVert(out, r * c1, r * s1, h, c1, s1, 0f, u1, 1f)
            pushVert(out, r * c0, r * s0, h, c0, s0, 0f, u0, 1f)

            // Top cap
            pushVert(out, 0f, 0f, h, 0f, 0f, 1f, 0.5f, 0.5f)
            pushVert(out, r * c0, r * s0, h, 0f, 0f, 1f, 0.5f + 0.5f * c0, 0.5f + 0.5f * s0)
            pushVert(out, r * c1, r * s1, h, 0f, 0f, 1f, 0.5f + 0.5f * c1, 0.5f + 0.5f * s1)

            // Bottom cap
            pushVert(out, 0f, 0f, -h, 0f, 0f, -1f, 0.5f, 0.5f)
            pushVert(out, r * c1, r * s1, -h, 0f, 0f, -1f, 0.5f + 0.5f * c1, 0.5f + 0.5f * s1)
            pushVert(out, r * c0, r * s0, -h, 0f, 0f, -1f, 0.5f + 0.5f * c0, 0.5f + 0.5f * s0)
        }
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }

    fun buildUnitSphere(stacks: Int = 12, slices: Int = 18): MeshData {
        val out = ArrayList<Float>(stacks * slices * 6 * 8)
        val r = 0.5f
        for (i in 0 until stacks) {
            val phi0 = (-PI / 2.0 + PI * i / stacks).toFloat()
            val phi1 = (-PI / 2.0 + PI * (i + 1) / stacks).toFloat()
            val v0 = i.toFloat() / stacks
            val v1 = (i + 1).toFloat() / stacks
            for (j in 0 until slices) {
                val th0 = (2.0 * PI * j / slices).toFloat()
                val th1 = (2.0 * PI * (j + 1) / slices).toFloat()
                val u0 = j.toFloat() / slices
                val u1 = (j + 1).toFloat() / slices

                val p00 = spherePt(phi0, th0)
                val p10 = spherePt(phi0, th1)
                val p11 = spherePt(phi1, th1)
                val p01 = spherePt(phi1, th0)

                pushVert(out, p00[0] * r, p00[1] * r, p00[2] * r, p00[0], p00[1], p00[2], u0, v0)
                pushVert(out, p10[0] * r, p10[1] * r, p10[2] * r, p10[0], p10[1], p10[2], u1, v0)
                pushVert(out, p11[0] * r, p11[1] * r, p11[2] * r, p11[0], p11[1], p11[2], u1, v1)

                pushVert(out, p00[0] * r, p00[1] * r, p00[2] * r, p00[0], p00[1], p00[2], u0, v0)
                pushVert(out, p11[0] * r, p11[1] * r, p11[2] * r, p11[0], p11[1], p11[2], u1, v1)
                pushVert(out, p01[0] * r, p01[1] * r, p01[2] * r, p01[0], p01[1], p01[2], u0, v1)
            }
        }
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }

    private fun spherePt(phi: Float, theta: Float): FloatArray =
        floatArrayOf(cos(phi) * cos(theta), cos(phi) * sin(theta), sin(phi))

    fun buildUnitTorus(majorSegs: Int = 20, minorSegs: Int = 12): MeshData {
        val out = ArrayList<Float>(majorSegs * minorSegs * 6 * 8)
        val majorR = 0.36f
        val minorR = 0.14f
        for (i in 0 until majorSegs) {
            val u0 = (2.0 * PI * i / majorSegs).toFloat()
            val u1 = (2.0 * PI * (i + 1) / majorSegs).toFloat()
            for (j in 0 until minorSegs) {
                val v0 = (2.0 * PI * j / minorSegs).toFloat()
                val v1 = (2.0 * PI * (j + 1) / minorSegs).toFloat()

                val p00 = torusPt(majorR, minorR, u0, v0)
                val p10 = torusPt(majorR, minorR, u1, v0)
                val p11 = torusPt(majorR, minorR, u1, v1)
                val p01 = torusPt(majorR, minorR, u0, v1)

                pushVert(out, p00[0], p00[1], p00[2], p00[3], p00[4], p00[5], i.toFloat() / majorSegs, j.toFloat() / minorSegs)
                pushVert(out, p10[0], p10[1], p10[2], p10[3], p10[4], p10[5], (i + 1f) / majorSegs, j.toFloat() / minorSegs)
                pushVert(out, p11[0], p11[1], p11[2], p11[3], p11[4], p11[5], (i + 1f) / majorSegs, (j + 1f) / minorSegs)

                pushVert(out, p00[0], p00[1], p00[2], p00[3], p00[4], p00[5], i.toFloat() / majorSegs, j.toFloat() / minorSegs)
                pushVert(out, p11[0], p11[1], p11[2], p11[3], p11[4], p11[5], (i + 1f) / majorSegs, (j + 1f) / minorSegs)
                pushVert(out, p01[0], p01[1], p01[2], p01[3], p01[4], p01[5], i.toFloat() / majorSegs, (j + 1f) / minorSegs)
            }
        }
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }

    private fun torusPt(R: Float, r: Float, u: Float, v: Float): FloatArray {
        val cu = cos(u); val su = sin(u)
        val cv = cos(v); val sv = sin(v)
        val x = (R + r * cv) * cu
        val y = (R + r * cv) * su
        val z = r * sv
        return floatArrayOf(x, y, z, cv * cu, cv * su, sv)
    }

    fun buildUnitPrism(): MeshData = buildUnitCylinder(segments = 3)

    /**
     * Revolved square cross-section (Second Life Tube preset: PathCurve = CIRCLE, ProfileCurve = SQUARE).
     */
    fun buildUnitTube(majorSegs: Int = 24): MeshData =
        buildRevolvedPolygon(majorSegs = majorSegs, polySides = 4, phaseRad = (PI / 4.0).toFloat())

    /**
     * Revolved equilateral-triangle cross-section (Second Life Ring preset: PathCurve = CIRCLE, ProfileCurve = EQUALTRI).
     */
    fun buildUnitRing(majorSegs: Int = 24): MeshData =
        buildRevolvedPolygon(majorSegs = majorSegs, polySides = 3, phaseRad = 0f)

    private fun buildRevolvedPolygon(majorSegs: Int, polySides: Int, phaseRad: Float): MeshData {
        val out = ArrayList<Float>(majorSegs * polySides * 6 * 8)
        val majorR = 0.34f
        val minorR = 0.16f
        for (i in 0 until majorSegs) {
            val u0 = (2.0 * PI * i / majorSegs).toFloat()
            val u1 = (2.0 * PI * (i + 1) / majorSegs).toFloat()
            val cu0 = cos(u0); val su0 = sin(u0)
            val cu1 = cos(u1); val su1 = sin(u1)
            for (j in 0 until polySides) {
                val v0 = phaseRad + (2.0 * PI * j / polySides).toFloat()
                val v1 = phaseRad + (2.0 * PI * (j + 1) / polySides).toFloat()
                val midV = 0.5f * (v0 + v1)
                val nRad = cos(midV)
                val nz = sin(midV)

                val r0 = majorR + minorR * cos(v0); val z0 = minorR * sin(v0)
                val r1 = majorR + minorR * cos(v1); val z1 = minorR * sin(v1)

                pushVert(out, r0 * cu0, r0 * su0, z0, nRad * cu0, nRad * su0, nz, i.toFloat() / majorSegs, j.toFloat() / polySides)
                pushVert(out, r0 * cu1, r0 * su1, z0, nRad * cu1, nRad * su1, nz, (i + 1f) / majorSegs, j.toFloat() / polySides)
                pushVert(out, r1 * cu1, r1 * su1, z1, nRad * cu1, nRad * su1, nz, (i + 1f) / majorSegs, (j + 1f) / polySides)

                pushVert(out, r0 * cu0, r0 * su0, z0, nRad * cu0, nRad * su0, nz, i.toFloat() / majorSegs, j.toFloat() / polySides)
                pushVert(out, r1 * cu1, r1 * su1, z1, nRad * cu1, nRad * su1, nz, (i + 1f) / majorSegs, (j + 1f) / polySides)
                pushVert(out, r1 * cu0, r1 * su0, z1, nRad * cu0, nRad * su0, nz, i.toFloat() / majorSegs, (j + 1f) / polySides)
            }
        }
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }

    /**
     * Builds the 256m x 256m terrain mesh from TerrainGrid height samples with smooth vertex normals.
     */
    fun buildTerrainMesh(terrain: TerrainGrid): MeshData {
        val n = terrain.gridSize
        val step = 256f / (n - 1)
        val out = ArrayList<Float>((n - 1) * (n - 1) * 6 * 8)

        fun h(x: Int, y: Int): Float {
            val cx = x.coerceIn(0, n - 1)
            val cy = y.coerceIn(0, n - 1)
            return terrain.heights[cy * n + cx]
        }

        fun normal(x: Int, y: Int): FloatArray {
            val dx = (h(x + 1, y) - h(x - 1, y)) / (2f * step)
            val dy = (h(x, y + 1) - h(x, y - 1)) / (2f * step)
            val nx = -dx
            val ny = -dy
            val nz = 1f
            val inv = 1f / sqrt(nx * nx + ny * ny + nz * nz)
            return floatArrayOf(nx * inv, ny * inv, nz * inv)
        }

        for (y in 0 until n - 1) {
            for (x in 0 until n - 1) {
                val x0 = x * step; val x1 = (x + 1) * step
                val y0 = y * step; val y1 = (y + 1) * step
                val z00 = h(x, y); val z10 = h(x + 1, y)
                val z01 = h(x, y + 1); val z11 = h(x + 1, y + 1)

                val n00 = normal(x, y); val n10 = normal(x + 1, y)
                val n01 = normal(x, y + 1); val n11 = normal(x + 1, y + 1)

                val u0 = x.toFloat() / (n - 1); val u1 = (x + 1f) / (n - 1)
                val v0 = y.toFloat() / (n - 1); val v1 = (y + 1f) / (n - 1)

                pushVert(out, x0, y0, z00, n00[0], n00[1], n00[2], u0, v0)
                pushVert(out, x1, y0, z10, n10[0], n10[1], n10[2], u1, v0)
                pushVert(out, x1, y1, z11, n11[0], n11[1], n11[2], u1, v1)

                pushVert(out, x0, y0, z00, n00[0], n00[1], n00[2], u0, v0)
                pushVert(out, x1, y1, z11, n11[0], n11[1], n11[2], u1, v1)
                pushVert(out, x0, y1, z01, n01[0], n01[1], n01[2], u0, v1)
            }
        }
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }

    fun buildWaterPlane(gridSubdiv: Int = 24): MeshData {
        val out = ArrayList<Float>(gridSubdiv * gridSubdiv * 6 * 8)
        val min = -128f
        val size = 512f
        val step = size / gridSubdiv
        for (y in 0 until gridSubdiv) {
            for (x in 0 until gridSubdiv) {
                val x0 = min + x * step; val x1 = x0 + step
                val y0 = min + y * step; val y1 = y0 + step
                pushQuad(
                    out,
                    floatArrayOf(x0, y0, 0f),
                    floatArrayOf(x1, y0, 0f),
                    floatArrayOf(x1, y1, 0f),
                    floatArrayOf(x0, y1, 0f),
                    0f, 0f, 1f
                )
            }
        }
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }

    /**
     * Unit 2D HUD quad in [0..1] x [0..1] for orthographic Avatar Attachment HUD Pass 2.
     */
    fun buildHudQuad(): MeshData {
        val out = ArrayList<Float>(6 * 8)
        pushVert(out, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)
        pushVert(out, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f)
        pushVert(out, 1f, 1f, 0f, 0f, 0f, 1f, 1f, 1f)

        pushVert(out, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)
        pushVert(out, 1f, 1f, 0f, 0f, 0f, 1f, 1f, 1f)
        pushVert(out, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f)
        return MeshData(toFloatBuffer(out), 6)
    }

    // --- Sculpted Organic Humanoid Classic Avatar Meshes (Centered at mPelvis = (0,0,0), +X forward, +Y left, +Z up) ---

    private data class LoftRing(
        val cx: Float,
        val cy: Float,
        val cz: Float,
        val rx: Float, // front-back half-depth (+X/-X)
        val ry: Float, // left-right half-width (+Y/-Y)
        val v: Float,
        val frontPinch: Float = 0f,
        val backPinch: Float = 0f
    )

    private fun pushLoftedTube(out: MutableList<Float>, rings: List<LoftRing>, segs: Int = 18, capBottom: Boolean = true, capTop: Boolean = true) {
        if (rings.size < 2) return

        fun ringPoint(r: LoftRing, angle: Float): FloatArray {
            val ca = cos(angle)
            val sa = sin(angle)
            val xMod = if (ca > 0f) r.rx * (1f + r.frontPinch * ca * ca) else r.rx * (1f + r.backPinch * ca * ca)
            val px = r.cx + ca * xMod
            val py = r.cy + sa * r.ry
            val pz = r.cz
            return floatArrayOf(px, py, pz)
        }

        val grid = Array(rings.size) { ri ->
            Array(segs) { si ->
                val ang = (2.0 * PI * si / segs).toFloat()
                ringPoint(rings[ri], ang)
            }
        }
        val norms = Array(rings.size) { ri ->
            Array(segs) { si ->
                val prevS = (si - 1 + segs) % segs
                val nextS = (si + 1) % segs
                val prevR = (ri - 1).coerceAtLeast(0)
                val nextR = (ri + 1).coerceAtMost(rings.lastIndex)

                val tx = grid[ri][nextS][0] - grid[ri][prevS][0]
                val ty = grid[ri][nextS][1] - grid[ri][prevS][1]
                val tz = grid[ri][nextS][2] - grid[ri][prevS][2]

                val bx = grid[nextR][si][0] - grid[prevR][si][0]
                val by = grid[nextR][si][1] - grid[prevR][si][1]
                val bz = grid[nextR][si][2] - grid[prevR][si][2]

                val nx = ty * bz - tz * by
                val ny = tz * bx - tx * bz
                val nz = tx * by - ty * bx
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
                floatArrayOf(nx / len, ny / len, nz / len)
            }
        }

        for (ri in 0 until rings.lastIndex) {
            val v0 = rings[ri].v
            val v1 = rings[ri + 1].v
            for (si in 0 until segs) {
                val sn = (si + 1) % segs
                val u0 = si.toFloat() / segs
                val u1 = (si + 1f) / segs

                val p00 = grid[ri][si]; val n00 = norms[ri][si]
                val p10 = grid[ri][sn]; val n10 = norms[ri][sn]
                val p11 = grid[ri + 1][sn]; val n11 = norms[ri + 1][sn]
                val p01 = grid[ri + 1][si]; val n01 = norms[ri + 1][si]

                pushVert(out, p00[0], p00[1], p00[2], n00[0], n00[1], n00[2], u0, v0)
                pushVert(out, p10[0], p10[1], p10[2], n10[0], n10[1], n10[2], u1, v0)
                pushVert(out, p11[0], p11[1], p11[2], n11[0], n11[1], n11[2], u1, v1)

                pushVert(out, p00[0], p00[1], p00[2], n00[0], n00[1], n00[2], u0, v0)
                pushVert(out, p11[0], p11[1], p11[2], n11[0], n11[1], n11[2], u1, v1)
                pushVert(out, p01[0], p01[1], p01[2], n01[0], n01[1], n01[2], u0, v1)
            }
        }

        if (capBottom) {
            val r0 = rings.first()
            for (si in 0 until segs) {
                val sn = (si + 1) % segs
                val p0 = grid[0][si]; val p1 = grid[0][sn]
                pushVert(out, r0.cx, r0.cy, r0.cz, 0f, 0f, -1f, 0.5f, r0.v)
                pushVert(out, p1[0], p1[1], p1[2], 0f, 0f, -1f, 0.5f, r0.v)
                pushVert(out, p0[0], p0[1], p0[2], 0f, 0f, -1f, 0.5f, r0.v)
            }
        }
        if (capTop) {
            val rl = rings.last()
            val li = rings.lastIndex
            for (si in 0 until segs) {
                val sn = (si + 1) % segs
                val p0 = grid[li][si]; val p1 = grid[li][sn]
                pushVert(out, rl.cx, rl.cy, rl.cz, 0f, 0f, 1f, 0.5f, rl.v)
                pushVert(out, p0[0], p0[1], p0[2], 0f, 0f, 1f, 0.5f, rl.v)
                pushVert(out, p1[0], p1[1], p1[2], 0f, 0f, 1f, 0.5f, rl.v)
            }
        }
    }

    /**
     * Sculpted organic classic SL Lower Body (pelvis, hips, contoured thighs, knees, calves, ankles, feet)
     * centered at mPelvis = (0, 0, 0), feet resting at z = -0.91m.
     */
    fun buildAvatarLowerMesh(): MeshData {
        val out = ArrayList<Float>(1600 * 8)
        // Pelvis & hips contour
        val pelvis = listOf(
            LoftRing(-0.01f, 0f, -0.14f, 0.095f, 0.145f, 0.55f, backPinch = 0.12f),
            LoftRing(-0.008f, 0f, -0.06f, 0.115f, 0.180f, 0.68f, backPinch = 0.16f),
            LoftRing(0.0f, 0f, 0.02f, 0.112f, 0.172f, 0.82f, backPinch = 0.08f),
            LoftRing(0.005f, 0f, 0.09f, 0.104f, 0.148f, 0.96f)
        )
        pushLoftedTube(out, pelvis, segs = 18)

        // Left (+Y) & Right (-Y) contoured legs & sculpted feet
        for (side in floatArrayOf(1f, -1f)) {
            val leg = listOf(
                LoftRing(0.075f, side * 0.095f, -0.91f, 0.095f, 0.044f, 0.02f, frontPinch = 0.20f), // Sole
                LoftRing(0.055f, side * 0.095f, -0.87f, 0.085f, 0.042f, 0.06f, frontPinch = 0.15f), // Instep
                LoftRing(0.005f, side * 0.095f, -0.82f, 0.044f, 0.038f, 0.12f),                     // Ankle
                LoftRing(-0.012f, side * 0.096f, -0.66f, 0.056f, 0.050f, 0.25f, backPinch = 0.16f), // Calf
                LoftRing(0.004f, side * 0.098f, -0.49f, 0.058f, 0.054f, 0.38f, frontPinch = 0.08f), // Knee
                LoftRing(0.002f, side * 0.100f, -0.30f, 0.078f, 0.072f, 0.50f, frontPinch = 0.06f), // Mid-thigh
                LoftRing(-0.004f, side * 0.098f, -0.10f, 0.092f, 0.086f, 0.62f, backPinch = 0.10f)  // Hip socket
            )
            pushLoftedTube(out, leg, segs = 14)
        }
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }

    /**
     * Sculpted organic classic SL Upper Body (waist, ribcage, chest, sloped shoulders, neck,
     * naturally relaxed tapered arms, elbows, forearms, and hands) centered at mPelvis = (0, 0, 0).
     */
    fun buildAvatarUpperMesh(): MeshData {
        val out = ArrayList<Float>(1800 * 8)
        // Contoured torso from waist through chest/shoulders to neck
        val torso = listOf(
            LoftRing(0.005f, 0f, 0.06f, 0.102f, 0.145f, 0.10f),                                   // Waist
            LoftRing(0.010f, 0f, 0.17f, 0.112f, 0.158f, 0.28f, frontPinch = 0.06f),               // Mid-ribcage
            LoftRing(0.018f, 0f, 0.30f, 0.128f, 0.192f, 0.52f, frontPinch = 0.14f),               // Chest
            LoftRing(0.008f, 0f, 0.41f, 0.112f, 0.215f, 0.72f),                                   // Clavicle/Shoulders
            LoftRing(0.000f, 0f, 0.47f, 0.076f, 0.120f, 0.84f),                                   // Trapezius slope
            LoftRing(0.002f, 0f, 0.51f, 0.056f, 0.058f, 0.91f),                                   // Lower neck
            LoftRing(0.004f, 0f, 0.57f, 0.052f, 0.054f, 0.98f)                                    // Upper neck
        )
        pushLoftedTube(out, torso, segs = 18)

        // Left (+Y) & Right (-Y) naturally relaxed tapered arms + sculpted hands
        for (side in floatArrayOf(1f, -1f)) {
            val arm = listOf(
                LoftRing(0.048f, side * 0.280f, -0.14f, 0.036f, 0.020f, 0.04f), // Fingertips
                LoftRing(0.042f, side * 0.278f, -0.07f, 0.044f, 0.026f, 0.12f), // Palm
                LoftRing(0.028f, side * 0.274f, -0.01f, 0.030f, 0.028f, 0.22f), // Wrist
                LoftRing(0.010f, side * 0.264f, 0.11f, 0.041f, 0.039f, 0.44f),  // Forearm
                LoftRing(-0.006f, side * 0.246f, 0.20f, 0.044f, 0.042f, 0.60f), // Elbow
                LoftRing(-0.002f, side * 0.226f, 0.31f, 0.052f, 0.050f, 0.78f), // Bicep
                LoftRing(0.004f, side * 0.198f, 0.41f, 0.062f, 0.062f, 0.94f)   // Deltoid cap
            )
            pushLoftedTube(out, arm, segs = 12)
        }
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }

    /**
     * Sculpted organic anatomical Head (jaw, chin, cheekbones, nose-bridge contour, brow, cranium)
     * centered relative to mPelvis = (0, 0, 0), spanning z = 0.53m..0.81m.
     */
    fun buildAvatarHeadMesh(): MeshData {
        val out = ArrayList<Float>(900 * 8)
        val head = listOf(
            LoftRing(0.030f, 0f, 0.530f, 0.052f, 0.044f, 0.12f, frontPinch = 0.16f), // Chin point
            LoftRing(0.022f, 0f, 0.562f, 0.080f, 0.068f, 0.26f, frontPinch = 0.12f), // Jawline
            LoftRing(0.016f, 0f, 0.602f, 0.098f, 0.084f, 0.42f, frontPinch = 0.14f), // Mouth / cheeks
            LoftRing(0.014f, 0f, 0.642f, 0.106f, 0.090f, 0.56f, frontPinch = 0.18f), // Nose bridge / cheekbones
            LoftRing(0.006f, 0f, 0.684f, 0.110f, 0.093f, 0.70f, frontPinch = 0.06f), // Brow / temples
            LoftRing(-0.004f, 0f, 0.734f, 0.104f, 0.090f, 0.84f),                    // Upper cranium
            LoftRing(-0.008f, 0f, 0.780f, 0.072f, 0.066f, 0.94f),                    // Crown taper
            LoftRing(-0.008f, 0f, 0.806f, 0.024f, 0.022f, 0.99f)                     // Crown top
        )
        pushLoftedTube(out, head, segs = 20)
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }

    /**
     * Sculpted hair silhouette hugging the cranium and nape for classic avatars with baked hair textures.
     */
    fun buildAvatarHairCapMesh(): MeshData {
        val out = ArrayList<Float>(600 * 8)
        val hair = listOf(
            LoftRing(-0.040f, 0f, 0.575f, 0.088f, 0.096f, 0.20f, frontPinch = -0.35f), // Nape sweep
            LoftRing(-0.016f, 0f, 0.640f, 0.106f, 0.104f, 0.45f, frontPinch = -0.18f), // Sides
            LoftRing(-0.004f, 0f, 0.705f, 0.120f, 0.106f, 0.70f),                      // Crown volume
            LoftRing(-0.008f, 0f, 0.768f, 0.098f, 0.088f, 0.88f),                      // Upper crown
            LoftRing(-0.010f, 0f, 0.816f, 0.042f, 0.038f, 0.98f)                       // Top
        )
        pushLoftedTube(out, hair, segs = 18)
        return MeshData(toFloatBuffer(out), out.size / MeshData.FLOATS_PER_VERTEX)
    }
}
