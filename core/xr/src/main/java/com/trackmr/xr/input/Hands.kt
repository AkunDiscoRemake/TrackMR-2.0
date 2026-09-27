package com.trackmr.xr.input

import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Vec3

enum class Handedness { LEFT, RIGHT }

enum class HandGesture { NONE, OPEN_PALM, POINT, PINCH, GRAB, THUMBS_UP }

/** MediaPipe / OpenXR-compatible 21 landmark indices. */
object HandJoint {
    const val WRIST = 0
    const val THUMB_CMC = 1; const val THUMB_MCP = 2; const val THUMB_IP = 3; const val THUMB_TIP = 4
    const val INDEX_MCP = 5; const val INDEX_PIP = 6; const val INDEX_DIP = 7; const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9; const val MIDDLE_PIP = 10; const val MIDDLE_DIP = 11; const val MIDDLE_TIP = 12
    const val RING_MCP = 13; const val RING_PIP = 14; const val RING_DIP = 15; const val RING_TIP = 16
    const val PINKY_MCP = 17; const val PINKY_PIP = 18; const val PINKY_DIP = 19; const val PINKY_TIP = 20
    const val COUNT = 21

    /** Bone pairs for the skeleton (20 bones + palm arch). */
    @JvmField val BONES = intArrayOf(
        0, 1, 1, 2, 2, 3, 3, 4,
        0, 5, 5, 6, 6, 7, 7, 8,
        5, 9, 9, 10, 10, 11, 11, 12,
        9, 13, 13, 14, 14, 15, 15, 16,
        13, 17, 0, 17, 17, 18, 18, 19, 19, 20,
    )
    @JvmField val TIPS = intArrayOf(THUMB_TIP, INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)
}

/**
 * Filtered, predicted state of one hand in world space. Produced by the hand tracking
 * module on the GL thread; consumed by input, UI, depth/occlusion and the Lua game API.
 */
class HandState(val side: Handedness) {
    @JvmField var tracked = false
    /** 0..1 visibility used to fade the skeleton in/out instead of popping. */
    @JvmField var presence = 0f
    @JvmField var confidence = 0f
    @JvmField var lastSeenNs = 0L
    @JvmField val joints = Array(HandJoint.COUNT) { Vec3() }
    /** Normalized image coordinates of the raw landmarks (for 2D ROI / debug). */
    @JvmField val imagePoints = FloatArray(HandJoint.COUNT * 2)

    @JvmField val palmCenter = Vec3()
    @JvmField val palmNormal = Vec3()
    @JvmField val palmPose = Pose()
    @JvmField val velocity = Vec3()
    @JvmField val indexTipVelocity = Vec3()
    /** Distance from the physical camera to the wrist, meters. */
    @JvmField var depthMeters = 0f
    @JvmField var palmSize = 0.085f

    // Pinch
    @JvmField var pinchStrength = 0f
    @JvmField var isPinching = false
    @JvmField var pinchStartNs = 0L
    @JvmField var justPinched = false
    @JvmField var justReleased = false
    @JvmField val pinchPoint = Vec3()

    // Gestures
    @JvmField var gesture = HandGesture.NONE
    @JvmField var previousGesture = HandGesture.NONE
    @JvmField var gestureStartNs = 0L
    @JvmField var palmFacingUser = false
    @JvmField var isGrabbing = false
    @JvmField var justGrabbed = false
    @JvmField var justUngrabbed = false
    /** Menu gesture (palm toward user + pinch held) fired this frame. */
    @JvmField var menuGesture = false

    // Pointer ray (far interaction)
    @JvmField val rayOrigin = Vec3()
    @JvmField val rayDir = Vec3(0f, 0f, -1f)
    @JvmField var rayValid = false

    fun pinchHeldSeconds(nowNs: Long): Float = if (isPinching) (nowNs - pinchStartNs) / 1e9f else 0f

    fun copyFrom(o: HandState) {
        tracked = o.tracked; presence = o.presence; confidence = o.confidence; lastSeenNs = o.lastSeenNs
        for (i in 0 until HandJoint.COUNT) joints[i].set(o.joints[i])
        System.arraycopy(o.imagePoints, 0, imagePoints, 0, imagePoints.size)
        palmCenter.set(o.palmCenter); palmNormal.set(o.palmNormal); palmPose.set(o.palmPose)
        velocity.set(o.velocity); indexTipVelocity.set(o.indexTipVelocity)
        depthMeters = o.depthMeters; palmSize = o.palmSize
        pinchStrength = o.pinchStrength; isPinching = o.isPinching; pinchStartNs = o.pinchStartNs
        justPinched = o.justPinched; justReleased = o.justReleased; pinchPoint.set(o.pinchPoint)
        gesture = o.gesture; previousGesture = o.previousGesture; gestureStartNs = o.gestureStartNs
        palmFacingUser = o.palmFacingUser; isGrabbing = o.isGrabbing
        justGrabbed = o.justGrabbed; justUngrabbed = o.justUngrabbed; menuGesture = o.menuGesture
        rayOrigin.set(o.rayOrigin); rayDir.set(o.rayDir); rayValid = o.rayValid
    }
}

class HandsFrame {
    @JvmField val left = HandState(Handedness.LEFT)
    @JvmField val right = HandState(Handedness.RIGHT)
    @JvmField var inferenceFps = 0f
    @JvmField var latencyMs = 0f
    @JvmField var source = "none"
    fun get(side: Handedness) = if (side == Handedness.LEFT) left else right
    inline fun forEach(block: (HandState) -> Unit) { block(left); block(right) }
}

/** Implemented by the hand tracking module (MediaPipe) or OpenXR (XR_EXT_hand_tracking). */
interface HandTrackingProvider {
    val isAvailable: Boolean
    val statusText: String
    /** Offers a camera frame; the provider decides whether to process or skip it. */
    fun wantsFrame(nowNs: Long): Boolean
    fun submit(image: com.trackmr.xr.tracking.CpuImage)
    /** Produces filtered + predicted hands for [renderTimeNs]. GL thread. */
    fun update(renderTimeNs: Long, headPose: Pose, out: HandsFrame)
    fun release()
}
