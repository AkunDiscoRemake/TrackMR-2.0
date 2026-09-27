package com.trackmr.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.BatteryManager
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrMode
import com.trackmr.xr.XrModule
import com.trackmr.xr.input.PointerEvent
import com.trackmr.xr.math.MathUtil
import com.trackmr.xr.math.Vec3
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Categories shown as filters in the home grid. */
enum class AppCategory(val label: String) { SYSTEM("Sistema"), MEDIA("Mídia"), GAMES("Jogos"), TOOLS("Ferramentas"), ANDROID("Android") }

/** A launchable XR experience (spatial app, game, window, environment...). Launch runs on the GL thread. */
class XrApp(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val icon: String = "✨",
    val accent: Int = UiTheme.neonBlue,
    val category: AppCategory = AppCategory.SYSTEM,
    val image: Bitmap? = null,
    val launch: (XrContext) -> Unit,
)

/** Global registry of launchable apps; feature modules contribute entries at startup. */
object AppRegistry {
    val apps = CopyOnWriteArrayList<XrApp>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun register(app: XrApp) { apps.removeAll { it.id == app.id }; apps.add(app); listeners.forEach { it() } }
    fun unregister(id: String) { apps.removeAll { it.id == id }; listeners.forEach { it() } }
    fun find(id: String) = apps.firstOrNull { it.id == id }
    fun addListener(l: () -> Unit) { listeners.add(l) }
}

/**
 * The spatial home: curved launcher panel, floating orb, wrist button, running-window dock,
 * MR/VR switch and quick toggles. Opens automatically at startup so the very first frame
 * is passthrough + spatial UI.
 */
class HomeShell(private val logo: Bitmap? = null) : XrModule {
    override val id = "home"
    private lateinit var ctx: XrContext
    lateinit var home: HomePanel; private set
    private lateinit var orb: OrbButton
    private lateinit var wrist: OrbButton
    lateinit var keyboard: XrKeyboard; private set
    val windows get() = ctx.service(WindowManager::class.java)
    private val tmp = Vec3()
    private val fwd = Vec3()
    private var firstFrame = true
    private var startTime = 0f
    var homeVisible = false; private set

    override fun onAttach(ctx: XrContext) {
        this.ctx = ctx
        ctx.register(HomeShell::class.java, this)
        home = HomePanel(ctx, this, logo)
        orb = OrbButton(logo, 0.085f) { toggleHome() }
        wrist = OrbButton(logo, 0.06f) { toggleHome() }
        wrist.priority = 10
        keyboard = XrKeyboard(ctx)
        ctx.register(XrKeyboard::class.java, keyboard)
        for (s in listOf<SpatialSurface>(home, orb, wrist)) { ctx.scene.add(s); ctx.input.register(s) }
        home.hide(false)
        wrist.hide(false)
        AppRegistry.addListener { ctx.runOnGl { home.rebuildApps() } }
    }

    override fun onGlReady(ctx: XrContext) {
        windows?.addListener { ctx.runOnGl { home.rebuildWindows() } }
    }

    fun toggleHome() { if (homeVisible) hideHome() else showHome() }

    fun showHome() {
        val head = ctx.headPose
        val yaw = head.q.yaw()
        val d = 1.25f
        home.pose.p.set(head.p.x - sin(yaw) * d, head.p.y - 0.05f, head.p.z - cos(yaw) * d)
        home.pose.q.setAxisAngle(0f, 1f, 0f, yaw)
        home.rebuildWindows()
        home.show()
        homeVisible = true
        UiAudio.open()
    }

    fun hideHome() {
        home.hide(); homeVisible = false
        keyboard.close()
    }

    /** Launch helper used by tiles: hides home so the new window takes focus. */
    fun launch(app: XrApp) {
        hideHome()
        try { app.launch(ctx) } catch (e: Exception) { ctx.notify("Falha ao abrir ${app.title}: ${e.message}") }
    }

