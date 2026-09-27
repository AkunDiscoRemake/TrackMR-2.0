package com.trackmr.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import com.google.ar.core.ArCoreApk
import com.trackmr.arcore.ArCoreTrackingProvider
import com.trackmr.depth.DepthModule
import com.trackmr.hands.HandsModule
import com.trackmr.mr.Camera2TrackingProvider
import com.trackmr.mr.MixedRealityModule
import com.trackmr.ui.HomeShell
import com.trackmr.ui.NotificationCenter
import com.trackmr.ui.PointerVisuals
import com.trackmr.ui.WindowManager
import com.trackmr.vr.VirtualRealityModule
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrSettings
import com.trackmr.xr.render.XrRenderer
import com.trackmr.xr.tracking.TrackingProvider
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * One running TrackMR XR session: context, tracking backend, modules and the GL surface.
 * The activity only forwards lifecycle and raw input; everything visible is spatial.
 */
class XrSession(private val activity: Activity, private val onContextLost: () -> Unit) {
    val settings = XrSettings(activity)
    val ctx = XrContext(activity, settings)
    val view: GLSurfaceView = GLSurfaceView(activity)
    private val renderer = XrRenderer(ctx) { activity.runOnUiThread(onContextLost) }
    private var resumed = false
    private var usingArCore = false
    private var arCoreFailed = false

    init {
        installTrackingProvider(initial = true)
        installModules()

        view.preserveEGLContextOnPause = true
        view.setEGLContextClientVersion(3)
        view.setEGLConfigChooser(XrConfigChooser())
        view.setRenderer(renderer)
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        view.keepScreenOn = true
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnTouchListener { _, e -> onTouch(e); true }

        val dm = activity.resources.displayMetrics
        renderer.xdpi = dm.xdpi
        renderer.ydpi = dm.ydpi
        @Suppress("DEPRECATION")
        renderer.displayRotation = activity.windowManager.defaultDisplay.rotation
    }

    private fun hasCamera() = activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun arCoreInstalled(): Boolean = try {
        val apk = ArCoreApk.getInstance()
        var a = apk.checkAvailability(activity)
        var tries = 0
        while (a.isTransient && tries < 10) { Thread.sleep(40); a = apk.checkAvailability(activity); tries++ }
        a == ArCoreApk.Availability.SUPPORTED_INSTALLED
    } catch (e: Throwable) { Log.w(TAG, "ARCore availability check failed", e); false }

    /** Picks the best tracking backend: ARCore 6DoF → Camera2 + IMU (3DoF passthrough) → IMU only. */
    private fun installTrackingProvider(initial: Boolean) {
        val camera = hasCamera()
        val tp: TrackingProvider = when {
            camera && !arCoreFailed && arCoreInstalled() -> { usingArCore = true; ArCoreTrackingProvider(activity, settings) }
            else -> { usingArCore = false; Camera2TrackingProvider(activity, useCamera = camera) }
        }
        val old = ctx.trackingProvider
        ctx.trackingProvider = tp
        if (!initial) {
            ctx.runOnGl {
                try { old?.release() } catch (_: Throwable) {}
                tp.onGlReady()
                tp.setDisplayGeometry(renderer.displayRotation, ctx.screenWidth, ctx.screenHeight)
                activity.runOnUiThread { if (resumed) resumeProvider() }
            }
        }
        Log.i(TAG, "Tracking backend: ${tp.capabilities.name}")
    }

    private fun installModules() {
        val logo: Bitmap? = try { BitmapFactory.decodeResource(activity.resources, R.mipmap.ic_launcher_foreground) } catch (_: Throwable) { null }
        // Order matters: rendering backends, perception, then spatial UI and features.
        ctx.addModule(MixedRealityModule())
        ctx.addModule(VirtualRealityModule())
        FeatureRegistry.installEarly(ctx)
        ctx.addModule(HandsModule())
        ctx.addModule(DepthModule())
        ctx.addModule(WindowManager())
        ctx.addModule(HomeShell(logo))
        ctx.addModule(NotificationCenter())
        ctx.addModule(PointerVisuals())
        SystemApps.register(ctx)
        FeatureRegistry.install(ctx)
    }

