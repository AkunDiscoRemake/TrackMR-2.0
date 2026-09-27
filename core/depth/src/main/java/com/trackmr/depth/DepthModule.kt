package com.trackmr.depth

import android.opengl.GLES30
import com.trackmr.xr.Renderable
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrMode
import com.trackmr.xr.XrModule
import com.trackmr.xr.gl.DynamicGeometry
import com.trackmr.xr.input.HandJoint
import com.trackmr.xr.input.HandState
import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Ray
import com.trackmr.xr.math.Vec3
import com.trackmr.xr.render.EyeContext
import com.trackmr.xr.render.Gfx
import com.trackmr.xr.render.RenderPass
import com.trackmr.xr.tracking.DepthFrame
import com.trackmr.xr.tracking.TrackedPlane
import java.nio.ByteOrder

/**
 * Depth for mixed reality.
 *
 *  - Environment depth (ARCore Depth API when supported) → RG8 texture sampled by every
 *    object shader for per-pixel occlusion (see Shaders.OCCLUSION_FS).
 *  - Hand depth: the tracked hands are rasterized depth-only (palm fan + finger strips) so
 *    virtual objects behind the user's real hands disappear, even without a depth sensor.
 *  - Fallback: detected ARCore planes are rendered depth-only as occluders (walls, tables).
 *  - Queries: distance along a ray / collision against the real environment for physics.
 */
class DepthModule : XrModule, Renderable {
    override val id = "depth"
    override val passes = Renderable.mask(RenderPass.OCCLUDERS)
    private lateinit var ctx: XrContext

    private var depthTex = 0
    private var texW = 0
    private var texH = 0
    private var uploadedSeq = -1L
    private var occluders: DynamicGeometry? = null

    // CPU copy of the latest depth for queries (distance estimation, collisions).
    private var cpuDepth: ShortArray = ShortArray(0)
    private var cpuW = 0
    private var cpuH = 0
    private val cpuPose = Pose()
    private val cpuIntr = FloatArray(4)
    @Volatile var hasEnvironmentDepth = false; private set

    private val tmp = Vec3()
    private val tmp2 = Vec3()
    private val side = Vec3()
    private val a = Vec3()
    private val b = Vec3()

    override val visible: Boolean get() = ctx.settings.mode == XrMode.MR && ctx.settings.occlusion

    override fun onAttach(ctx: XrContext) {
        this.ctx = ctx
        ctx.scene.add(this)
        ctx.register(DepthModule::class.java, this)
    }

    override fun onGlReady(ctx: XrContext) { occluders = DynamicGeometry(4096) }

    override fun onFrame(ctx: XrContext, dt: Float) {
        val d = ctx.tracking.depth
        val occ = ctx.occlusion
        if (d.valid && ctx.settings.occlusion) {
            if (d.sequence != uploadedSeq) { upload(d); uploadedSeq = d.sequence }
            occ.enabled = true
            occ.depthTexture = depthTex
            // world → depth camera (view matrix of the physical camera at depth capture)
            d.cameraPose.toViewMatrix(occ.worldToDepthCamera)
            System.arraycopy(d.intrinsics, 0, occ.intrinsics, 0, 4)
            hasEnvironmentDepth = true
        } else {
            occ.enabled = false
            hasEnvironmentDepth = false
        }
        buildOccluders()
    }

