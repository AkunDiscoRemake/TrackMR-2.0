package com.trackmr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.roundToInt

/**
 * Retained widget tree rasterized into a [SpatialPanel] texture. Coordinates are panel
 * pixels. Widgets only exist inside 3D panels — they are never attached to Android views.
 */
open class UiNode {
    val bounds = RectF()
    var visible = true
        set(v) { if (field != v) { field = v; invalidate() } }
    var enabled = true
    var hovered = false
    var pressed = false
    var parent: UiNode? = null
    var host: SpatialPanel? = null
        get() = field ?: parent?.host
    val children = ArrayList<UiNode>()
    var onClick: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var tag: Any? = null

    open val interactive: Boolean get() = onClick != null || onLongPress != null

    fun <T : UiNode> add(child: T): T { child.parent = this; children.add(child); invalidate(); return child }
    fun remove(child: UiNode) { children.remove(child); child.parent = null; invalidate() }
    fun clearChildren() { children.forEach { it.parent = null }; children.clear(); invalidate() }

    fun at(x: Float, y: Float, w: Float, h: Float): UiNode { bounds.set(x, y, x + w, y + h); return this }

    open fun draw(c: Canvas) { for (ch in children) if (ch.visible) ch.draw(c) }

    /** Deepest interactive node under (x, y). */
    open fun hitTest(x: Float, y: Float): UiNode? {
        if (!visible || !bounds.contains(x, y) && bounds.width() > 0f) return null
        for (i in children.indices.reversed()) {
            val r = children[i].hitTest(x, y)
            if (r != null) return r
        }
        return if (interactive && enabled && bounds.contains(x, y)) this else null
    }

    open fun onPress(x: Float, y: Float) {}
    open fun onDrag(x: Float, y: Float) {}
    open fun onRelease(x: Float, y: Float) {}
    open fun performClick() { onClick?.invoke() }

    fun invalidate() { host?.invalidate() }
}

class UiLabel(
    text: String,
    var size: Float = 28f,
    var color: Int = UiTheme.textPrimary,
    var align: Paint.Align = Paint.Align.LEFT,
    var bold: Boolean = false,
    var maxLines: Int = 1,
) : UiNode() {
    var text: String = text
        set(v) { if (field != v) { field = v; layout = null; invalidate() } }
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var layout: StaticLayout? = null

    override fun draw(c: Canvas) {
        paint.textSize = size; paint.color = color
        paint.typeface = if (bold) UiTheme.bold else UiTheme.medium
        if (maxLines <= 1) {
            paint.textAlign = align
            val t = TextUtils.ellipsize(text, paint, bounds.width(), TextUtils.TruncateAt.END).toString()
            val x = when (align) { Paint.Align.CENTER -> bounds.centerX(); Paint.Align.RIGHT -> bounds.right; else -> bounds.left }
            val y = bounds.centerY() - (paint.descent() + paint.ascent()) / 2f
            c.drawText(t, x, y, paint)
        } else {
            paint.textAlign = Paint.Align.LEFT
            val l = layout ?: StaticLayout.Builder.obtain(text, 0, text.length, paint, bounds.width().roundToInt().coerceAtLeast(1))
                .setAlignment(when (align) { Paint.Align.CENTER -> Layout.Alignment.ALIGN_CENTER; Paint.Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL })
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build().also { layout = it }
            c.save(); c.translate(bounds.left, bounds.top); l.draw(c); c.restore()
        }
    }
}

enum class ButtonStyle { PRIMARY, SECONDARY, GHOST, DANGER, ACCENT }

