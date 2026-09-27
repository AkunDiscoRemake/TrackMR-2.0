package com.trackmr.xr

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.trackmr.xr.input.HandTrackingProvider
import com.trackmr.xr.input.HandsFrame
import com.trackmr.xr.input.InputSystem
import com.trackmr.xr.math.Pose
import com.trackmr.xr.render.EyeContext
import com.trackmr.xr.render.RenderPass
import com.trackmr.xr.tracking.TrackingFrame
import com.trackmr.xr.tracking.TrackingProvider
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A platform subsystem plugged into the XR frame loop (MR passthrough, environments,
 * windows, browser, cinema, games...). All callbacks except [onAttach] run on the GL thread.
 */
interface XrModule {
    val id: String
    fun onAttach(ctx: XrContext) {}
    fun onGlReady(ctx: XrContext) {}
    fun onFrame(ctx: XrContext, dt: Float) {}
    fun onModeChanged(ctx: XrContext, mode: XrMode) {}
    fun onPause(ctx: XrContext) {}
    fun onResume(ctx: XrContext) {}
    fun onDestroy(ctx: XrContext) {}
}

/** Something drawn in the XR scene. */
interface Renderable {
    /** Bit mask of [RenderPass] ordinals this renderable participates in. */
    val passes: Int
    val visible: Boolean get() = true
    /** Sort key for transparent pass (world position distance); lower draws later = on top. */
    fun sortDistanceSq(eye: EyeContext): Float = 0f
    fun render(eye: EyeContext, pass: RenderPass)

    companion object {
        fun mask(vararg p: RenderPass): Int { var m = 0; for (x in p) m = m or (1 shl x.ordinal); return m }
    }
}

class XrScene {
    private val items = CopyOnWriteArrayList<Renderable>()
    private val sortBuffer = ArrayList<Renderable>(64)
    private val sortKeys = HashMap<Renderable, Float>(64)

    fun add(r: Renderable) { if (!items.contains(r)) items.add(r) }
    fun remove(r: Renderable) { items.remove(r) }
    fun contains(r: Renderable) = items.contains(r)

    fun render(eye: EyeContext, pass: RenderPass) {
        val bit = 1 shl pass.ordinal
        if (pass == RenderPass.TRANSPARENT) {
            sortBuffer.clear(); sortKeys.clear()
            for (r in items) if (r.visible && (r.passes and bit) != 0) { sortBuffer.add(r); sortKeys[r] = r.sortDistanceSq(eye) }
            // Far to near.
            sortBuffer.sortWith { a, b -> (sortKeys[b] ?: 0f).compareTo(sortKeys[a] ?: 0f) }
            for (r in sortBuffer) r.render(eye, pass)
            return
        }
        for (r in items) if (r.visible && (r.passes and bit) != 0) r.render(eye, pass)
    }
}

/**
 * Shared XR platform state: settings, tracking, hands, input, scene, services and thread
 * hand-off helpers. There is exactly one per running TrackMR session.
 */
class XrContext(val activity: Activity, val settings: XrSettings) {
    val input = InputSystem()
    val scene = XrScene()
    val hands = HandsFrame()
    val tracking = TrackingFrame()
    val modules = CopyOnWriteArrayList<XrModule>()
    /** Scene lighting written by MR light estimation or the active VR environment. */
    val lighting = com.trackmr.xr.render.SceneLighting()
    /** Environment depth occlusion (MR). */
    val occlusion = com.trackmr.xr.render.OcclusionState()
    private val services = HashMap<Class<*>, Any>()
    private val glQueue = ConcurrentLinkedQueue<Runnable>()
    val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var trackingProvider: TrackingProvider? = null
    @Volatile var handProvider: HandTrackingProvider? = null
    @Volatile var externalCompositor: ExternalCompositor? = null

    @JvmField var nowNs = 0L
    @JvmField var timeSeconds = 0.0
    @JvmField var frameIndex = 0L
    @JvmField var screenWidth = 1
    @JvmField var screenHeight = 1
    @JvmField var fps = 0f
    @JvmField var renderScale = 1f
    @JvmField var cpuFrameMs = 0f
    @Volatile var glReady = false
        internal set

    val headPose: Pose get() = tracking.headPose
    val mode: XrMode get() = settings.mode

    private val notices = CopyOnWriteArrayList<(String) -> Unit>()

    fun <T : Any> register(type: Class<T>, service: T) { synchronized(services) { services[type] = service } }
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> service(type: Class<T>): T? = synchronized(services) { services[type] as T? }
    inline fun <reified T : Any> service(): T? = service(T::class.java)

    fun addModule(m: XrModule) { modules.add(m); m.onAttach(this); if (glReady) runOnGl { m.onGlReady(this) } }
    inline fun <reified T : XrModule> module(): T? = modules.firstOrNull { it is T } as T?

    fun runOnGl(r: Runnable) { glQueue.add(r) }
    fun runOnUi(r: Runnable) { if (Looper.myLooper() == Looper.getMainLooper()) r.run() else mainHandler.post(r) }

    internal fun drainGlQueue() {
        var n = 0
        while (n < 64) { val r = glQueue.poll() ?: break; r.run(); n++ }
    }

    /** Switches between MR (passthrough) and VR (immersive environment). */
    fun setMode(mode: XrMode) {
        if (settings.mode == mode) return
        settings.mode = mode
        runOnGl { for (m in modules) m.onModeChanged(this, mode) }
        notify(if (mode == XrMode.MR) "Realidade Mista" else "Realidade Virtual")
    }

    /** Spatial notification (rendered as a floating 3D toast by the UI layer). */
    fun notify(text: String) { for (n in notices) n(text) }
    fun addNoticeListener(l: (String) -> Unit) { notices.add(l) }
}

/** Frame data provided by an external compositor such as OpenXR/Monado. */
class ExternalFrame {
    val eyePoses = arrayOf(Pose(), Pose())
    /** Tangent extents per eye: left, right, down, up (positive magnitudes). */
    val eyeTans = arrayOf(FloatArray(4), FloatArray(4))
    var width = 0
    var height = 0
    var positionTracked = false
    var shouldRender = true
}

/**
 * Optional external compositor (OpenXR). When active the TrackMR renderer draws into the
 * runtime's swapchain images and skips its own lens distortion.
 */
interface ExternalCompositor {
    val isActive: Boolean
    fun beginFrame(out: ExternalFrame): Boolean
    /** Returns the GL texture of the acquired swapchain image for [eye]. */
    fun acquireEye(eye: Int): Int
    fun releaseEye(eye: Int)
    fun endFrame()
}
