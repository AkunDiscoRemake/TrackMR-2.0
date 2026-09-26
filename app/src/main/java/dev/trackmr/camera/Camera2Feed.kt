package dev.trackmr.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan

/** Camera2 fallback is real passthrough, NOT SLAM or a palm detector by itself. */
class Camera2Feed(private val activity: Activity,private val consumer: CameraConsumer?) : CameraFeed {
    override val name="Camera2"
    @Volatile override var status="Camera2: iniciando";private set
    private val thread=HandlerThread("TrackMR-camera",android.os.Process.THREAD_PRIORITY_DISPLAY).apply{start()}
    private val handler=Handler(thread.looper)
    private var device: CameraDevice?=null
    private var capture: CameraCaptureSession?=null
    private var reader: ImageReader?=null
    private var texture: SurfaceTexture?=null
    private var surface: Surface?=null
    private val fresh=AtomicBoolean(false)
    private val closing=AtomicBoolean(false)
    private val output=CameraFrame()
    private var clockKnown=false
    private var rotated=0
    private val rawTransform=FloatArray(16)
    private val rotation=FloatArray(16)
    @Volatile private var cameraFailed=false
    @SuppressLint("MissingPermission")
    override fun start(texture: Int,width: Int,height: Int): Boolean {
        return try {
            val manager=activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id=manager.cameraIdList.firstOrNull{manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK} ?: error("Sem câmera traseira")
            val c=manager.getCameraCharacteristics(id)
            val sizes=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.YUV_420_888)
            val size=sizes.filter{it.width<=1280&&it.height<=720}.minByOrNull{abs(it.width-640)+abs(it.height-480)} ?: sizes.minBy{it.width*it.height}
            clockKnown=c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            @Suppress("DEPRECATION") val screen=activity.windowManager.defaultDisplay.rotation*90
            rotated=((c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)-screen+360)%360
            val physical=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val fovy=if(physical!=null&&focal!=null)(2*atan(physical.height/(2*focal))*180/Math.PI).toFloat() else 55f
            val aspect=if(rotated%180==0)size.width.toFloat()/size.height else size.height.toFloat()/size.width
            Matrix.perspectiveM(output.projection,0,fovy.coerceIn(25f,100f),aspect,.05f,100f)
            output.width=size.width;output.height=size.height;output.realtimeClock=clockKnown
            Matrix.setIdentityM(rotation,0);Matrix.translateM(rotation,0,.5f,.5f,0f);Matrix.rotateM(rotation,0,rotated.toFloat(),0f,0f,1f);Matrix.translateM(rotation,0,-.5f,-.5f,0f)
            this.texture=SurfaceTexture(texture).apply{setDefaultBufferSize(size.width,size.height);setOnFrameAvailableListener({fresh.set(true)},handler)}
            surface=Surface(this.texture)
            val map=when(rotated){90->floatArrayOf(1f,0f,1f,1f,0f,0f);180->floatArrayOf(1f,1f,0f,1f,1f,0f);270->floatArrayOf(0f,1f,0f,0f,1f,1f);else->floatArrayOf(0f,0f,1f,0f,0f,1f)}
            reader=ImageReader.newInstance(size.width,size.height,ImageFormat.YUV_420_888,2).apply{
                setOnImageAvailableListener({ source->
                    val image=runCatching{source.acquireLatestImage()}.getOrNull() ?: return@setOnImageAvailableListener
                    if(!closing.get()&&consumer?.reserve(SystemClock.elapsedRealtimeNanos())==true)consumer.submit(image,map,clockKnown) else image.close()
                },handler)
            }
            manager.openCamera(id,object: CameraDevice.StateCallback(){
                override fun onOpened(camera: CameraDevice){
                    if(closing.get()){camera.close();return};device=camera
                    try {
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(surface!!,reader!!.surface),object: CameraCaptureSession.StateCallback(){
                        override fun onConfigured(session: CameraCaptureSession){
                            if(closing.get()){session.close();return};capture=session
                            try {
                                val request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{
                                    addTarget(surface!!);addTarget(reader!!.surface)
                                    set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
                                    val range=c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.filter{it.upper<=30}?.maxByOrNull{it.upper*100-it.lower}
                                    if(range!=null)set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,range)
                                }.build()
                                session.setRepeatingRequest(request,null,handler);status="Camera2 • passthrough mono • sem SLAM"
                            }catch(e: Exception){status="Camera2 captura: ${e.message}";cameraFailed=true}
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession){status="Camera2: configuração recusada";cameraFailed=true}
                    },handler)
                    }catch(e: Exception){status="Camera2 sessão: ${e.message}";cameraFailed=true;camera.close()}
                }
                override fun onDisconnected(camera: CameraDevice){camera.close();status="Camera2 desconectada";cameraFailed=true}
                override fun onError(camera: CameraDevice,code: Int){camera.close();status="Camera2 erro $code";cameraFailed=true}
            },handler)
            true
        }catch(e: Exception){status="Camera2: ${e.javaClass.simpleName}: ${e.message}";cameraFailed=true;false}
    }
    override fun frame(width: Int,height: Int): CameraFrame {
        if(fresh.getAndSet(false)&&!closing.get()) {
            texture?.updateTexImage();texture?.getTransformMatrix(rawTransform)
            Matrix.multiplyMM(output.textureTransform,0,rawTransform,0,rotation,0)
            output.timestampNs=if(clockKnown)texture?.timestamp ?: 0 else SystemClock.elapsedRealtimeNanos()
        }
        output.active=!cameraFailed&&output.timestampNs>0&&SystemClock.elapsedRealtimeNanos()-output.timestampNs in 0L..300_000_000L
        return output
    }
    override fun pause(){closing.set(true);output.active=false;capture?.close();device?.close()}
    override fun close(){pause();reader?.close();surface?.release();texture?.release();thread.quitSafely()}
}
