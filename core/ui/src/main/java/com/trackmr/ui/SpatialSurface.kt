package com.trackmr.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.trackmr.xr.Renderable
import com.trackmr.xr.gl.ExternalSurfaceTexture
import com.trackmr.xr.gl.Mesh
import com.trackmr.xr.gl.MeshFactory
import com.trackmr.xr.input.Interactable
import com.trackmr.xr.input.PointerEvent
import com.trackmr.xr.input.PointerEventType
import com.trackmr.xr.input.SurfaceHit
import com.trackmr.xr.math.MathUtil
import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Ray
import com.trackmr.xr.math.Vec3
import com.trackmr.xr.render.EyeContext
import com.trackmr.xr.render.Gfx
import com.trackmr.xr.render.RenderPass
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A rectangular surface in 3D space, flat or cylindrically curved (concave toward the
 * viewer). Handles geometry, ray/fingertip hit testing and open/close animations.
 * Local frame: surface faces +Z, u right, v down; curve axis is the vertical line (0, y, R).
 */
abstract class SpatialSurface(widthM: Float, heightM: Float, radius: Float = 0f) : Renderable, Interactable {
    val pose = Pose()
    var widthM = widthM; private set
    var heightM = heightM; private set
    /** Curvature radius in meters; 0 = flat. */
    var radius = radius; private set
    private var mesh: Mesh? = null
    private var meshDirty = true

    var opacity = 0f
    var targetOpacity = 1f
    var animScale = 0.85f
    var targetScale = 1f
    var hidden = false
    /** Extra priority for input on overlapping surfaces (keyboards > windows). */
    var priority = 0
    var interactive = true
    private var lastTime = -1.0

    override val passes: Int = Renderable.mask(RenderPass.TRANSPARENT)
    override val visible: Boolean get() = !hidden && (opacity > 0.01f || targetOpacity > 0f)
    override val isInteractive: Boolean get() = interactive && !hidden && opacity > 0.5f
    override val interactionPriority: Int get() = priority

    private val model = FloatArray(16)
    private val lo = Vec3()
    private val ld = Vec3()
    private val lp = Vec3()

    fun resize(w: Float, h: Float) {
        if (abs(w - widthM) < 1e-4f && abs(h - heightM) < 1e-4f) return
        widthM = w; heightM = h; meshDirty = true
        onResized()
    }

    fun setCurvature(r: Float) {
        val nr = if (r < 0.3f) 0f else r
        if (abs(nr - radius) < 1e-3f) return
        radius = nr; meshDirty = true
    }

    open fun onResized() {}

    fun show() { hidden = false; targetOpacity = 1f; targetScale = 1f }
    fun hide(animated: Boolean = true) {
        targetOpacity = 0f; targetScale = 0.85f
        if (!animated) { opacity = 0f; animScale = 0.85f }
    }

    protected fun currentMesh(): Mesh {
        if (meshDirty || mesh == null) {
            mesh?.release()
            mesh = MeshFactory.curvedPanel(widthM, heightM, radius, if (radius > 0f) 40 else 1)
            meshDirty = false
        }
        return mesh!!
    }

    override fun sortDistanceSq(eye: EyeContext): Float = pose.p.distanceSq(eye.eyePos) - priority * 0.01f

    /** Per-frame animation tick (runs once per frame from the first eye). */
    open fun tick(dt: Float) {
        opacity = MathUtil.damp(opacity, targetOpacity, 14f, dt)
        animScale = MathUtil.damp(animScale, targetScale, 16f, dt)
        if (targetOpacity == 0f && opacity < 0.02f) { opacity = 0f; hidden = true }
    }

    override fun render(eye: EyeContext, pass: RenderPass) {
        if (eye.eyeIndex == 0) {
            val dt = if (lastTime < 0) 0.016f else (eye.timeSeconds - lastTime).toFloat().coerceIn(0f, 0.1f)
            lastTime = eye.timeSeconds
            tick(dt)
        }
        if (opacity <= 0.01f) return
        pose.toMatrix(model, animScale, animScale, animScale)
        drawSurface(eye, model, currentMesh())
    }

    protected abstract fun drawSurface(eye: EyeContext, model: FloatArray, mesh: Mesh)

    // ---- hit testing ----