    private fun resumeProvider() {
        val tp = ctx.trackingProvider ?: return
        val ok = try { tp.resume() } catch (e: Throwable) { Log.e(TAG, "resume", e); false }
        if (!ok && usingArCore) {
            arCoreFailed = true
            ctx.notify("ARCore indisponível — usando rastreamento 3DoF")
            installTrackingProvider(initial = false)
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) installTrackingProvider(initial = false)
        else ctx.notify("Sem câmera: passthrough e mãos desativados (modo VR)")
    }

    fun resume() {
        resumed = true
        resumeProvider()
        view.onResume()
        for (m in ctx.modules) try { m.onResume(ctx) } catch (e: Throwable) { Log.e(TAG, "onResume ${m.id}", e) }
    }

    fun pause() {
        resumed = false
        for (m in ctx.modules) try { m.onPause(ctx) } catch (e: Throwable) { Log.e(TAG, "onPause ${m.id}", e) }
        view.onPause()
        try { ctx.trackingProvider?.pause() } catch (_: Throwable) {}
    }

    fun destroy() {
        for (m in ctx.modules) try { m.onDestroy(ctx) } catch (e: Throwable) { Log.e(TAG, "onDestroy ${m.id}", e) }
        try { ctx.handProvider?.release() } catch (_: Throwable) {}
        try { ctx.trackingProvider?.release() } catch (_: Throwable) {}
        ctx.input.clear()
    }

    /** Cardboard button / screen tap = select (gaze pointer). */
    private fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> ctx.input.onScreenTouch(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ctx.input.onScreenTouch(false)
        }
    }

    fun onSelectButton(down: Boolean) = ctx.input.onScreenTouch(down)

    fun onMenuButton() {
        ctx.runOnGl { ctx.service(HomeShell::class.java)?.toggleHome() }
    }

    /** Tries RGB888 without depth/alpha (we render into our own FBO), then safer fallbacks. */
    private class XrConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val attempts = arrayOf(
                intArrayOf(8, 8, 8, 0, 0, 0), intArrayOf(8, 8, 8, 8, 0, 0),
                intArrayOf(8, 8, 8, 8, 16, 0), intArrayOf(5, 6, 5, 0, 16, 0),
            )
            for (a in attempts) choose(egl, display, a)?.let { return it }
            throw IllegalStateException("No EGL config for OpenGL ES 3")
        }

        private fun choose(egl: EGL10, display: EGLDisplay, a: IntArray): EGLConfig? {
            val attribs = intArrayOf(
                EGL10.EGL_RED_SIZE, a[0], EGL10.EGL_GREEN_SIZE, a[1], EGL10.EGL_BLUE_SIZE, a[2],
                EGL10.EGL_ALPHA_SIZE, a[3], EGL10.EGL_DEPTH_SIZE, a[4], EGL10.EGL_STENCIL_SIZE, a[5],
                EGL10.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */, EGL10.EGL_NONE,
            )
            val num = IntArray(1)
            if (!egl.eglChooseConfig(display, attribs, null, 0, num) || num[0] <= 0) return null
            val configs = arrayOfNulls<EGLConfig>(num[0])
            egl.eglChooseConfig(display, attribs, configs, num[0], num)
            // Prefer exact channel sizes (eglChooseConfig returns "at least" matches).
            val v = IntArray(1)
            fun attr(c: EGLConfig, k: Int): Int { egl.eglGetConfigAttrib(display, c, k, v); return v[0] }
            return configs.filterNotNull().firstOrNull {
                attr(it, EGL10.EGL_RED_SIZE) == a[0] && attr(it, EGL10.EGL_GREEN_SIZE) == a[1] &&
                    attr(it, EGL10.EGL_BLUE_SIZE) == a[2] && attr(it, EGL10.EGL_DEPTH_SIZE) >= a[4]
            } ?: configs.firstOrNull()
        }
    }

    companion object { private const val TAG = "TrackMR" }
}
