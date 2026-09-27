package com.trackmr.hands

import com.trackmr.xr.XrSettings
import com.trackmr.xr.input.HandGesture
import com.trackmr.xr.input.HandJoint
import com.trackmr.xr.input.HandState
import com.trackmr.xr.input.Handedness
import com.trackmr.xr.input.HandsFrame
import com.trackmr.xr.math.MathUtil
import com.trackmr.xr.math.OneEuroFilter3
import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Vec3

/**
 * GL-thread stage of the hand pipeline: temporal association, One Euro filtering,
 * velocity-based prediction to the render time, pinch/gesture state machines and the
 * pointer ray. Allocation free per frame.
 */
class HandProcessor(private val settings: XrSettings) {

    private class Track(val side: Handedness) {
        val filters = Array(HandJoint.COUNT) { OneEuroFilter3(1.4f, 0.9f) }
        val filtered = Array(HandJoint.COUNT) { Vec3() }
        val velocity = Array(HandJoint.COUNT) { Vec3() }
        val rayFilter = OneEuroFilter3(0.9f, 1.5f)
        val rayDir = Vec3(0f, 0f, -1f)
        var lastMeasurementNs = 0L
        var measuredAtNs = 0L
        var active = false
        var pinchFrames = 0
        var releaseFrames = 0
        var pinching = false
        var candidateGesture = HandGesture.NONE
        var candidateFrames = 0
        var menuFired = false
        var wasGrabbing = false
        val image = FloatArray(42)
        var depth = 0f
        var score = 0f
    }

    private val tracks = arrayOf(Track(Handedness.LEFT), Track(Handedness.RIGHT))
    private var lastSequence = -1L
    private val tmp = Vec3()
    private val tmp2 = Vec3()
    private val tmp3 = Vec3()
    private val right = Vec3()

    fun update(raw: RawHands?, nowNs: Long, head: Pose, out: HandsFrame) {
        if (raw != null && raw.sequence != lastSequence) {
            lastSequence = raw.sequence
            ingest(raw)
        }
        for (t in tracks) produce(t, out.get(t.side), nowNs, head)
    }

    private fun ingest(raw: RawHands) {
        val tSec = raw.captureNs / 1e9
        var usedLeft = false; var usedRight = false
        for (k in 0 until raw.count) {
            val h = raw.hands[k]
            var side = h.side
            // Two hands with the same label: give the second one the free slot.
            if ((side == Handedness.LEFT && usedLeft) || (side == Handedness.RIGHT && usedRight)) {
                side = if (side == Handedness.LEFT) Handedness.RIGHT else Handedness.LEFT
            }
            if (side == Handedness.LEFT) usedLeft = true else usedRight = true
            val t = tracks[side.ordinal]
            // Re-acquired after a gap: reset filters to avoid dragging from stale positions.
            if (!t.active || raw.captureNs - t.lastMeasurementNs > 300_000_000L) {
                for (f in t.filters) f.reset()
                t.rayFilter.reset()
            }
            for (i in 0 until HandJoint.COUNT) {
                t.filters[i].filter(h.world[i], tSec, t.filtered[i])
                t.filters[i].velocity(t.velocity[i])
            }
            System.arraycopy(h.image, 0, t.image, 0, 42)
            t.depth = h.depth
            t.score = h.score
            t.lastMeasurementNs = raw.captureNs
            t.measuredAtNs = System.nanoTime()
            t.active = true
            updatePinch(t)
        }
    }

    /** Pinch on filtered measurements (never on predictions) with hysteresis + debounce. */
    private fun updatePinch(t: Track) {
        val palm = t.filtered[HandJoint.WRIST].distance(t.filtered[HandJoint.MIDDLE_MCP]).coerceAtLeast(0.04f)
        val ratio = t.filtered[HandJoint.THUMB_TIP].distance(t.filtered[HandJoint.INDEX_TIP]) / palm
        if (!t.pinching) {
            if (ratio < 0.24f) { t.pinchFrames++; if (t.pinchFrames >= 2) { t.pinching = true; t.releaseFrames = 0 } } else t.pinchFrames = 0
        } else {
            if (ratio > 0.40f) { t.releaseFrames++; if (t.releaseFrames >= 2) { t.pinching = false; t.pinchFrames = 0 } } else t.releaseFrames = 0
        }
    }

    private fun produce(t: Track, h: HandState, nowNs: Long, head: Pose) {
        h.justPinched = false; h.justReleased = false; h.menuGesture = false
        h.justGrabbed = false; h.justUngrabbed = false
        val sinceMeasure = (nowNs - t.measuredAtNs) / 1e9f
        val lost = !t.active || sinceMeasure > 0.25f
        if (lost) {
            if (h.isPinching) { h.isPinching = false; h.justReleased = true }
            if (h.isGrabbing) { h.isGrabbing = false; h.justUngrabbed = true }
            t.active = t.active && sinceMeasure < 1f
            t.pinching = false
            h.tracked = false
            h.rayValid = false
            h.presence = (h.presence - 0.12f).coerceAtLeast(0f)
            return
        }
        h.tracked = true
        h.presence = (h.presence + 0.2f).coerceAtMost(1f)
        h.confidence = t.score
        h.lastSeenNs = t.lastMeasurementNs
        h.depthMeters = t.depth
        System.arraycopy(t.image, 0, h.imagePoints, 0, 42)

        // Prediction: extrapolate to photon time; damp velocity when nearly still (kills jitter).
        val horizon = (((nowNs - t.lastMeasurementNs) / 1e9f) + settings.handPredictionMs / 1000f).coerceIn(0f, 0.1f)
        for (i in 0 until HandJoint.COUNT) {
            val v = t.velocity[i]
            val speed = v.length()
            val k = MathUtil.smoothstep(0.06f, 0.35f, speed) * horizon
            h.joints[i].set(t.filtered[i]).addScaled(v, k)
        }
        h.velocity.set(t.velocity[HandJoint.MIDDLE_MCP])
        h.indexTipVelocity.set(t.velocity[HandJoint.INDEX_TIP])

        derive(t, h, nowNs, head)
    }

