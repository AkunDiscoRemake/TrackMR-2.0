package com.trackmr.ui

import android.graphics.Canvas
import android.graphics.Paint
import com.trackmr.xr.XrContext
import com.trackmr.xr.input.PointerEvent
import com.trackmr.xr.input.PointerEventType
import com.trackmr.xr.math.MathUtil
import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Quat
import com.trackmr.xr.math.Vec3
import com.trackmr.xr.tracking.XrAnchor
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp

enum class WindowState { NORMAL, MINIMIZED, CLOSED }

/**
 * A spatial window: any [SpatialSurface] content plus a floating control bar providing
 * move (drag the title), resize (drag ⤡), curve, bring closer / push away, minimize,
 * close, pin to the world (ARCore anchor) or lazily follow the user.
 */
class XrWindow(
    val ctx: XrContext,
    title: String,
    val content: SpatialSurface,
    val icon: String = "🪟",
    val accent: Int = UiTheme.neonBlue,
) {
    val pose = Pose()
    var title = title
        set(v) { field = v; controls.titleLabel.text = v }
    var state = WindowState.NORMAL; private set
    var follow = false
        set(v) { field = v; controls.followBtn.icon = if (v) "🚶" else "📌"; controls.followBtn.invalidate(); if (v) releaseAnchor() else anchorHere() }
    var tag: Any? = null
    var onClose: (() -> Unit)? = null
    var onMinimize: ((Boolean) -> Unit)? = null
    var onResize: ((Float, Float) -> Unit)? = null
    var minScale = 0.4f
    var maxScale = 4f
    var resizable = true
    var keepAspect = true
    private var anchor: XrAnchor? = null
    private val anchorOffset = Pose()

    val controls = ControlBar(this)
    private val tmp = Vec3()
    private val tmp2 = Vec3()
    private val fwd = Vec3()

    /** Distance from the head when last placed (used by follow mode). */
    var followDistance = 1.4f

    init {
        content.show()
        controls.show()
    }

    val isVisible get() = state == WindowState.NORMAL

    fun attach() {
        ctx.scene.add(content); ctx.input.register(content)
        ctx.scene.add(controls); ctx.input.register(controls)
    }

    fun detach() {
        ctx.scene.remove(content); ctx.input.unregister(content)
        ctx.scene.remove(controls); ctx.input.unregister(controls)
        releaseAnchor()
    }

    fun minimize() {
        if (state != WindowState.NORMAL) return
        state = WindowState.MINIMIZED
        content.hide(); controls.hide()
        onMinimize?.invoke(true)
        UiAudio.close()
    }

    fun restore() {
        if (state != WindowState.MINIMIZED) return
        state = WindowState.NORMAL
        content.show(); controls.show()
        onMinimize?.invoke(false)
        UiAudio.open()
    }

    fun close() {
        if (state == WindowState.CLOSED) return
        state = WindowState.CLOSED
        content.hide(); controls.hide()
        onClose?.invoke()
        UiAudio.close()
    }

    /** Places the window [distance] m in front of the head (yaw only), at eye height offset [dy]. */
    fun placeInFront(distance: Float = 1.4f, yawOffsetRad: Float = 0f, dy: Float = -0.08f) {
        val head = ctx.headPose
        val yaw = head.q.yaw() + yawOffsetRad
        pose.p.set(head.p.x - kotlin.math.sin(yaw) * distance, head.p.y + dy, head.p.z - kotlin.math.cos(yaw) * distance)
        faceHead()
        followDistance = distance
        if (!follow) anchorHere()
        syncChildren()
    }

    fun faceHead() {
        tmp.setSub(ctx.headPose.p, pose.p)
        tmp.y = 0f
        if (tmp.lengthSq() < 1e-6f) return
        tmp.normalize()
        // Surface +Z toward the user.
        pose.q.setAxisAngle(0f, 1f, 0f, kotlin.math.atan2(tmp.x, tmp.z))
    }

    fun distanceToHead(): Float = pose.p.distance(ctx.headPose.p)

    /** Bring closer (<1) or push away (>1). */
    fun scaleDistance(factor: Float) {
        tmp.setSub(pose.p, ctx.headPose.p)
        val d = tmp.length()
        val nd = (d * factor).coerceIn(0.45f, 8f)
        tmp.normalize().scale(nd)
        pose.p.set(ctx.headPose.p).add(tmp)
        followDistance = nd
        if (content.radius > 0f) content.setCurvature(nd)
        anchorHere()
        syncChildren()
    }

    fun setSize(w: Float, h: Float) {
        content.resize(w, h)
        controls.layoutFor(w)
        onResize?.invoke(w, h)
        syncChildren()
    }

    /** Cycles flat → gentle → cinema-like curvature. */
    fun cycleCurvature() {
        val d = distanceToHead()
        val next = when {
            content.radius <= 0f -> d * 2.2f
            content.radius > d * 1.5f -> d
            else -> 0f
        }
        content.setCurvature(next)
        syncChildren()
    }

    private fun anchorHere() {
        releaseAnchor()
        val a = ctx.trackingProvider?.createAnchor(pose) ?: return
        anchor = a
        anchorOffset.identity()
    }

    private fun releaseAnchor() { anchor?.detach(); anchor = null }

    fun onFrame(dt: Float) {
        if (state != WindowState.NORMAL) return
        val a = anchor
        if (!follow && a != null && !controls.dragging && a.update()) {
            // World-locked through ARCore anchor (drift corrected by SLAM).
            pose.setCompose(a.pose, anchorOffset)
        }
        if (follow && !controls.dragging) lazyFollow(dt)
        syncChildren()
    }

    private fun lazyFollow(dt: Float) {
        val head = ctx.headPose
        head.forward(fwd); fwd.y = 0f; fwd.normalize()
        tmp.setSub(pose.p, head.p); tmp.y = 0f
        val dist = tmp.length().coerceAtLeast(0.01f)
        tmp.scale(1f / dist)
        val angle = acos(tmp.dot(fwd).coerceIn(-1f, 1f))
        if (angle > 0.5f || abs(dist - followDistance) > 0.25f) {
            val k = 1f - exp(-4f * dt)
            tmp2.set(head.p).addScaled(fwd, followDistance)
            tmp2.y = MathUtil.lerp(pose.p.y, head.p.y - 0.08f, k)
            pose.p.lerp(tmp2, k)
            faceHead()
        }
    }

    /** Positions content + control bar relative to the window pose. */
    fun syncChildren() {
        content.pose.set(pose)
        val h = content.heightM
        pose.transformPoint(0f, -h / 2f - controls.heightM / 2f - 0.02f, if (content.radius > 0f) 0.03f else 0.01f, controls.pose.p)
        controls.pose.q.set(pose.q)
        // Tilt the bar slightly toward the eyes.
        controls.pose.q.mul(tiltQ)
    }

    fun beginMove() { releaseAnchor() }
    fun endMove() { followDistance = distanceToHead(); if (!follow) anchorHere() }

    companion object {
        private val tiltQ = Quat().setAxisAngle(1f, 0f, 0f, -0.18f)
    }
}

