package com.trackmr.hands

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.trackmr.xr.XrSettings
import com.trackmr.xr.input.HandTrackingProvider
import com.trackmr.xr.input.HandsFrame
import com.trackmr.xr.input.Handedness
import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Vec3
import com.trackmr.xr.tracking.CpuImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/** Nativa: YUV_420_888 → RGBA bitmap (crop + resize + low-light gamma). */
object VisionNative {
    val loaded: Boolean = try { System.loadLibrary("trackmr_vision"); true } catch (e: Throwable) { false }

    @JvmStatic external fun yuvToBitmap(
        y: java.nio.ByteBuffer, u: java.nio.ByteBuffer, v: java.nio.ByteBuffer,
        yStride: Int, uvStride: Int, uvPixelStride: Int, srcW: Int, srcH: Int,
        roiX: Int, roiY: Int, roiW: Int, roiH: Int, bitmap: Bitmap, lowLightBoost: Boolean,
    ): Float
}

/** One hand measurement already lifted to world space (produced on the MediaPipe thread). */
class RawHand {
    @JvmField var side = Handedness.RIGHT
    @JvmField var score = 0f
    @JvmField val world = Array(21) { Vec3() }
    @JvmField val image = FloatArray(42) // normalized full-image coords
    @JvmField var depth = 0f
}

class RawHands {
    @JvmField var count = 0
    @JvmField val hands = arrayOf(RawHand(), RawHand())
    @JvmField var captureNs = 0L
    @JvmField var doneNs = 0L
    @JvmField var sequence = 0L
    @JvmField val cameraPos = Vec3()
}

/**
 * MediaPipe Hand Landmarker (LIVE_STREAM) with TrackMR latency optimizations:
 *  - at most one frame in flight (the GL thread never blocks; stale frames are skipped)
 *  - adaptive inference rate + thermal-friendly cadence
 *  - stable ROI crop around tracked hands (higher effective resolution, same input size);
 *    the crop only moves when a hand approaches its border so MediaPipe's internal
 *    landmark-to-ROI tracking stays valid, with periodic full-frame scans for new hands
 *  - native YUV conversion straight into a reused Bitmap (no per-frame allocations)
 *  - metric 3D hand lifting via translation-only PnP, done off the GL thread
 *  - double-buffered results consumed lock-free by [HandProcessor] for filtering/prediction
 */
class MediaPipeHandTracker(private val context: Context, private val settings: XrSettings) : HandTrackingProvider {

