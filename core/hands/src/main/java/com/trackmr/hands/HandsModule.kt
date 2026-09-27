package com.trackmr.hands

import android.opengl.GLES30
import com.trackmr.xr.Renderable
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrModule
import com.trackmr.xr.gl.DynamicGeometry
import com.trackmr.xr.input.HandJoint
import com.trackmr.xr.input.HandState
import com.trackmr.xr.input.Handedness
import com.trackmr.xr.render.EyeContext
import com.trackmr.xr.render.Gfx
import com.trackmr.xr.render.RenderPass

/**
 * Hand tracking module: owns the MediaPipe tracker (unless another provider such as
 * OpenXR XR_EXT_hand_tracking was installed first) and renders an ultra-thin skeleton:
 * one line batch (40 vertices per hand) + one point batch for joints — two draw calls total.
 */
class HandsModule : XrModule, Renderable {
    override val id = "hands"
    override val passes = Renderable.mask(RenderPass.OVERLAY)
    private lateinit var ctx: XrContext
    private var lines: DynamicGeometry? = null
    private var points: DynamicGeometry? = null
    private var tracker: MediaPipeHandTracker? = null

    override val visible: Boolean get() = ctx.settings.handSkeleton

    override fun onAttach(ctx: XrContext) {
        this.ctx = ctx
        if (ctx.handProvider == null) {
            tracker = MediaPipeHandTracker(ctx.activity.applicationContext, ctx.settings)
            ctx.handProvider = tracker
        }
        ctx.scene.add(this)
    }

    override fun onGlReady(ctx: XrContext) {
        lines = DynamicGeometry(2 * HandJoint.BONES.size)
        points = DynamicGeometry(2 * (HandJoint.COUNT + 1))
    }

    override fun onFrame(ctx: XrContext, dt: Float) {
        val l = lines ?: return
        val p = points ?: return
        l.begin(); p.begin()
        build(ctx.hands.left, l, p)
        build(ctx.hands.right, l, p)
        l.upload(); p.upload()
    }

    private fun build(h: HandState, l: DynamicGeometry, p: DynamicGeometry) {
        if (h.presence <= 0.01f) return
        val a = h.presence
        // Joy-Con inspired identity: left = neon blue, right = neon red.
        val r: Float; val g: Float; val b: Float
        if (h.side == Handedness.LEFT) { r = 0.1f; g = 0.78f; b = 1f } else { r = 1f; g = 0.32f; b = 0.36f }
        val bones = HandJoint.BONES
        var i = 0
        while (i < bones.size) {
            val j0 = h.joints[bones[i]]; val j1 = h.joints[bones[i + 1]]
            l.vertex(j0.x, j0.y, j0.z, r, g, b, 0.85f * a)
            l.vertex(j1.x, j1.y, j1.z, r, g, b, 0.85f * a)
            i += 2
        }
        for (k in 0 until HandJoint.COUNT) {
            val j = h.joints[k]
            val tip = k == 4 || k == 8 || k == 12 || k == 16 || k == 20
            val s = if (tip) 1f else 0.75f
            p.vertex(j.x, j.y, j.z, r * s + (1 - s), g * s + (1 - s), b * s + (1 - s), a)
        }
        // Pinch glow grows with pinch strength.
        val ps = h.pinchStrength
        if (ps > 0.2f) p.vertex(h.pinchPoint.x, h.pinchPoint.y, h.pinchPoint.z, 1f, 1f, 1f, ps * a * (if (h.isPinching) 1f else 0.5f))
    }

    override fun render(eye: EyeContext, pass: RenderPass) {
        val gfx = Gfx.get
        val c = gfx.color
        c.use()
        c.mat4("uViewProj", eye.viewProj)
        c.i1("uAdditive", 0)
        c.i1("uRound", 0)
        lines?.draw(GLES30.GL_LINES)
        c.i1("uRound", 1)
        // ~7 mm joints at arm's length; size scales with the eye's pixel density.
        c.f1("uPointSize", 0.0075f)
        c.f1("uPointScale", eye.pixelsPerTan)
        points?.draw(GLES30.GL_POINTS)
    }

    override fun onDestroy(ctx: XrContext) { tracker?.release() }
}
