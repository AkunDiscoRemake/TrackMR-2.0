package com.trackmr.browser

import android.graphics.Paint
import android.net.Uri
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
import com.trackmr.ui.UiNode
import com.trackmr.ui.UiTextField
import com.trackmr.ui.UiTheme
import com.trackmr.ui.WindowManager
import com.trackmr.ui.XrApp
import com.trackmr.ui.XrKeyboard
import com.trackmr.ui.XrWindow
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrModule
import com.trackmr.xr.gl.ExternalSurfaceTexture
import com.trackmr.xr.input.PointerEventType
import org.json.JSONArray

/** Launcher entry + favorites storage for the XR browser. */
class BrowserModule : XrModule {
    override val id = "browser"
    private lateinit var ctx: XrContext
    private var window: BrowserWindow? = null
    val favorites = ArrayList<Pair<String, String>>()
    /** Video links found by the browser are handed to the cinema if present. */
    var onVideoDownload: ((String) -> Unit)? = null

    override fun onAttach(ctx: XrContext) {
        this.ctx = ctx
        ctx.register(BrowserModule::class.java, this)
        loadFavorites()
        AppRegistry.register(XrApp("browser", "Navegador", "Web em superfície curva", "🌐", UiTheme.neonBlue, AppCategory.TOOLS) { open(null) })
    }

    /** Opens the browser window (or a new tab with [url]). GL thread. */
    fun open(url: String?) {
        val wm = ctx.service(WindowManager::class.java) ?: return
        val existing = window
        if (existing != null && existing.window.state != com.trackmr.ui.WindowState.CLOSED) {
            wm.focus(existing.window)
            if (url != null) existing.newTab(url)
            return
        }
        val bw = BrowserWindow(ctx, this, url ?: BrowserEngine.HOME)
        window = bw
        bw.window.onClose = { bw.release(); window = null }
        wm.open(bw.window, 1.3f)
    }

    fun openUri(uri: Uri) = open(uri.toString())

    fun isFavorite(url: String) = favorites.any { it.second == url }
    fun toggleFavorite(title: String, url: String) {
        if (isFavorite(url)) favorites.removeAll { it.second == url } else favorites.add(title to url)
        saveFavorites()
    }

    private fun loadFavorites() {
        favorites.clear()
        val raw = ctx.settings.getString("browser.favorites", "")
        if (raw.isEmpty()) {
            favorites += listOf("DuckDuckGo" to "https://duckduckgo.com/", "Wikipedia" to "https://pt.m.wikipedia.org/",
                "YouTube" to "https://m.youtube.com/", "GitHub" to "https://github.com/", "Sketchfab" to "https://sketchfab.com/")
            return
        }
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); favorites.add(o.getString("t") to o.getString("u")) }
        } catch (_: Exception) {}
    }

    private fun saveFavorites() {
        val a = JSONArray()
        favorites.forEach { a.put(org.json.JSONObject().put("t", it.first).put("u", it.second)) }
        ctx.settings.put("browser.favorites", a.toString())
    }

    override fun onPause(ctx: XrContext) { window?.let { w -> ctx.runOnUi { w.engine?.pause() } } }
    override fun onResume(ctx: XrContext) { window?.let { w -> ctx.runOnUi { w.engine?.resume() } } }
    override fun onDestroy(ctx: XrContext) { window?.release() }
}

/** The browser window: curved page surface + toolbar (tabs, navigation, address bar, favorites). */
class BrowserWindow(private val ctx: XrContext, private val module: BrowserModule, startUrl: String) : BrowserEngine.Listener, KeyboardTarget {
    private val pageW = 1280
    private val pageH = 800
    val page = ExternalSurfacePanel(1.28f, 0.8f, 2.2f)
    val toolbar = BrowserToolbar(this)
    val window = XrWindow(ctx, "Navegador", page, "🌐", UiTheme.neonBlue)
    private var texture: ExternalSurfaceTexture? = null
    @Volatile var engine: BrowserEngine? = null; private set
    private var typingUrl = false
    private var addressText = ""
    private val keyboard get() = ctx.service(XrKeyboard::class.java)

    init {
        page.placeholderColor = floatArrayOf(0.95f, 0.95f, 0.98f)
        window.addAccessoryAbove(toolbar, 0.015f)
        window.onResize = { w, h -> toolbar.resizeTo(w) }
        val tex = ExternalSurfaceTexture(pageW, pageH)
        texture = tex
        page.source = tex
        ctx.runOnUi {
            val e = BrowserEngine(ctx, tex.surface, pageW, pageH, this)
            engine = e
            e.start()
            e.newTab(startUrl)
        }
        page.onPointerEvent = { ev, u, v ->
            val action = when (ev.type) {
                PointerEventType.DOWN -> MotionEvent.ACTION_DOWN
                PointerEventType.DRAG -> MotionEvent.ACTION_MOVE
                PointerEventType.UP -> MotionEvent.ACTION_UP
                PointerEventType.CANCEL -> MotionEvent.ACTION_CANCEL
                else -> -1
            }
            if (action >= 0) {
                val uu = u.coerceIn(0f, 1f); val vv = v.coerceIn(0f, 1f)
                ctx.runOnUi { engine?.touch(action, uu, vv) }
            }
        }
    }

