package com.trackmr.ui

import com.trackmr.xr.XrContext
import com.trackmr.xr.math.Quat
import com.trackmr.xr.math.Vec3

/** Receiver of spatial keyboard input (address bars, search, web text fields, emulators). */
interface KeyboardTarget {
    fun onKeyText(text: String)
    fun onKeyBackspace()
    fun onKeyEnter()
    fun onKeyboardClosed() {}
}

/**
 * Curved spatial QWERTY keyboard. Keys are large enough for direct fingertip typing
 * (poke) and also work with pinch rays or gaze + Cardboard trigger.
 */
class XrKeyboard(private val ctx: XrContext) : SpatialPanel(0.66f, 0.27f, 820f, radius = 0.9f) {
    private var target: KeyboardTarget? = null
    private var shift = false
    private var symbols = false
    val isOpen get() = !hidden && targetOpacity > 0f

    private val letters = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val syms = listOf("1234567890", "@#$%&*-+()", "!\"':;/?_=", ".,~<>[]")

    init {
        priority = 5
        glassColor = UiTheme.withAlpha(UiTheme.violetDeep, 235)
        hidden = true; opacity = 0f; targetOpacity = 0f
        build()
    }

    private fun build() {
        root.clearChildren()
        val rows = if (symbols) syms else letters
        val W = pixelW.toFloat(); val H = pixelH.toFloat()
        val rowH = (H - 20f) / 5f
        val keyW = (W - 24f) / 10.5f
        rows.forEachIndexed { r, row ->
            val y = 10f + r * rowH
            var x = 12f + (10 - row.length) * keyW / 2f
            if (r == 3) {
                key(if (shift) "⬆" else "⇧", 12f, y, keyW * 1.4f, rowH) { shift = !shift; build() }
                x = 12f + keyW * 1.55f
            }
            for (ch in row) {
                val s = if (shift && !symbols) ch.uppercaseChar().toString() else ch.toString()
                key(s, x, y, keyW - 6f, rowH) { type(s) }
                x += keyW
            }
            if (r == 3) key("⌫", W - 12f - keyW * 1.4f, y, keyW * 1.4f, rowH, ButtonStyle.ACCENT) { UiAudio.key(); target?.onKeyBackspace() }
        }
        val y = 10f + 4 * rowH
        key(if (symbols) "ABC" else "?123", 12f, y, keyW * 1.5f, rowH) { symbols = !symbols; build() }
        key(".com", 12f + keyW * 1.6f, y, keyW * 1.3f, rowH) { type(".com") }
        key("espaço", 12f + keyW * 3.0f, y, keyW * 4.4f, rowH) { type(" ") }
        key("/", 12f + keyW * 7.5f, y, keyW * 0.9f, rowH) { type("/") }
        key("⏎", 12f + keyW * 8.5f, y, keyW * 1.1f, rowH, ButtonStyle.PRIMARY) { UiAudio.key(); target?.onKeyEnter() }
        key("✖", W - 12f - keyW * 0.85f, y, keyW * 0.85f, rowH, ButtonStyle.DANGER) { close() }
        invalidate()
    }

    private fun key(label: String, x: Float, y: Float, w: Float, h: Float, style: ButtonStyle = ButtonStyle.SECONDARY, action: () -> Unit) {
        val b = UiButton(label, null, style, textSize = if (label.length > 2) 22f else 30f) { action() }
        b.at(x, y + 3f, w, h - 6f)
        root.add(b)
    }

    private fun type(s: String) {
        UiAudio.key()
        target?.onKeyText(s)
        if (shift && !symbols) { shift = false; build() }
    }

    /** Opens the keyboard for [t], placed below/in front of [anchorWindow] (or the user's view). */
    fun open(t: KeyboardTarget, anchorWindow: XrWindow? = null) {
        if (target !== t) target?.onKeyboardClosed()
        target = t
        val head = ctx.headPose
        if (anchorWindow != null) {
            val w = anchorWindow
            val d = w.distanceToHead().coerceAtMost(1.1f)
            val dir = Vec3().setSub(w.pose.p, head.p); dir.y = 0f; dir.normalize()
            pose.p.set(head.p).addScaled(dir, d * 0.72f).add(0f, -0.36f, 0f)
        } else {
            val yaw = head.q.yaw()
            pose.p.set(head.p.x - kotlin.math.sin(yaw) * 0.6f, head.p.y - 0.38f, head.p.z - kotlin.math.cos(yaw) * 0.6f)
        }
        val to = Vec3().setSub(head.p, pose.p); to.y = 0f; to.normalize()
        pose.q.setAxisAngle(0f, 1f, 0f, kotlin.math.atan2(to.x, to.z))
        pose.q.mul(Quat().setAxisAngle(1f, 0f, 0f, -0.55f)) // tilt up like a real keyboard
        if (!ctx.scene.contains(this)) { ctx.scene.add(this); ctx.input.register(this) }
        show()
    }

    fun close() {
        hide()
        target?.onKeyboardClosed()
        target = null
    }

    fun isTarget(t: KeyboardTarget) = target === t
}
