package com.trackmr.cinema

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.trackmr.environments.EnvironmentModule
import com.trackmr.mr.MixedRealityModule
import com.trackmr.ui.ExternalSurfacePanel
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrModule
import com.trackmr.xr.gl.ExternalSurfaceTexture
import com.trackmr.xr.input.PointerEventType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

enum class StereoLayout(val label: String) { MONO("2D"), SBS("3D SBS"), OU("3D OU") }
enum class ScreenSize(val label: String, val widthM: Float, val distanceM: Float) {
    SMALL("TV", 2.2f, 2.2f), MEDIUM("Home", 4f, 3.4f), LARGE("Cinema", 8f, 6f), HUGE("IMAX", 14f, 9f)
}

data class VideoEntry(val title: String, val uri: Uri, val durationMs: Long = 0, val subtitle: String = "")

/**
 * Spatial cinema: ExoPlayer decoding straight into a GL external texture shown on a giant
 * curved screen. Supports sizes (TV → IMAX), distance, curvature, 3D SBS/OU, dark room
 * (dims passthrough / switches to a dark environment) and spatial playback controls.
 */
class CinemaModule : XrModule {
    override val id = "cinema"
    private lateinit var ctx: XrContext
    private var player: ExoPlayer? = null
    private var texture: ExternalSurfaceTexture? = null
    val screen = ExternalSurfacePanel(4f, 2.25f, 5f)
    lateinit var controls: CinemaControls; private set
    var active = false; private set
    var size = ScreenSize.MEDIUM; private set
    var distance = ScreenSize.MEDIUM.distanceM; private set
    var stereo = StereoLayout.MONO; private set
    var darkRoom = true; private set
    var curved = true; private set
    private var aspect = 16f / 9f
    var current: VideoEntry? = null; private set
    val library = CopyOnWriteArrayList<VideoEntry>()
    private val io = Executors.newSingleThreadExecutor()
    private var previousEnv: String? = null
    private var previousShowInMr = false

    // Playback state mirrored for the GL-thread UI (written on the main thread).
    @Volatile var isPlaying = false; private set
    @Volatile var positionMs = 0L; private set
    @Volatile var durationMs = 0L; private set
    @Volatile var volume = 1f; private set
    @Volatile var buffering = false; private set

    override fun onAttach(ctx: XrContext) {
        this.ctx = ctx
        ctx.register(CinemaModule::class.java, this)
        controls = CinemaControls(ctx, this)
        screen.hide(false); controls.hide(false)
        screen.priority = -5
        screen.onPointerEvent = { e, _, _ -> if (e.type == PointerEventType.CLICK) ctx.runOnGl { controls.wake() } }
        ctx.scene.add(screen); ctx.input.register(screen)
        ctx.scene.add(controls); ctx.input.register(controls)
        library.addAll(SAMPLES)
    }

    override fun onGlReady(ctx: XrContext) {
        texture = ExternalSurfaceTexture(1920, 1080).also { screen.source = it }
    }

    /** Opens the cinema with [entry] (or the library if null). GL thread. */
    fun open(entry: VideoEntry? = null) {
        if (!active) {
            active = true
            place()
            screen.show(); controls.show(); controls.wake()
            applyDarkRoom()
            scanLocalVideos()
        }
        if (entry != null) play(entry) else controls.showLibrary(true)
    }

    fun close() {
        active = false
        screen.hide(); controls.hide()
        ctx.runOnUi { player?.pause() }
        restoreRoom()
    }

