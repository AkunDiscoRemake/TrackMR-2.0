package dev.trackmr.tracking

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import dev.trackmr.camera.CameraConsumer
import dev.trackmr.handtracking.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class HandTracker(private val context: Context,private val preferGpu: Boolean=false) : CameraConsumer, HandBackend {
    data class Batch(val hands: List<HandSample>,val events: List<GestureEvent>,val timestampNs: Long,
        val receivedNs: Long,val sensorTimestampNs: Long,val preprocessMs: Float,val inferenceMs: Float,val filterMs: Float,val clockKnown: Boolean)
    val latest=AtomicReference<Batch?>(null)
    @Volatile override var kind=BackendKind.NONE;private set
    @Volatile override var error: String?=null;private set
    override val available get()=kind!=BackendKind.NONE
    val status get()=if(!enabled)"Mãos pausadas pelo limite térmico" else error ?: "${kind.name} • ${latest.get()?.hands?.size ?: 0} mãos • ${completed.get()} frames"
    val completed=AtomicLong(0)
    @Volatile var intervalMs=33L
    @Volatile var inputWidth=384
    @Volatile var enabled=true
    val dropped=AtomicLong(0)
    private val busy=AtomicBoolean(false)
    private val closed=AtomicBoolean(false)
    private val worker=Executors.newSingleThreadExecutor { r->Thread(r,"TrackMR-inference").apply{priority=4} }
    private var landmarker: HandLandmarker?=null
    private val association=HandAssociation()
    private val temporal=Array(2){TemporalHandPipeline(trackId=it)}
    private val gestures=Array(2){GestureEngine()}
    private val lastSeen=LongArray(2)
    private val twoHands=TwoHandGestures()
    private var plan: YuvSamplingPlan?=null
    private val lumaTable=IntArray(256)
    @Volatile var modelBytes=0L;private set
    private var pixels=IntArray(0)
    private var bitmap: Bitmap?=null
    private var lastSubmitted=0L
    private var lastImageTimestamp=0L
    private var lastModelTimestamp=0L
    private var failures=0
    init { worker.execute{initialize(preferGpu)} }
    private fun initialize(gpu: Boolean){
        try{
            landmarker?.close();landmarker=null
            modelBytes=context.assets.openFd("hand_landmarker.task").use{it.length}
            check(modelBytes in 1_000_001..19_999_999){"Hand Landmarker ausente/inválido no APK"}
            val delegate=if(gpu)Delegate.GPU else Delegate.CPU
            landmarker=HandLandmarker.createFromOptions(context,HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(delegate).build())
                .setNumHands(2).setRunningMode(RunningMode.VIDEO).setMinHandDetectionConfidence(.5f)
                .setMinHandPresenceConfidence(.5f).setMinTrackingConfidence(.5f).build())
            kind=if(gpu)BackendKind.MEDIAPIPE_GPU else BackendKind.MEDIAPIPE_CPU;error=null;failures=0
        }catch(e: Exception){android.util.Log.e("TrackMR-hands","Backend initialization failed",e);if(gpu)initialize(false) else {kind=BackendKind.NONE;error="MediaPipe indisponível: ${e.javaClass.simpleName}: ${e.message}"}}
    }
    @Synchronized override fun reserve(nowNs: Long): Boolean {
        if(closed.get()||!enabled||!available||nowNs-lastSubmitted<intervalMs*1_000_000||!busy.compareAndSet(false,true)){dropped.incrementAndGet();return false}
        lastSubmitted=nowNs;return true
    }
    override fun cancelReservation(){busy.set(false)}
    @Synchronized override fun submit(image: Image,viewTransform: FloatArray,clockKnown: Boolean){
        val received=SystemClock.elapsedRealtimeNanos()
        if(closed.get()){image.close();busy.set(false);return}
        worker.execute{
            val capture=received // local acquisition clock for expiry; sensor clock only for diagnostics
            var imageClosed=false
            try{
                val sensorTimestamp=image.timestamp
                val crop=image.cropRect
                val cropX=crop.left.toFloat()/image.width;val cropY=crop.top.toFloat()/image.height
                val cropW=crop.width().toFloat()/image.width;val cropH=crop.height().toFloat()/image.height
                val model=landmarker ?: return@execute
                if(capture<=lastImageTimestamp)return@execute
                lastImageTimestamp=capture
                val preStart=SystemClock.elapsedRealtimeNanos()
                val rotation=ImageOrientation.degrees(viewTransform)
                val input=yuvToBitmap(image,rotation)
                image.close();imageClosed=true // release camera buffer before expensive inference
                val preMs=(SystemClock.elapsedRealtimeNanos()-preStart)/1e6f
                val mpImage=BitmapImageBuilder(input).build()
                val inferStart=SystemClock.elapsedRealtimeNanos()
                val stamp=maxOf(capture/1_000_000,lastModelTimestamp+1);lastModelTimestamp=stamp
                val result=try{model.detectForVideo(mpImage,stamp)}finally{mpImage.close()}
                val inferMs=(SystemClock.elapsedRealtimeNanos()-inferStart)/1e6f
                val filterStart=SystemClock.elapsedRealtimeNanos()
                val observations=result.landmarks().mapIndexed { index,points->
                    val raw=FloatArray(63)
                    points.forEachIndexed{i,p->
                        val x=cropX+ImageOrientation.sourceX(p.x(),p.y(),rotation)*cropW;val y=cropY+ImageOrientation.sourceY(p.x(),p.y(),rotation)*cropH
                        raw[i*3]=viewTransform[0]+x*(viewTransform[2]-viewTransform[0])+y*(viewTransform[4]-viewTransform[0])
                        raw[i*3+1]=viewTransform[1]+x*(viewTransform[3]-viewTransform[1])+y*(viewTransform[5]-viewTransform[1])
                        raw[i*3+2]=p.z()
                    }
                    val handedness=result.handedness()[index][0]
                    HandObservation(raw,capture,if(handedness.categoryName()=="Left")Side.LEFT else Side.RIGHT,handedness.score(),imageQuality)
                }
                val slots=association.associate(observations)
                val filtered=ArrayList<HandSample>(2);val events=ArrayList<GestureEvent>()
                observations.forEachIndexed{i,o->val slot=slots[i];if(slot>=0){
                    if(capture-lastSeen[slot]>350_000_000){temporal[slot].reset();gestures[slot].reset()}
                    lastSeen[slot]=capture
                    temporal[slot].update(o)?.let{s->filtered+=s;events+=gestures[slot].update(s)}
                }}
                events+=twoHands.update(filtered.getOrNull(0),filtered.getOrNull(1))
                latest.set(Batch(filtered,events,capture,received,sensorTimestamp,preMs,inferMs,(SystemClock.elapsedRealtimeNanos()-filterStart)/1e6f,clockKnown))
                failures=0;error=null;completed.incrementAndGet()
            }catch(e: Exception){latest.set(null);error="Tracking: ${e.javaClass.simpleName}: ${e.message}";android.util.Log.e("TrackMR-hands",error,e);if(++failures>=3&&kind==BackendKind.MEDIAPIPE_GPU)initialize(false)}
            finally{if(!imageClosed)image.close();busy.set(false)}
        }
    }
    private var imageQuality=1f
    private fun yuvToBitmap(image: Image,rotation: Int): Bitmap {
        val crop=image.cropRect
        val yp=image.planes[0];val up=image.planes[1];val vp=image.planes[2]
        val requested=inputWidth.coerceIn(192,512)
        var p=plan
        if(p==null||p.left!=crop.left||p.top!=crop.top||p.sourceWidth!=crop.width()||p.sourceHeight!=crop.height()||p.targetWidth!=requested||p.rotation!=rotation||
            p.yStride!=yp.rowStride||p.yPixel!=yp.pixelStride||p.uStride!=up.rowStride||p.uPixel!=up.pixelStride||p.vStride!=vp.rowStride||p.vPixel!=vp.pixelStride){
            p=YuvSamplingPlan(crop.left,crop.top,crop.width(),crop.height(),requested,rotation,yp.rowStride,yp.pixelStride,up.rowStride,up.pixelStride,vp.rowStride,vp.pixelStride);plan=p
        }
        val width=p.width;val height=p.height
        if(bitmap?.width!=width||bitmap?.height!=height){
            bitmap?.recycle();bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);pixels=IntArray(width*height)
        }
        val yPlane=yp;val uPlane=up;val vPlane=vp
        val yBuffer=yp.buffer;val uBuffer=up.buffer;val vBuffer=vp.buffer
        val yBase=yBuffer.position();val uBase=uBuffer.position();val vBase=vBuffer.position()
        var sum=0.0;var variance=0.0;var count=0;var prior=0
        for(sy in crop.top until crop.bottom step 16)for(sx in crop.left until crop.right step 16){
            val luma=yPlane.buffer.get(yBase+sy*yPlane.rowStride+sx*yPlane.pixelStride).toInt() and 255
            sum+=luma;variance+=kotlin.math.abs(luma-prior);prior=luma;count++
        }
        val mean=(sum/count.coerceAtLeast(1)).toFloat()
        imageQuality=when{mean<15->.3f;mean<30->.65f;variance/count.coerceAtLeast(1)<2->.65f;else->1f}
        // Bounded exposure compensation, never expensive deblurring or invented detail.
        val gain=if(mean<65)(65/mean.coerceAtLeast(35f)).coerceAtMost(1.4f) else 1f
        for(i in 0..255)lumaTable[i]=298*((i-16)*gain).toInt()
        for(y in 0 until height){
            val yr=yBase+p.yRows[y];val ur=uBase+p.uRows[y];val vr=vBase+p.vRows[y]
            var index=y*width
            for(x in 0 until width){
                val yy=lumaTable[yBuffer.get(yr+p.yColumns[x]).toInt() and 255]
                val u=(uBuffer.get(ur+p.uColumns[x]).toInt() and 255)-128
                val v=(vBuffer.get(vr+p.vColumns[x]).toInt() and 255)-128
                val red=((yy+409*v+128) shr 8).coerceIn(0,255)
                val green=((yy-100*u-208*v+128) shr 8).coerceIn(0,255)
                val blue=((yy+516*u+128) shr 8).coerceIn(0,255)
                pixels[index++]=(255 shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return bitmap!!.apply { setPixels(pixels,0,width,0,0,width,height) }
    }
    @Synchronized fun closeAfterDrain(onDrained: ()->Unit){
        if(!closed.compareAndSet(false,true))return
        worker.execute{try{landmarker?.close();landmarker=null;bitmap?.recycle();latest.set(null)}finally{onDrained()}}
        worker.shutdown()
    }
    override fun close(){closeAfterDrain{}}
}
