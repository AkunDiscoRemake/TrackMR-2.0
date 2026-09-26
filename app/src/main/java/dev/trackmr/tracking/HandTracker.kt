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
import dev.trackmr.core.HandFilter
import dev.trackmr.core.PinchGesture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One worker / one frame in flight. Never queue camera frames behind inference. */
class HandTracker(private val context: Context) : AutoCloseable {
    data class Sample(val points: FloatArray, val captureNs: Long, val inferenceMs: Float, val pinch: Boolean)
    val latest = AtomicReference<Sample?>(null)
    @Volatile var status = "Mãos: iniciando"; private set
    @Volatile var intervalMs = 33L
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "TrackMR-hands").apply { priority = 4 } }
    private var landmarker: HandLandmarker? = null
    private val filter = HandFilter()
    private val pinch = PinchGesture()
    private val raw = FloatArray(63)
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private var lastSubmitted = 0L
    private var lastModelTimestamp = 0L
    private var side = ""
    init { worker.execute { initialize() } }
    private fun initialize() {
        fun create(delegate: Delegate): HandLandmarker = HandLandmarker.createFromOptions(context,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(delegate).build())
                .setNumHands(1).setRunningMode(RunningMode.VIDEO)
                .setMinHandDetectionConfidence(.65f).setMinTrackingConfidence(.6f)
                .setMinHandPresenceConfidence(.6f).build())
        try { landmarker = create(Delegate.GPU); status = "Mãos: GPU • 1 mão" }
        catch (_: Exception) {
            try { landmarker = create(Delegate.CPU); status = "Mãos: CPU • 1 mão" }
            catch (e: Exception) { status = "Mãos indisponíveis: ${e.javaClass.simpleName}" }
        }
    }
    /** Reserve before acquiring an ARCore image. Call cancelReservation if acquisition fails. */
    fun reserve(nowNs: Long): Boolean {
        if (closed.get() || nowNs - lastSubmitted < intervalMs * 1_000_000 || !busy.compareAndSet(false, true)) return false
        lastSubmitted = nowNs; return true
    }
    fun cancelReservation() { busy.set(false) }
    fun submit(image: Image, viewTransform: FloatArray) {
        if (closed.get()) { image.close(); busy.set(false); return }
        worker.execute {
            val captureNs = image.timestamp
            try {
                val model = landmarker ?: return@execute
                // Downsample YUV directly into a reusable 384px bitmap. No JPEG encode/decode.
                val input = image.use { yuvToBitmap(it) }
                val mpImage = BitmapImageBuilder(input).build()
                val start = SystemClock.elapsedRealtimeNanos()
                val stamp = maxOf(lastModelTimestamp + 1, captureNs / 1_000_000)
                lastModelTimestamp = stamp
                val result = try { model.detectForVideo(mpImage, stamp) } finally { mpImage.close() }
                if (result.landmarks().isEmpty()) {
                    latest.set(null); filter.reset(); pinch.reset(); side = ""; return@execute
                }
                val currentSide = result.handedness()[0][0].categoryName()
                if (side != currentSide) { filter.reset(); pinch.reset(); side = currentSide }
                val points = result.landmarks()[0]
                for (i in 0..20) {
                    val p = points[i]
                    raw[i*3] = viewTransform[0] + p.x() * (viewTransform[2]-viewTransform[0]) + p.y() * (viewTransform[4]-viewTransform[0])
                    raw[i*3+1] = viewTransform[1] + p.x() * (viewTransform[3]-viewTransform[1]) + p.y() * (viewTransform[5]-viewTransform[1])
                    raw[i*3+2] = p.z()
                }
                val filtered = FloatArray(63) // immutable publication; never overwritten by inference
                val seconds = captureNs / 1e9
                if (filter.update(raw, filtered, seconds)) latest.set(Sample(filtered, captureNs,
                    (SystemClock.elapsedRealtimeNanos()-start)/1e6f, pinch.update(filtered, seconds)))
            } catch (e: Exception) {
                latest.set(null); status = "Mãos: ${e.javaClass.simpleName}"
            } finally { image.close(); busy.set(false) }
        }
    }
    private fun yuvToBitmap(image: Image): Bitmap {
        val width = minOf(384, image.width)
        val height = (image.height.toLong()*width/image.width).toInt()
        if (bitmap?.width != width || bitmap?.height != height) {
            bitmap?.recycle(); bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pixels = IntArray(width*height)
        }
        val yPlane=image.planes[0]; val uPlane=image.planes[1]; val vPlane=image.planes[2]
        val yBase=yPlane.buffer.position(); val uBase=uPlane.buffer.position(); val vBase=vPlane.buffer.position()
        for (y in 0 until height) {
            val sy=y*image.height/height
            for (x in 0 until width) {
                val sx=x*image.width/width
                val yy=(yPlane.buffer.get(yBase+sy*yPlane.rowStride+sx*yPlane.pixelStride).toInt() and 255)-16
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
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        worker.execute { landmarker?.close(); landmarker=null; bitmap?.recycle(); latest.set(null) }
        worker.shutdown() // ordered after any in-flight inference; do not interrupt native code
    }
}
