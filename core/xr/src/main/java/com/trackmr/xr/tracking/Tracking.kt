package com.trackmr.xr.tracking

import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Ray
import com.trackmr.xr.math.Vec3
import java.nio.ByteBuffer

enum class TrackingQuality { NONE, ORIENTATION_ONLY, LIMITED, FULL_6DOF }

/** A detected real-world plane (ARCore) or a synthetic floor (fallback). */
class TrackedPlane {
    @JvmField var id = 0L
    @JvmField val centerPose = Pose()
    @JvmField var extentX = 0f
    @JvmField var extentZ = 0f
    /** Polygon in plane-local XZ (x0,z0,x1,z1,...). */
    @JvmField var polygon = FloatArray(0)
    @JvmField var polygonSize = 0
    @JvmField var type = Type.HORIZONTAL_UP
    enum class Type { HORIZONTAL_UP, HORIZONTAL_DOWN, VERTICAL }
    val normal: Vec3 = Vec3(0f, 1f, 0f)
}

/**
 * Environment depth map in the physical camera's image space (ARCore Depth API).
 * 16-bit millimeters, little endian.
 */
class DepthFrame {
    @JvmField var buffer: ByteBuffer? = null
    @JvmField var width = 0
    @JvmField var height = 0
    @JvmField var rowStride = 0
    @JvmField var timestampNs = 0L
    /** fx, fy, cx, cy normalized by width/height. */
    @JvmField val intrinsics = FloatArray(4)
    @JvmField val cameraPose = Pose()
    @JvmField var valid = false
    @JvmField var sequence = 0L
}

/** Per-frame tracking output. Owned by the renderer and reused each frame. */
class TrackingFrame {
    @JvmField var timestampNs = 0L
    @JvmField val headPose = Pose()
    @JvmField var quality = TrackingQuality.NONE
    @JvmField var trackingLostReason: String? = null

    // Passthrough camera background (view space of the display-oriented camera).
    @JvmField var cameraTextureId = 0
    @JvmField var backgroundValid = false
    /** 4 corners (x,y,z) in head/view space: bottom-left, bottom-right, top-left, top-right. */
    @JvmField val backgroundCorners = FloatArray(12)
    /** Matching texture coordinates (u,v) for the 4 corners. */
    @JvmField val backgroundUvs = FloatArray(8)
    /** Physical camera horizontal/vertical tan half-FOV (for MR "fill" mode). */
    @JvmField var cameraTanHalfX = 0.6f
    @JvmField var cameraTanHalfY = 0.45f

    // Light estimation (MR realism)
    @JvmField var lightValid = false
    @JvmField var ambientIntensity = 1f
    @JvmField val colorCorrection = floatArrayOf(1f, 1f, 1f, 1f)
    @JvmField val mainLightDir = Vec3(0f, -1f, 0f)

    @JvmField val planes = ArrayList<TrackedPlane>(16)
    @JvmField var planesVersion = 0L
    @JvmField val depth = DepthFrame()
    /** Estimated floor height (world Y) when known. */
    @JvmField var floorY = Float.NaN
}

/**
 * A camera image in CPU memory (YUV_420_888), exposed without copies. Must be closed
 * quickly — the underlying buffers belong to the camera pipeline.
 */
interface CpuImage : AutoCloseable {
    val width: Int
    val height: Int
    val timestampNs: Long
    val yBuffer: ByteBuffer
    val uBuffer: ByteBuffer
    val vBuffer: ByteBuffer
    val yRowStride: Int
    val uvRowStride: Int
    val uvPixelStride: Int
    /** fx, fy, cx, cy in pixels of this image. */
    val intrinsics: FloatArray
    /** World pose of the physical camera (x right, y up relative to image readout, -z forward). */
    val cameraPose: Pose
}

class HitResult {
    @JvmField val pose = Pose()
    @JvmField var distance = 0f
    @JvmField var planeType: TrackedPlane.Type? = null
    @JvmField var fromDepth = false
}

interface XrAnchor {
    val pose: Pose
    val isTracking: Boolean
    fun update(): Boolean
    fun detach()
}

class TrackingCapabilities(
    val sixDof: Boolean,
    val passthrough: Boolean,
    val planes: Boolean,
    val depth: Boolean,
    val anchors: Boolean,
    val lightEstimation: Boolean,
    val name: String,
)

/**
 * Source of head pose + passthrough camera. Implementations: ARCore (6DoF), Camera2 +
 * rotation-vector sensor (3DoF fallback), sensor-only (VR without camera).
 * All methods except [release] are invoked on the GL thread.
 */
interface TrackingProvider {
    val capabilities: TrackingCapabilities
    fun onGlReady()
    fun setDisplayGeometry(rotation: Int, width: Int, height: Int)
    fun resume(): Boolean
    fun pause()
    /** Updates tracking; returns false when no new data could be produced this frame. */
    fun update(frame: TrackingFrame): Boolean
    /** Latest camera image for computer vision, or null. Caller must close it. */
    fun acquireCpuImage(): CpuImage?
    fun hitTest(ray: Ray, out: HitResult): Boolean
    fun createAnchor(pose: Pose): XrAnchor?
    /** Re-centres yaw so the current heading becomes forward. */
    fun recenter() {}
    fun release()
}
