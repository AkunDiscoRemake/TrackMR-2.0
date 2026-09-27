package com.trackmr.cinema

import android.graphics.Paint
import com.trackmr.ui.ButtonStyle
import com.trackmr.ui.SpatialPanel
import com.trackmr.ui.SpatialSurface
import com.trackmr.ui.UiButton
import com.trackmr.ui.UiLabel
import com.trackmr.ui.UiNode
import com.trackmr.ui.UiScroll
import com.trackmr.ui.UiSegmented
import com.trackmr.ui.UiSlider
import com.trackmr.ui.UiTheme
import com.trackmr.ui.UiTile
import com.trackmr.ui.UiToggle
import com.trackmr.xr.XrContext
import com.trackmr.xr.input.PointerEvent
import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Quat
import kotlin.math.cos
import kotlin.math.sin

/**
 * Floating cinema remote: sits low and close to the user (easy pinch / poke), auto-fades
 * while a movie plays and wakes up when the screen is clicked or the panel is hovered.
 */
class CinemaControls(private val ctx: XrContext, private val cinema: CinemaModule) :
    SpatialPanel(1.0f, 0.42f, 820f, radius = 1.2f) {

    private val title = root.add(UiLabel("Cinema", 30f, UiTheme.textPrimary, Paint.Align.LEFT, bold = true))
    private val time = root.add(UiLabel("0:00 / 0:00", 22f, UiTheme.textSecondary, Paint.Align.RIGHT))
    private val seek = root.add(UiSlider("", 0f, 1f, 0f, 0f, { "" }, UiTheme.neonPink) { cinema.seekTo(it); idle = 0f })
    private val back = root.add(UiButton("", "⏪", ButtonStyle.SECONDARY, textSize = 30f) { cinema.seekBy(-10_000) })
    private val play = root.add(UiButton("", "▶", ButtonStyle.PRIMARY, UiTheme.neonPink, 34f) { cinema.togglePlay() })
    private val fwd = root.add(UiButton("", "⏩", ButtonStyle.SECONDARY, textSize = 30f) { cinema.seekBy(10_000) })
    private val vol = root.add(UiSlider("🔊", 0f, 1f, 1f, 0.05f, { "${(it * 100).toInt()}%" }, UiTheme.neonBlue) { cinema.setVolume(it) })
    private val sizeSeg = root.add(UiSegmented(ScreenSize.values().map { it.label }, cinema.size.ordinal, UiTheme.neonGold) { cinema.setSize(ScreenSize.values()[it]) })
    private val stereoSeg = root.add(UiSegmented(StereoLayout.values().map { it.label }, 0, UiTheme.neonMint) { cinema.setStereo(StereoLayout.values()[it]) })
    private val dark = root.add(UiToggle("Sala escura", cinema.darkRoom) { cinema.setDarkRoom(it) })
    private val curve = root.add(UiToggle("Tela curva", cinema.curved) { cinema.setCurved(it) })
    private val nearer = root.add(UiButton("", "➖", ButtonStyle.GHOST, textSize = 26f) { cinema.setDistance(cinema.distance * 0.85f) })
    private val farther = root.add(UiButton("", "➕", ButtonStyle.GHOST, textSize = 26f) { cinema.setDistance(cinema.distance * 1.18f) })
    private val libBtn = root.add(UiButton("Biblioteca", "🎞", ButtonStyle.SECONDARY, textSize = 22f) { showLibrary(!libraryOpen) })
    private val closeBtn = root.add(UiButton("", "✖", ButtonStyle.DANGER, textSize = 26f) { cinema.close() })

    private val controlsGroup = listOf<UiNode>(time, seek, back, play, fwd, vol, sizeSeg, stereoSeg, dark, curve, nearer, farther)
    private val library = root.add(UiScroll()).also { it.visible = false }
    private var libraryOpen = false
    private var idle = 0f
    private var lastPlaying = false
    private var uiTimer = 0f

    init { priority = 6; layout() }

    override fun layout() {
        val W = pixelW.toFloat(); val H = pixelH.toFloat()
        title.at(30f, 14f, W * 0.55f, 48f)
        libBtn.at(W - 330f, 12f, 220f, 54f)
        closeBtn.at(W - 96f, 12f, 66f, 54f)
        time.at(W * 0.55f, 70f, W * 0.42f, 34f)
        seek.at(30f, 100f, W - 60f, 50f)
        back.at(30f, 160f, 90f, 80f)
        play.at(130f, 156f, 110f, 88f)
        fwd.at(250f, 160f, 90f, 80f)
        vol.at(370f, 158f, 260f, 84f)
        nearer.at(W - 170f, 170f, 66f, 60f)
        farther.at(W - 96f, 170f, 66f, 60f)
        sizeSeg.at(30f, 262f, W * 0.48f, 56f)
        stereoSeg.at(W * 0.52f, 262f, W * 0.48f - 30f, 56f)
        dark.at(30f, 330f, W * 0.45f, 60f)
        curve.at(W * 0.52f, 330f, W * 0.45f, 60f)
        library.at(20f, 76f, W - 40f, H - 90f)
        onLibraryChanged()
    }

    fun showLibrary(open: Boolean) {
        libraryOpen = open
        library.visible = open
        for (n in controlsGroup) n.visible = !open
        libBtn.text = if (open) "Controles" else "Biblioteca"
        libBtn.invalidate()
        wake()
        invalidate()
    }

    fun onLibraryChanged() {
        library.clearChildren()
        val cols = 3; val gap = 14f
        val tw = (library.bounds.width() - gap * (cols + 1) - 10f) / cols
        val th = 120f
        cinema.library.forEachIndexed { i, v ->
            val mins = if (v.durationMs > 0) " • ${v.durationMs / 60000} min" else ""
            val t = UiTile(v.title, v.subtitle + mins, if (v.uri.scheme == "https") "🌐" else "🎬", UiTheme.accents[i % UiTheme.accents.size]) { cinema.play(v) }
            t.at(library.bounds.left + gap + (i % cols) * (tw + gap), library.bounds.top + gap / 2 + (i / cols) * (th + gap), tw, th)
            library.add(t)
        }
        library.contentHeight = ((cinema.library.size + cols - 1) / cols) * (th + gap) + gap
        invalidate()
    }

    fun onVideoChanged() { title.text = "🎬  " + (cinema.current?.title ?: "Cinema"); invalidate() }

    fun wake() { idle = 0f; targetOpacity = 1f; if (hidden && cinema.active) show() }

    /** Keeps the remote below the screen's center line, near the user. */
    fun placeNear(head: Pose, yaw: Float) {
        val d = 0.8f
        pose.p.set(head.p.x - sin(yaw) * d, head.p.y - 0.42f, head.p.z - cos(yaw) * d)
        pose.q.setAxisAngle(0f, 1f, 0f, yaw).mul(tilt)
    }

    fun follow(screen: SpatialSurface) { /* remote stays near the user; nothing to do */ }

    override fun onSurfacePointer(e: PointerEvent, x: Float, y: Float): Boolean { idle = 0f; if (opacity < 0.9f) targetOpacity = 1f; return false }

    /** Called every frame from the module (GL thread). */
    fun tickState(dt: Float) {
        uiTimer += dt
        if (uiTimer >= 0.25f) {
            uiTimer = 0f
            val dur = cinema.durationMs; val pos = cinema.positionMs
            if (dur > 0 && !seek.pressed) seek.value = pos.toFloat() / dur
            time.text = "${fmt(pos)} / ${fmt(dur)}" + if (cinema.buffering) "  ⏳" else ""
            val playing = cinema.isPlaying
            if (playing != lastPlaying) { play.icon = if (playing) "⏸" else "▶"; play.invalidate(); lastPlaying = playing }
            if (!vol.pressed) vol.value = cinema.volume
            if (stereoSeg.selected != cinema.stereo.ordinal) stereoSeg.selected = cinema.stereo.ordinal
            invalidate()
        }
        // Auto-fade while playing (keeps the movie clean), fully hidden = only screen visible.
        if (cinema.isPlaying && !libraryOpen) {
            idle += dt
            if (idle > 5f && targetOpacity > 0.25f) targetOpacity = 0.0f
        } else if (!hidden) targetOpacity = 1f
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    companion object { private val tilt = Quat().setAxisAngle(1f, 0f, 0f, -0.5f) }
}