open class UiButton(
    text: String,
    var icon: String? = null,
    var style: ButtonStyle = ButtonStyle.SECONDARY,
    var accent: Int = UiTheme.neonBlue,
    var textSize: Float = 26f,
    click: (() -> Unit)? = null,
) : UiNode() {
    var text: String = text
        set(v) { if (field != v) { field = v; invalidate() } }
    var selected = false
        set(v) { if (field != v) { field = v; invalidate() } }
    init { onClick = click }

    override fun draw(c: Canvas) {
        val r = bounds.height() * 0.5f
        val base = when (style) {
            ButtonStyle.PRIMARY -> null
            ButtonStyle.SECONDARY -> UiTheme.violetGlassLight
            ButtonStyle.GHOST -> Color.TRANSPARENT
            ButtonStyle.DANGER -> Color.rgb(200, 40, 70)
            ButtonStyle.ACCENT -> UiTheme.withAlpha(accent, 190)
        }
        if (base == null) {
            fillPaint.shader = android.graphics.LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                Color.rgb(120, 40, 255), Color.rgb(230, 60, 200), android.graphics.Shader.TileMode.CLAMP)
            fillPaint.color = Color.WHITE
        } else { fillPaint.shader = null; fillPaint.color = base }
        if (selected) { fillPaint.shader = null; fillPaint.color = UiTheme.withAlpha(accent, 210) }
        c.drawRoundRect(bounds, r, r, fillPaint)
        if (hovered || pressed) {
            overlay.color = if (pressed) UiTheme.pressed else UiTheme.hover
            c.drawRoundRect(bounds, r, r, overlay)
            ring.color = UiTheme.withAlpha(if (style == ButtonStyle.DANGER) UiTheme.neonRed else UiTheme.neonBlue, 220)
            c.drawRoundRect(bounds, r, r, ring)
        } else if (style == ButtonStyle.SECONDARY) {
            ring.color = UiTheme.stroke
            c.drawRoundRect(bounds, r, r, ring)
        }
        text(c)
    }

    protected fun text(c: Canvas) {
        tp.textSize = textSize
        tp.color = if (enabled) UiTheme.textPrimary else UiTheme.textMuted
        tp.typeface = UiTheme.bold
        tp.textAlign = Paint.Align.CENTER
        val label = if (icon != null && text.isNotEmpty()) "$icon  $text" else icon ?: text
        val y = bounds.centerY() - (tp.descent() + tp.ascent()) / 2f
        c.drawText(TextUtils.ellipsize(label, tp, bounds.width() - 16f, TextUtils.TruncateAt.END).toString(), bounds.centerX(), y, tp)
    }

    companion object {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    }
}