    fun play(entry: VideoEntry) {
        current = entry
        controls.showLibrary(false)
        controls.onVideoChanged()
        val surface = texture?.surface
        ctx.runOnUi {
            val p = player ?: createPlayer()
            if (surface != null) p.setVideoSurface(surface)
            p.setMediaItem(MediaItem.fromUri(entry.uri))
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun createPlayer(): ExoPlayer {
        val p = ExoPlayer.Builder(ctx.activity).build()
        p.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                durationMs = p.duration.coerceAtLeast(0)
            }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width <= 0 || v.height <= 0) return
                val a = v.width * v.pixelWidthHeightRatio / v.height
                ctx.runOnGl {
                    texture?.resize(v.width, v.height)
                    aspect = a
                    // Heuristic: full-SBS videos are ~32:9.
                    if (a > 3.2f && stereo == StereoLayout.MONO) setStereo(StereoLayout.SBS) else applyGeometry()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                ctx.notify("Erro de reprodução: ${error.errorCodeName}")
            }
        })
        player = p
        // Poll position for the UI at 4 Hz.
        val poll = object : Runnable {
            override fun run() {
                val pl = player ?: return
                positionMs = pl.currentPosition; durationMs = pl.duration.coerceAtLeast(0); volume = pl.volume
                ctx.mainHandler.postDelayed(this, 250)
            }
        }
        ctx.mainHandler.post(poll)
        return p
    }

    fun togglePlay() = ctx.runOnUi { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekBy(ms: Long) = ctx.runOnUi { player?.let { it.seekTo((it.currentPosition + ms).coerceIn(0, it.duration.coerceAtLeast(0))) } }
    fun seekTo(fraction: Float) = ctx.runOnUi { player?.let { if (it.duration > 0) it.seekTo((it.duration * fraction).toLong()) } }
    fun setVolume(v: Float) = ctx.runOnUi { player?.volume = v; volume = v }

    fun setSize(s: ScreenSize) { size = s; distance = s.distanceM; place() }
    fun setDistance(d: Float) { distance = d.coerceIn(1.2f, 20f); place() }
    fun setCurved(c: Boolean) { curved = c; applyGeometry() }

    fun setStereo(s: StereoLayout) {
        stereo = s
        screen.stereoRects = when (s) {
            StereoLayout.MONO -> null
            StereoLayout.SBS -> arrayOf(floatArrayOf(0f, 0f, 0.5f, 1f), floatArrayOf(0.5f, 0f, 0.5f, 1f))
            StereoLayout.OU -> arrayOf(floatArrayOf(0f, 0f, 1f, 0.5f), floatArrayOf(0f, 0.5f, 1f, 0.5f))
        }
        applyGeometry()
    }

    fun setDarkRoom(d: Boolean) { darkRoom = d; applyDarkRoom() }

    private fun applyGeometry() {
        val frameAspect = when (stereo) { StereoLayout.SBS -> aspect / 2f; StereoLayout.OU -> aspect * 2f; StereoLayout.MONO -> aspect }
        val w = size.widthM
        screen.resize(w, w / frameAspect.coerceIn(0.5f, 4f))
        screen.setCurvature(if (curved) distance * 1.1f else 0f)
        controls.follow(screen)
    }

    /** Centers the screen in front of the user at the chosen distance, slightly above eye level. */
    private fun place() {
        val head = ctx.headPose
        val yaw = head.q.yaw()
        screen.pose.p.set(head.p.x - sin(yaw) * distance, head.p.y + size.widthM * 0.06f, head.p.z - cos(yaw) * distance)
        screen.pose.q.setAxisAngle(0f, 1f, 0f, yaw)
        applyGeometry()
        controls.placeNear(head, yaw)
    }

    private fun applyDarkRoom() {
        val mr = ctx.modules.firstOrNull { it is MixedRealityModule } as MixedRealityModule?
        val env = ctx.service(EnvironmentModule::class.java)
        if (active && darkRoom) {
            mr?.passthroughOpacity = 0.12f
            if (env != null && previousEnv == null) {
                previousEnv = env.currentId; previousShowInMr = env.showInMr
            }
        } else restoreRoom()
    }

    private fun restoreRoom() {
        val mr = ctx.modules.firstOrNull { it is MixedRealityModule } as MixedRealityModule?
        mr?.passthroughOpacity = 1f
        val env = ctx.service(EnvironmentModule::class.java)
        if (env != null && previousEnv != null) { env.showInMr = previousShowInMr; previousEnv = null }
    }

    /** Finds videos on the device (MediaStore) and in the app's videos folder. */
    fun scanLocalVideos() {
        io.execute {
            val found = ArrayList<VideoEntry>()
            try {
                val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ctx.activity.contentResolver.query(collection,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION),
                    null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                    while (c.moveToNext() && found.size < 200) {
                        val id = c.getLong(0)
                        found.add(VideoEntry(c.getString(1) ?: "Vídeo", ContentUris.withAppendedId(collection, id), c.getLong(2), "Dispositivo"))
                    }
                }
            } catch (e: SecurityException) {
                Log.i("TrackMR-Cinema", "No media permission: ${e.message}")
            } catch (e: Exception) { Log.w("TrackMR-Cinema", "scan", e) }
            ctx.activity.getExternalFilesDir("videos")?.listFiles()?.filter { it.isFile }?.forEach {
                found.add(VideoEntry(it.nameWithoutExtension, Uri.fromFile(it), 0, "TrackMR/videos"))
            }
            ctx.runOnGl {
                library.clear(); library.addAll(found); library.addAll(SAMPLES)
                controls.onLibraryChanged()
            }
        }
    }

    override fun onFrame(ctx: XrContext, dt: Float) { if (active) controls.tickState(dt) }

    override fun onPause(ctx: XrContext) { ctx.runOnUi { player?.pause() } }

    override fun onDestroy(ctx: XrContext) {
        ctx.runOnUi { player?.release(); player = null }
        io.shutdownNow()
    }

    companion object {
        /** Openly licensed samples (Blender Foundation, CC-BY) streamed on demand, never bundled. */
        val SAMPLES = listOf(
            VideoEntry("Big Buck Bunny", Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"), 596000, "Blender Foundation • CC-BY"),
            VideoEntry("Sintel", Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"), 888000, "Blender Foundation • CC-BY"),
            VideoEntry("Tears of Steel", Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"), 734000, "Blender Foundation • CC-BY"),
        )
    }
}
