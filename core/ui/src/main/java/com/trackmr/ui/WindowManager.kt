package com.trackmr.ui

import com.trackmr.xr.XrContext
import com.trackmr.xr.XrModule
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns every spatial window: placement in a comfortable arc in front of the user, focus,
 * minimize/restore, closing, recentering and per-frame follow/anchor updates.
 */
class WindowManager : XrModule {
    override val id = "windows"
    private lateinit var ctx: XrContext
    val windows = CopyOnWriteArrayList<XrWindow>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun onAttach(ctx: XrContext) {
        this.ctx = ctx
        ctx.register(WindowManager::class.java, this)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners) l() }

    /**
     * Opens [w] in front of the user. Subsequent windows fan out left/right along an arc
     * at the same distance so nothing overlaps. Must be called on the GL thread.
     */
    fun open(w: XrWindow, distance: Float = 1.35f) {
        val visible = windows.filter { it.isVisible }
        val slot = visible.size
        val angleStep = (w.content.widthM / distance) * 1.08f
        val yawOffset = when {
            slot == 0 -> 0f
            slot % 2 == 1 -> angleStep * ((slot + 1) / 2)
            else -> -angleStep * (slot / 2)
        }
        w.placeInFront(distance, yawOffset)
        w.attach()
        windows.add(w)
        val userClose = w.onClose
        w.onClose = { userClose?.invoke(); ctx.runOnGl { remove(w) } }
        UiAudio.open()
        changed()
    }

    private fun remove(w: XrWindow) {
        w.detach()
        windows.remove(w)
        changed()
    }

    fun focus(w: XrWindow) {
        if (w.state == WindowState.MINIMIZED) w.restore()
        w.placeInFront(w.followDistance.coerceIn(0.8f, 3f))
        changed()
    }

    fun find(tag: Any): XrWindow? = windows.firstOrNull { it.tag == tag && it.state != WindowState.CLOSED }

    /** Brings an existing tagged window forward or creates it. */
    fun openOrFocus(tag: Any, factory: () -> XrWindow): XrWindow {
        val existing = find(tag)
        if (existing != null) { focus(existing); return existing }
        val w = factory(); w.tag = tag
        open(w)
        return w
    }

    fun minimizeAll() { windows.forEach { it.minimize() }; changed() }
    fun closeAll() { windows.toList().forEach { it.close() } }

    fun recenter() {
        var i = 0
        for (w in windows) if (w.isVisible) {
            val step = (w.content.widthM / 1.35f) * 1.08f
            val yaw = if (i == 0) 0f else if (i % 2 == 1) step * ((i + 1) / 2) else -step * (i / 2)
            w.placeInFront(1.35f, yaw); i++
        }
    }

    override fun onFrame(ctx: XrContext, dt: Float) {
        CanvasTexture.beginFrame()
        for (w in windows) w.onFrame(dt)
    }

    override fun onDestroy(ctx: XrContext) { closeAll() }
}
