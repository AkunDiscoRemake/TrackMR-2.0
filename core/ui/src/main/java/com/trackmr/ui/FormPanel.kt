package com.trackmr.ui

import android.graphics.Paint

/**
 * Tabbed spatial form (settings, per-game configs, streaming options). Rows are built
 * declaratively; values are read back through getters so the form always reflects the
 * current state when a tab is (re)opened.
 *
 * ```
 * FormPanel(1.1f, 0.75f).apply {
 *     tab("Exibição") {
 *         toggle("Estéreo", { s.stereo }) { s.stereo = it }
 *         slider("IPD", 0.055f, 0.075f, { s.ipd }, format = { "%.1f mm".format(it * 1000) }) { s.ipd = it }
 *     }
 * }
 * ```
 */
open class FormPanel(widthM: Float, heightM: Float, radius: Float = 0f) : SpatialPanel(widthM, heightM, 820f, radius) {
    class Tab(val title: String, val build: Builder.() -> Unit)

    inner class Builder {
        internal var y = 0f
        private val w get() = scroll.bounds.width() - 30f
        private val x get() = scroll.bounds.left + 12f
        private fun top() = scroll.bounds.top + y

        fun header(text: String) {
            scroll.add(UiLabel(text, 24f, UiTheme.neonBlue, bold = true).also { it.at(x, top() + 10f, w, 40f) })
            y += 54f
        }

        fun info(text: String, lines: Int = 1) {
            val h = 34f * lines + 10f
            scroll.add(UiLabel(text, 22f, UiTheme.textSecondary, maxLines = lines).also { it.at(x, top(), w, h) })
            y += h + 6f
        }

        fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            scroll.add(UiToggle(label, get(), UiTheme.neonMint) { set(it) }.also { it.at(x, top(), w, 64f) })
            y += 72f
        }

        fun slider(label: String, min: Float, max: Float, get: () -> Float, step: Float = 0f,
                   format: (Float) -> String = { "%.2f".format(it) }, set: (Float) -> Unit) {
            scroll.add(UiSlider(label, min, max, get(), step, format, UiTheme.neonPink) { set(it) }.also { it.at(x, top(), w, 84f) })
            y += 92f
        }

        fun segmented(label: String, options: List<String>, get: () -> Int, set: (Int) -> Unit) {
            scroll.add(UiLabel(label, 22f, UiTheme.textSecondary).also { it.at(x, top(), w, 32f) })
            y += 34f
            scroll.add(UiSegmented(options, get().coerceIn(0, options.size - 1), UiTheme.neonBlue) { set(it) }.also { it.at(x, top(), w, 60f) })
            y += 72f
        }

        fun button(text: String, icon: String? = null, style: ButtonStyle = ButtonStyle.SECONDARY, action: () -> Unit) {
            scroll.add(UiButton(text, icon, style, textSize = 24f) { action() }.also { it.at(x, top(), w, 64f) })
            y += 74f
        }

        fun buttons(vararg items: Pair<String, () -> Unit>) {
            val bw = (w - (items.size - 1) * 12f) / items.size
            items.forEachIndexed { i, (t, a) ->
                scroll.add(UiButton(t, null, ButtonStyle.SECONDARY, textSize = 22f) { a() }.also { it.at(x + i * (bw + 12f), top(), bw, 60f) })
            }
            y += 70f
        }

        fun textField(hint: String, get: () -> String, onFocus: (UiTextField) -> Unit) {
            scroll.add(UiTextField(hint, get()) { onFocus(it) }.also { it.at(x, top(), w, 62f) })
            y += 72f
        }

        fun spacer(h: Float = 16f) { y += h }
    }

    private val tabs = ArrayList<Tab>()
    private var current = 0
    private val titleLabel = root.add(UiLabel("", 34f, UiTheme.textPrimary, bold = true))
    private var tabBar: UiSegmented? = null
    protected val scroll = root.add(UiScroll())
    var title: String = ""
        set(v) { field = v; titleLabel.text = v; invalidate() }

    fun tab(title: String, build: Builder.() -> Unit): FormPanel { tabs.add(Tab(title, build)); rebuildTabs(); return this }

    private fun rebuildTabs() {
        tabBar?.let { root.remove(it) }
        if (tabs.size > 1) {
            tabBar = root.add(UiSegmented(tabs.map { it.title }, current, UiTheme.neonPink) { select(it) })
        } else tabBar = null
        layout()
    }

    fun select(i: Int) { current = i.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)); refresh() }

    /** Rebuilds the current tab from live values. */
    fun refresh() {
        scroll.clearChildren()
        val t = tabs.getOrNull(current) ?: return
        val b = Builder()
        b.y = 8f
        t.build(b)
        scroll.contentHeight = b.y + 20f
        scroll.scrollY = scroll.scrollY
        invalidate()
    }

    override fun layout() {
        val W = pixelW.toFloat(); val H = pixelH.toFloat()
        titleLabel.at(34f, 16f, W - 68f, 54f)
        var top = 78f
        tabBar?.let { it.at(24f, top, W - 48f, 58f); top += 72f }
        scroll.at(20f, top, W - 40f, H - top - 16f)
        refresh()
    }

    init { titleLabel.align = Paint.Align.LEFT }
}
