package dev.trackmr.tracking

import android.app.Activity
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.SystemClock

/** ARCore is the sole camera owner. CameraX must NOT be opened alongside this session. */
class ArTracking(private val activity: Activity, private val sixDof: Boolean, val hands: HandTracker?) : AutoCloseable {
    private var session: Session? = null
    private val poseMatrix = FloatArray(16)
    private var origin: Pose? = null
    private var configuredTexture = -1
    private var installationRequested = false
    private val imageCoordinates = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f,0f,1f,0f,0f,1f)); rewind()
    }
    private val viewCoordinates = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer()
    @Volatile var status = "3DoF • Cardboard"; private set
    /** Return false when ARCore installer interrupted resume; caller can retry on next onResume. */
    fun resume(): Boolean {
        try {
            if (session == null) {
                val availability = ArCoreApk.getInstance().checkAvailability(activity)
                if (availability.isUnsupported) { status="ARCore não suportado • 3DoF"; return false }
                if (ArCoreApk.getInstance().requestInstall(activity, !installationRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installationRequested=true;status="Instale o Google Play Services for AR"; return false
                }
                session = Session(activity).apply {
                    configure(Config(this).apply {
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        focusMode = Config.FocusMode.AUTO
                        planeFindingMode = Config.PlaneFindingMode.DISABLED
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                        depthMode = Config.DepthMode.DISABLED
                        instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    })
                }
            }
            session!!.resume(); status="ARCore: buscando tracking"; return true
        } catch (e: Exception) { status="ARCore: ${e.javaClass.simpleName} • 3DoF"; return false }
    }
    /** GL thread; latest-camera-image mode avoids waiting for a new camera frame. */
    fun frame(cameraTexture: Int, width: Int, height: Int): FloatArray? {
        val s = session ?: return null
        try {
            if (configuredTexture != cameraTexture) { s.setCameraTextureName(cameraTexture); configuredTexture=cameraTexture }
            @Suppress("DEPRECATION")
            s.setDisplayGeometry(activity.windowManager.defaultDisplay.rotation,width,height)
            val frame = s.update()
            val now = SystemClock.elapsedRealtimeNanos()
            hands?.let { h ->
                if (h.reserve(now)) {
                    try {
                        imageCoordinates.rewind(); viewCoordinates.rewind()
                        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED,imageCoordinates,Coordinates2d.VIEW_NORMALIZED,viewCoordinates)
                        val transform=FloatArray(6); viewCoordinates.rewind(); viewCoordinates.get(transform)
                        h.submit(frame.acquireCameraImage(),transform)
                    } catch (_: NotYetAvailableException) { h.cancelReservation() }
                    catch (_: Exception) { h.cancelReservation() }
                }
            }
            if (frame.camera.trackingState != TrackingState.TRACKING) {
                status="3DoF • ARCore ${frame.camera.trackingFailureReason.name.lowercase()}"; return null
            }
            status=if(sixDof) "6DoF • ARCore" else "3DoF • câmera para mãos"
            if (!sixDof) return null
            val pose=frame.camera.displayOrientedPose
            if (origin==null) {
                pose.toMatrix(poseMatrix,0)
                val yaw=kotlin.math.atan2(poseMatrix[8],poseMatrix[10])
                origin=Pose(pose.translation,floatArrayOf(0f,kotlin.math.sin(yaw/2),0f,kotlin.math.cos(yaw/2)))
            }
            origin!!.inverse().compose(pose).toMatrix(poseMatrix,0)
            return poseMatrix
        } catch (e: Exception) { status="3DoF • ${e.javaClass.simpleName}"; return null }
    }
    fun recenter() { origin=null }
    fun contextLost() { configuredTexture=-1 }
    fun pause() { runCatching { session?.pause() } }
    override fun close() {
        val old=session;session=null
        // Session outlives every acquired Image, including an in-flight YUV conversion.
        if(hands!=null)hands.closeAfterDrain { old?.close() } else old?.close()
    }
}