    override fun onFrame(ctx: XrContext, dt: Float) {
        startTime += dt
        if (firstFrame && (ctx.tracking.quality.ordinal > 0 || startTime > 1.5f)) { firstFrame = false; showHome() }
        val head = ctx.headPose
        head.forward(fwd); fwd.y = 0f
        if (fwd.lengthSq() < 1e-4f) fwd.set(0f, 0f, -1f)
        fwd.normalize()

        // Orb: lazily follows, low in the field of view.
        tmp.set(head.p).addScaled(fwd, 0.55f); tmp.y = head.p.y - 0.30f
        val k = MathUtil.dampFactor(3f, dt)
        if (orb.pose.p.distanceSq(tmp) > 0.0625f || orb.pose.p.lengthSq() == 0f) orb.pose.p.set(tmp) else orb.pose.p.lerp(tmp, k)
        faceHead(orb, head.p)
        orb.targetOpacity = if (homeVisible) 0.55f else 1f

        // Wrist button on the left palm when it faces the user.
        val lh = ctx.hands.left
        if (lh.tracked && lh.palmFacingUser) {
            tmp.set(lh.palmCenter).addScaled(lh.palmNormal, 0.035f)
            wrist.pose.p.set(tmp)
            faceHead(wrist, head.p)
            if (wrist.targetOpacity == 0f) wrist.show()
        } else if (wrist.targetOpacity > 0f) wrist.hide()

        if (ctx.hands.left.menuGesture || ctx.hands.right.menuGesture) toggleHome()
        home.update(dt)
    }

    private fun faceHead(s: SpatialSurface, headP: Vec3) {
        tmp.setSub(headP, s.pose.p)
        s.pose.q.setAxisAngle(0f, 1f, 0f, atan2(tmp.x, tmp.z))
    }
}

/** Round floating button (orb / wrist) drawn with a glowing gradient and the TrackMR logo. */
class OrbButton(private val logo: Bitmap?, size: Float, private val action: () -> Unit) : SpatialPanel(size, size, 1400f) {
    init {
        drawGlass = false
        root.add(object : UiNode() { override val interactive = true }.also { it.onClick = action })
        priority = 8
    }

    override fun layout() { root.children.firstOrNull()?.at(0f, 0f, pixelW.toFloat(), pixelH.toFloat()) }
    override fun onResized() { super.onResized(); layout() }

    override fun drawBackground(c: Canvas) {
        val w = pixelW.toFloat(); val r = w / 2f
        if (root.children.firstOrNull()?.bounds?.isEmpty != false) layout()
        val hov = root.children.firstOrNull()?.hovered == true
        p.shader = RadialGradient(r, r, r, intArrayOf(UiTheme.neonPink, UiTheme.violet, UiTheme.withAlpha(UiTheme.violet, 0)),
            floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r, p)
        p.shader = null
        ring.color = if (hov) Color.WHITE else UiTheme.withAlpha(Color.WHITE, 150)
        ring.strokeWidth = w * 0.05f
        c.drawCircle(r, r, r * 0.78f, ring)
        val l = logo
        if (l != null && !l.isRecycled) {
            val s = r * 1.15f
            c.drawBitmap(l, null, android.graphics.RectF(r - s / 2, r - s / 2, r + s / 2, r + s / 2), bmp)
        } else {
            val tp = UiTheme.textPaint(w * 0.36f, Color.WHITE, UiTheme.bold).apply { textAlign = Paint.Align.CENTER }
            c.drawText("MR", r, r - (tp.descent() + tp.ascent()) / 2, tp)
        }
    }

    companion object {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
}

/** The main launcher panel. */
class HomePanel(private val ctx: XrContext, private val shell: HomeShell, private val logo: Bitmap?) :
    SpatialPanel(1.3f, 0.8f, 780f, radius = 1.4f) {

    private val clock = root.add(UiLabel("--:--", 44f, UiTheme.textPrimary, Paint.Align.LEFT, bold = true))
    private val status = root.add(UiLabel("", 22f, UiTheme.textSecondary))
    private val modeSwitch = root.add(UiSegmented(listOf("MR", "VR"), if (ctx.mode == XrMode.MR) 0 else 1, UiTheme.neonPink) { i ->
        ctx.setMode(if (i == 0) XrMode.MR else XrMode.VR)
    })
    private val recenterBtn = root.add(UiButton("Recentralizar", "🎯", ButtonStyle.SECONDARY, textSize = 22f) {
        ctx.trackingProvider?.recenter(); shell.windows?.recenter(); shell.showHome()
    })
    private val filters = root.add(UiSegmented(listOf("Tudo") + AppCategory.values().map { it.label }, 0, UiTheme.neonBlue) { i ->
        category = if (i == 0) null else AppCategory.values()[i - 1]; rebuildApps()
    })
    private val grid = root.add(UiScroll())
    private val dockTitle = root.add(UiLabel("Janelas abertas", 22f, UiTheme.textMuted))
    private val dock = root.add(UiNode())
    private var category: AppCategory? = null
    private var clockTimer = 0f
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        priority = 2
        layout()
        rebuildApps()
    }

