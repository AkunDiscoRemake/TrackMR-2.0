package com.trackmr.arcore

import android.app.Activity
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.trackmr.xr.XrSettings
import com.trackmr.xr.gl.GlUtil
import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Quat
import com.trackmr.xr.math.Ray
import com.trackmr.xr.math.Vec3
import com.trackmr.xr.tracking.CpuImage
import com.trackmr.xr.tracking.HitResult
import com.trackmr.xr.tracking.TrackedPlane
import com.trackmr.xr.tracking.TrackingCapabilities
import com.trackmr.xr.tracking.TrackingFrame
import com.trackmr.xr.tracking.TrackingProvider
import com.trackmr.xr.tracking.TrackingQuality
import com.trackmr.xr.tracking.XrAnchor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet

/**
 * 6DoF tracking through ARCore: SLAM head pose, planes, anchors, light estimation, depth,
 * and the passthrough camera texture. A TrackMR "origin" transform sits between ARCore world
 * space and TrackMR world space so that re-centering never disturbs ARCore itself.
 */
class ArCoreTrackingProvider(private val activity: Activity, private val settings: XrSettings) : TrackingProvider {

    private var session: Session? = null
    private var frame: Frame? = null
    private var camera: Camera? = null
    private var cameraTex = 0
    private var displayRotation = 1
    private var geometryDirty = true
    private var depthEnabled = false
    private var lastDepthTs = 0L
    private var depthBuffer: ByteBuffer? = null
    private var frameCount = 0L
    private var lastCameraTs = 0L

    /** ARCore world → TrackMR world. */
    private val origin = Pose()
    private val originInv = Pose()

    private val ndc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uvs = FloatArray(8)
    private val proj = FloatArray(16)
    private val invProj = FloatArray(16)
    private val tmp4 = FloatArray(4)
    private val tmp4b = FloatArray(4)
    private val arT = FloatArray(3)
    private val arQ = FloatArray(4)
    private val planePool = ArrayList<TrackedPlane>()
    private val tmpPose = Pose()

    override var capabilities = TrackingCapabilities(true, true, true, false, true, true, "ARCore")
        private set

    var lastError: String? = null; private set

    companion object {
        private const val TAG = "TrackMR-ARCore"

        /** Non-blocking availability check (never opens Play Store / 2D dialogs). */
        fun isInstalledAndSupported(activity: Activity): Boolean = try {
            ArCoreApk.getInstance().checkAvailability(activity) == ArCoreApk.Availability.SUPPORTED_INSTALLED
        } catch (e: Throwable) { false }

        fun isSupportedButNotInstalled(activity: Activity): Boolean = try {
            val a = ArCoreApk.getInstance().checkAvailability(activity)
            a == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED || a == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD
        } catch (e: Throwable) { false }

        /** User-initiated install (from a spatial button). */
        fun requestInstall(activity: Activity) {
            try { ArCoreApk.getInstance().requestInstall(activity, true) } catch (e: Throwable) { Log.w(TAG, "install: ${e.message}") }
        }
    }

    override fun onGlReady() {
        cameraTex = GlUtil.genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        session?.setCameraTextureName(cameraTex)
    }

