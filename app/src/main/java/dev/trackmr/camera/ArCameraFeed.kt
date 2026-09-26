package dev.trackmr.camera

import android.app.Activity
import android.opengl.Matrix
import android.os.SystemClock
import com.google.ar.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Single owner for passthrough, SLAM, plane detection and inference camera images. */
class ArCameraFeed(private val activity: Activity,private val consumer: CameraConsumer?,private val useDepth: Boolean=false) : CameraFeed {
    override val name="ARCore"
    override var status="ARCore: iniciando";private set
    private var session: Session?=null
    private val output=CameraFrame()
    private var lastFrame: Frame?=null
    private var origin: Pose?=null
    private val matrix=FloatArray(16)
    private var lastSourceTimestamp=0L
    private var lastDepthImageTimestamp=0L
    private var cameraClockKnown=false
    private val anchors=mutableListOf<Anchor>()
    private val coordinates=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val transformed=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer()
    override fun start(texture: Int,width: Int,height: Int): Boolean {
        return try {
            val availability=ArCoreApk.getInstance().checkAvailability(activity)
            if(availability!=ArCoreApk.Availability.SUPPORTED_INSTALLED){status="ARCore: $availability";return false}
            val s=Session(activity)
            try {
                s.configure(Config(s).apply {
                    updateMode=Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode=Config.FocusMode.AUTO
                    planeFindingMode=Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    lightEstimationMode=Config.LightEstimationMode.AMBIENT_INTENSITY
                    depthMode=if(useDepth&&s.isDepthModeSupported(Config.DepthMode.AUTOMATIC))Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                })
                cameraClockKnown=runCatching{
                    val manager=activity.getSystemService(android.hardware.camera2.CameraManager::class.java)
                    manager.getCameraCharacteristics(s.cameraConfig.cameraId).get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                }.getOrDefault(false)
                output.depthAvailable=s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                s.setCameraTextureName(texture)
                @Suppress("DEPRECATION")
                s.setDisplayGeometry(activity.windowManager.defaultDisplay.rotation,width.coerceAtLeast(1),height.coerceAtLeast(1))
                s.resume();session=s;status="ARCore: aguardando imagem";true
            }catch(e: Exception){s.close();throw e}
        }catch(e: Exception){status="ARCore: ${e.javaClass.simpleName}: ${e.message}";false}
    }
    override fun frame(width: Int,height: Int): CameraFrame {
        val s=session ?: return output
        try {
            @Suppress("DEPRECATION")
            s.setDisplayGeometry(activity.windowManager.defaultDisplay.rotation,width,height)
            val f=s.update();lastFrame=f
            if(f.timestamp!=lastSourceTimestamp&&f.timestamp>0){lastSourceTimestamp=f.timestamp;output.timestampNs=SystemClock.elapsedRealtimeNanos()}
            output.active=output.timestampNs>0&&SystemClock.elapsedRealtimeNanos()-output.timestampNs in 0L..350_000_000L
            output.width=width;output.height=height
            f.camera.getProjectionMatrix(output.projection,0,.05f,100f)
            coordinates.rewind();coordinates.put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f)).rewind();transformed.rewind()
            f.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,coordinates,Coordinates2d.TEXTURE_NORMALIZED,transformed)
            val a=transformed.get(0);val b=transformed.get(1)
            Matrix.setIdentityM(output.textureTransform,0)
            output.textureTransform[0]=transformed.get(2)-a;output.textureTransform[1]=transformed.get(3)-b
            output.textureTransform[4]=transformed.get(4)-a;output.textureTransform[5]=transformed.get(5)-b
            output.textureTransform[12]=a;output.textureTransform[13]=b
            if(consumer?.reserve(SystemClock.elapsedRealtimeNanos())==true){
                try{
                    coordinates.rewind();coordinates.put(floatArrayOf(0f,0f,1f,0f,0f,1f)).rewind();transformed.rewind()
                    f.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED,coordinates,Coordinates2d.VIEW_NORMALIZED,transformed)
                    val map=FloatArray(6);transformed.rewind();transformed.get(map)
                    consumer.submit(f.acquireCameraImage(),map,cameraClockKnown)
                }catch(_: Exception){consumer.cancelReservation()}
            }
            output.tracking=f.camera.trackingState==TrackingState.TRACKING
            if(output.tracking){
                val pose=f.camera.displayOrientedPose
                if(origin==null){pose.toMatrix(matrix,0);val yaw=kotlin.math.atan2(matrix[8],matrix[10]);origin=Pose(pose.translation,floatArrayOf(0f,kotlin.math.sin(yaw/2),0f,kotlin.math.cos(yaw/2)))}
                origin!!.inverse().compose(pose).toMatrix(matrix,0);output.pose=matrix
                status="ARCore • 6DoF • câmera ativa"
            }else{output.pose=null;status="ARCore • câmera ativa • ${f.camera.trackingFailureReason}"}
            if(useDepth&&output.depthAvailable&&SystemClock.elapsedRealtimeNanos()-output.depthTimestampNs>66_000_000){
                try{f.acquireDepthImage16Bits().use{image->
                    val size=image.width*image.height*2
                    if(output.depthData?.capacity()!=size)output.depthData=ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
                    val target=output.depthData!!;target.clear();val plane=image.planes[0];val source=plane.buffer
                    for(row in 0 until image.height){val line=source.duplicate();line.position(source.position()+row*plane.rowStride);line.limit(line.position()+image.width*2);target.put(line)}
                    target.flip();output.depthWidth=image.width;output.depthHeight=image.height
                    if(image.timestamp!=lastDepthImageTimestamp){lastDepthImageTimestamp=image.timestamp;output.depthTimestampNs=SystemClock.elapsedRealtimeNanos()}
                }}catch(_: com.google.ar.core.exceptions.NotYetAvailableException){} // depth is sparse/optional
            }
            output.planes=s.getAllTrackables(Plane::class.java).count{it.trackingState==TrackingState.TRACKING&&it.subsumedBy==null}
            output.anchors=anchors.count{it.trackingState==TrackingState.TRACKING}
            if(f.lightEstimate.state==LightEstimate.State.VALID)output.light=f.lightEstimate.pixelIntensity.coerceIn(.25f,2f)
        }catch(e: Exception){output.active=false;output.pose=null;status="ARCore frame: ${e.javaClass.simpleName}"}
        return output
    }
    override fun placeAtCenter(): Boolean {
        if(!output.tracking)return false
        val hit=lastFrame?.hitTest(output.width/2f,output.height/2f)?.firstOrNull { val t=it.trackable;t is Plane&&t.isPoseInPolygon(it.hitPose) } ?: return false
        if(anchors.size>=8)anchors.removeAt(0).detach()
        anchors+=hit.createAnchor();return true
    }
    override fun anchorPositions(): FloatArray {
        val base=origin?.inverse() ?: return FloatArray(0)
        val valid=anchors.filter{it.trackingState==TrackingState.TRACKING}
        return FloatArray(valid.size*3).also{out->valid.forEachIndexed{i,a->base.compose(a.pose).getTranslation(out,i*3)}}
    }
    override fun recenter(){origin=null}
    override fun pause(){runCatching{session?.pause()};output.active=false}
    override fun close(){anchors.forEach{it.detach()};anchors.clear();session?.close();session=null;output.active=false}
}
