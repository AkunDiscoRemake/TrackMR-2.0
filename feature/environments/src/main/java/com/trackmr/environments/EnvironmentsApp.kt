package com.trackmr.environments

import android.graphics.Paint
import com.trackmr.ui.AppCategory
import com.trackmr.ui.AppRegistry
import com.trackmr.ui.ButtonStyle
import com.trackmr.ui.SpatialPanel
import com.trackmr.ui.UiButton
import com.trackmr.ui.UiLabel
import com.trackmr.ui.UiScroll
import com.trackmr.ui.UiSlider
import com.trackmr.ui.UiTheme
import com.trackmr.ui.UiTile
import com.trackmr.ui.UiToggle
import com.trackmr.ui.WindowManager
import com.trackmr.ui.XrApp
import com.trackmr.ui.XrWindow
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrMode
import java.util.concurrent.Executors

/** Spatial environment library: thumbnails, instant switching, brightness/rotation, MR background. */
class EnvironmentLibraryPanel(private val ctx: XrContext, private val env: EnvironmentModule) :
    SpatialPanel(1.15f, 0.72f, 800f, radius = 1.6f) {

    private val title = root.add(UiLabel("🌄  Ambientes 360°", 34f, UiTheme.textPrimary, Paint.Align.LEFT, bold = true))
    private val grid = root.add(UiScroll())
    private val brightness = root.add(UiSlider("Brilho", 0.3f, 1.6f, env.brightness, 0.05f, { "${(it * 100).toInt()}%" }, UiTheme.neonGold) { env.brightness = it })
    private val rotation = root.add(UiSlider("Rotação", -180f, 180f, Math.toDegrees(env.yaw.toDouble()).toFloat(), 5f, { "${it.toInt()}°" }, UiTheme.neonBlue) {
        env.yaw = Math.toRadians(it.toDouble()).toFloat()
    })
    private val mrToggle = root.add(UiToggle("Fundo virtual no modo MR", env.showInMr) { env.showInMr = it })
    private val vrButton = root.add(UiButton("Entrar em VR", "🥽", ButtonStyle.PRIMARY, UiTheme.neonPink, 24f) {
        ctx.setMode(if (ctx.mode == XrMode.VR) XrMode.MR else XrMode.VR); updateVrButton()
    })
    private val rescan = root.add(UiButton("Atualizar", "🔄", ButtonStyle.GHOST, textSize = 22f) { env.scanLibrary(); rebuild() })
    private val thumbExec = Executors.newSingleThreadExecutor()

    init {
        priority = 2
        env.addListener { ctx.runOnGl { markSelected() } }
        layout()
        rebuild()
        updateVrButton()
    }

    private fun updateVrButton() { vrButton.text = if (ctx.mode == XrMode.VR) "Voltar ao MR" else "Entrar em VR"; vrButton.invalidate() }

    override fun layout() {
        val W = pixelW.toFloat(); val H = pixelH.toFloat()
        title.at(34f, 18f, W * 0.5f, 56f)
        rescan.at(W - 210f, 20f, 180f, 56f)
        grid.at(24f, 86f, W - 48f, H - 86f - 180f)
        brightness.at(40f, H - 170f, W * 0.42f, 80f)
        rotation.at(W * 0.52f, H - 170f, W * 0.44f, 80f)
        mrToggle.at(40f, H - 80f, W * 0.5f, 62f)
        vrButton.at(W - 330f, H - 82f, 290f, 64f)
    }

    fun rebuild() {
        grid.clearChildren()
        val cols = 4; val gap = 18f
        val tw = (grid.bounds.width() - gap * (cols + 1) - 10f) / cols
        val th = tw * 0.72f
        env.library.forEachIndexed { i, info ->
            val t = UiTile(info.title, info.subtitle, "🌐", info.accent) { env.select(info.id) }
            t.tag = info.id
            t.selected = info.id == env.currentId
            t.at(grid.bounds.left + gap + (i % cols) * (tw + gap), grid.bounds.top + gap / 2 + (i / cols) * (th + gap), tw, th)
            grid.add(t)
            thumbExec.execute {
                val bmp = env.thumbnail(info)
                if (bmp != null) ctx.runOnGl { t.image = bmp; t.invalidate() }
            }
        }
        grid.contentHeight = ((env.library.size + cols - 1) / cols) * (th + gap) + gap
        invalidate()
    }

    private fun markSelected() {
        for (c in grid.children) if (c is UiTile) { c.selected = c.tag == env.currentId; c.invalidate() }
        updateVrButton()
    }
}

object EnvironmentsApp {
    fun register(ctx: XrContext, env: EnvironmentModule) {
        AppRegistry.register(XrApp("environments", "Ambientes", "Espaços 360°", "🌄", UiTheme.neonGold, AppCategory.MEDIA) { c ->
            val wm = c.service(WindowManager::class.java) ?: return@XrApp
            wm.openOrFocus("environments") { XrWindow(c, "Ambientes", EnvironmentLibraryPanel(c, env), "🌄", UiTheme.neonGold) }
        })
    }
}
