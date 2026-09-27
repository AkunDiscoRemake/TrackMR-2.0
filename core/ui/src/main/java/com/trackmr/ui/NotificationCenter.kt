package com.trackmr.ui

import android.graphics.Paint
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrModule
import com.trackmr.xr.math.MathUtil
import com.trackmr.xr.math.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.atan2

/**
 * Spatial toasts: short messages from any thread via `ctx.notify(text)`, shown on a small
 * glass pill that lazily floats at the top of the field of view.
 */
class NotificationCenter : XrModule {
    override val id = "notifications"
    private lateinit var ctx: XrContext
    private val queue = ConcurrentLinkedQueue<String>()
    private lateinit var panel: SpatialPanel
    private lateinit var label: UiLabel
    private var remaining = 0f
    private val target = Vec3()
    private val fwd = Vec3()
    private val tmp = Vec3()
    private val history = ArrayDeque<String>()

    val recent: List<String> get() = history.toList()

    override fun onAttach(ctx: XrContext) {
        this.ctx = ctx
        panel = SpatialPanel(0.56f, 0.075f, 900f).apply {
            priority = 20
            interactive = false
            cornerPx = 34f
            glassColor = UiTheme.withAlpha(UiTheme.violetDeep, 230)
        }
        label = panel.root.add(UiLabel("", 26f, UiTheme.textPrimary, Paint.Align.CENTER, bold = true, maxLines = 2))
        label.at(20f, 4f, panel.pixelW - 40f, panel.pixelH - 8f)
        panel.hide(false)
        ctx.scene.add(panel)
        ctx.addNoticeListener { queue.add(it) }
    }

    override fun onFrame(ctx: XrContext, dt: Float) {
        if (remaining > 0f) {
            remaining -= dt
            if (remaining <= 0f) panel.hide()
        }
        if (remaining <= 0.25f) {
            val next = queue.poll()
            if (next != null) {
                label.text = next
                panel.invalidate()
                history.addFirst(next); while (history.size > 20) history.removeLast()
                remaining = (2.2f + next.length * 0.04f).coerceAtMost(6f)
                if (panel.hidden) snap()
                panel.show()
            }
        }
        if (!panel.hidden) follow(dt)
    }

    private fun computeTarget() {
        val head = ctx.headPose
        head.forward(fwd)
        target.set(head.p).addScaled(fwd, 0.9f)
        target.y += 0.22f
    }

    private fun snap() { computeTarget(); panel.pose.p.set(target); face() }

    private fun follow(dt: Float) {
        computeTarget()
        panel.pose.p.lerp(target, MathUtil.dampFactor(5f, dt))
        face()
    }

    private fun face() {
        tmp.setSub(ctx.headPose.p, panel.pose.p)
        panel.pose.q.setAxisAngle(0f, 1f, 0f, atan2(tmp.x, tmp.z))
    }
}