    private fun derive(t: Track, h: HandState, nowNs: Long, head: Pose) {
        val j = h.joints
        val wrist = j[HandJoint.WRIST]
        h.palmSize = wrist.distance(j[HandJoint.MIDDLE_MCP]).coerceAtLeast(0.04f)
        // Palm center & normal
        h.palmCenter.set(wrist).add(j[HandJoint.INDEX_MCP]).add(j[HandJoint.MIDDLE_MCP]).add(j[HandJoint.PINKY_MCP]).scale(0.25f)
        tmp.setSub(j[HandJoint.INDEX_MCP], wrist)
        tmp2.setSub(j[HandJoint.PINKY_MCP], wrist)
        h.palmNormal.setCross(tmp, tmp2).normalize()
        if (h.side == Handedness.LEFT) h.palmNormal.scale(-1f)
        // Palm pose: -Z toward fingers, +Y = palm normal
        tmp3.setSub(j[HandJoint.MIDDLE_MCP], wrist).normalize()
        h.palmPose.p.set(h.palmCenter)
        h.palmPose.q.setLookRotation(tmp3, h.palmNormal)
        tmp.setSub(head.p, h.palmCenter).normalize()
        h.palmFacingUser = h.palmNormal.dot(tmp) > 0.55f

        // Pinch
        val ratio = j[HandJoint.THUMB_TIP].distance(j[HandJoint.INDEX_TIP]) / h.palmSize
        h.pinchStrength = 1f - MathUtil.smoothstep(0.2f, 0.6f, ratio)
        h.pinchPoint.set(j[HandJoint.THUMB_TIP]).add(j[HandJoint.INDEX_TIP]).scale(0.5f)
        if (t.pinching && !h.isPinching) { h.isPinching = true; h.justPinched = true; h.pinchStartNs = nowNs }
        else if (!t.pinching && h.isPinching) { h.isPinching = false; h.justReleased = true; t.menuFired = false }

        // Gestures (debounced over 3 frames, pinch is immediate)
        val idx = extended(j, HandJoint.INDEX_PIP, HandJoint.INDEX_TIP)
        val mid = extended(j, HandJoint.MIDDLE_PIP, HandJoint.MIDDLE_TIP)
        val ring = extended(j, HandJoint.RING_PIP, HandJoint.RING_TIP)
        val pinky = extended(j, HandJoint.PINKY_PIP, HandJoint.PINKY_TIP)
        val thumbOut = j[HandJoint.THUMB_TIP].distance(j[HandJoint.PINKY_MCP]) > j[HandJoint.THUMB_IP].distance(j[HandJoint.PINKY_MCP]) * 1.08f
        val g = when {
            h.isPinching -> HandGesture.PINCH
            !idx && !mid && !ring && !pinky && thumbOut && j[HandJoint.THUMB_TIP].y > j[HandJoint.INDEX_MCP].y + 0.035f -> HandGesture.THUMBS_UP
            !idx && !mid && !ring && !pinky -> HandGesture.GRAB
            idx && !mid && !ring && !pinky -> HandGesture.POINT
            idx && mid && ring && pinky -> HandGesture.OPEN_PALM
            else -> HandGesture.NONE
        }
        if (g == HandGesture.PINCH) { t.candidateGesture = g; t.candidateFrames = 3 }
        else if (g == t.candidateGesture) t.candidateFrames++ else { t.candidateGesture = g; t.candidateFrames = 1 }
        if (t.candidateFrames >= 3 && h.gesture != t.candidateGesture) {
            h.previousGesture = h.gesture
            h.gesture = t.candidateGesture
            h.gestureStartNs = nowNs
        }
        val grabbing = h.gesture == HandGesture.GRAB
        if (grabbing && !t.wasGrabbing) h.justGrabbed = true
        if (!grabbing && t.wasGrabbing) h.justUngrabbed = true
        t.wasGrabbing = grabbing
        h.isGrabbing = grabbing

        // System menu gesture: palm toward the face + pinch held 0.5 s.
        if (h.isPinching && h.palmFacingUser && !t.menuFired && h.pinchHeldSeconds(nowNs) > 0.5f) {
            h.menuGesture = true; t.menuFired = true
        }

        // Pointer ray: from an estimated shoulder through a pinch-stable point on the hand.
        head.right(right)
        right.y = 0f; right.normalize()
        val side = if (h.side == Handedness.RIGHT) 0.16f else -0.16f
        tmp.set(head.p).addScaled(right, side).add(0f, -0.2f, 0f)            // shoulder
        tmp2.set(j[HandJoint.INDEX_MCP]).add(j[HandJoint.THUMB_MCP]).scale(0.5f) // anchor
        tmp3.setSub(tmp2, tmp).normalize()
        t.rayFilter.filter(tmp3, nowNs / 1e9, t.rayDir)
        t.rayDir.normalize()
        h.rayOrigin.set(tmp2)
        h.rayDir.set(t.rayDir)
        // No ray while the palm faces the user (system gesture / wrist menu) or while pointing a fist.
        h.rayValid = !h.palmFacingUser && h.gesture != HandGesture.GRAB
    }

    private fun extended(j: Array<Vec3>, pip: Int, tip: Int): Boolean {
        val w = j[HandJoint.WRIST]
        return j[tip].distance(w) > j[pip].distance(w) * 1.12f
    }
}
