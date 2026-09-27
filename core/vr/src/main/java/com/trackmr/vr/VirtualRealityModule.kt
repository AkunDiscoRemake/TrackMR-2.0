package com.trackmr.vr

import android.opengl.GLES30
import com.trackmr.xr.Renderable
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrMode
import com.trackmr.xr.XrModule
import com.trackmr.xr.gl.DynamicGeometry
import com.trackmr.xr.render.EyeContext
import com.trackmr.xr.render.Gfx
import com.trackmr.xr.render.RenderPass

/**
 * VR mode foundation: a luminous floor grid anchored to the estimated floor so users keep
 * spatial grounding in fully virtual spaces (drawn over any 360° environment, very cheap:
 * a single line batch). Also resets lighting to studio defaults when entering VR.
 */
class VirtualRealityModule : XrModule, Renderable {
    override val id = "vr"
    override val passes = Renderable.mask(RenderPass.TRANSPARENT)
    private lateinit var ctx: XrContext
    private var grid: DynamicGeometry? = null
    private var builtFloor = Float.NaN
    @Volatile var showGrid = true

    override val visible: Boolean get() = ctx.settings.mode == XrMode.VR && showGrid

    override fun onAttach(ctx: XrContext) { this.ctx = ctx; ctx.scene.add(this) }

    override fun onGlReady(ctx: XrContext) { grid = DynamicGeometry(1024) }

    override fun onModeChanged(ctx: XrContext, mode: XrMode) {
        if (mode == XrMode.VR) {
            ctx.lighting.ambient.set(0.45f, 0.42f, 0.55f)
            ctx.lighting.lightColor.set(1f, 0.95f, 0.9f)
        }
    }

    fun floorY(): Float {
        val f = ctx.tracking.floorY
        return if (f.isNaN()) ctx.headPose.p.y - 1.55f else f
    }

    override fun render(eye: EyeContext, pass: RenderPass) {
        val g = grid ?: return
        val fy = floorY()
        if (builtFloor.isNaN() || kotlin.math.abs(builtFloor - fy) > 0.01f) {
            builtFloor = fy
            g.begin()
            val n = 20; val step = 0.5f
            for (i in -n..n) {
                val a = 0.35f * (1f - kotlin.math.abs(i).toFloat() / n)
                val x = i * step
                g.vertex(x, fy, -n * step, 0.45f, 0.3f, 1f, 0f); g.vertex(x, fy, 0f, 0.55f, 0.35f, 1f, a)
                g.vertex(x, fy, 0f, 0.55f, 0.35f, 1f, a); g.vertex(x, fy, n * step, 0.45f, 0.3f, 1f, 0f)
                g.vertex(-n * step, fy, x, 0.2f, 0.8f, 1f, 0f); g.vertex(0f, fy, x, 0.2f, 0.8f, 1f, a)
                g.vertex(0f, fy, x, 0.2f, 0.8f, 1f, a); g.vertex(n * step, fy, x, 0.2f, 0.8f, 1f, 0f)
            }
            g.upload()
        }
        val gfx = Gfx.get
        gfx.color.use()
        gfx.color.mat4("uViewProj", eye.viewProj)
        gfx.color.i1("uRound", 0)
        gfx.color.i1("uAdditive", 1)
        g.draw(GLES30.GL_LINES)
    }
}
