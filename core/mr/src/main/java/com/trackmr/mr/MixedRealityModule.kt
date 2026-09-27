package com.trackmr.mr

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.trackmr.xr.Renderable
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrMode
import com.trackmr.xr.XrModule
import com.trackmr.xr.gl.DynamicGeometry
import com.trackmr.xr.gl.GlUtil
import com.trackmr.xr.math.MathUtil
import com.trackmr.xr.render.EyeContext
import com.trackmr.xr.render.Gfx
import com.trackmr.xr.render.RenderPass
import com.trackmr.xr.tracking.TrackedPlane
import java.nio.FloatBuffer

/**
 * Mixed Reality compositor: draws the passthrough camera behind all virtual content, maps
 * ARCore light estimation onto the virtual scene lighting, and optionally visualizes planes.
 * MR is the default TrackMR mode — this module renders from the very first frame.
 */
class MixedRealityModule : XrModule, Renderable {
    override val id = "mr"
    override val passes = Renderable.mask(RenderPass.BACKGROUND, RenderPass.TRANSPARENT)
    private lateinit var ctx: XrContext
    private var vao = IntArray(1)
    private var vbo = IntArray(1)
    private lateinit var verts: FloatBuffer
    private var planeGeo: DynamicGeometry? = null
    /** 0..1 dimming used by cinema "dark room" mode (1 = full passthrough). */
    @Volatile var passthroughOpacity = 1f
    private var currentOpacity = 1f

    override val visible: Boolean get() = ctx.settings.mode == XrMode.MR

    override fun onAttach(ctx: XrContext) { this.ctx = ctx; ctx.scene.add(this) }

    override fun onGlReady(ctx: XrContext) {
        verts = GlUtil.floatBuffer(4 * 5)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4 * 5 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glBindVertexArray(0)
        planeGeo = DynamicGeometry(4096)
    }

    override fun onFrame(ctx: XrContext, dt: Float) {
        currentOpacity = MathUtil.damp(currentOpacity, passthroughOpacity, 6f, dt)
        val t = ctx.tracking
        if (!t.backgroundValid) return
        // Upload background quad (corners in head space + camera UVs).
        verts.position(0)
        for (i in 0..3) {
            verts.put(t.backgroundCorners[i * 3]); verts.put(t.backgroundCorners[i * 3 + 1]); verts.put(t.backgroundCorners[i * 3 + 2])
            verts.put(t.backgroundUvs[i * 2]); verts.put(t.backgroundUvs[i * 2 + 1])
        }
        verts.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 80, verts)

        if (ctx.settings.mode == XrMode.MR) applyLightEstimate(ctx)
    }

    /** Maps ARCore ambient intensity / color correction onto virtual lighting. */
    private fun applyLightEstimate(ctx: XrContext) {
        val t = ctx.tracking
        val l = ctx.lighting
        if (!t.lightValid) return
        val k = (t.ambientIntensity / 0.466f).coerceIn(0.25f, 1.6f) // 0.466 ≈ ARCore mid-grey
        val cc = t.colorCorrection
        l.ambient.set(0.38f * k * cc[0], 0.38f * k * cc[1], 0.42f * k * cc[2])
        l.lightColor.set(0.95f * k * cc[0], 0.93f * k * cc[1], 0.9f * k * cc[2])
    }

    override fun render(eye: EyeContext, pass: RenderPass) {
        if (pass == RenderPass.BACKGROUND) renderPassthrough(eye) else if (ctx.settings.planeVisualization) renderPlanes(eye)
    }

    private fun renderPassthrough(eye: EyeContext) {
        val t = ctx.tracking
        if (!t.backgroundValid || t.cameraTextureId == 0 || currentOpacity < 0.01f) return
        val g = Gfx.get
        g.camera.use()
        // Background is head-locked (no IPD translation): identical in both eyes, at infinity.
        g.camera.mat4("uProj", eye.proj)
        g.camera.f1("uBrightness", ctx.settings.passthroughBrightness * currentOpacity)
        g.camera.f3("uTint", 1f, 1f, 1f)
        g.camera.f1("uVignette", 0.03f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t.cameraTextureId)
        g.camera.i1("uTex", 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    private val pv = com.trackmr.xr.math.Vec3()
    private val pw = com.trackmr.xr.math.Vec3()

    /** Thin animated outlines of detected planes (debug / placement feedback). */
    private fun renderPlanes(eye: EyeContext) {
        val geo = planeGeo ?: return
        if (eye.eyeIndex == 0) {
            geo.begin()
            for (p in ctx.tracking.planes) {
                val n = p.polygonSize / 2
                if (n < 3) continue
                val (r, g, b) = when (p.type) {
                    TrackedPlane.Type.HORIZONTAL_UP -> Triple(0.3f, 0.9f, 1f)
                    TrackedPlane.Type.VERTICAL -> Triple(1f, 0.4f, 0.9f)
                    else -> Triple(1f, 0.8f, 0.3f)
                }
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    pv.set(p.polygon[i * 2], 0f, p.polygon[i * 2 + 1]); p.centerPose.transformPoint(pv, pw)
                    geo.vertex(pw.x, pw.y, pw.z, r, g, b, 0.8f)
                    pv.set(p.polygon[j * 2], 0f, p.polygon[j * 2 + 1]); p.centerPose.transformPoint(pv, pw)
                    geo.vertex(pw.x, pw.y, pw.z, r, g, b, 0.8f)
                }
            }
            geo.upload()
        }
        val g = Gfx.get
        g.color.use()
        g.color.mat4("uViewProj", eye.viewProj)
        g.color.i1("uRound", 0)
        g.color.i1("uAdditive", 0)
        geo.draw(GLES30.GL_LINES)
    }
}