    fun newTab(url: String = BrowserEngine.HOME) = ctx.runOnUi { engine?.newTab(url) }
    fun back() = ctx.runOnUi { engine?.back() }
    fun forward() = ctx.runOnUi { engine?.forward() }
    fun reload() = ctx.runOnUi { engine?.reload() }
    fun home() = ctx.runOnUi { engine?.active?.let { engine?.load(it, BrowserEngine.HOME) } }
    fun switchTab(id: Int) = ctx.runOnUi { engine?.tabs?.firstOrNull { it.id == id }?.let { engine?.switchTo(it) } }
    fun closeTab(id: Int) = ctx.runOnUi { engine?.let { e -> e.tabs.firstOrNull { it.id == id }?.let { e.closeTab(it) }; if (e.tabs.isEmpty()) e.newTab(BrowserEngine.HOME) } }
    fun openFavorite(url: String) = ctx.runOnUi { engine?.active?.let { engine?.load(it, url) } }

    fun toggleFavorite() {
        val t = engine?.active ?: return
        module.toggleFavorite(t.title, t.url)
        toolbar.refresh(snapshot())
    }

    fun favorites() = module.favorites
    fun isFavorite(url: String) = module.isFavorite(url)

    /** Address bar focused: open the XR keyboard below the window. */
    fun editAddress() {
        typingUrl = true
        addressText = ""
        toolbar.setAddress("", editing = true)
        keyboard?.open(this, window)
    }

    fun openTyping() { typingUrl = false; keyboard?.open(this, window) }

    // --- KeyboardTarget ---
    override fun onKeyText(text: String) {
        if (typingUrl) { addressText += text; toolbar.setAddress(addressText, true) }
        else ctx.runOnUi { engine?.typeText(text) }
    }
    override fun onKeyBackspace() {
        if (typingUrl) { addressText = addressText.dropLast(1); toolbar.setAddress(addressText, true) }
        else ctx.runOnUi { engine?.key(KeyEvent.KEYCODE_DEL) }
    }
    override fun onKeyEnter() {
        if (typingUrl) {
            val text = addressText
            typingUrl = false
            keyboard?.close()
            ctx.runOnUi { engine?.active?.let { engine?.load(it, text) } }
        } else ctx.runOnUi { engine?.key(KeyEvent.KEYCODE_ENTER) }
    }
    override fun onKeyboardClosed() {
        if (typingUrl) { typingUrl = false; ctx.runOnGl { toolbar.refresh(snapshot()) } }
    }

    // --- Engine listener (main thread) → GL thread snapshots ---
    data class TabSnap(val id: Int, val title: String, val active: Boolean)
    data class Snapshot(val url: String, val title: String, val progress: Int, val back: Boolean, val fwd: Boolean, val tabs: List<TabSnap>)

    private fun snapshot(): Snapshot {
        val e = engine
        val a = e?.active
        return Snapshot(a?.url ?: "", a?.title ?: "", a?.progress ?: 100, a?.canGoBack ?: false, a?.canGoForward ?: false,
            e?.tabs?.map { TabSnap(it.id, it.title, it === a) } ?: emptyList())
    }

    override fun onTabsChanged() { val s = snapshot(); ctx.runOnGl { toolbar.refresh(s); window.title = s.title.ifEmpty { "Navegador" } } }
    override fun onTabUpdated(tab: BrowserTab) { if (tab === engine?.active) onTabsChanged() }
    override fun onTextInputFocused(focused: Boolean) {
        ctx.runOnGl { if (focused) openTyping() else if (!typingUrl && keyboard?.isTarget(this) == true) keyboard?.close() }
    }
    override fun onDownload(url: String, mime: String?) {
        if (mime?.startsWith("video/") == true) ctx.runOnGl { module.onVideoDownload?.invoke(url) ?: ctx.notify("Download: $url") }
        else ctx.notify("Downloads não suportados: ${Uri.parse(url).lastPathSegment}")
    }

    fun release() {
        val e = engine; engine = null
        ctx.runOnUi { e?.release() }
        ctx.runOnGl { texture?.release(); texture = null; page.source = null }
        if (keyboard?.isTarget(this) == true) keyboard?.close()
    }
}

