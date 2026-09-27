package com.trackmr.xr.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.trackmr.xr.ExternalFrame
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrMode
import com.trackmr.xr.gl.FrameBuffer
import com.trackmr.xr.input.HandState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * TrackMR frame loop (GL thread):
 *   tracking (ARCore/fallback) → hand inference hand-off → hand filtering/prediction →
 *   spatial input → modules → per-eye passes → lens distortion (or OpenXR submit).
 */
class XrRenderer(private val ctx: XrContext, private val onContextLost: () -> Unit) : GLSurfaceView.Renderer {
    private val rig = StereoRig()
    private val dynRes = DynamicResolution()
    private var eyeFbo: FrameBuffer? = null
    private val eyes = arrayOf(EyeContext(), EyeContext())
    private val extFrame = ExternalFrame()
    private val extFbo = IntArray(1)
    private val extDepth = IntArray(1)
    private var extDepthSize = 0L
    private var surfaceCreatedCount = 0

    @Volatile var xdpi = 400f
    @Volatile var ydpi = 400f
    @Volatile var displayRotation = 1

    private var lastFrameNs = 0L
    private var fpsAccum = 0f
    private var fpsFrames = 0
    private var lastThermalCheckNs = 0L
    private val power = ctx.activity.getSystemService(PowerManager::class.java)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        surfaceCreatedCount++
        if (surfaceCreatedCount > 1) {
            // EGL context was lost: every module's GL objects are invalid. Rebuild the session.
            Log.w(TAG, "EGL context recreated — restarting XR session")
            onContextLost()
            return
        }
        Gfx.init()
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_DITHER)
        ctx.trackingProvider?.onGlReady()
        for (m in ctx.modules) {
            try { m.onGlReady(ctx) } catch (e: Exception) { Log.e(TAG, "Module ${m.id} GL init failed", e) }
        }
        ctx.glReady = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        ctx.screenWidth = width
        ctx.screenHeight = height
        ctx.trackingProvider?.setDisplayGeometry(displayRotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!Gfx.isReady()) return
        val frameStart = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 1f / 60f else ((frameStart - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
        val frameMs = if (lastFrameNs == 0L) 16.6f else (frameStart - lastFrameNs) / 1e6f
        lastFrameNs = frameStart
        ctx.nowNs = frameStart
        ctx.timeSeconds += dt
        ctx.frameIndex++
        updateFps(dt)

        ctx.drainGlQueue()

        // 1. Tracking
        val tracking = ctx.tracking
        val tp = ctx.trackingProvider
        if (tp != null) {
            try { tp.update(tracking) } catch (e: Exception) { Log.e(TAG, "Tracking update failed", e) }
        }

        // 2. Hand tracking: hand a camera frame to the async detector only when it is idle.
        val hp = ctx.handProvider
        if (hp != null && ctx.settings.handTracking && hp.isAvailable) {
            if (tp != null && hp.wantsFrame(frameStart)) {
                try { tp.acquireCpuImage()?.use { hp.submit(it) } } catch (e: Exception) { Log.w(TAG, "CPU image: ${e.message}") }
            }
            hp.update(frameStart, tracking.headPose, ctx.hands)
        } else {
            clearHand(ctx.hands.left); clearHand(ctx.hands.right)
        }

        // 3. Input + modules
        ctx.input.update(frameStart, tracking.headPose, ctx.hands)
        for (m in ctx.modules) {
            try { m.onFrame(ctx, dt) } catch (e: Exception) { Log.e(TAG, "Module ${m.id} frame failed", e) }
        }

        // 4. Resolution governor
        checkThermal(frameStart)
        val scale = dynRes.update(frameMs, ctx.settings.targetFps, ctx.settings.baseRenderScale, ctx.settings.dynamicResolution, dt)
        ctx.renderScale = scale

        // 5. Render
        val ext = ctx.externalCompositor
        if (ext != null && ext.isActive) renderExternal(ext) else renderNative(scale)
        ctx.cpuFrameMs = (System.nanoTime() - frameStart) / 1e6f
    }

    private fun clearHand(h: HandState) {
        h.tracked = false; h.presence = 0f; h.rayValid = false; h.isPinching = false
        h.justPinched = false; h.justReleased = false; h.menuGesture = false
    }

    private fun renderNative(scale: Float) {
        val s = ctx.settings
        val sw = ctx.screenWidth; val sh = ctx.screenHeight
        rig.configure(s, sw, sh, xdpi, ydpi)
        val eyeCount = rig.eyeCount
        val bufW = max(64, (sw * scale).roundToInt()).let { it - it % 2 }
        val bufH = max(64, (sh * scale).roundToInt())
        val fbo = eyeFbo?.also { it.resize(bufW, bufH) } ?: FrameBuffer(bufW, bufH).also { eyeFbo = it }

        val head = ctx.tracking.headPose
        val mr = s.mode == XrMode.MR
        val fillScale = if (mr) computeFillScale() else 1f

        fbo.bind()
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glViewport(0, 0, bufW, bufH)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        for (i in 0 until eyeCount) {
            val eye = eyes[i]
            val layout = rig.eyes[i]
            val vx = (layout.rect[0] * bufW).roundToInt()
            val vw = (layout.rect[2] * bufW).roundToInt()
            eye.eyeIndex = i
            eye.viewportW = vw; eye.viewportH = bufH
            // Eye pose: head pose offset by ±IPD/2 along head right.
            val off = if (eyeCount == 1) 0f else if (i == 0) -s.ipd / 2f else s.ipd / 2f
            eye.headPose.set(head)
            eye.eyePose.set(head)
            head.transformPoint(off, 0f, 0f, eye.eyePose.p)
            eye.eyePose.toViewMatrix(eye.view)
            rig.projection(i, NEAR, FAR, fillScale, eye.proj)
            System.arraycopy(layout.tan, 0, eye.tanExtents, 0, 4)
            eye.pixelsPerTan = bufH / ((layout.tan[2] + layout.tan[3]) * fillScale)
            eye.update()
            GLES30.glViewport(vx, 0, vw, bufH)
            renderEye(eye, mr)
        }
        fbo.invalidateDepth()

        // Lens pass to the default framebuffer.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, sw, sh)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        val g = Gfx.get
        g.distort.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fbo.colorTex)
        g.distort.i1("uTex", 0)
        for (i in 0 until eyeCount) {
            val l = rig.eyes[i]
            g.distort.f4("uEyeRect", l.rect[0], l.rect[1], l.rect[2], l.rect[3])
            l.mesh?.draw()
        }
    }

    /** MR fill: zoom the virtual FOV toward the physical camera FOV so passthrough fills the lens. */
    private fun computeFillScale(): Float {
        val t = ctx.tracking
        if (!t.backgroundValid) return 1f
        val l = rig.eyes[0].tan
        val eyeTanY = (l[2] + l[3]) * 0.5f
        val ratio = (t.cameraTanHalfY / eyeTanY).coerceIn(0.3f, 1f)
        return 1f + (ratio - 1f) * ctx.settings.passthroughFill
    }

    private fun renderEye(eye: EyeContext, mr: Boolean) {
        eye.timeSeconds = ctx.timeSeconds
        eye.mixedReality = mr
        ctx.lighting.applyTo(eye)
        if (mr) ctx.occlusion.applyTo(eye) else eye.occlusionEnabled = false
        val scene = ctx.scene
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)
        scene.render(eye, RenderPass.BACKGROUND)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glColorMask(false, false, false, false)
        scene.render(eye, RenderPass.OCCLUDERS)
        GLES30.glColorMask(true, true, true, true)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        scene.render(eye, RenderPass.OPAQUE)

        GLES30.glDepthMask(false)
        scene.render(eye, RenderPass.TRANSPARENT)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        scene.render(eye, RenderPass.OVERLAY)
        GLES30.glDepthMask(true)
    }

    private fun renderExternal(ext: com.trackmr.xr.ExternalCompositor) {
        if (!ext.beginFrame(extFrame)) return
        if (!extFrame.shouldRender) { ext.endFrame(); return }
        if (extFbo[0] == 0) GLES30.glGenFramebuffers(1, extFbo, 0)
        val w = extFrame.width; val h = extFrame.height
        val sizeKey = (w.toLong() shl 32) or h.toLong()
        if (extDepthSize != sizeKey) {
            if (extDepth[0] != 0) GLES30.glDeleteRenderbuffers(1, extDepth, 0)
            GLES30.glGenRenderbuffers(1, extDepth, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, extDepth[0])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
            extDepthSize = sizeKey
        }
        val mr = ctx.settings.mode == XrMode.MR
        // If the runtime lacks positional tracking, keep ARCore translation (6DoF fusion).
        val arHead = ctx.tracking.headPose
        for (i in 0..1) {
            val tex = ext.acquireEye(i)
            FrameBuffer.attachTexture(extFbo[0], tex)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, extDepth[0])
            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            val eye = eyes[i]
            eye.eyeIndex = i
            eye.viewportW = w; eye.viewportH = h
            eye.eyePose.set(extFrame.eyePoses[i])
            if (!extFrame.positionTracked) {
                val off = if (i == 0) -ctx.settings.ipd / 2f else ctx.settings.ipd / 2f
                eye.eyePose.q.rotate(off, 0f, 0f, eye.eyePose.p)
                eye.eyePose.p.add(arHead.p)
            }
            eye.headPose.set(eye.eyePose)
            eye.eyePose.toViewMatrix(eye.view)
            val t = extFrame.eyeTans[i]
            android.opengl.Matrix.frustumM(eye.proj, 0, -t[0] * NEAR, t[1] * NEAR, -t[2] * NEAR, t[3] * NEAR, NEAR, FAR)
            System.arraycopy(t, 0, eye.tanExtents, 0, 4)
            eye.pixelsPerTan = h / (t[2] + t[3])
            eye.update()
            renderEye(eye, mr)
            ext.releaseEye(i)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        ext.endFrame()
    }

    private fun updateFps(dt: Float) {
        fpsAccum += dt; fpsFrames++
        if (fpsAccum >= 0.5f) { ctx.fps = fpsFrames / fpsAccum; fpsAccum = 0f; fpsFrames = 0 }
    }

    private fun checkThermal(now: Long) {
        if (now - lastThermalCheckNs < 2_000_000_000L) return
        lastThermalCheckNs = now
        if (Build.VERSION.SDK_INT >= 30 && power != null) {
            val headroom = try { power.getThermalHeadroom(10) } catch (e: Exception) { Float.NaN }
            dynRes.thermalCap = when {
                headroom.isNaN() -> 1f
                headroom >= 0.95f -> 0.7f
                headroom >= 0.85f -> 0.85f
                else -> 1f
            }
        }
    }

    companion object {
        private const val TAG = "TrackMR-Renderer"
        const val NEAR = 0.03f
        const val FAR = 200f
    }
}