    override fun raycast(ray: Ray, out: SurfaceHit): Boolean {
        pose.inverseTransformPoint(ray.origin, lo)
        pose.inverseTransformDir(ray.dir, ld)
        val hw = widthM / 2f; val hh = heightM / 2f
        if (radius <= 0f) {
            if (abs(ld.z) < 1e-6f) return false
            val t = -lo.z / ld.z
            if (t < 0f) return false
            val x = lo.x + ld.x * t; val y = lo.y + ld.y * t
            if (x < -hw || x > hw || y < -hh || y > hh) return false
            out.distance = t
            out.u = (x + hw) / widthM; out.v = (hh - y) / heightM
            lp.set(x, y, 0f)
            pose.transformPoint(lp, out.point)
            pose.q.rotate(0f, 0f, 1f, out.normal)
            return true
        }
        val r = radius
        val oz = lo.z - r
        val a = ld.x * ld.x + ld.z * ld.z
        if (a < 1e-9f) return false
        val b = 2f * (lo.x * ld.x + oz * ld.z)
        val c = lo.x * lo.x + oz * oz - r * r
        val disc = b * b - 4f * a * c
        if (disc < 0f) return false
        val s = sqrt(disc)
        val span = widthM / r
        for (t in floatArrayOf((-b - s) / (2f * a), (-b + s) / (2f * a))) {
            if (t < 0f) continue
            val x = lo.x + ld.x * t; val y = lo.y + ld.y * t; val z = lo.z + ld.z * t
            if (z >= r) continue
            val phi = atan2(x, r - z)
            if (abs(phi) > span / 2f || y < -hh || y > hh) continue
            out.distance = t
            out.u = phi / span + 0.5f; out.v = (hh - y) / heightM
            lp.set(x, y, z)
            pose.transformPoint(lp, out.point)
            lp.set(-x, 0f, r - z).normalize()
            pose.q.rotate(lp, out.normal)
            return true
        }
        return false
    }

    override fun pokeDistance(point: Vec3, out: SurfaceHit): Float {
        pose.inverseTransformPoint(point, lo)
        val hw = widthM / 2f; val hh = heightM / 2f
        val margin = 0.01f
        if (lo.y < -hh - margin || lo.y > hh + margin) return Float.MAX_VALUE
        val dist: Float
        if (radius <= 0f) {
            if (lo.x < -hw - margin || lo.x > hw + margin) return Float.MAX_VALUE
            dist = lo.z
            out.u = ((lo.x + hw) / widthM).coerceIn(0f, 1f)
            lp.set(lo.x, lo.y, 0f)
            pose.q.rotate(0f, 0f, 1f, out.normal)
        } else {
            val r = radius
            val phi = atan2(lo.x, r - lo.z)
            val span = widthM / r
            if (abs(phi) > span / 2f + margin / r) return Float.MAX_VALUE
            val rr = sqrt(lo.x * lo.x + (lo.z - r) * (lo.z - r))
            dist = r - rr
            out.u = (phi / span + 0.5f).coerceIn(0f, 1f)
            lp.set(kotlin.math.sin(phi) * r, lo.y, r - kotlin.math.cos(phi) * r)
            ld.set(-lp.x, 0f, r - lp.z).normalize()
            pose.q.rotate(ld, out.normal)
        }
        out.v = ((hh - lo.y) / heightM).coerceIn(0f, 1f)
        pose.transformPoint(lp, out.point)
        out.distance = abs(dist)
        return dist
    }
}

/**
 * Canvas-rasterized spatial panel hosting a [UiNode] tree. Glass background, rounded
 * corners, hover/press/scroll dispatch, click sounds.
 */