    override fun layout() {
        val W = pixelW.toFloat(); val H = pixelH.toFloat()
        clock.at(130f, 22f, 240f, 60f)
        status.at(130f, 78f, W * 0.45f, 32f)
        modeSwitch.at(W - 520f, 30f, 200f, 64f)
        recenterBtn.at(W - 300f, 30f, 250f, 64f)
        filters.at(40f, 128f, W - 80f, 56f)
        grid.at(30f, 200f, W - 60f, H - 200f - 150f)
        dockTitle.at(44f, H - 142f, 400f, 30f)
        dock.at(30f, H - 106f, W - 60f, 86f)
        invalidate()
    }

    override fun onResized() { super.onResized(); layout(); rebuildApps(); rebuildWindows() }

    override fun drawBackground(c: Canvas) {
        super.drawBackground(c)
        val l = logo
        if (l != null && !l.isRecycled) c.drawBitmap(l, null, android.graphics.RectF(36f, 26f, 116f, 106f), bmpPaint)
        sep.color = UiTheme.stroke
        c.drawLine(40f, pixelH - 156f, pixelW - 40f, pixelH - 156f, sep)
    }

    fun rebuildApps() {
        grid.clearChildren()
        val apps = AppRegistry.apps.filter { category == null || it.category == category }
        val cols = 5
        val gap = 18f
        val tw = (grid.bounds.width() - gap * (cols + 1) - 10f) / cols
        val th = tw * 0.82f
        apps.forEachIndexed { i, app ->
            val col = i % cols; val row = i / cols
            val t = UiTile(app.title, app.subtitle, app.icon, app.accent, app.image) { shell.launch(app) }
            t.at(grid.bounds.left + gap + col * (tw + gap), grid.bounds.top + gap / 2 + row * (th + gap), tw, th)
            grid.add(t)
        }
        val rows = (apps.size + cols - 1) / cols
        grid.contentHeight = rows * (th + gap) + gap
        grid.scrollY = grid.scrollY
        if (apps.isEmpty()) grid.add(UiLabel("Nenhum app nesta categoria", 26f, UiTheme.textMuted, Paint.Align.CENTER)
            .also { it.at(grid.bounds.left, grid.bounds.top + 40f, grid.bounds.width(), 60f) })
        invalidate()
    }

    fun rebuildWindows() {
        dock.clearChildren()
        val wm = shell.windows ?: return
        var x = dock.bounds.left
        for (w in wm.windows) {
            if (w.state == WindowState.CLOSED) continue
            val label = if (w.state == WindowState.MINIMIZED) "${w.title} ▾" else w.title
            val b = UiButton(label.take(22), w.icon, ButtonStyle.SECONDARY, w.accent, 22f) { shell.hideHome(); wm.focus(w) }
            val bw = 60f + label.take(22).length * 12f
            if (x + bw > dock.bounds.right) break
            b.at(x, dock.bounds.top + 8f, bw, 70f)
            dock.add(b); x += bw + 14f
        }
        if (dock.children.isEmpty()) dock.add(UiLabel("Nenhuma janela — abra um app acima", 22f, UiTheme.textMuted)
            .also { it.at(dock.bounds.left + 14f, dock.bounds.top, 700f, 86f) })
        else {
            val closeAll = UiButton("Fechar tudo", "✖", ButtonStyle.DANGER, textSize = 20f) { wm.closeAll() }
            closeAll.at(dock.bounds.right - 220f, dock.bounds.top + 8f, 210f, 70f)
            dock.add(closeAll)
        }
        invalidate()
    }

    fun update(dt: Float) {
        clockTimer -= dt
        if (clockTimer > 0f || hidden) return
        clockTimer = 5f
        clock.text = fmt.format(Date())
        val bm = ctx.activity.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batt = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val track = when (ctx.tracking.quality.name) { "FULL_6DOF" -> "6DoF"; "ORIENTATION_ONLY" -> "3DoF"; "LIMITED" -> "6DoF limitado"; else -> "sem tracking" }
        val hands = ctx.hands.source
        status.text = "🔋 ${if (batt >= 0) "$batt%" else "--"}   •   ${ctx.fps.toInt()} fps   •   $track   •   mãos: $hands"
        val sel = if (ctx.mode == XrMode.MR) 0 else 1
        if (modeSwitch.selected != sel) modeSwitch.selected = sel
        invalidate()
    }

    override fun onSurfacePointer(e: PointerEvent, x: Float, y: Float): Boolean = false

    companion object {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val sep = Paint().apply { strokeWidth = 2f }
    }
}