    private var landmarker: HandLandmarker? = null
    @Volatile private var ready = false
    @Volatile private var failed: String? = null
    @Volatile private var inFlight = false
    private var inFlightSinceNs = 0L
    private var lastSubmitNs = 0L
    private var lastTimestampMs = 0L
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "TrackMR-HandInit") }

    // Input bitmaps: 4:3, reused. Two so one can be filled while MediaPipe still reads the other.
    private var bitmaps: Array<Bitmap>? = null
    private var bmpIndex = 0
    private var inputW = 0
    private var inputH = 0

    // Metadata of the frame in flight
    private val metaPose = Pose()
    private val metaIntr = FloatArray(4)
    private var metaRoiX = 0f; private var metaRoiY = 0f; private var metaRoiW = 1f; private var metaRoiH = 1f
    private var metaImgW = 1; private var metaImgH = 1
    private var metaCaptureNs = 0L

    // ROI state (image pixels)
    private var roiX = 0f; private var roiY = 0f; private var roiW = 0f; private var roiH = 0f
    private var roiValid = false
    private var framesSinceFull = 0
    private var lastHandCount = 0

    // Results
    private val results = arrayOf(RawHands(), RawHands())
    private val published = AtomicInteger(-1)
    private var seq = 0L
    private val processor = HandProcessor(settings)

    private val ab = FloatArray(42)
    private val wl = FloatArray(63)
    private val t3 = FloatArray(3)
    private val tmp = Vec3()

    // Stats
    private var inferCount = 0
    private var inferWindowStart = 0L
    @Volatile var inferenceFps = 0f; private set
    @Volatile var lastLatencyMs = 0f; private set
    @Volatile var meanLuma = 0f; private set

    override val isAvailable: Boolean get() = ready
    override val statusText: String
        get() = failed ?: if (!ready) "Carregando modelo de mãos…" else "MediaPipe %.0f Hz • %.0f ms".format(inferenceFps, lastLatencyMs)

    init { loader.execute { createLandmarker(settings.handGpu) } }

    private fun createLandmarker(gpu: Boolean) {
        if (!VisionNative.loaded) { failed = "Biblioteca nativa de visão indisponível"; return }
        try {
            val base = BaseOptions.builder()
                .setModelAssetPath("models/hand_landmarker.task")
                .setDelegate(if (gpu) Delegate.GPU else Delegate.CPU)
                .build()
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(settings.maxHands)
                .setMinHandDetectionConfidence(0.45f)
                .setMinHandPresenceConfidence(0.45f)
                .setMinTrackingConfidence(0.4f)
                .setResultListener { r: HandLandmarkerResult, _: MPImage -> onResult(r) }
                .setErrorListener { e: RuntimeException -> Log.w(TAG, "landmarker error: ${e.message}"); inFlight = false }
                .build()
            landmarker = HandLandmarker.createFromOptions(context, opts)
            ready = true
            failed = null
            Log.i(TAG, "Hand landmarker ready (${if (gpu) "GPU" else "CPU"})")
        } catch (e: Throwable) {
            Log.e(TAG, "Hand landmarker init failed", e)
            if (gpu) { createLandmarker(false); return }
            failed = "Modelo de mãos indisponível (${e.javaClass.simpleName})"
        }
    }

    override fun wantsFrame(nowNs: Long): Boolean {
        if (!ready) return false
        if (inFlight) {
            // Watchdog: never wait forever for a lost result.
            if (nowNs - inFlightSinceNs > 500_000_000L) inFlight = false else return false
        }
        val period = 1_000_000_000L / settings.handInferenceHz
        return nowNs - lastSubmitNs >= period - 2_000_000L
    }

    override fun submit(image: CpuImage) {
        val lm = landmarker ?: return
        val w = image.width; val h = image.height
        ensureBitmaps()
        val bmps = bitmaps ?: return

        chooseRoi(w, h)
        val bmp = bmps[bmpIndex]; bmpIndex = bmpIndex xor 1
        val mean = VisionNative.yuvToBitmap(
            image.yBuffer, image.uBuffer, image.vBuffer,
            image.yRowStride, image.uvRowStride, image.uvPixelStride, w, h,
            roiX.toInt(), roiY.toInt(), roiW.toInt(), roiH.toInt(), bmp, settings.handLowLightBoost,
        )
        if (mean < 0f) return
        meanLuma = mean

        metaPose.set(image.cameraPose)
        System.arraycopy(image.intrinsics, 0, metaIntr, 0, 4)
        metaRoiX = roiX; metaRoiY = roiY; metaRoiW = roiW; metaRoiH = roiH
        metaImgW = w; metaImgH = h
        metaCaptureNs = image.timestampNs

        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestampMs) ts = lastTimestampMs + 1
        lastTimestampMs = ts
        val now = System.nanoTime()
        lastSubmitNs = now
        inFlightSinceNs = now
        inFlight = true
        submitWallNs = now
        try {
            lm.detectAsync(BitmapImageBuilder(bmp).build(), ts)
        } catch (e: Throwable) {
            inFlight = false
            Log.w(TAG, "detectAsync: ${e.message}")
        }
    }

    @Volatile private var submitWallNs = 0L

    private fun ensureBitmaps() {
        val size = settings.handInputSize
        val bw = size; val bh = size * 3 / 4
        if (bitmaps != null && inputW == bw && inputH == bh) return
        bitmaps = arrayOf(Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888))
        inputW = bw; inputH = bh
    }

    /** Stable ROI: follows hands lazily; full frame when searching or periodically. */
    private fun chooseRoi(w: Int, h: Int) {
        framesSinceFull++
        val wantFull = !settings.handRoi || !roiValid || lastHandCount == 0 ||
            (lastHandCount < settings.maxHands && framesSinceFull > 12) || framesSinceFull > 45
        if (wantFull) {
            roiX = 0f; roiY = 0f; roiW = w.toFloat(); roiH = h.toFloat()
            framesSinceFull = 0
        }
    }

    /** Updates ROI from the latest landmarks (called on the result thread). */
    private fun updateRoiFromHands(r: RawHands) {
        if (r.count == 0) { roiValid = false; return }
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        for (k in 0 until r.count) {
            val img = r.hands[k].image
            for (i in 0 until 21) {
                minX = min(minX, img[i * 2]); maxX = max(maxX, img[i * 2])
                minY = min(minY, img[i * 2 + 1]); maxY = max(maxY, img[i * 2 + 1])
            }
        }
        val w = metaImgW.toFloat(); val h = metaImgH.toFloat()
        val cx = (minX + maxX) / 2f * w; val cy = (minY + maxY) / 2f * h
        var bw = (maxX - minX) * w * 2.0f; var bh = (maxY - minY) * h * 2.0f
        // Keep 4:3 aspect (matches the input bitmap) and a sane minimum size.
        bw = max(bw, bh * 4f / 3f); bh = bw * 3f / 4f
        bw = max(bw, w * 0.45f); bh = max(bh, h * 0.45f)
        if (bw >= w * 0.9f) { roiValid = false; return }
        val nx = (cx - bw / 2f).coerceIn(0f, w - bw); val ny = (cy - bh / 2f).coerceIn(0f, h - bh)
        // Hysteresis: move the crop only when the hands leave its inner 70 %.
        val inside = roiValid && roiW > 0f &&
            minX * w > roiX + roiW * 0.15f && maxX * w < roiX + roiW * 0.85f &&
            minY * h > roiY + roiH * 0.15f && maxY * h < roiY + roiH * 0.85f &&
            bw <= roiW * 1.1f && bw >= roiW * 0.6f
        if (!inside) { roiX = nx; roiY = ny; roiW = bw; roiH = bh }
        roiValid = true
    }

    private fun onResult(result: HandLandmarkerResult) {
        val now = System.nanoTime()
        val slot = (published.get() + 1).let { if (it < 0) 0 else it % 2 }
        val out = results[slot]
        out.count = 0
        val lms = result.landmarks()
        val worlds = result.worldLandmarks()
        val handed = result.handedness()
        val fx = metaIntr[0]; val fy = metaIntr[1]; val cx = metaIntr[2]; val cy = metaIntr[3]
        // Letterbox-free: ROI and bitmap share 4:3 aspect, so normalized coords map linearly.
        for (k in 0 until min(lms.size, 2)) {
            val lm = lms[k]
            if (lm.size < 21 || k >= worlds.size) continue
            val wlm = worlds[k]
            val hand = out.hands[out.count]
            val cat = handed.getOrNull(k)?.firstOrNull()
            // MediaPipe assumes mirrored (selfie) input; the rear camera is not mirrored.
            hand.side = if (cat?.categoryName() == "Left") Handedness.RIGHT else Handedness.LEFT
            hand.score = cat?.score() ?: 0.5f
            for (i in 0 until 21) {
                val u = metaRoiX + lm[i].x() * metaRoiW
                val v = metaRoiY + lm[i].y() * metaRoiH
                hand.image[i * 2] = u / metaImgW; hand.image[i * 2 + 1] = v / metaImgH
                ab[i * 2] = (u - cx) / fx; ab[i * 2 + 1] = (v - cy) / fy
                wl[i * 3] = wlm[i].x(); wl[i * 3 + 1] = wlm[i].y(); wl[i * 3 + 2] = wlm[i].z()
            }
            val ok = HandGeometry.solveTranslation(ab, wl, t3)
            if (!ok) { t3[0] = 0f; t3[1] = 0f; t3[2] = HandGeometry.depthFromPalm(ab) }
            // Back-project image points at their metric depth (keeps skeleton registered with passthrough).
            for (i in 0 until 21) {
                val z = (wl[i * 3 + 2] + t3[2]).coerceAtLeast(0.05f)
                // CV (x right, y down, z fwd) → GL camera (x right, y up, -z fwd) → world
                tmp.set(ab[i * 2] * z, -ab[i * 2 + 1] * z, -z)
                metaPose.transformPoint(tmp, hand.world[i])
            }
            hand.depth = t3[2]
            out.count++
        }
        out.captureNs = metaCaptureNs
        out.doneNs = now
        out.sequence = ++seq
        out.cameraPos.set(metaPose.p)
        lastHandCount = out.count
        updateRoiFromHands(out)
        published.set(slot)

        lastLatencyMs = (now - submitWallNs) / 1e6f
        inferCount++
        if (now - inferWindowStart > 1_000_000_000L) {
            inferenceFps = inferCount * 1e9f / (now - inferWindowStart)
            inferCount = 0; inferWindowStart = now
        }
        inFlight = false
    }

    override fun update(renderTimeNs: Long, headPose: Pose, out: HandsFrame) {
        val slot = published.get()
        processor.update(if (slot >= 0) results[slot] else null, renderTimeNs, headPose, out)
        out.inferenceFps = inferenceFps
        out.latencyMs = lastLatencyMs
        out.source = "MediaPipe"
    }

    override fun release() {
        ready = false
        loader.execute { try { landmarker?.close() } catch (_: Throwable) {}; landmarker = null }
        loader.shutdown()
    }

    companion object { private const val TAG = "TrackMR-Hands" }
}