/**
 * Floating window controls. Dragging the title moves the window along the pointer ray
 * (move the hand toward/away from the body to push/pull); dragging ⤡ resizes.
 */
class ControlBar(private val w: XrWindow) : SpatialPanel(0.62f, 0.075f, 900f) {
    val minimizeBtn = root.add(UiButton("", "➖", ButtonStyle.GHOST, textSize = 30f) { w.minimize() })
    val followBtn = root.add(UiButton("", "📌", ButtonStyle.GHOST, textSize = 30f) { w.follow = !w.follow })
    val curveBtn = root.add(UiButton("", "◠", ButtonStyle.GHOST, textSize = 34f) { w.cycleCurvature() })
    val closerBtn = root.add(UiButton("", "🔍", ButtonStyle.GHOST, textSize = 28f) { w.scaleDistance(0.8f) })
    val fartherBtn = root.add(UiButton("", "🔭", ButtonStyle.GHOST, textSize = 28f) { w.scaleDistance(1.25f) })
    val titleLabel = UiLabel(w.title, 26f, UiTheme.textPrimary, Paint.Align.CENTER, bold = true)
    val grab = root.add(object : UiNode() { override val interactive = true }.also { it.add(titleLabel) })
    val resizeBtn = root.add(UiButton("", "⤡", ButtonStyle.GHOST, textSize = 34f) { })
    val closeBtn = root.add(UiButton("", "✖", ButtonStyle.GHOST, textSize = 28f) { w.close() })

