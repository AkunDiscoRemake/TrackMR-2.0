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
    val status get()=error ?: "${kind.name} • ${latest.get()?.hands?.size ?: 0} mãos • ${completed.get()} frames"
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
    private val temporal=Array(2){TemporalHandPipeline()}
    private val gestures=Array(2){GestureEngine()}
    private val lastSeen=LongArray(2)
    private val twoHands=TwoHandGestures()
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
            val delegate=if(gpu)Delegate.GPU else Delegate.CPU
            landmarker=HandLandmarker.createFromOptions(context,HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(delegate).build())
                .setNumHands(2).setRunningMode(RunningMode.VIDEO).setMinHandDetectionConfidence(.5f)
                .setMinHandPresenceConfidence(.5f).setMinTrackingConfidence(.5f).build())
            kind=if(gpu)BackendKind.MEDIAPIPE_GPU else BackendKind.MEDIAPIPE_CPU;error=null;failures=0
        }catch(e: Exception){android.util.Log.e("TrackMR-hands","Backend initialization failed",e);if(gpu)initialize(false) else {kind=BackendKind.NONE;error="MediaPipe indisponível: ${e.javaClass.simpleName}"}}
    }
    @Synchronized override fun reserve(nowNs: Long): Boolean {
        if(closed.get()||!enabled||nowNs-lastSubmitted<intervalMs*1_000_000||!busy.compareAndSet(false,true)){dropped.incrementAndGet();return false}
        lastSubmitted=nowNs;return true
    }
    override fun cancelReservation(){busy.set(false)}
    @Synchronized override fun submit(image: Image,viewTransform: FloatArray,clockKnown: Boolean){
        val received=SystemClock.elapsedRealtimeNanos()
        if(closed.get()){image.close();busy.set(false);return}
        worker.execute{
            val capture=received // local acquisition clock for expiry; sensor clock only for diagnostics
            val sensorTimestamp=image.timestamp
            try{
                val model=landmarker ?: return@execute
                if(capture<=lastImageTimestamp)return@execute
                lastImageTimestamp=capture
                val preStart=SystemClock.elapsedRealtimeNanos()
                val rotation=ImageOrientation.degrees(viewTransform)
                val input=yuvToBitmap(image,rotation)
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
                        val x=ImageOrientation.sourceX(p.x(),p.y(),rotation);val y=ImageOrientation.sourceY(p.x(),p.y(),rotation)
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
                    if(capture-lastSeen[slot]>180_000_000){temporal[slot].reset();gestures[slot].reset()}
                    lastSeen[slot]=capture
                    temporal[slot].update(o)?.let{s->filtered+=s;events+=gestures[slot].update(s)}
                }}
                events+=twoHands.update(filtered.getOrNull(0),filtered.getOrNull(1))
                latest.set(Batch(filtered,events,capture,received,sensorTimestamp,preMs,inferMs,(SystemClock.elapsedRealtimeNanos()-filterStart)/1e6f,clockKnown))
                failures=0;error=null;completed.incrementAndGet()
            }catch(e: Exception){latest.set(null);error="Tracking: ${e.javaClass.simpleName}: ${e.message}";android.util.Log.e("TrackMR-hands",error,e);if(++failures>=3&&kind==BackendKind.MEDIAPIPE_GPU)initialize(false)}
            finally{image.close();busy.set(false)}
        }
    }
    private var imageQuality=1f
    private fun yuvToBitmap(image: Image,rotation: Int): Bitmap {
        val sourceWidth=minOf(inputWidth.coerceIn(192,512),image.width)
        val sourceHeight=(image.height.toLong()*sourceWidth/image.width).toInt()
        val width=if(rotation%180==0)sourceWidth else sourceHeight
        val height=if(rotation%180==0)sourceHeight else sourceWidth
        if (bitmap?.width != width || bitmap?.height != height) {
            bitmap?.recycle(); bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pixels = IntArray(width*height)
        }
        val yPlane=image.planes[0]; val uPlane=image.planes[1]; val vPlane=image.planes[2]
        val yBase=yPlane.buffer.position(); val uBase=uPlane.buffer.position(); val vBase=vPlane.buffer.position()
        var sum=0.0;var variance=0.0;var count=0;var prior=0
        for(sy in 0 until image.height step 16)for(sx in 0 until image.width step 16){
            val luma=yPlane.buffer.get(yBase+sy*yPlane.rowStride+sx*yPlane.pixelStride).toInt() and 255
            sum+=luma;variance+=kotlin.math.abs(luma-prior);prior=luma;count++
        }
        val mean=(sum/count.coerceAtLeast(1)).toFloat()
        imageQuality=when{mean<15->.3f;mean<30->.65f;variance/count.coerceAtLeast(1)<2->.65f;else->1f}
        // Bounded exposure compensation, never expensive deblurring or invented detail.
        val gain=if(mean<65)(65/mean.coerceAtLeast(35f)).coerceAtMost(1.4f) else 1f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx=(x+.5f)/width;val ny=(y+.5f)/height
                val sx=(ImageOrientation.sourceX(nx,ny,rotation)*image.width).toInt().coerceIn(0,image.width-1)
                val sy=(ImageOrientation.sourceY(nx,ny,rotation)*image.height).toInt().coerceIn(0,image.height-1)
                val yy=(((yPlane.buffer.get(yBase+sy*yPlane.rowStride+sx*yPlane.pixelStride).toInt() and 255)-16)*gain).toInt()
                val u=(uPlane.buffer.get(uBase+(sy/2)*uPlane.rowStride+(sx/2)*uPlane.pixelStride).toInt() and 255)-128
                val v=(vPlane.buffer.get(vBase+(sy/2)*vPlane.rowStride+(sx/2)*vPlane.pixelStride).toInt() and 255)-128
                val r=((298*yy+409*v+128) shr 8).coerceIn(0,255)
                val g=((298*yy-100*u-208*v+128) shr 8).coerceIn(0,255)
                val b=((298*yy+516*u+128) shr 8).coerceIn(0,255)
                pixels[y*width+x]=(255 shl 24) or (r shl 16) or (g shl 8) or b
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
