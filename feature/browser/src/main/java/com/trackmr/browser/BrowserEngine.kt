package com.trackmr.browser

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.trackmr.xr.XrContext

/** One browser tab: its own WebView, all hosted off-screen on the browser's virtual display. */
class BrowserTab(val id: Int, val webView: WebView) {
    @Volatile var title: String = "Nova aba"
    @Volatile var url: String = ""
    @Volatile var progress: Int = 100
    @Volatile var canGoBack = false
    @Volatile var canGoForward = false
}

/**
 * Renders real web pages (Chromium WebView) into a GL external texture: WebViews live in a
 * [Presentation] on a private [VirtualDisplay] whose surface is the XR panel's texture.
 * Pointer events from hands/gaze are injected as touch events. Main-thread only.
 */
class BrowserEngine(
    private val ctx: XrContext,
    private val surface: Surface,
    val widthPx: Int,
    val heightPx: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onTabsChanged()
        fun onTabUpdated(tab: BrowserTab)
        fun onTextInputFocused(focused: Boolean)
        fun onDownload(url: String, mime: String?)
    }

    private val dm = ctx.activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private lateinit var container: FrameLayout
    val tabs = ArrayList<BrowserTab>()
    var active: BrowserTab? = null; private set
    private var nextId = 1
    private var downTime = 0L

    fun start() {
        val vd = dm.createVirtualDisplay("TrackMR-Browser", widthPx, heightPx, DPI, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        display = vd
        val p = object : Presentation(ctx.activity, vd.display) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                container = FrameLayout(context)
                container.setBackgroundColor(android.graphics.Color.WHITE)
                setContentView(container)
            }
        }
        presentation = p
        p.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(url: String): BrowserTab {
        val ctxPres = presentation?.context ?: ctx.activity
        val wv = WebView(ctxPres)
        val tab = BrowserTab(nextId++, wv)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            // Identify as a mobile XR browser so sites serve touch-friendly layouts.
            userAgentString = userAgentString.replace("; wv", "") + " TrackMR/2.0 XR"
        }
        wv.addJavascriptInterface(Bridge(tab), "TrackMRBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                return !(u.scheme == "http" || u.scheme == "https" || u.scheme == "about" || u.scheme == "data")
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { tab.url = url; update(tab) }
            override fun onPageFinished(view: WebView, url: String) {
                tab.url = url; tab.title = view.title ?: url; update(tab)
                view.evaluateJavascript(FOCUS_JS, null)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress = newProgress; update(tab) }
            override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title ?: tab.url; update(tab) }
        }
        wv.setDownloadListener { dUrl, _, _, mime, _ -> listener.onDownload(dUrl, mime) }
        tabs.add(tab)
        switchTo(tab)
        load(tab, url)
        listener.onTabsChanged()
        return tab
    }

    fun switchTo(tab: BrowserTab) {
        if (!::container.isInitialized) return
        active?.webView?.onPause()
        container.removeAllViews()
        container.addView(tab.webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tab.webView.onResume()
        active = tab
        listener.onTabsChanged()
    }

    fun closeTab(tab: BrowserTab) {
        val idx = tabs.indexOf(tab)
        tabs.remove(tab)
        if (active === tab) {
            container.removeView(tab.webView)
            active = null
            tabs.getOrNull(idx.coerceAtMost(tabs.size - 1))?.let { switchTo(it) }
        }
        tab.webView.destroy()
        listener.onTabsChanged()
    }

    fun load(tab: BrowserTab, input: String) {
        val url = normalize(input)
        tab.url = url
        tab.webView.loadUrl(url)
    }

    fun back() { active?.webView?.let { if (it.canGoBack()) it.goBack() } }
    fun forward() { active?.webView?.let { if (it.canGoForward()) it.goForward() } }
    fun reload() { active?.webView?.reload() }
    fun stop() { active?.webView?.stopLoading() }

    private fun update(tab: BrowserTab) {
        tab.canGoBack = tab.webView.canGoBack(); tab.canGoForward = tab.webView.canGoForward()
        listener.onTabUpdated(tab)
    }

    /** Injects a touch event at normalized page coordinates. */
    fun touch(action: Int, u: Float, v: Float) {
        val wv = active?.webView ?: return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val e = MotionEvent.obtain(downTime, now, action, u * widthPx, v * heightPx, 0)
        e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        wv.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Scroll wheel style scrolling (e.g. thumbstick / pinch-drag with the other hand). */
    fun scrollBy(dyPx: Int) { active?.webView?.scrollBy(0, dyPx) }

    fun typeText(text: String) {
        val wv = active?.webView ?: return
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        if (events != null) for (e in events) wv.dispatchKeyEvent(e)
        else wv.evaluateJavascript("document.execCommand('insertText', false, ${jsString(text)});", null)
    }

    fun key(code: Int) {
        val wv = active?.webView ?: return
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun pause() { tabs.forEach { it.webView.onPause() } }
    fun resume() { active?.webView?.onResume() }

    fun release() {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        presentation?.dismiss(); presentation = null
        display?.release(); display = null
    }

    private inner class Bridge(val tab: BrowserTab) {
        @JavascriptInterface fun inputFocus(focused: Boolean) { if (tab === active) ctx.runOnUi { listener.onTextInputFocused(focused) } }
    }

    companion object {
        const val DPI = 200
        const val HOME = "https://duckduckgo.com/"
        private const val FOCUS_JS = """(function(){if(window.__trackmr)return;window.__trackmr=1;
function ed(e){var t=e&&e.target;if(!t)return false;var n=t.tagName;return n==='INPUT'||n==='TEXTAREA'||t.isContentEditable;}
document.addEventListener('focusin',function(e){if(ed(e))TrackMRBridge.inputFocus(true);},true);
document.addEventListener('focusout',function(e){if(ed(e))TrackMRBridge.inputFocus(false);},true);})();"""

        fun normalize(input: String): String {
            val t = input.trim()
            if (t.isEmpty()) return HOME
            if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") || t.startsWith("file:")) return t
            return if (t.contains('.') && !t.contains(' ')) "https://$t"
            else "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }

        fun jsString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
    }
}
