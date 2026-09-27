package com.trackmr.xr.input

import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Ray
import com.trackmr.xr.math.Vec3
import java.util.concurrent.CopyOnWriteArrayList

enum class PointerSource { HAND_LEFT, HAND_RIGHT, GAZE }

class SurfaceHit {
    @JvmField var distance = Float.MAX_VALUE
    @JvmField val point = Vec3()
    @JvmField val normal = Vec3(0f, 0f, 1f)
    /** Surface coordinates 0..1 (u right, v down) when the target is a panel. */
    @JvmField var u = 0f
    @JvmField var v = 0f
    fun set(o: SurfaceHit): SurfaceHit {
        distance = o.distance; point.set(o.point); normal.set(o.normal); u = o.u; v = o.v; return this
    }
}

enum class PointerEventType { HOVER_ENTER, HOVER, HOVER_EXIT, DOWN, DRAG, UP, CLICK, LONG_PRESS, CANCEL }

class PointerEvent {
    @JvmField var type = PointerEventType.HOVER
    @JvmField var pointer: Pointer? = null
    @JvmField val hit = SurfaceHit()
    /** True when the interaction is a direct touch (index fingertip) rather than a ray. */
    @JvmField var direct = false
}

/**
 * Anything that can be pointed at: panels, windows, keyboard keys, 3D objects.
 * Implementations are called on the GL thread.
 */
interface Interactable {
    val isInteractive: Boolean get() = true
    /** Higher wins on ties (e.g. keyboards over the window behind them). */
    val interactionPriority: Int get() = 0
    fun raycast(ray: Ray, out: SurfaceHit): Boolean
    /** Signed distance of [point] to the touchable surface (positive in front) or MAX_VALUE. */
    fun pokeDistance(point: Vec3, out: SurfaceHit): Float = Float.MAX_VALUE
    fun onPointer(e: PointerEvent)
}

class Pointer(val source: PointerSource) {
    @JvmField val ray = Ray()
    @JvmField var active = false
    @JvmField var pressed = false
    @JvmField var justPressed = false
    @JvmField var justReleased = false
    @JvmField var hover: Interactable? = null
    @JvmField var captured: Interactable? = null
    @JvmField val hit = SurfaceHit()
    @JvmField var hasHit = false
    @JvmField var pressStartNs = 0L
    @JvmField val pressStartPoint = Vec3()
    @JvmField var longPressFired = false
    @JvmField var direct = false
    @JvmField var hand: HandState? = null
    /** Visual: 0..1 press strength (pinch strength) for cursor animation. */
    @JvmField var strength = 0f
    /** Max travel on the surface since press (meters) to tell clicks from drags. */
    @JvmField var travel = 0f
    @JvmField var wasDown = false
    @JvmField var directTarget: Interactable? = null
}

/**
 * Routes hand rays, direct fingertip touch and gaze/Cardboard-button input to the spatial UI.
 * Pinch is the primary click everywhere.
 */
class InputSystem {
    private val targets = CopyOnWriteArrayList<Interactable>()
    val left = Pointer(PointerSource.HAND_LEFT)
    val right = Pointer(PointerSource.HAND_RIGHT)
    val gaze = Pointer(PointerSource.GAZE)
    val pointers = arrayOf(right, left, gaze)

    private val event = PointerEvent()
    private val tmpHit = SurfaceHit()
    private val tmpVec = Vec3()

    @Volatile private var screenDown = false
    @Volatile private var screenTapPending = false
    /** Set by modules that consume a pinch themselves (e.g. game grabbing) to suppress UI clicks. */
    @JvmField var suppressHandsUntilRelease = false
    /** When false, gaze pointer only appears if no hand is tracked. */
    @JvmField var gazeAlwaysOn = false

    fun register(t: Interactable) { if (!targets.contains(t)) targets.add(t) }
    fun unregister(t: Interactable) {
        targets.remove(t)
        for (p in pointers) {
            if (p.hover === t) p.hover = null
            if (p.captured === t) p.captured = null
        }
    }

    /** Cardboard trigger / screen touch (called from the UI thread). */
    fun onScreenTouch(down: Boolean) {
        if (down && !screenDown) screenTapPending = true
        screenDown = down
    }

    fun anyHover(): Boolean = pointers.any { it.active && it.hover != null }

    fun update(nowNs: Long, head: Pose, hands: HandsFrame) {
        updateHandPointer(right, hands.right, nowNs)
        updateHandPointer(left, hands.left, nowNs)

        // Gaze pointer: head forward. Active when no hand ray is available (or forced).
        val handsActive = right.active || left.active
        gaze.active = gazeAlwaysOn || !handsActive || screenDown
        head.forward(gaze.ray.dir)
        gaze.ray.origin.set(head.p)
        val tapped = screenTapPending
        screenTapPending = false
        val wasPressed = gaze.pressed
        gaze.pressed = gaze.active && (screenDown || tapped)
        gaze.strength = if (gaze.pressed) 1f else 0f
        if (tapped && !screenDown && !wasPressed) {
            // A quick tap that started and ended between frames: synthesize press then release.
            processPointer(gaze, nowNs, forcePressed = true)
            gaze.pressed = false
        }
        processPointer(gaze, nowNs, forcePressed = false)
        processPointer(right, nowNs, false)
        processPointer(left, nowNs, false)
    }

