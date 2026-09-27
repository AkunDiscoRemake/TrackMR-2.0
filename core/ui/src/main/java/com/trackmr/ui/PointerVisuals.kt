package com.trackmr.ui

import android.opengl.GLES30
import com.trackmr.xr.Renderable
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrModule
import com.trackmr.xr.gl.DynamicGeometry
import com.trackmr.xr.input.Pointer
import com.trackmr.xr.input.PointerSource
import com.trackmr.xr.math.Vec3
import com.trackmr.xr.render.EyeContext
import com.trackmr.xr.render.Gfx
import com.trackmr.xr.render.RenderPass

/**
 * Pointer feedback: gradient rays from the hands, a cursor that tightens as the pinch
 * closes, and a gaze reticle for Cardboard-button use. One line + one point batch.
 */
class PointerVisuals : XrModule, Renderable {
    override val id = "pointers"
    override val passes = Renderable.mask(RenderPass.OVERLAY)
    private lateinit var ctx: XrContext
    private var lines: DynamicGeometry? = null
    private var points: DynamicGeometry? = null
    private val end = Vec3()
    private val a = Vec3()

    override fun onAttach(ctx: XrContext) { this.ctx = ctx; ctx.scene.add(this) }
    override fun onGlReady(ctx: XrContext) { lines = DynamicGeometry(64); points = DynamicGeometry(16) }

    override fun onFrame(ctx: XrContext, dt: Float) {
        val l = lines ?: return
        val p = points ?: return
        l.begin(); p.begin()
        for (ptr in ctx.input.pointers) build(ptr, l, p)
        l.upload(); p.upload()
    }

    private fun build(ptr: Pointer, l: DynamicGeometry, p: DynamicGeometry) {
        if (!ptr.active) return
        val gaze = ptr.source == PointerSource.GAZE
        val r: Float; val g: Float; val b: Float
        when (ptr.source) {
            PointerSource.HAND_LEFT -> { r = 0.2f; g = 0.8f; b = 1f }
            PointerSource.HAND_RIGHT -> { r = 1f; g = 0.4f; b = 0.5f }
            PointerSource.GAZE -> { r = 1f; g = 1f; b = 1f }
        }
        val len = if (ptr.hasHit) ptr.hit.distance else if (gaze) 2f else 0.35f
        ptr.ray.pointAt(len, end)
        if (!gaze && !ptr.direct) {
            // Ray fades from transparent at the hand to bright near the target.
            ptr.ray.pointAt(0.06f, a)
            l.vertex(a.x, a.y, a.z, r, g, b, 0f)
            l.vertex(end.x, end.y, end.z, r, g, b, if (ptr.hasHit) 0.85f else 0.25f)
        }
        if (ptr.hasHit || gaze) {
            val s = ptr.strength
            val alpha = if (ptr.hasHit) 1f else 0.5f
            // Slightly in front of the surface to avoid z-fighting.
            if (ptr.hasHit) end.addScaled(ptr.hit.normal, 0.004f)
            p.vertex(end.x, end.y, end.z, r * (1 - s) + s, g * (1 - s) + s, b * (1 - s) + s, alpha)
        }
    }

    override fun render(eye: EyeContext, pass: RenderPass) {
        val c = Gfx.get.color
        c.use()
        c.mat4("uViewProj", eye.viewProj)
        c.i1("uAdditive", 0)
        c.i1("uRound", 0)
        lines?.draw(GLES30.GL_LINES)
        c.i1("uRound", 1)
        c.f1("uPointSize", 0.012f)
        c.f1("uPointScale", eye.pixelsPerTan)
        points?.draw(GLES30.GL_POINTS)
    }
}