open class SpatialPanel(
    widthM: Float,
    heightM: Float,
    val pxPerMeter: Float = 800f,
    radius: Float = 0f,
) : SpatialSurface(widthM, heightM, radius) {
    val root = UiNode().also { it.host = this }
    var pixelW = (widthM * pxPerMeter).toInt().coerceAtLeast(16); private set
    var pixelH = (heightM * pxPerMeter).toInt().coerceAtLeast(16); private set
    private var tex: CanvasTexture? = null
    @Volatile private var needsRedraw = true
    var drawGlass = true
    var glassColor = UiTheme.violetGlass
    var cornerPx = 36f
    var tint = floatArrayOf(1f, 1f, 1f)
    var occludable = false

    private var hoverNode: UiNode? = null
    private var pressNode: UiNode? = null
    private var scrollNode: UiScroll? = null
    private var downY = 0f
    private var lastY = 0f
    private var scrolling = false

    fun invalidate() { needsRedraw = true }

    override fun onResized() {
        pixelW = (widthM * pxPerMeter).toInt().coerceAtLeast(16)
        pixelH = (heightM * pxPerMeter).toInt().coerceAtLeast(16)
        tex?.release(); tex = null
        layout()
        invalidate()
    }

    /** Override to (re)position widgets after resize. */
    open fun layout() {}

    /** Custom drawing under the widget tree. */
    open fun drawBackground(c: Canvas) {
        if (!drawGlass) return
        rect.set(0f, 0f, pixelW.toFloat(), pixelH.toFloat())
        glass.color = glassColor
        c.drawRoundRect(rect, cornerPx, cornerPx, glass)
        highlight.shader = android.graphics.LinearGradient(0f, 0f, 0f, pixelH * 0.5f,
            UiTheme.withAlpha(android.graphics.Color.WHITE, 26), 0, android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, cornerPx, cornerPx, highlight)
        border.color = UiTheme.stroke
        rect.inset(1.5f, 1.5f)
        c.drawRoundRect(rect, cornerPx, cornerPx, border)
    }

    open fun onFrame(dt: Float) {}

    override fun tick(dt: Float) {
        super.tick(dt)
        onFrame(dt)
        if (needsRedraw && CanvasTexture.tryAcquireRedraw()) {
            needsRedraw = false
            val t = tex ?: CanvasTexture(pixelW, pixelH).also { tex = it }
            t.clear()
            drawBackground(t.canvas)
            root.draw(t.canvas)
            t.dirty = true
        }
    }

    override fun drawSurface(eye: EyeContext, model: FloatArray, mesh: Mesh) {
        val t = tex ?: return
        val id = t.textureId()
        if (id == 0) return
        Gfx.get.drawUnlit(eye, mesh, model, 0, tint[0], tint[1], tint[2], opacity, texture = id, occlusion = occludable)
    }

    /** Hook for subclasses to consume raw pointer events first. */
    open fun onSurfacePointer(e: PointerEvent, x: Float, y: Float): Boolean = false

    override fun onPointer(e: PointerEvent) {
        val x = e.hit.u * pixelW
        val y = e.hit.v * pixelH
        if (onSurfacePointer(e, x, y)) return
        when (e.type) {
            PointerEventType.HOVER_ENTER, PointerEventType.HOVER -> {
                val n = root.hitTest(x, y)
                if (n !== hoverNode) {
                    hoverNode?.let { it.hovered = false; it.invalidate() }
                    hoverNode = n
                    n?.let { it.hovered = true; it.invalidate(); UiAudio.hover() }
                }
                (n as? UiSegmented)?.hoverAt(x)
            }
            PointerEventType.HOVER_EXIT -> { hoverNode?.let { it.hovered = false; it.invalidate() }; hoverNode = null }
            PointerEventType.DOWN -> {
                val n = root.hitTest(x, y)
                pressNode = n
                scrolling = false
                downY = y; lastY = y
                scrollNode = findScroll(n) ?: findScrollAt(root, x, y)
                n?.let { it.pressed = true; it.onPress(x, y); it.invalidate() }
            }
            PointerEventType.DRAG -> {
                val n = pressNode
                val sc = scrollNode
                val sliderLike = n is UiSlider
                if (sc != null && !sliderLike && !scrolling && abs(y - downY) > 14f) {
                    scrolling = true
                    n?.let { it.pressed = false; it.invalidate() }
                }
                if (scrolling && sc != null) sc.scrollY -= (y - lastY)
                else n?.onDrag(x, y)
                lastY = y
            }
            PointerEventType.UP -> pressNode?.let { it.pressed = false; it.onRelease(x, y); it.invalidate() }
            PointerEventType.CLICK -> {
                val n = pressNode
                if (n != null && !scrolling && n.enabled) { UiAudio.click(); n.performClick() }
                pressNode = null
            }
            PointerEventType.LONG_PRESS -> pressNode?.onLongPress?.invoke()
            PointerEventType.CANCEL -> { pressNode?.pressed = false; pressNode = null; hoverNode?.hovered = false; hoverNode = null; invalidate() }
        }
    }

    private fun findScroll(n: UiNode?): UiScroll? {
        var p = n
        while (p != null) { if (p is UiScroll) return p; p = p.parent }
        return null
    }

    private fun findScrollAt(node: UiNode, x: Float, y: Float): UiScroll? {
        for (c in node.children) {
            if (!c.visible) continue
            if (c is UiScroll && c.bounds.contains(x, y)) return c
            findScrollAt(c, x, y)?.let { return it }
        }
        return null
    }

    fun release() { tex?.release(); tex = null }

    companion object {
        private val rect = RectF()
        private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
        private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    }
}

/**
 * Surface showing an external GL texture (video frames, web pages, Android app virtual
 * displays). Pointer events are forwarded as normalized coordinates.
 */
open class ExternalSurfacePanel(widthM: Float, heightM: Float, radius: Float = 0f) : SpatialSurface(widthM, heightM, radius) {
    var source: ExternalSurfaceTexture? = null
    var brightness = 1f
    /** Sub-rectangle of the source (u, v, w, h) — e.g. one eye of side-by-side 3D video. */
    val uvRect = floatArrayOf(0f, 0f, 1f, 1f)
    /** Optional per-eye rect for stereo (SBS/OU) content; null = mono. */
    var stereoRects: Array<FloatArray>? = null
    var onPointerEvent: ((PointerEvent, Float, Float) -> Unit)? = null
    /** Placeholder shown before the first frame arrives. */
    var placeholderColor = floatArrayOf(0.08f, 0.04f, 0.16f)

    override fun tick(dt: Float) {
        super.tick(dt)
        source?.latch()
    }

    override fun drawSurface(eye: EyeContext, model: FloatArray, mesh: Mesh) {
        val src = source
        val g = Gfx.get
        if (src == null || !src.hasFrame) {
            g.drawUnlit(eye, mesh, model, 3, placeholderColor[0], placeholderColor[1], placeholderColor[2], 0.92f * opacity, corner = 0.04f)
            return
        }
        val r = stereoRects?.get(eye.eyeIndex.coerceIn(0, 1)) ?: uvRect
        g.drawExternal(eye, mesh, model, src.textureId, src.transform, brightness, opacity, r[0], r[1], r[2], r[3])
    }

    override fun onPointer(e: PointerEvent) { onPointerEvent?.invoke(e, e.hit.u, e.hit.v) }
}