/** Toolbar surface: tab strip + navigation row. */
class BrowserToolbar(private val bw: BrowserWindow) : SpatialPanel(1.28f, 0.2f, 800f, radius = 2.2f) {
    private val backBtn = root.add(UiButton("", "◀", ButtonStyle.GHOST, textSize = 30f) { bw.back() })
    private val fwdBtn = root.add(UiButton("", "▶", ButtonStyle.GHOST, textSize = 30f) { bw.forward() })
    private val reloadBtn = root.add(UiButton("", "⟳", ButtonStyle.GHOST, textSize = 32f) { bw.reload() })
    private val homeBtn = root.add(UiButton("", "🏠", ButtonStyle.GHOST, textSize = 26f) { bw.home() })
    private val address = root.add(UiTextField("Pesquise ou digite um endereço") { bw.editAddress() })
    private val favBtn = root.add(UiButton("", "☆", ButtonStyle.GHOST, UiTheme.neonGold, 30f) { bw.toggleFavorite() })
    private val kbBtn = root.add(UiButton("", "⌨", ButtonStyle.GHOST, textSize = 30f) { bw.openTyping() })
    private val newTabBtn = root.add(UiButton("", "＋", ButtonStyle.GHOST, textSize = 30f) { bw.newTab() })
    private val tabStrip = root.add(UiNode())
    private val favStrip = root.add(UiNode())
    private val closeTabBtn = root.add(UiButton("", "✕", ButtonStyle.GHOST, textSize = 26f) { last?.tabs?.firstOrNull { it.active }?.let { bw.closeTab(it.id) } })
    private val progress = root.add(ProgressBar())
    private var last: BrowserWindow.Snapshot? = null

    init { priority = 3; cornerPx = 28f; layout() }

    fun resizeTo(w: Float) { resize(w, heightM); setCurvature(bw.page.radius) }

    override fun layout() {
        val W = pixelW.toFloat()
        tabStrip.at(16f, 6f, W - 150f, 50f)
        closeTabBtn.at(W - 130f, 6f, 56f, 50f)
        newTabBtn.at(W - 70f, 6f, 56f, 50f)
        var x = 12f
        for (b in listOf(backBtn, fwdBtn, reloadBtn, homeBtn)) { b.at(x, 62f, 60f, 56f); x += 64f }
        address.at(x + 6f, 62f, W - x - 160f, 56f)
        favBtn.at(W - 146f, 62f, 64f, 56f)
        kbBtn.at(W - 78f, 62f, 64f, 56f)
        favStrip.at(16f, 122f, W - 32f, braceH())
        progress.at(16f, pixelH - 6f, W - 32f, 4f)
        last?.let { refresh(it) }
    }

    fun setAddress(text: String, editing: Boolean) {
        address.text = text; address.focused = editing; address.invalidate(); invalidate()
    }

    fun refresh(s: BrowserWindow.Snapshot) {
        last = s
        if (!address.focused) address.text = s.url
        backBtn.enabled = s.back; fwdBtn.enabled = s.fwd
        favBtn.icon = if (bw.isFavorite(s.url)) "★" else "☆"
        progress.value = s.progress
        favStrip.clearChildren()
        var fx = favStrip.bounds.left
        for ((title, url) in bw.favorites()) {
            val w = 40f + title.length * 13f
            if (fx + w > favStrip.bounds.right) break
            favStrip.add(UiButton(title, "★", ButtonStyle.GHOST, UiTheme.neonGold, 18f) { bw.openFavorite(url) }
                .also { it.at(fx, favStrip.bounds.top, w, favStrip.bounds.height()) })
            fx += w + 8f
        }
        tabStrip.clearChildren()
        val n = s.tabs.size.coerceAtLeast(1)
        val tw = ((tabStrip.bounds.width() - (n - 1) * 8f) / n).coerceAtMost(300f)
        s.tabs.forEachIndexed { i, t ->
            val b = UiButton(t.title.take(24).ifEmpty { "Nova aba" }, if (t.active) "●" else null,
                if (t.active) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY, textSize = 20f) { bw.switchTab(t.id) }
            b.onLongPress = { bw.closeTab(t.id) }
            b.at(tabStrip.bounds.left + i * (tw + 8f), tabStrip.bounds.top, tw, tabStrip.bounds.height())
            tabStrip.add(b)
        }
        invalidate()
    }

    private fun braceH() = (pixelH - 130f).coerceAtLeast(20f)
}

private class ProgressBar : UiNode() {
    var value = 100
        set(v) { if (field != v) { field = v; invalidate() } }
    override fun draw(c: android.graphics.Canvas) {
        if (value >= 100) return
        p.color = UiTheme.neonBlue
        c.drawRoundRect(bounds.left, bounds.top, bounds.left + bounds.width() * value / 100f, bounds.bottom, 3f, 3f, p)
    }
    companion object { private val p = Paint(Paint.ANTI_ALIAS_FLAG) }
}
