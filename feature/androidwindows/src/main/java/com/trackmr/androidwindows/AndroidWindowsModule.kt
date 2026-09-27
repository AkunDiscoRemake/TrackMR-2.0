package com.trackmr.androidwindows

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.KeyEvent
import android.view.MotionEvent
import com.trackmr.ui.AppCategory
import com.trackmr.ui.AppRegistry
import com.trackmr.ui.ButtonStyle
import com.trackmr.ui.ExternalSurfacePanel
import com.trackmr.ui.KeyboardTarget
import com.trackmr.ui.SpatialPanel
import com.trackmr.ui.UiButton
import com.trackmr.ui.UiLabel
import com.trackmr.ui.UiScroll
import com.trackmr.ui.UiTextField
import com.trackmr.ui.UiTheme
import com.trackmr.ui.UiTile
import com.trackmr.ui.WindowManager
import com.trackmr.ui.WindowState
import com.trackmr.ui.XrApp
import com.trackmr.ui.XrKeyboard
import com.trackmr.ui.XrWindow
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrModule
import com.trackmr.xr.gl.ExternalSurfaceTexture
import com.trackmr.xr.input.PointerEventType
import java.util.concurrent.Executors

/** An installed launcher activity. */
data class AndroidAppInfo(val label: String, val packageName: String, val activity: String) {
    val component get() = "$packageName/$activity"
}

/** Options for launching an app window (used by the emulation system for per-game setups). */
data class AndroidWindowOptions(
    val widthPx: Int = 1280,
    val heightPx: Int = 800,
    val dpi: Int = 220,
    val widthM: Float = 1.2f,
    val curvature: Float = 2.4f,
    /** Extra `am start` arguments, e.g. `-a android.intent.action.VIEW -d file:///rom.iso`. */
    val extraArgs: String = "",
)

/**
 * Android 2D apps inside TrackMR — always as spatial XR windows, never as flat Android UI.
 * Each window owns a virtual display rendered into a GL texture; the app is launched onto
 * it through Shizuku and hand/gaze pointer events are injected as touches.
 */
class AndroidWindowsModule : XrModule {
    override val id = "androidwindows"
    private lateinit var ctx: XrContext
    private val io = Executors.newSingleThreadExecutor()
    val windows = ArrayList<AndroidAppWindow>()
    var apps: List<AndroidAppInfo> = emptyList(); private set
    private val icons = HashMap<String, Bitmap>()

    override fun onAttach(ctx: XrContext) {
        this.ctx = ctx
        ctx.register(AndroidWindowsModule::class.java, this)
        ShizukuBridge.installListeners()
        AppRegistry.register(XrApp("android", "Apps Android", "Janelas XR via Shizuku", "📱", UiTheme.neonMint, AppCategory.ANDROID) { openLauncher() })
        refreshApps()
    }

    fun refreshApps(done: (() -> Unit)? = null) {
        io.execute {
            val pm = ctx.activity.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != ctx.activity.packageName }
                .map { AndroidAppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
                .sortedBy { it.label.lowercase() }
            apps = list
            done?.let { ctx.runOnGl(it) }
        }
    }

    fun icon(app: AndroidAppInfo): Bitmap? = synchronized(icons) {
        icons[app.packageName] ?: try {
            val d = ctx.activity.packageManager.getApplicationIcon(app.packageName)
            drawableToBitmap(d, 192).also { icons[app.packageName] = it }
        } catch (_: Exception) { null }
    }

    fun openLauncher() {
        val wm = ctx.service(WindowManager::class.java) ?: return
        wm.openOrFocus("android-launcher") { XrWindow(ctx, "Apps Android", AndroidLauncherPanel(ctx, this), "📱", UiTheme.neonMint) }
    }

    /** Opens [app] in a new spatial window. GL thread. */
    fun launch(app: AndroidAppInfo, options: AndroidWindowOptions = AndroidWindowOptions()): AndroidAppWindow? {
        when (ShizukuBridge.status()) {
            ShizukuBridge.Status.NOT_RUNNING -> { ctx.notify("Inicie o Shizuku para abrir apps Android em janelas XR"); openLauncher(); return null }
            ShizukuBridge.Status.NO_PERMISSION -> { ShizukuBridge.requestPermission(); ctx.notify("Conceda a permissão do Shizuku"); return null }
            ShizukuBridge.Status.READY -> {}
        }
        val wm = ctx.service(WindowManager::class.java) ?: return null
        val w = AndroidAppWindow(ctx, this, app, options)
        windows.add(w)
        w.window.onClose = { w.release(); windows.remove(w) }
        wm.open(w.window, 1.25f)
        w.start()
        return w
    }

    override fun onFrame(ctx: XrContext, dt: Float) { for (w in windows) w.onFrame(dt) }

    override fun onDestroy(ctx: XrContext) { windows.toList().forEach { it.release() }; io.shutdownNow() }

    companion object {
        fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.setBounds(0, 0, size, size)
            d.draw(c)
            return b
        }
    }
}