/** Large card for launchers / libraries: gradient, emoji icon or thumbnail, title, subtitle. */
class UiTile(
    var title: String,
    var subtitle: String = "",
    var icon: String = "✨",
    var accent: Int = UiTheme.neonBlue,
    var image: Bitmap? = null,
    click: (() -> Unit)? = null,
) : UiNode() {
    var badge: String? = null
    var selected = false
    init { onClick = click }

    override fun draw(c: Canvas) {
        val lift = if (hovered) 6f else 0f
        rect.set(bounds.left, bounds.top - lift, bounds.right, bounds.bottom - lift)
        val r = 28f
        // Glow when hovered/selected
        if (hovered || selected) {
            glow.color = UiTheme.withAlpha(accent, if (hovered) 120 else 70)
            c.drawRoundRect(rect.left - 8, rect.top - 8, rect.right + 8, rect.bottom + 8, r + 8, r + 8, glow)
        }
        bg.shader = android.graphics.LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            UiTheme.withAlpha(accent, 235), UiTheme.lerpColor(accent, UiTheme.violetDeep, 0.72f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, bg)
        val img = image
        val textTop = rect.bottom - rect.height() * 0.3f
        if (img != null && !img.isRecycled) {
            c.save()
            clip.reset(); clip.addRoundRect(rect.left + 8, rect.top + 8, rect.right - 8, textTop, r - 6, r - 6, android.graphics.Path.Direction.CW)
            c.clipPath(clip)
            src.set(0, 0, img.width, img.height)
            // center-crop
            val dstW = rect.width() - 16; val dstH = textTop - rect.top - 8
            val s = maxOf(dstW / img.width, dstH / img.height)
            val w = img.width * s; val h = img.height * s
            dst.set(rect.centerX() - w / 2, rect.top + 8 + dstH / 2 - h / 2, rect.centerX() + w / 2, rect.top + 8 + dstH / 2 + h / 2)
            c.drawBitmap(img, src, dst, bmpPaint)
            c.restore()
        } else {
            tp.textAlign = Paint.Align.CENTER
            tp.textSize = rect.height() * 0.34f
            tp.color = Color.WHITE
            c.drawText(icon, rect.centerX(), rect.top + rect.height() * 0.48f, tp)
        }
        tp.textAlign = Paint.Align.LEFT
        tp.typeface = UiTheme.bold
        tp.textSize = (rect.height() * 0.1f).coerceIn(20f, 34f)
        tp.color = UiTheme.textPrimary
        c.drawText(TextUtils.ellipsize(title, tp, rect.width() - 36, TextUtils.TruncateAt.END).toString(), rect.left + 18, textTop + tp.textSize + 6, tp)
        if (subtitle.isNotEmpty()) {
            tp.typeface = UiTheme.regular
            tp.textSize = (rect.height() * 0.075f).coerceIn(16f, 24f)
            tp.color = UiTheme.textSecondary
            c.drawText(TextUtils.ellipsize(subtitle, tp, rect.width() - 36, TextUtils.TruncateAt.END).toString(), rect.left + 18, rect.bottom - 16, tp)
        }
        badge?.let {
            tp.typeface = UiTheme.bold; tp.textSize = 18f; tp.color = Color.WHITE
            val bw = tp.measureText(it) + 24
            badgeBg.color = UiTheme.withAlpha(Color.BLACK, 120)
            c.drawRoundRect(rect.right - bw - 12, rect.top + 12, rect.right - 12, rect.top + 44, 16f, 16f, badgeBg)
            c.drawText(it, rect.right - bw, rect.top + 35, tp)
        }
        if (hovered) { ringP.color = UiTheme.withAlpha(Color.WHITE, 200); c.drawRoundRect(rect, r, r, ringP) }
    }

    companion object {
        private val rect = RectF()
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = android.graphics.BlurMaskFilter(14f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val clip = android.graphics.Path()
        private val src = android.graphics.Rect()
        private val dst = RectF()
    }
}

class UiSlider(
    var label: String,
    var min: Float,
    var max: Float,
    value: Float,
    var step: Float = 0f,
    var format: (Float) -> String = { "%.2f".format(it) },
    var accent: Int = UiTheme.neonBlue,
    var onChange: ((Float) -> Unit)? = null,
) : UiNode() {
    var value = value
        set(v) {
            var nv = v.coerceIn(min, max)
            if (step > 0f) nv = (Math.round((nv - min) / step) * step + min).coerceIn(min, max)
            if (nv != field) { field = nv; invalidate() }
        }
    override val interactive = true
    private val track = RectF()

    override fun draw(c: Canvas) {
        val hasLabel = label.isNotEmpty()
        val ty = if (hasLabel) bounds.top + bounds.height() * 0.68f else bounds.centerY()
        if (hasLabel) {
            tp.textSize = 22f; tp.color = UiTheme.textSecondary; tp.textAlign = Paint.Align.LEFT; tp.typeface = UiTheme.medium
            c.drawText(label, bounds.left, bounds.top + 26f, tp)
            tp.textAlign = Paint.Align.RIGHT; tp.color = UiTheme.textPrimary; tp.typeface = UiTheme.bold
            c.drawText(format(value), bounds.right, bounds.top + 26f, tp)
        }
        track.set(bounds.left, ty - 6f, bounds.right, ty + 6f)
        paint.color = UiTheme.withAlpha(Color.WHITE, 40); paint.shader = null
        c.drawRoundRect(track, 6f, 6f, paint)
        val t = if (max > min) (value - min) / (max - min) else 0f
        val kx = bounds.left + t * bounds.width()
        paint.shader = android.graphics.LinearGradient(bounds.left, 0f, kx.coerceAtLeast(bounds.left + 1), 0f, UiTheme.withAlpha(accent, 160), accent, android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(bounds.left, ty - 6f, kx, ty + 6f, 6f, 6f, paint)
        paint.shader = null
        paint.color = Color.WHITE
        c.drawCircle(kx, ty, if (hovered || pressed) 17f else 13f, paint)
        if (pressed) { paint.color = UiTheme.withAlpha(accent, 90); c.drawCircle(kx, ty, 28f, paint) }
    }

    private fun setFromX(x: Float) {
        val t = ((x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        val old = value
        value = min + t * (max - min)
        if (value != old) onChange?.invoke(value)
    }

    override fun onPress(x: Float, y: Float) = setFromX(x)
    override fun onDrag(x: Float, y: Float) = setFromX(x)

    companion object {
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    }
}

class UiToggle(var label: String, value: Boolean, var accent: Int = UiTheme.neonMint, var onChange: ((Boolean) -> Unit)? = null) : UiNode() {
    var value = value
        set(v) { if (field != v) { field = v; invalidate() } }
    init { onClick = { this.value = !this.value; onChange?.invoke(this.value) } }

    override fun draw(c: Canvas) {
        if (hovered) { p.color = UiTheme.hover; c.drawRoundRect(bounds, 18f, 18f, p) }
        tp.textSize = 24f; tp.color = UiTheme.textPrimary; tp.typeface = UiTheme.medium; tp.textAlign = Paint.Align.LEFT
        c.drawText(TextUtils.ellipsize(label, tp, bounds.width() - 110f, TextUtils.TruncateAt.END).toString(), bounds.left + 12f, bounds.centerY() + 9f, tp)
        val w = 76f; val h = 40f
        val x = bounds.right - w - 10f; val y = bounds.centerY() - h / 2
        p.color = if (value) accent else UiTheme.withAlpha(Color.WHITE, 50)
        c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, p)
        p.color = Color.WHITE
        c.drawCircle(if (value) x + w - h / 2 else x + h / 2, y + h / 2, h / 2 - 5f, p)
    }

    companion object {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    }
}

class UiSegmented(val options: List<String>, selected: Int, var accent: Int = UiTheme.neonBlue, var onSelect: ((Int) -> Unit)? = null) : UiNode() {
    var selected = selected
        set(v) { if (field != v) { field = v; invalidate() } }
    private var hoverIndex = -1
    override val interactive = true

    private fun indexAt(x: Float) = (((x - bounds.left) / bounds.width()) * options.size).toInt().coerceIn(0, options.size - 1)

    override fun draw(c: Canvas) {
        val r = bounds.height() / 2
        p.color = UiTheme.withAlpha(Color.BLACK, 70)
        c.drawRoundRect(bounds, r, r, p)
        val w = bounds.width() / options.size
        for (i in options.indices) {
            val l = bounds.left + i * w
            if (i == selected) { p.color = accent; c.drawRoundRect(l + 4, bounds.top + 4, l + w - 4, bounds.bottom - 4, r - 4, r - 4, p) }
            else if (hovered && i == hoverIndex) { p.color = UiTheme.hover; c.drawRoundRect(l + 4, bounds.top + 4, l + w - 4, bounds.bottom - 4, r - 4, r - 4, p) }
            tp.textSize = (bounds.height() * 0.42f).coerceAtMost(26f); tp.textAlign = Paint.Align.CENTER; tp.typeface = UiTheme.bold
            tp.color = if (i == selected) Color.rgb(20, 8, 40) else UiTheme.textPrimary
            c.drawText(TextUtils.ellipsize(options[i], tp, w - 12, TextUtils.TruncateAt.END).toString(), l + w / 2, bounds.centerY() - (tp.descent() + tp.ascent()) / 2, tp)
        }
    }

    override fun onPress(x: Float, y: Float) { hoverIndex = indexAt(x) }
    override fun onDrag(x: Float, y: Float) { val i = indexAt(x); if (i != hoverIndex) { hoverIndex = i; invalidate() } }
    override fun performClick() {
        val i = hoverIndex.coerceIn(0, options.size - 1)
        selected = i; onSelect?.invoke(i)
    }
    fun hoverAt(x: Float) { val i = indexAt(x); if (i != hoverIndex) { hoverIndex = i; invalidate() } }

    companion object {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    }
}

class UiTextField(var hint: String, text: String = "", var onFocus: ((UiTextField) -> Unit)? = null) : UiNode() {
    var text: String = text
        set(v) { if (field != v) { field = v; invalidate() } }
    var focused = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var textSize = 26f
    init { onClick = { onFocus?.invoke(this) } }

    override fun draw(c: Canvas) {
        val r = bounds.height() / 2
        p.color = UiTheme.withAlpha(Color.BLACK, if (focused) 140 else 90)
        c.drawRoundRect(bounds, r, r, p)
        s.color = if (focused) UiTheme.neonBlue else if (hovered) UiTheme.withAlpha(Color.WHITE, 160) else UiTheme.stroke
        c.drawRoundRect(bounds, r, r, s)
        tp.textSize = textSize; tp.typeface = UiTheme.medium; tp.textAlign = Paint.Align.LEFT
        val show = text.ifEmpty { hint }
        tp.color = if (text.isEmpty()) UiTheme.textMuted else UiTheme.textPrimary
        val avail = bounds.width() - 2 * r
        // Show the tail of long text while typing.
        var t = show
        while (t.length > 1 && tp.measureText(t) > avail) t = t.substring(1)
        val y = bounds.centerY() - (tp.descent() + tp.ascent()) / 2
        c.drawText(t, bounds.left + r, y, tp)
        if (focused) {
            val cx = bounds.left + r + if (text.isEmpty()) 0f else tp.measureText(t)
            p.color = UiTheme.neonBlue
            c.drawRect(cx + 2, bounds.top + 12, cx + 5, bounds.bottom - 12, p)
        }
    }

    companion object {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    }
}

/** Glass background card / section. */
class UiCard(var color: Int = UiTheme.violetGlassLight, var radius: Float = 28f, var strokeColor: Int = UiTheme.stroke) : UiNode() {
    override fun draw(c: Canvas) {
        p.color = color
        c.drawRoundRect(bounds, radius, radius, p)
        if (strokeColor != 0) { s.color = strokeColor; c.drawRoundRect(bounds, radius, radius, s) }
        super.draw(c)
    }
    companion object {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    }
}

class UiImage(var bitmap: Bitmap?, var radius: Float = 20f) : UiNode() {
    override fun draw(c: Canvas) {
        val b = bitmap ?: return
        if (b.isRecycled) return
        c.save()
        path.reset(); path.addRoundRect(bounds, radius, radius, android.graphics.Path.Direction.CW)
        c.clipPath(path)
        c.drawBitmap(b, null, bounds, paint)
        c.restore()
    }
    companion object {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val path = android.graphics.Path()
    }
}

/** Vertical scroll container. Drag anywhere inside to scroll; children laid out in content space. */
class UiScroll : UiNode() {
    var scrollY = 0f
        set(v) { val nv = v.coerceIn(0f, maxScroll()); if (nv != field) { field = nv; invalidate() } }
    var contentHeight = 0f

    fun maxScroll() = (contentHeight - bounds.height()).coerceAtLeast(0f)

    override fun draw(c: Canvas) {
        c.save()
        c.clipRect(bounds)
        c.translate(0f, -scrollY)
        for (ch in children) if (ch.visible && ch.bounds.bottom >= bounds.top + scrollY && ch.bounds.top <= bounds.bottom + scrollY) ch.draw(c)
        c.restore()
        if (maxScroll() > 0f) {
            val frac = bounds.height() / contentHeight
            val h = bounds.height() * frac
            val y = bounds.top + (bounds.height() - h) * (scrollY / maxScroll())
            p.color = UiTheme.withAlpha(Color.WHITE, 80)
            c.drawRoundRect(bounds.right - 8, y, bounds.right - 2, y + h, 3f, 3f, p)
        }
    }

    override fun hitTest(x: Float, y: Float): UiNode? {
        if (!visible || !bounds.contains(x, y)) return null
        for (i in children.indices.reversed()) {
            val r = children[i].hitTest(x, y + scrollY)
            if (r != null) return r
        }
        return null
    }

    companion object { private val p = Paint(Paint.ANTI_ALIAS_FLAG) }
}