    private fun upload(d: DepthFrame) {
        val buf = d.buffer ?: return
        if (depthTex == 0 || texW != d.width || texH != d.height) {
            if (depthTex != 0) GLES30.glDeleteTextures(1, intArrayOf(depthTex), 0)
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            depthTex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTex)
            // NEAREST: high/low bytes must never be interpolated.
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, d.width, d.height, 0, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, null)
            texW = d.width; texH = d.height
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, d.rowStride / 2)
        buf.position(0)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, d.width, d.height, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)

        // CPU copy (tightly packed shorts) for queries.
        val n = d.width * d.height
        if (cpuDepth.size != n) cpuDepth = ShortArray(n)
        val sb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until d.height) {
            val row = y * d.rowStride
            for (x in 0 until d.width) cpuDepth[y * d.width + x] = sb.getShort(row + x * 2)
        }
        cpuW = d.width; cpuH = d.height
        cpuPose.set(d.cameraPose)
        System.arraycopy(d.intrinsics, 0, cpuIntr, 0, 4)
    }

    /** Real surface depth (meters from the physical camera) along the camera ray through [world], or NaN. */
    fun realDepthAt(world: Vec3): Float {
        if (!hasEnvironmentDepth || cpuW == 0) return Float.NaN
        cpuPose.inverseTransformPoint(world, tmp)
        val z = -tmp.z
        if (z <= 0.05f) return Float.NaN
        val u = cpuIntr[0] * (tmp.x / z) + cpuIntr[2]
        val v = cpuIntr[3] - cpuIntr[1] * (tmp.y / z)
        if (u < 0f || v < 0f || u >= 1f || v >= 1f) return Float.NaN
        val mm = cpuDepth[(v * cpuH).toInt() * cpuW + (u * cpuW).toInt()].toInt() and 0xFFFF
        return if (mm == 0) Float.NaN else mm / 1000f
    }

    /** Signed distance of [world] in front of the real surface along the camera ray (negative = inside). */
    fun penetration(world: Vec3): Float {
        val real = realDepthAt(world)
        if (real.isNaN()) return Float.NaN
        cpuPose.inverseTransformPoint(world, tmp)
        return real - (-tmp.z)
    }

    /**
     * Distance estimation along a world ray: environment depth when available, then planes,
     * then the tracking provider's hit test (instant placement / synthetic floor).
     */
    fun raycastEnvironment(ray: Ray, maxDist: Float = 8f): Float {
        if (hasEnvironmentDepth) {
            var t = 0.1f
            while (t < maxDist) {
                ray.pointAt(t, tmp2)
                val pen = penetration(tmp2)
                if (!pen.isNaN() && pen < 0f) return t
                t += 0.03f + t * 0.03f
            }
        }
        var best = Float.NaN
        for (p in ctx.tracking.planes) {
            val tt = com.trackmr.xr.math.MathUtil.rayPlane(ray.origin, ray.dir, p.centerPose.p, p.normal)
            if (!tt.isNaN() && tt < maxDist && (best.isNaN() || tt < best)) {
                ray.pointAt(tt, tmp2)
                p.centerPose.inverseTransformPoint(tmp2, tmp)
                if (kotlin.math.abs(tmp.x) <= p.extentX / 2 && kotlin.math.abs(tmp.z) <= p.extentZ / 2) best = tt
            }
        }
        if (!best.isNaN()) return best
        val hr = com.trackmr.xr.tracking.HitResult()
        return if (ctx.trackingProvider?.hitTest(ray, hr) == true) hr.distance else Float.NaN
    }

    // ---- Occluder geometry (hands + planes), depth-only ----

    private fun buildOccluders() {
        val g = occluders ?: return
        g.begin()
        if (ctx.settings.mode != XrMode.MR || !ctx.settings.occlusion) { g.upload(); return }
        addHand(g, ctx.hands.left)
        addHand(g, ctx.hands.right)
        if (!hasEnvironmentDepth) for (p in ctx.tracking.planes) addPlane(g, p)
        g.upload()
    }

    private fun addHand(g: DynamicGeometry, h: HandState) {
        if (!h.tracked || h.presence < 0.5f) return
        val j = h.joints
        // Palm fan: wrist, thumb CMC, index/middle/ring/pinky MCP
        val fan = intArrayOf(HandJoint.WRIST, HandJoint.THUMB_CMC, HandJoint.INDEX_MCP, HandJoint.MIDDLE_MCP, HandJoint.RING_MCP, HandJoint.PINKY_MCP)
        for (k in 1 until fan.size - 1) {
            tri(g, j[fan[0]], j[fan[k]], j[fan[k + 1]])
        }
        // Finger strips (camera-facing quads, ~1.8 cm wide)
        val head = ctx.headPose.p
        val bones = HandJoint.BONES
        var i = 0
        while (i < bones.size) {
            val p0 = j[bones[i]]; val p1 = j[bones[i + 1]]
            a.setSub(p1, p0)
            b.setSub(head, p0)
            side.setCross(a, b).normalize().scale(0.009f)
            quad(g, p0, p1)
            i += 2
        }
    }

    private val q0 = Vec3(); private val q1 = Vec3(); private val q2 = Vec3(); private val q3 = Vec3()

    private fun quad(g: DynamicGeometry, p0: Vec3, p1: Vec3) {
        q0.set(p0).sub(side); q1.set(p0).add(side); q2.set(p1).add(side); q3.set(p1).sub(side)
        tri(g, q0, q1, q2); tri(g, q0, q2, q3)
    }

    private fun tri(g: DynamicGeometry, x: Vec3, y: Vec3, z: Vec3) {
        g.vertex(x.x, x.y, x.z, 0f, 0f, 0f, 1f)
        g.vertex(y.x, y.y, y.z, 0f, 0f, 0f, 1f)
        g.vertex(z.x, z.y, z.z, 0f, 0f, 0f, 1f)
    }

    private val pp = Vec3(); private val pc = Vec3(); private val pn = Vec3()

    private fun addPlane(g: DynamicGeometry, p: TrackedPlane) {
        val n = p.polygonSize / 2
        if (n < 3) return
        // Slightly pushed back so virtual objects resting on the plane are not clipped.
        p.centerPose.transformPoint(0f, -0.01f, 0f, pc)
        for (i in 0 until n) {
            val k = (i + 1) % n
            p.centerPose.transformPoint(p.polygon[i * 2], -0.01f, p.polygon[i * 2 + 1], pp)
            p.centerPose.transformPoint(p.polygon[k * 2], -0.01f, p.polygon[k * 2 + 1], pn)
            tri(g, pc, pp, pn)
        }
    }

    override fun render(eye: EyeContext, pass: RenderPass) {
        val g = occluders ?: return
        if (g.count == 0) return
        val gfx = Gfx.get
        gfx.color.use()
        gfx.color.mat4("uViewProj", eye.viewProj)
        gfx.color.i1("uRound", 0)
        gfx.color.i1("uAdditive", 0)
        g.draw(GLES30.GL_TRIANGLES)
    }
}