/** One Android app running on its own virtual display, shown as an XR window. */
class AndroidAppWindow(
    private val ctx: XrContext,
    private val module: AndroidWindowsModule,
    val app: AndroidAppInfo,
    private val options: AndroidWindowOptions,
) : KeyboardTarget {
    private var pxW = options.widthPx
    private var pxH = options.heightPx
    val panel = ExternalSurfacePanel(options.widthM, options.widthM * options.heightPx / options.widthPx, options.curvature)
    val toolbar = AndroidWindowToolbar(this)
    val window = XrWindow(ctx, app.label, panel, "📱", UiTheme.neonMint)
    private var texture: ExternalSurfaceTexture? = ExternalSurfaceTexture(pxW, pxH)
    private var display: VirtualDisplay? = null
    @Volatile var displayId = -1; private set
    private var resizePending = 0f
    private val keyboard get() = ctx.service(XrKeyboard::class.java)

    init {
        panel.source = texture
        panel.placeholderColor = floatArrayOf(0.06f, 0.05f, 0.1f)
        window.addAccessoryAbove(toolbar, 0.012f)
        window.onResize = { w, h -> resizePending = 0.4f; toolbar.resize(w.coerceAtLeast(0.5f), toolbar.heightM) }
        window.onMinimize = { min -> if (min) ShizukuBridge.key(displayId, KeyEvent.KEYCODE_MEDIA_PAUSE) }
        panel.onPointerEvent = { e, u, v ->
            val action = when (e.type) {
                PointerEventType.DOWN -> MotionEvent.ACTION_DOWN
                PointerEventType.DRAG -> MotionEvent.ACTION_MOVE
                PointerEventType.UP -> MotionEvent.ACTION_UP
                PointerEventType.CANCEL -> MotionEvent.ACTION_CANCEL
                else -> -1
            }
            if (action >= 0 && displayId >= 0) ShizukuBridge.touch(displayId, action, u.coerceIn(0f, 1f) * pxW, v.coerceIn(0f, 1f) * pxH)
        }
    }

    fun start() {
        val surface = texture?.surface ?: return
        ctx.runOnUi {
            val dm = ctx.activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            // PUBLIC + OWN_CONTENT_ONLY: other apps may be launched onto it (by Shizuku/shell), no mirroring.
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            val vd = try { dm.createVirtualDisplay("TrackMR-${app.packageName}", pxW, pxH, options.dpi, surface, flags) }
                catch (e: SecurityException) { dm.createVirtualDisplay("TrackMR-${app.packageName}", pxW, pxH, options.dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) }
            display = vd
            displayId = vd.display.displayId
            val comp = app.component + if (options.extraArgs.isNotEmpty()) " " + options.extraArgs else ""
            ShizukuBridge.launchOnDisplay(comp, displayId) { ok, out ->
                if (!ok) ctx.notify("Falha ao abrir ${app.label}: ${out.take(120)}")
            }
        }
    }

    fun back() = ShizukuBridge.key(displayId, KeyEvent.KEYCODE_BACK)
    fun home() = ShizukuBridge.key(displayId, KeyEvent.KEYCODE_HOME)
    fun openKeyboard() { keyboard?.open(this, window) }
    fun restart() { ShizukuBridge.forceStop(app.packageName); ShizukuBridge.launchOnDisplay(app.component, displayId) { _, _ -> } }

    override fun onKeyText(text: String) = ShizukuBridge.text(displayId, text)
    override fun onKeyBackspace() = ShizukuBridge.key(displayId, KeyEvent.KEYCODE_DEL)
    override fun onKeyEnter() = ShizukuBridge.key(displayId, KeyEvent.KEYCODE_ENTER)

    /** Debounced virtual-display resize so the app re-lays out at the new window aspect. */
    fun onFrame(dt: Float) {
        if (resizePending > 0f) {
            resizePending -= dt
            if (resizePending <= 0f) applyResize()
        }
    }

    private fun applyResize() {
        val aspect = panel.widthM / panel.heightM
        val w = (pxH * aspect).toInt().coerceIn(480, 2560) and 1.inv()
        if (w == pxW) return
        pxW = w
        texture?.resize(pxW, pxH)
        val h = pxH
        ctx.runOnUi { display?.resize(w, h, options.dpi) }
    }

    fun release() {
        if (keyboard?.isTarget(this) == true) keyboard?.close()
        val d = display; display = null
        ctx.runOnUi { d?.release() }
        val t = texture; texture = null
        ctx.runOnGl { panel.source = null; t?.release() }
    }
}

/** Back / home / keyboard / restart controls above an Android window. */
class AndroidWindowToolbar(private val w: AndroidAppWindow) : SpatialPanel(0.62f, 0.075f, 900f) {
    init {
        cornerPx = 30f
        val items = listOf(
            UiButton("", "◀", ButtonStyle.GHOST, textSize = 30f) { w.back() },
            UiButton("", "⌂", ButtonStyle.GHOST, textSize = 32f) { w.home() },
            UiButton("", "⌨", ButtonStyle.GHOST, textSize = 30f) { w.openKeyboard() },
            UiButton("", "⟳", ButtonStyle.GHOST, textSize = 30f) { w.restart() },
        )
        items.forEach { root.add(it) }
        root.add(UiLabel(w.app.label, 24f, UiTheme.textSecondary, Paint.Align.RIGHT))
        layout()
    }

