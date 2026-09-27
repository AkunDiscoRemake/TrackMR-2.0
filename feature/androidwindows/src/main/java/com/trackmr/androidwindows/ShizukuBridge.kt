package com.trackmr.androidwindows

import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.Executors

/**
 * Privileged helpers through Shizuku (shell identity): launch activities on TrackMR's
 * virtual displays and inject input events into them. Nothing here works without the
 * user installing/starting Shizuku and granting TrackMR permission.
 */
object ShizukuBridge {
    enum class Status(val label: String) {
        NOT_RUNNING("Shizuku não está em execução"),
        NO_PERMISSION("Permissão do Shizuku necessária"),
        READY("Shizuku conectado"),
    }

    private const val TAG = "TrackMR-Shizuku"
    private const val REQ = 4242
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "TrackMR-Shizuku") }
    @Volatile private var inputManager: Any? = null
    @Volatile private var injectMethod: java.lang.reflect.Method? = null
    private var setDisplayId: java.lang.reflect.Method? = null
    private var listenersInstalled = false
    @Volatile var onStatusChanged: (() -> Unit)? = null

    init {
        if (Build.VERSION.SDK_INT >= 28) try { HiddenApiBypass.addHiddenApiExemptions("L") } catch (t: Throwable) { Log.w(TAG, "bypass", t) }
    }

    fun installListeners() {
        if (listenersInstalled) return
        listenersInstalled = true
        Shizuku.addBinderReceivedListenerSticky { onStatusChanged?.invoke() }
        Shizuku.addBinderDeadListener { inputManager = null; onStatusChanged?.invoke() }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == REQ) onStatusChanged?.invoke()
            Log.i(TAG, "permission result $result")
        }
    }

    fun status(): Status = try {
        when {
            !Shizuku.pingBinder() -> Status.NOT_RUNNING
            Shizuku.isPreV11() -> Status.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
            else -> Status.READY
        }
    } catch (t: Throwable) { Status.NOT_RUNNING }

    fun requestPermission() {
        try { if (Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQ) } catch (t: Throwable) { Log.w(TAG, "request", t) }
    }

    /** Runs a shell command as the Shizuku user (shell). Returns exit code and output. */
    fun shell(cmd: String): Pair<Int, String> {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
            p.waitFor() to out
        } catch (t: Throwable) {
            Log.e(TAG, "shell failed: $cmd", t)
            -1 to (t.message ?: t.javaClass.simpleName)
        }
    }

    fun shellAsync(cmd: String, done: (Int, String) -> Unit = { _, _ -> }) = exec.execute { val (c, o) = shell(cmd); done(c, o) }

    /** Launches [component] (package/activity) on [displayId]. */
    fun launchOnDisplay(component: String, displayId: Int, done: (Boolean, String) -> Unit) {
        shellAsync("am start --display $displayId --windowingMode 1 -f 0x10008000 -n $component") { code, out ->
            val ok = code == 0 && !out.contains("Error", ignoreCase = true)
            done(ok, out.trim())
        }
    }

    fun forceStop(pkg: String) = shellAsync("am force-stop $pkg")

    private fun ensureInput(): Boolean {
        if (injectMethod != null) return true
        return try {
            val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val im = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            injectMethod = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            inputManager = im
            setDisplayId = try { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
            true
        } catch (t: Throwable) { Log.e(TAG, "input manager", t); false }
    }

    /** Injects an input event on [displayId] asynchronously (mode 0 = async). */
    fun inject(event: InputEvent, displayId: Int) {
        exec.execute {
            if (status() != Status.READY || !ensureInput()) return@execute
            try {
                setDisplayId?.invoke(event, displayId)
                injectMethod?.invoke(inputManager, event, 0)
            } catch (t: Throwable) { Log.w(TAG, "inject", t) }
            if (event is MotionEvent) event.recycle()
        }
    }

    private var downTime = 0L

    fun touch(displayId: Int, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val e = MotionEvent.obtain(downTime, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        inject(e, displayId)
    }

    fun scroll(displayId: Int, x: Float, y: Float, vScroll: Float) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll) })
        inject(MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0), displayId)
    }

    fun key(displayId: Int, keyCode: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId)
        inject(KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId)
    }

    fun text(displayId: Int, text: String) {
        val events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        if (events != null) events.forEach { inject(KeyEvent(it), displayId) }
        else shellAsync("input -d $displayId text " + "'" + text.replace("'", "'\\''").replace(" ", "%s") + "'")
    }
}
