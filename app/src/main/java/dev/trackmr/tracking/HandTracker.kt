package dev.trackmr.tracking

import android.content.Context
import android.media.Image
import android.os.SystemClock
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
    @Volatile var modelCheck="aguardando";private set
    private companion object {
        // At most once per delegate/process. No reference landmarks or images are retained.
        val checkedModes=java.util.concurrent.ConcurrentHashMap<Boolean,HandInputImage.Mode>()
    }
    val completed=AtomicLong(0)
    val received=AtomicLong(0)
    val failedFrames=AtomicLong(0)
    @Volatile var stage="carregando modelo";private set
    @Volatile var rawHands=0;private set
    @Volatile var filteredHands=0;private set
    @Volatile var inputDescription="sem imagem";private set
    val diagnostic get()="Modelo: $modelCheck • ${inputImage.mode}\nRecebidas ${received.get()} • inferências ${completed.get()} • falhas ${failedFrames.get()}\nBrutas $rawHands • filtradas $filteredHands • $stage\n$inputDescription"
    fun hint(now: Long): String {
        val failure=error
        return when {
        !enabled->"Mãos pausadas: temperatura"
        failure!=null->failure
        !available->"Mãos: $stage"
        received.get()==0L->"Mãos: aguardando imagem CPU"
        completed.get()==0L->"Mãos: $stage"
        latest.get()?.let{!SampleFreshness.usable(now-it.timestampNs,it.preprocessMs+it.inferenceMs+it.filterMs)}!=false->"Mãos: resultado expirado • $stage"
        rawHands==0->"Inferência OK • nenhuma mão detectada"
        filteredHands==0->"$rawHands detectadas • rejeitadas pelo filtro"
        else->"Mãos $filteredHands • inferências ${completed.get()}"
        }
    }
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
    private val inputImage=HandInputImage().apply{mode=HandInputImage.Mode.BITMAP}
    private var lastSubmitted=0L
    private var lastImageTimestamp=0L
    private var lastModelTimestamp=0L
    private var failures=0
    init { worker.execute{initialize(preferGpu)} }
    private fun createModel(gpu: Boolean)=HandLandmarker.createFromOptions(context,HandLandmarker.HandLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(if(gpu)Delegate.GPU else Delegate.CPU).build())
        .setNumHands(2).setRunningMode(RunningMode.VIDEO).setMinHandDetectionConfidence(.5f)
        .setMinHandPresenceConfidence(.5f).setMinTrackingConfidence(.5f).build())
    private fun initialize(gpu: Boolean){
        kind=BackendKind.NONE
        try{
            landmarker?.close();landmarker=null
            modelBytes=context.assets.openFd("hand_landmarker.task").use{it.length}
            check(modelBytes in 1_000_001..19_999_999){"Hand Landmarker ausente/inválido no APK"}
            var checked=checkedModes[gpu]
            if(checked==null){
                stage="autoteste local do modelo";modelCheck="testando"
                landmarker=createModel(gpu)
                // Use the HandLandmarker Android documented Bitmap path, not raw-buffer JNI.
                HandModelCheck.verify(context,landmarker!!,HandInputImage.Mode.BITMAP)
                checked=HandInputImage.Mode.BITMAP
                // Crucial: destroy reference tracking state before any live camera frame.
                landmarker?.close();landmarker=null
                checkedModes[gpu]=checked!!
            }
            inputImage.mode=checked!!;modelCheck="OK ${checked.name}"
            landmarker=createModel(gpu)
            kind=if(gpu)BackendKind.MEDIAPIPE_GPU else BackendKind.MEDIAPIPE_CPU;error=null;failures=0;stage="aguardando imagem"
            android.util.Log.i("TrackMR-hands","Native model check $modelCheck; live camera counters start at zero")
        }catch(e: Exception){initializationFailed(e,gpu)}
        catch(e: LinkageError){initializationFailed(e,false)} // JNI/ABI mismatch is not an Exception.
    }
    private fun initializationFailed(e: Throwable,gpu: Boolean){
        android.util.Log.e("TrackMR-hands","Backend initialization failed",e)
        if(gpu)initialize(false)else{
            kind=BackendKind.NONE;modelCheck="FALHOU";stage="erro de inicialização"
            error="MediaPipe: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
    @Synchronized override fun reserve(nowNs: Long): Boolean {
        if(closed.get()||!enabled||!available||nowNs-lastSubmitted<intervalMs*1_000_000||!busy.compareAndSet(false,true)){dropped.incrementAndGet();return false}
        lastSubmitted=nowNs;return true
    }
    override fun cancelReservation(){busy.set(false)}
    @Synchronized override fun submit(image: Image,viewTransform: FloatArray,clockKnown: Boolean){
        val received=SystemClock.elapsedRealtimeNanos()
        if(closed.get()){image.close();busy.set(false);return}
        this.received.incrementAndGet()
        worker.execute{
            stage="pré-processamento"
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
                prepareRgba(image,rotation)
                image.close();imageClosed=true // release camera buffer before expensive inference
                val preMs=(SystemClock.elapsedRealtimeNanos()-preStart)/1e6f
                val inferStart=SystemClock.elapsedRealtimeNanos()
                val stamp=maxOf(capture/1_000_000,lastModelTimestamp+1);lastModelTimestamp=stamp
                stage="inferência"
                val result=inputImage.withImage{model.detectForVideo(it,stamp)}
                rawHands=result.landmarks().size;stage="filtro"
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
                filteredHands=filtered.size;stage="aguardando imagem"
                failures=0;error=null;completed.incrementAndGet()
            }catch(e: Exception){failedFrames.incrementAndGet();latest.set(null);error="Tracking: ${e.javaClass.simpleName}: ${e.message}";android.util.Log.e("TrackMR-hands",error,e);if(++failures>=3&&kind==BackendKind.MEDIAPIPE_GPU)initialize(false)}
            finally{if(!imageClosed)image.close();busy.set(false)}
        }
    }
    private var imageQuality=1f
    private fun prepareRgba(image: Image,rotation: Int) {
        val crop=image.cropRect
        val yp=image.planes[0];val up=image.planes[1];val vp=image.planes[2]
        val requested=inputWidth.coerceIn(192,512)
        var p=plan
        if(p==null||p.left!=crop.left||p.top!=crop.top||p.sourceWidth!=crop.width()||p.sourceHeight!=crop.height()||p.targetWidth!=requested||p.rotation!=rotation||
            p.yStride!=yp.rowStride||p.yPixel!=yp.pixelStride||p.uStride!=up.rowStride||p.uPixel!=up.pixelStride||p.vStride!=vp.rowStride||p.vPixel!=vp.pixelStride){
            p=YuvSamplingPlan(crop.left,crop.top,crop.width(),crop.height(),requested,rotation,yp.rowStride,yp.pixelStride,up.rowStride,up.pixelStride,vp.rowStride,vp.pixelStride);plan=p
            inputDescription="${image.width}×${image.height} → ${p.width}×${p.height} RGBA • rotação $rotation°"
        }
        val width=p.width;val height=p.height
        val pixels=inputImage.prepare(width,height)
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
                pixels.put(index++,(255 shl 24) or (blue shl 16) or (green shl 8) or red)
            }
        }
    }
    @Synchronized fun closeAfterDrain(onDrained: ()->Unit){
        if(!closed.compareAndSet(false,true))return
        worker.execute{try{landmarker?.close();landmarker=null;inputImage.close();latest.set(null)}finally{onDrained()}}
        worker.shutdown()
    }
    override fun close(){closeAfterDrain{}}
}
