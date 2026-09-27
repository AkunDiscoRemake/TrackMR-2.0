package com.trackmr.mr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.opengl.GLES11Ext
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.trackmr.xr.gl.GlUtil
import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Quat
import com.trackmr.xr.math.Ray
import com.trackmr.xr.math.Vec3
import com.trackmr.xr.tracking.CpuImage
import com.trackmr.xr.tracking.HitResult
import com.trackmr.xr.tracking.TrackingCapabilities
import com.trackmr.xr.tracking.TrackingFrame
import com.trackmr.xr.tracking.TrackingProvider
import com.trackmr.xr.tracking.TrackingQuality
import com.trackmr.xr.tracking.XrAnchor
import java.nio.ByteBuffer
import kotlin.math.atan
import kotlin.math.tan

/**
 * Fallback tracking when ARCore is unavailable: Camera2 passthrough + game rotation vector
 * (3DoF) with a neck model. Also serves as a pure 3DoF VR tracker when [useCamera] is false.
 */
class Camera2TrackingProvider(
    private val activity: Activity,
    private val useCamera: Boolean,
) : TrackingProvider, SensorEventListener {

    override val capabilities = TrackingCapabilities(false, useCamera, false, false, false, false,
        if (useCamera) "Camera2 + IMU (3DoF)" else "IMU (3DoF)")

    private val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var device: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var cameraTex = 0
    @Volatile private var frameAvailable = false
    private val stMatrix = FloatArray(16)
    private var previewSize = Size(1280, 720)
    private var sensorOrientation = 90
    private var tanHalfX = 0.62f
    private var tanHalfY = 0.35f
    private var focalPx = 500f
    private var displayRotation = 1

    private val rotMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val latestQuat = FloatArray(4)
    @Volatile private var hasRotation = false
    private val yawOffset = Quat()
    private val headRot = Quat()
    private val tmpQ = Quat()
    private val neck = Vec3()

    override fun onGlReady() {
        if (!useCamera) return
        cameraTex = GlUtil.genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        surfaceTexture = SurfaceTexture(cameraTex).also { st ->
            st.setOnFrameAvailableListener { frameAvailable = true }
        }
        if (device != null) startSession()
    }

    override fun setDisplayGeometry(rotation: Int, width: Int, height: Int) { displayRotation = rotation }

    override fun resume(): Boolean {
        val rv = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rv != null) sensorManager.registerListener(this, rv, SensorManager.SENSOR_DELAY_FASTEST)
        if (useCamera) openCamera()
        return true
    }

    override fun pause() {
        sensorManager.unregisterListener(this)
        closeCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            val id = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            val ch = cameraManager.getCameraCharacteristics(id)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            previewSize = sizes.filter { it.width <= 1920 && it.width * 9 == it.height * 16 }.maxByOrNull { it.width }
                ?: sizes.minByOrNull { kotlin.math.abs(it.width - 1280) } ?: Size(1280, 720)
            // FOV from physical sensor size + focal length.
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            if (phys != null && focal != null && focal > 0f) {
                tanHalfX = (phys.width / 2f) / focal
                // Preview is cropped to its aspect ratio from the full sensor width.
                tanHalfY = tanHalfX * previewSize.height / previewSize.width
            }
            cameraThread = HandlerThread("TrackMR-Camera2").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
            focalPx = 320f / tanHalfX
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) { device = cd; if (surfaceTexture != null) startSession() }
                override fun onDisconnected(cd: CameraDevice) { cd.close(); device = null }
                override fun onError(cd: CameraDevice, error: Int) { cd.close(); device = null; Log.e(TAG, "camera error $error") }
            }, cameraHandler)
        } catch (e: Exception) { Log.e(TAG, "openCamera failed", e) }
    }

    @Suppress("DEPRECATION")
    private fun startSession() {
        val cd = device ?: return
        val st = surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(st).also { previewSurface = it }
        val reader = imageReader ?: return
        try {
            cd.createCaptureSession(listOf(surface, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    captureSession = s
                    val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface); addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                    try { s.setRepeatingRequest(req.build(), null, cameraHandler) } catch (e: Exception) { Log.e(TAG, "repeat", e) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { Log.e(TAG, "configure failed") }
            }, cameraHandler)
        } catch (e: Exception) { Log.e(TAG, "session failed", e) }
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        captureSession = null; device = null; imageReader = null
        cameraThread?.quitSafely(); cameraThread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        // Landscape remap (display rotation 90° is the VR Box orientation).
        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
            else -> System.arraycopy(rotMatrix, 0, remapped, 0, 9)
        }
        // Sensor world (E, N, Up) → GL world (x=E, y=Up, z=-N). Columns = display axes in world.
        val m = remapped
        tmpQ.setFromBasis(
            m[0], m[6], -m[3],
            m[1], m[7], -m[4],
            m[2], m[8], -m[5],
        )
        synchronized(latestQuat) {
            latestQuat[0] = tmpQ.x; latestQuat[1] = tmpQ.y; latestQuat[2] = tmpQ.z; latestQuat[3] = tmpQ.w
        }
        if (!hasRotation) { hasRotation = true; pendingRecenter = true }
    }

    @Volatile private var pendingRecenter = false

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun update(frame: TrackingFrame): Boolean {
        synchronized(latestQuat) { headRot.set(latestQuat[0], latestQuat[1], latestQuat[2], latestQuat[3]) }
        if (pendingRecenter) { pendingRecenter = false; recenter() }
        frame.headPose.q.setMul(yawOffset, headRot).normalize()
        // Neck model: eyes rotate around a pivot 10 cm below / 8 cm behind them.
        frame.headPose.q.rotate(0f, 0.1f, -0.08f, neck)
        frame.headPose.p.set(neck.x, 1.6f + neck.y - 0.1f, neck.z + 0.08f)
        frame.quality = if (hasRotation) TrackingQuality.ORIENTATION_ONLY else TrackingQuality.NONE
        frame.timestampNs = System.nanoTime()
        frame.floorY = 0f

        if (useCamera && surfaceTexture != null) {
            if (frameAvailable) {
                frameAvailable = false
                try { surfaceTexture!!.updateTexImage(); surfaceTexture!!.getTransformMatrix(stMatrix) } catch (_: Exception) {}
            }
            frame.cameraTextureId = cameraTex
            val d = 50f
            val c = frame.backgroundCorners
            val xs = floatArrayOf(-1f, 1f, -1f, 1f); val ys = floatArrayOf(-1f, -1f, 1f, 1f)
            // Relative image rotation: sensor vs display (0 or 180 in landscape).
            val flip = ((sensorOrientation - (if (displayRotation == Surface.ROTATION_270) 270 else 90) + 360) % 360) == 180
            for (i in 0..3) {
                c[i * 3] = xs[i] * tanHalfX * d; c[i * 3 + 1] = ys[i] * tanHalfY * d; c[i * 3 + 2] = -d
                var u = (xs[i] + 1f) / 2f; var v = (ys[i] + 1f) / 2f
                if (flip) { u = 1f - u; v = 1f - v }
                frame.backgroundUvs[i * 2] = stMatrix[0] * u + stMatrix[4] * v + stMatrix[12]
                frame.backgroundUvs[i * 2 + 1] = stMatrix[1] * u + stMatrix[5] * v + stMatrix[13]
            }
            frame.cameraTanHalfX = tanHalfX
            frame.cameraTanHalfY = tanHalfY
            frame.backgroundValid = true
        } else frame.backgroundValid = false
        return true
    }

    override fun acquireCpuImage(): CpuImage? {
        val img = try { imageReader?.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        val pose = Pose()
        // Image readout axes match the display-oriented head in landscape.
        pose.q.setMul(yawOffset, headRot)
        pose.p.set(0f, 1.6f, 0f)
        val fx = img.width / 2f / tanHalfX
        return Camera2CpuImage(img, floatArrayOf(fx, fx, img.width / 2f, img.height / 2f), pose)
    }

    override fun hitTest(ray: Ray, out: HitResult): Boolean {
        // Synthetic floor at y = 0.
        if (ray.dir.y >= -1e-3f) return false
        val t = -ray.origin.y / ray.dir.y
        ray.pointAt(t, out.pose.p)
        out.pose.q.identity()
        out.distance = t
        return true
    }

    override fun createAnchor(pose: Pose): XrAnchor? = null

    override fun recenter() {
        val yaw = Quat().set(headRot).yaw()
        yawOffset.setAxisAngle(0f, 1f, 0f, -yaw)
    }

    override fun release() { pause(); surfaceTexture?.release(); previewSurface?.release() }

    private class Camera2CpuImage(private val img: Image, override val intrinsics: FloatArray, override val cameraPose: Pose) : CpuImage {
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

    companion object { private const val TAG = "TrackMR-Camera2" }
}

@Suppress("unused")
private fun fovDeg(tanHalf: Float) = Math.toDegrees(2.0 * atan(tanHalf.toDouble())).toFloat()
@Suppress("unused")
private fun tanHalf(deg: Float) = tan(Math.toRadians(deg / 2.0)).toFloat()