    var dragging = false; private set
    private var mode = 0 // 1 move, 2 resize
    private var grabDist = 1f
    private val offset = Vec3()
    private var handRef = 0f
    private var startW = 1f
    private var startH = 1f
    private var startSpan = 1f
    private val tmp = Vec3()

    init {
        priority = 1
        cornerPx = 30f
        layoutFor(w.content.widthM)
    }

    fun layoutFor(contentW: Float) {
        resize(contentW.coerceIn(0.62f, 1.1f), 0.075f)
        layout()
    }

    override fun layout() {
        val h = pixelH.toFloat(); val W = pixelW.toFloat()
        val bs = h - 12f
        var x = 8f
        for (b in listOf(minimizeBtn, followBtn, curveBtn, closerBtn, fartherBtn)) { b.at(x, 6f, bs, bs); x += bs + 4f }
        closeBtn.at(W - bs - 8f, 6f, bs, bs)
        resizeBtn.at(W - 2 * bs - 12f, 6f, bs, bs)
        grab.at(x + 6f, 6f, W - x - 2 * bs - 30f, bs)
        titleLabel.at(grab.bounds.left, 6f, grab.bounds.width(), bs)
        invalidate()
    }

    override fun drawBackground(c: Canvas) {
        super.drawBackground(c)
        // Grab pill hint under the title.
        val g = grab.bounds
        pill.color = UiTheme.withAlpha(android.graphics.Color.WHITE, if (grab.hovered || dragging) 200 else 70)
        c.drawRoundRect(g.centerX() - 40f, g.bottom - 7f, g.centerX() + 40f, g.bottom - 2f, 3f, 3f, pill)
    }

    private fun handDistance(e: PointerEvent): Float {
        val hand = e.pointer?.hand ?: return 0f
        if (!hand.tracked) return 0f
        return hand.palmCenter.distance(w.ctx.headPose.p)
    }

    override fun onSurfacePointer(e: PointerEvent, x: Float, y: Float): Boolean {
        val p = e.pointer ?: return false
        when (e.type) {
            PointerEventType.DOWN -> {
                val n = root.hitTest(x, y)
                mode = when (n) { grab -> 1; resizeBtn -> if (w.resizable) 2 else 0; else -> 0 }
                if (mode == 0) return false
                dragging = true
                w.beginMove()
                grabDist = e.hit.distance.coerceAtLeast(0.2f)
                offset.setSub(w.pose.p, e.hit.point)
                handRef = handDistance(e)
                startW = w.content.widthM; startH = w.content.heightM
                tmp.setSub(e.hit.point, w.pose.p)
                startSpan = tmp.length().coerceAtLeast(0.05f)
                UiAudio.click()
                return true
            }
            PointerEventType.DRAG -> {
                if (!dragging) return false
                val ray = p.ray
                if (mode == 1) {
                    val hd = handDistance(e)
                    val push = if (handRef > 0f && hd > 0f) exp((hd - handRef) * 5f) else 1f
                    val d = (grabDist * push).coerceIn(0.35f, 8f)
                    ray.pointAt(d, tmp)
                    w.pose.p.set(tmp).add(offset)
                    w.faceHead()
                    w.syncChildren()
                } else if (mode == 2) {
                    // Project ray onto the window plane and scale by distance from the center.
                    val n = Vec3(); w.pose.q.rotate(0f, 0f, 1f, n)
                    val t = MathUtil.rayPlane(ray.origin, ray.dir, w.pose.p, n)
                    if (!t.isNaN()) {
                        ray.pointAt(t, tmp)
                        val span = tmp.sub(w.pose.p).length()
                        val s = (span / startSpan).coerceIn(w.minScale / 2f, 3f)
                        val nw = (startW * s).coerceIn(0.3f, 12f)
                        val nh = if (w.keepAspect) startH * nw / startW else startH
                        w.setSize(nw, nh)
                    }
                }
                return true
            }
            PointerEventType.UP, PointerEventType.CANCEL -> {
                if (!dragging) return false
                dragging = false
                if (mode == 1) w.endMove()
                mode = 0
                return true
            }
            PointerEventType.CLICK -> if (mode == 0 && !dragging) return false else return true
            else -> return false
        }
    }

    companion object { private val pill = Paint(Paint.ANTI_ALIAS_FLAG) }
}