    private fun updateHandPointer(p: Pointer, h: HandState, nowNs: Long) {
        p.hand = h
        p.active = h.tracked && h.rayValid && h.presence > 0.5f
        if (!p.active) {
            if (p.pressed) { p.pressed = false }
            p.direct = false
            return
        }
        p.ray.origin.set(h.rayOrigin)
        p.ray.dir.set(h.rayDir)
        p.strength = h.pinchStrength

        // Direct touch with the index fingertip has priority over the ray when close.
        val tip = h.joints[HandJoint.INDEX_TIP]
        var best: Interactable? = null
        var bestD = 0.035f
        for (t in targets) {
            if (!t.isInteractive) continue
            val d = t.pokeDistance(tip, tmpHit)
            if (d < bestD && d > -0.06f) { bestD = d; best = t; }
        }
        p.directTarget = best
        if (best != null) {
            best.pokeDistance(tip, p.hit)
            p.direct = true
            // Aim the ray straight at the touched point so hover logic stays consistent.
            p.ray.origin.set(tip).addScaled(p.hit.normal, 0.1f)
            p.ray.dir.set(p.hit.normal).scale(-1f)
            val touching = if (p.pressed && p.captured === best) bestD < 0.02f else bestD < 0.006f
            p.pressed = touching || h.isPinching
        } else {
            p.direct = false
            p.pressed = h.isPinching && !suppressHandsUntilRelease
        }
        if (!h.isPinching) suppressHandsUntilRelease = false
    }

    private fun raycastAll(ray: Ray, out: SurfaceHit): Interactable? {
        var best: Interactable? = null
        var bestDist = Float.MAX_VALUE
        var bestPrio = Int.MIN_VALUE
        for (t in targets) {
            if (!t.isInteractive) continue
            if (t.raycast(ray, tmpHit)) {
                val d = tmpHit.distance
                val prio = t.interactionPriority
                if (prio > bestPrio || (prio == bestPrio && d < bestDist)) {
                    bestPrio = prio; bestDist = d; best = t; out.set(tmpHit)
                }
            }
        }
        return best
    }

    private fun processPointer(p: Pointer, nowNs: Long, forcePressed: Boolean) {
        val pressed = (p.pressed || forcePressed) && p.active
        p.justPressed = pressed && p.captured == null && !p.wasDown
        p.justReleased = !pressed && p.wasDown

        if (!p.active) {
            if (p.captured != null) fire(p.captured!!, PointerEventType.CANCEL, p)
            if (p.hover != null) fire(p.hover!!, PointerEventType.HOVER_EXIT, p)
            p.captured = null; p.hover = null; p.hasHit = false; p.wasDown = false
            return
        }

        val captured = p.captured
        if (captured != null) {
            // While pressed, events go to the captured target even off-surface (drag).
            if (captured.raycast(p.ray, tmpHit)) { p.hit.set(tmpHit); p.hasHit = true }
            else p.hasHit = false
            if (pressed) {
                p.travel = maxOf(p.travel, p.hit.point.distance(p.pressStartPoint))
                fire(captured, PointerEventType.DRAG, p)
                if (!p.longPressFired && nowNs - p.pressStartNs > 650_000_000L && p.travel < 0.015f) {
                    p.longPressFired = true
                    fire(captured, PointerEventType.LONG_PRESS, p)
                }
            } else {
                fire(captured, PointerEventType.UP, p)
                if (p.hasHit && p.travel < 0.03f && !p.longPressFired) fire(captured, PointerEventType.CLICK, p)
                p.captured = null
            }
            p.wasDown = pressed
            return
        }

        val target: Interactable? = if (p.direct && p.directTarget != null) p.directTarget else raycastAll(p.ray, p.hit)
        p.hasHit = target != null
        if (target !== p.hover) {
            p.hover?.let { fire(it, PointerEventType.HOVER_EXIT, p) }
            p.hover = target
            target?.let { fire(it, PointerEventType.HOVER_ENTER, p) }
        } else if (target != null) fire(target, PointerEventType.HOVER, p)

        if (pressed && !p.wasDown && target != null) {
            p.captured = target
            p.pressStartNs = nowNs
            p.pressStartPoint.set(p.hit.point)
            p.longPressFired = false
            p.travel = 0f
            fire(target, PointerEventType.DOWN, p)
            if (forcePressed) {
                fire(target, PointerEventType.UP, p)
                fire(target, PointerEventType.CLICK, p)
                p.captured = null
            }
        }
        p.wasDown = pressed && !forcePressed
    }

    private fun fire(t: Interactable, type: PointerEventType, p: Pointer) {
        event.type = type
        event.pointer = p
        event.hit.set(p.hit)
        event.direct = p.direct
        try { t.onPointer(event) } catch (e: Exception) {
            android.util.Log.e("TrackMR-Input", "Interactable error", e)
        }
    }

    fun clear() { targets.clear() }
}