    override fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        displayRotation = rotation
        geometryDirty = true
    }

    override fun resume(): Boolean {
        try {
            val s = session ?: Session(activity).also { session = it; configure(it) }
            if (cameraTex != 0) s.setCameraTextureName(cameraTex)
            s.resume()
            geometryDirty = true
            return true
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            Log.e(TAG, "ARCore resume failed", e)
            session?.close(); session = null
            return false
        }
    }

    private fun configure(s: Session) {
        // Camera config: prefer 60 fps (fluid passthrough), then a small CPU image for hands.
        val filter = CameraConfigFilter(s)
        filter.setFacingDirection(CameraConfig.FacingDirection.BACK)
        val configs = try {
            filter.setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_60))
            s.getSupportedCameraConfigs(filter).ifEmpty {
                filter.setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
                s.getSupportedCameraConfigs(filter)
            }
        } catch (e: Throwable) { emptyList() }
        val chosen = configs.minByOrNull { c ->
            val img = c.imageSize
            val tex = c.textureSize
            val cpuScore = kotlin.math.abs(img.width * img.height - 640 * 480) / 1000
            val texScore = kotlin.math.abs(tex.width - 1920) / 10
            cpuScore + texScore
        }
        if (chosen != null) s.cameraConfig = chosen

        val cfg = Config(s)
        cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        cfg.focusMode = Config.FocusMode.AUTO
        cfg.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        cfg.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
        cfg.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        depthEnabled = settings.depthApi && s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        cfg.depthMode = if (depthEnabled) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        s.configure(cfg)
        capabilities = TrackingCapabilities(true, true, true, depthEnabled, true, true, "ARCore" + if (depthEnabled) " + Depth" else "")
        Log.i(TAG, "ARCore configured: tex=${s.cameraConfig.textureSize} cpu=${s.cameraConfig.imageSize} fps=${s.cameraConfig.fpsRange} depth=$depthEnabled")
    }

    override fun pause() { try { session?.pause() } catch (e: Throwable) { Log.w(TAG, "pause", e) } }

    private fun updateDisplayGeometry(s: Session) {
        val tex = s.cameraConfig.textureSize
        // Geometry == texture aspect so ARCore's projection covers the whole camera image.
        val w = maxOf(tex.width, tex.height); val h = minOf(tex.width, tex.height)
        s.setDisplayGeometry(displayRotation, w, h)
        geometryDirty = false
    }

    override fun update(frame: TrackingFrame): Boolean {
        val s = session ?: return false
        if (geometryDirty) updateDisplayGeometry(s)
        val f = try { s.update() } catch (e: Throwable) {
            frame.trackingLostReason = e.javaClass.simpleName
            return false
        }
        this.frame = f
        val cam = f.camera
        camera = cam
        frameCount++
        frame.timestampNs = f.timestamp
        frame.cameraTextureId = cameraTex

        when (cam.trackingState) {
            TrackingState.TRACKING -> { frame.quality = TrackingQuality.FULL_6DOF; frame.trackingLostReason = null }
            TrackingState.PAUSED -> { frame.quality = TrackingQuality.LIMITED; frame.trackingLostReason = cam.trackingFailureReason.name }
            else -> { frame.quality = TrackingQuality.NONE }
        }
        // Head = display-oriented physical camera, expressed in TrackMR world.
        toTrackmr(cam.displayOrientedPose, frame.headPose)

        // Passthrough background geometry (only when geometry changes).
        if (f.hasDisplayGeometryChanged() || !frame.backgroundValid || frameCount % 120L == 0L) {
            f.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc, Coordinates2d.TEXTURE_NORMALIZED, uvs)
            System.arraycopy(uvs, 0, frame.backgroundUvs, 0, 8)
            cam.getProjectionMatrix(proj, 0, 0.1f, 100f)
            Matrix.invertM(invProj, 0, proj, 0)
            for (i in 0..3) {
                tmp4[0] = ndc[i * 2]; tmp4[1] = ndc[i * 2 + 1]; tmp4[2] = 1f; tmp4[3] = 1f
                Matrix.multiplyMV(tmp4b, 0, invProj, 0, tmp4, 0)
                val w = if (kotlin.math.abs(tmp4b[3]) < 1e-6f) 1f else tmp4b[3]
                frame.backgroundCorners[i * 3] = tmp4b[0] / w
                frame.backgroundCorners[i * 3 + 1] = tmp4b[1] / w
                frame.backgroundCorners[i * 3 + 2] = tmp4b[2] / w
            }
            frame.cameraTanHalfX = 1f / proj[0]
            frame.cameraTanHalfY = 1f / proj[5]
            frame.backgroundValid = true
        }

        // Light estimation
        val le = f.lightEstimate
        if (le.state == LightEstimate.State.VALID) {
            frame.lightValid = true
            frame.ambientIntensity = le.pixelIntensity
            le.getColorCorrection(frame.colorCorrection, 0)
        } else frame.lightValid = false

        if (frameCount % 15L == 0L) updatePlanes(s, frame)
        if (depthEnabled && settings.occlusion) updateDepth(f, cam, frame) else frame.depth.valid = false
        lastCameraTs = f.timestamp
        return true
    }

    private fun updatePlanes(s: Session, out: TrackingFrame) {
        out.planes.clear()
        var floor = Float.NaN
        var idx = 0
        for (p in s.getAllTrackables(Plane::class.java)) {
            if (p.trackingState != TrackingState.TRACKING || p.subsumedBy != null) continue
            val tp = if (idx < planePool.size) planePool[idx] else TrackedPlane().also { planePool.add(it) }
            idx++
            tp.id = System.identityHashCode(p).toLong()
            toTrackmr(p.centerPose, tp.centerPose)
            tp.extentX = p.extentX; tp.extentZ = p.extentZ
            val poly = p.polygon
            val n = poly.remaining()
            if (tp.polygon.size < n) tp.polygon = FloatArray(n)
            poly.get(tp.polygon, 0, n)
            tp.polygonSize = n
            tp.type = when (p.type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING -> TrackedPlane.Type.HORIZONTAL_UP
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> TrackedPlane.Type.HORIZONTAL_DOWN
                else -> TrackedPlane.Type.VERTICAL
            }
            tp.centerPose.up(tp.normal)
            if (tp.type == TrackedPlane.Type.HORIZONTAL_UP) {
                val y = tp.centerPose.p.y
                if (floor.isNaN() || y < floor) floor = y
            }
            out.planes.add(tp)
        }
        out.planesVersion++
        // Floor = lowest upward plane at least 0.5 m below the head.
        out.floorY = if (!floor.isNaN() && floor < out.headPose.p.y - 0.5f) floor else Float.NaN
    }

    private fun updateDepth(f: Frame, cam: Camera, out: TrackingFrame) {
        try {
            f.acquireDepthImage16Bits().use { img ->
                if (img.timestamp == lastDepthTs) return
                lastDepthTs = img.timestamp
                val plane = img.planes[0]
                val src = plane.buffer
                val size = src.remaining()
                val dst = depthBuffer?.takeIf { it.capacity() >= size } ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { depthBuffer = it }
                dst.clear(); dst.put(src); dst.flip()
                val d = out.depth
                d.buffer = dst
                d.width = img.width; d.height = img.height
                d.rowStride = plane.rowStride
                d.timestampNs = img.timestamp
                val intr = cam.imageIntrinsics
                val fl = intr.focalLength; val pp = intr.principalPoint; val dims = intr.imageDimensions
                d.intrinsics[0] = fl[0] / dims[0]; d.intrinsics[1] = fl[1] / dims[1]
                d.intrinsics[2] = pp[0] / dims[0]; d.intrinsics[3] = pp[1] / dims[1]
                toTrackmr(cam.pose, d.cameraPose)
                d.valid = true
                d.sequence++
            }
        } catch (e: NotYetAvailableException) {
            // Depth warms up during the first frames.
        } catch (e: Throwable) {
            Log.w(TAG, "depth: ${e.message}")
        }
    }

    override fun acquireCpuImage(): CpuImage? {
        val f = frame ?: return null
        val cam = camera ?: return null
        if (cam.trackingState == TrackingState.STOPPED) return null
        val img = try { f.acquireCameraImage() } catch (e: NotYetAvailableException) { return null } catch (e: Throwable) { return null }
        val intr = cam.imageIntrinsics
        val fl = intr.focalLength; val pp = intr.principalPoint
        val pose = Pose()
        toTrackmr(cam.pose, pose)
        return ArCpuImage(img, floatArrayOf(fl[0], fl[1], pp[0], pp[1]), pose)
    }

    override fun hitTest(ray: Ray, out: HitResult): Boolean {
        val f = frame ?: return false
        val o = Vec3(); val d = Vec3()
        originInv.transformPoint(ray.origin, o)
        originInv.transformDir(ray.dir, d)
        val hits = try { f.hitTest(floatArrayOf(o.x, o.y, o.z), 0, floatArrayOf(d.x, d.y, d.z), 0) } catch (e: Throwable) { return false }
        for (h in hits) {
            val t = h.trackable
            val ok = when (t) {
                is Plane -> t.isPoseInPolygon(h.hitPose)
                is DepthPoint -> true
                is Point -> t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
            if (!ok) continue
            toTrackmr(h.hitPose, out.pose)
            out.distance = h.distance
            out.fromDepth = t is DepthPoint
            out.planeType = if (t is Plane) (if (t.type == Plane.Type.VERTICAL) TrackedPlane.Type.VERTICAL else TrackedPlane.Type.HORIZONTAL_UP) else null
            return true
        }
        return false
    }

    override fun createAnchor(pose: Pose): XrAnchor? {
        val s = session ?: return null
        val ar = Pose().setCompose(originInv, pose)
        return try { ArAnchor(s.createAnchor(toAr(ar))) } catch (e: Throwable) { null }
    }

    /** Yaw re-center around the current head position. */
    override fun recenter() {
        val cam = camera ?: return
        val head = Pose(); fromAr(cam.displayOrientedPose, head)
        val yaw = head.q.yaw()
        val rot = Quat().setAxisAngle(0f, 1f, 0f, -yaw)
        // origin' = T(p) * R * T(-p) with p = head position in ARCore space
        val p = head.p
        val rp = Vec3(); rot.rotate(p, rp)
        origin.q.set(rot)
        origin.p.set(p.x - rp.x, p.y - rp.y, p.z - rp.z)
        originInv.setInverse(origin)
    }

    override fun release() {
        try { session?.close() } catch (e: Throwable) { }
        session = null
    }

    // ---- pose helpers ----
    private fun fromAr(src: com.google.ar.core.Pose, out: Pose) {
        src.getTranslation(arT, 0); src.getRotationQuaternion(arQ, 0)
        out.p.set(arT[0], arT[1], arT[2]); out.q.set(arQ[0], arQ[1], arQ[2], arQ[3])
    }

    private fun toTrackmr(src: com.google.ar.core.Pose, out: Pose) {
        fromAr(src, tmpPose)
        out.setCompose(origin, tmpPose)
    }

    private fun toAr(p: Pose) = com.google.ar.core.Pose(floatArrayOf(p.p.x, p.p.y, p.p.z), floatArrayOf(p.q.x, p.q.y, p.q.z, p.q.w))

    private inner class ArAnchor(private val anchor: Anchor) : XrAnchor {
        override val pose = Pose()
        override val isTracking get() = anchor.trackingState == TrackingState.TRACKING
        override fun update(): Boolean {
            if (anchor.trackingState != TrackingState.TRACKING) return false
            val t = Pose(); fromAr(anchor.pose, t)
            pose.setCompose(origin, t)
            return true
        }
        override fun detach() = anchor.detach()
    }

    private class ArCpuImage(private val img: android.media.Image, override val intrinsics: FloatArray, override val cameraPose: Pose) : CpuImage {
        override val width = img.width
        override val height = img.height
        override val timestampNs = img.timestamp
        override val yBuffer: ByteBuffer = img.planes[0].buffer
        override val uBuffer: ByteBuffer = img.planes[1].buffer
        override val vBuffer: ByteBuffer = img.planes[2].buffer
        override val yRowStride = img.planes[0].rowStride
        override val uvRowStride = img.planes[1].rowStride
        override val uvPixelStride = img.planes[1].pixelStride
        override fun close() = img.close()
    }
}