    override fun layout() {
        val h = pixelH - 12f
        var x = 10f
        for (c in root.children) {
            if (c is UiButton) { c.at(x, 6f, h, h); x += h + 6f }
            else c.at(x + 10f, 6f, pixelW - x - 30f, h)
        }
        invalidate()
    }
}

/** Launcher grid of installed Android apps + Shizuku status/help. */
class AndroidLauncherPanel(private val ctx: XrContext, private val module: AndroidWindowsModule) :
    SpatialPanel(1.1f, 0.72f, 800f, radius = 1.6f) {
    private val title = root.add(UiLabel("📱  Apps Android", 34f, UiTheme.textPrimary, bold = true))
    private val status = root.add(UiLabel("", 22f, UiTheme.textSecondary, maxLines = 2))
    private val action = root.add(UiButton("Conceder", "🔑", ButtonStyle.PRIMARY, UiTheme.neonMint, 22f) { onAction() })
    private val search = root.add(UiTextField("Buscar apps") { openSearch() })
    private val grid = root.add(UiScroll())
    private var filter = ""
    private var statusTimer = 0f
    private val iconExec = Executors.newSingleThreadExecutor()

    init {
        priority = 2
        ShizukuBridge.onStatusChanged = { ctx.runOnGl { updateStatus() } }
        layout()
        updateStatus()
        module.refreshApps { rebuild() }
    }

    override fun layout() {
        val W = pixelW.toFloat(); val H = pixelH.toFloat()
        title.at(34f, 16f, W * 0.5f, 54f)
        search.at(W * 0.55f, 18f, W * 0.45f - 30f, 54f)
        status.at(34f, 78f, W - 320f, 64f)
        action.at(W - 270f, 80f, 240f, 58f)
        grid.at(20f, 150f, W - 40f, H - 166f)
        rebuild()
    }

    private fun onAction() {
        when (ShizukuBridge.status()) {
            ShizukuBridge.Status.NO_PERMISSION -> ShizukuBridge.requestPermission()
            ShizukuBridge.Status.NOT_RUNNING -> {
                val i = ctx.activity.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ctx.notify(if (i != null) "Abra o Shizuku fora do headset e toque em Iniciar" else "Instale o Shizuku: shizuku.rikka.app")
            }
            ShizukuBridge.Status.READY -> module.refreshApps { rebuild() }
        }
    }

    private fun updateStatus() {
        val s = ShizukuBridge.status()
        status.text = s.label + when (s) {
            ShizukuBridge.Status.READY -> " — escolha um app para abrir em uma janela XR"
            ShizukuBridge.Status.NO_PERMISSION -> " — toque em Conceder"
            ShizukuBridge.Status.NOT_RUNNING -> " — instale/inicie o Shizuku (wireless debugging ou root)"
        }
        action.text = when (s) { ShizukuBridge.Status.READY -> "Atualizar"; ShizukuBridge.Status.NO_PERMISSION -> "Conceder"; else -> "Ajuda" }
        action.invalidate(); invalidate()
    }

    override fun onFrame(dt: Float) {
        statusTimer += dt
        if (statusTimer > 3f) { statusTimer = 0f; updateStatus() }
    }

    private fun openSearch() {
        val kb = ctx.service(XrKeyboard::class.java) ?: return
        search.focused = true
        filter = ""
        kb.open(object : KeyboardTarget {
            override fun onKeyText(text: String) { filter += text; search.text = filter; rebuild() }
            override fun onKeyBackspace() { filter = filter.dropLast(1); search.text = filter; rebuild() }
            override fun onKeyEnter() { kb.close() }
            override fun onKeyboardClosed() { search.focused = false }
        })
    }

    fun rebuild() {
        grid.clearChildren()
        val list = module.apps.filter { filter.isEmpty() || it.label.contains(filter, true) }
        val cols = 6; val gap = 14f
        val tw = (grid.bounds.width() - gap * (cols + 1) - 10f) / cols
        val th = tw * 0.95f
        list.forEachIndexed { i, app ->
            val t = UiTile(app.label, "", "📱", UiTheme.accents[i % UiTheme.accents.size]) { module.launch(app) }
            t.at(grid.bounds.left + gap + (i % cols) * (tw + gap), grid.bounds.top + gap / 2 + (i / cols) * (th + gap), tw, th)
            grid.add(t)
            iconExec.execute { module.icon(app)?.let { b -> ctx.runOnGl { t.image = b; t.invalidate() } } }
        }
        grid.contentHeight = ((list.size + cols - 1) / cols) * (th + gap) + gap
        invalidate()
    }
}
