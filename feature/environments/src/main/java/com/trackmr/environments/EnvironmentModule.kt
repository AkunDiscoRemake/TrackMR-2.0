package com.trackmr.environments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import com.trackmr.xr.Renderable
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrMode
import com.trackmr.xr.XrModule
import com.trackmr.xr.gl.Texture2D
import com.trackmr.xr.math.MathUtil
import com.trackmr.xr.render.EyeContext
import com.trackmr.xr.render.Gfx
import com.trackmr.xr.render.RenderPass
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.random.Random

/** Where an environment's equirectangular panorama comes from. */
sealed class EnvSource {
    data class Asset(val path: String) : EnvSource()
    data class FileSrc(val file: File) : EnvSource()
    /** Procedurally generated (no asset needed). */
    data class Procedural(val kind: String) : EnvSource()
}

data class EnvironmentInfo(
    val id: String,
    val title: String,
    val subtitle: String,
    val source: EnvSource,
    val accent: Int,
    /** Brightness multiplier (night scenes can be boosted slightly). */
    val brightness: Float = 1f,
)

/**
 * 360° environment system: library (repo panoramas, downloaded packs, procedural spaces),
 * asynchronous decoding, GPU crossfade between environments, small LRU texture cache for
 * instant switching, and image-based ambient lighting for virtual objects.
 */
class EnvironmentModule : XrModule, Renderable {
    override val id = "environments"
    override val passes = Renderable.mask(RenderPass.BACKGROUND)
    private lateinit var ctx: XrContext
    val library = CopyOnWriteArrayList<EnvironmentInfo>()
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "TrackMR-EnvDecode").apply { priority = Thread.MIN_PRIORITY + 1 } }

    private class Loaded(val tex: Texture2D, val ambient: FloatArray, var lastUsed: Long)
    private val cache = LinkedHashMap<String, Loaded>()
    private var current: Loaded? = null
    private var previous: Loaded? = null
    private var mix = 1f
    var currentId: String? = null; private set
    private var pendingId: String? = null
    val isLoading get() = pendingId != null

    /** Show the environment in MR too (virtual background replacing passthrough). */
    @Volatile var showInMr = false
    /** 0..1 fade used by cinema "dark room" and portals. */
    @Volatile var opacity = 1f
    private var currentOpacity = 0f
    @Volatile var brightness = 1f
    /** Yaw rotation of the panorama in radians. */
    @Volatile var yaw = 0f
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val thumbs = HashMap<String, Bitmap>()

    override val visible: Boolean get() = currentOpacity > 0.01f && current != null

    override fun onAttach(ctx: XrContext) {
        this.ctx = ctx
        ctx.register(EnvironmentModule::class.java, this)
        ctx.scene.add(this)
        scanLibrary()
    }

    override fun onGlReady(ctx: XrContext) {
        val want = ctx.settings.environmentId
        select(if (library.any { it.id == want }) want else library.firstOrNull()?.id ?: return)
    }

    fun addListener(l: (String) -> Unit) { listeners.add(l) }

    /** Rebuilds the library from APK assets, downloaded packs and procedural spaces. */
    fun scanLibrary() {
        val list = ArrayList<EnvironmentInfo>()
        val am = ctx.activity.assets
        val names = try { am.list("environments")?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        for (n in names.sorted()) {
            if (!IMAGE.matches(n)) continue
            val id = n.substringBeforeLast('.')
            val meta = KNOWN[id]
            list.add(EnvironmentInfo(id, meta?.first ?: prettify(id), meta?.second ?: "360°", EnvSource.Asset("environments/$n"),
                meta?.third ?: accentFor(id), if (id.contains("night")) 1.1f else 1f))
        }
        // Packs downloaded by the asset pipeline / user: <external files>/environments/*.jpg|png|webp
        val ext = ctx.activity.getExternalFilesDir("environments")
        ext?.listFiles()?.filter { IMAGE.matches(it.name) }?.sortedBy { it.name }?.forEach { f ->
            val id = "file_" + f.nameWithoutExtension
            list.add(EnvironmentInfo(id, prettify(f.nameWithoutExtension), "Pacote externo", EnvSource.FileSrc(f), accentFor(id)))
        }
        list.add(EnvironmentInfo("studio", "Estúdio TrackMR", "Espaço procedural", EnvSource.Procedural("studio"), Color.rgb(150, 110, 255)))
        list.add(EnvironmentInfo("starfield", "Céu Estrelado", "Espaço procedural", EnvSource.Procedural("stars"), Color.rgb(60, 140, 255)))
        list.add(EnvironmentInfo("void", "Sala Escura", "Ideal para cinema", EnvSource.Procedural("void"), Color.rgb(40, 40, 60), 1f))
        library.clear(); library.addAll(list)
    }

    /** Switches environment: instant if cached, otherwise decodes asynchronously then crossfades. */
    fun select(id: String) {
        val info = library.firstOrNull { it.id == id } ?: return
        if (id == currentId && pendingId == null) return
        cache[id]?.let { ctx.runOnGl { activate(id, it) }; return }
        pendingId = id
        val maxW = when (ctx.settings.resolution.name) { "LOW" -> 2048; "MEDIUM" -> 3072; else -> 4096 }
        decoder.execute {
            val bmp = try { decode(info, maxW) } catch (e: Throwable) { Log.e(TAG, "decode ${info.id}", e); null }
            if (bmp == null) { pendingId = null; ctx.notify("Não foi possível carregar ${info.title}"); return@execute }
            val amb = averageColor(bmp)
            ctx.runOnGl {
                if (pendingId != id) { bmp.recycle(); return@runOnGl }
                val tex = Texture2D.fromBitmap(bmp, mipmaps = true, repeat = true)
                bmp.recycle()
                val l = Loaded(tex, amb, System.nanoTime())
                cache[id] = l
                activate(id, l)
            }
        }
    }

    private fun activate(id: String, l: Loaded) {
        pendingId = null
        previous = current
        current = l
        l.lastUsed = System.nanoTime()
        mix = if (previous == null) 1f else 0f
        currentId = id
        ctx.settings.environmentId = id
        trimCache()
        applyLighting()
        for (li in listeners) li(id)
    }

    /** Keeps at most 3 panoramas on the GPU (current, previous and one more for fast toggles). */
    private fun trimCache() {
        while (cache.size > 3) {
            val victim = cache.entries.filter { it.value !== current && it.value !== previous }.minByOrNull { it.value.lastUsed } ?: break
            victim.value.tex.release()
            cache.remove(victim.key)
        }
    }

    /** Frees everything except the active panorama (called on memory pressure). */
    fun trimMemory() {
        ctx.runOnGl {
            val keep = current
            for ((k, v) in cache.entries.toList()) if (v !== keep) { v.tex.release(); cache.remove(k) }
            previous = null; mix = 1f
        }
    }

    private fun applyLighting() {
        if (ctx.mode != XrMode.VR) return
        val a = current?.ambient ?: return
        ctx.lighting.ambient.set(0.25f + a[0] * 0.6f, 0.25f + a[1] * 0.6f, 0.25f + a[2] * 0.6f)
        ctx.lighting.lightColor.set(0.7f + a[0] * 0.4f, 0.7f + a[1] * 0.4f, 0.7f + a[2] * 0.4f)
    }

    override fun onModeChanged(ctx: XrContext, mode: XrMode) { applyLighting() }

    override fun onFrame(ctx: XrContext, dt: Float) {
        val target = (if (ctx.mode == XrMode.VR || showInMr) 1f else 0f) * opacity
        currentOpacity = MathUtil.damp(currentOpacity, target, 5f, dt)
        if (mix < 1f) {
            mix = (mix + dt / 0.6f).coerceAtMost(1f)
            if (mix >= 1f) previous = null
        }
    }

    override fun render(eye: EyeContext, pass: RenderPass) {
        val cur = current ?: return
        val g = Gfx.get
        val sky = g.sky
        sky.use()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, eye.eyePos.x, eye.eyePos.y, eye.eyePos.z)
        Matrix.rotateM(model, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        eye.mvp(model, mvp)
        sky.mat4("uMvp", mvp)
        val prev = previous
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, (prev ?: cur).tex.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cur.tex.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        sky.i1("uTexA", 0)
        sky.i1("uTexB", 1)
        sky.f1("uMix", if (prev == null) 1f else MathUtil.smoothstep(0f, 1f, mix))
        val info = library.firstOrNull { it.id == currentId }
        sky.f1("uBrightness", brightness * (info?.brightness ?: 1f))
        sky.f1("uOpacity", currentOpacity)
        g.skySphere.draw()
    }

    override fun onDestroy(ctx: XrContext) {
        decoder.shutdownNow()
        for (v in cache.values) v.tex.release()
        cache.clear(); current = null; previous = null
        thumbs.values.forEach { it.recycle() }; thumbs.clear()
    }

    /** Small preview for library tiles (decoded off the GL thread, cached). */
    fun thumbnail(info: EnvironmentInfo): Bitmap? = synchronized(thumbs) {
        thumbs[info.id] ?: try { decode(info, 320)?.also { thumbs[info.id] = it } } catch (_: Throwable) { null }
    }

    private fun decode(info: EnvironmentInfo, maxW: Int): Bitmap? {
        return when (val s = info.source) {
            is EnvSource.Procedural -> procedural(s.kind, minOf(maxW, 2048))
            is EnvSource.Asset -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.activity.assets.open(s.path).use { BitmapFactory.decodeStream(it, null, o) }
                val opts = sampled(o.outWidth, maxW)
                ctx.activity.assets.open(s.path).use { BitmapFactory.decodeStream(it, null, opts) }?.let { fit(it, maxW) }
            }
            is EnvSource.FileSrc -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(s.file.absolutePath, o)
                BitmapFactory.decodeFile(s.file.absolutePath, sampled(o.outWidth, maxW))?.let { fit(it, maxW) }
            }
        }
    }

    private fun sampled(w: Int, maxW: Int) = BitmapFactory.Options().apply {
        var s = 1
        while (w / (s * 2) >= maxW) s *= 2
        inSampleSize = s
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    private fun fit(b: Bitmap, maxW: Int): Bitmap {
        if (b.width <= maxW) return b
        val s = Bitmap.createScaledBitmap(b, maxW, (b.height.toLong() * maxW / b.width).toInt(), true)
        if (s !== b) b.recycle()
        return s
    }

    private fun averageColor(b: Bitmap): FloatArray {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        val stepX = maxOf(1, b.width / 32); val stepY = maxOf(1, b.height / 16)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) { val c = b.getPixel(x, y); r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++; x += stepX }
            y += stepY
        }
        return if (n == 0) floatArrayOf(0.5f, 0.5f, 0.5f) else floatArrayOf(r / (255f * n), g / (255f * n), bl / (255f * n))
    }

    /** Procedural equirectangular spaces rendered with Canvas (no downloads). */
    private fun procedural(kind: String, w: Int): Bitmap {
        val h = w / 2
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (kind) {
            "studio" -> {
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                    intArrayOf(Color.rgb(20, 6, 48), Color.rgb(90, 4, 189), Color.rgb(255, 96, 214), Color.rgb(40, 16, 80), Color.rgb(12, 8, 24)),
                    floatArrayOf(0f, 0.35f, 0.5f, 0.52f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                // Soft light panels around the horizon.
                p.shader = null
                for (i in 0 until 8) {
                    val cx = (i + 0.5f) * w / 8f
                    p.shader = RadialGradient(cx, h * 0.3f, h * 0.18f, Color.argb(90, 180, 220, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    c.drawCircle(cx, h * 0.3f, h * 0.18f, p)
                }
                // Floor grid (equirectangular lower half).
                p.shader = null; p.color = Color.argb(60, 120, 200, 255); p.strokeWidth = 2f
                for (i in 0 until 48) c.drawLine(i * w / 48f, h * 0.52f, i * w / 48f, h.toFloat(), p)
                var y = h * 0.52f; var step = 6f
                while (y < h) { c.drawLine(0f, y, w.toFloat(), y, p); y += step; step *= 1.35f }
            }
            "stars" -> {
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                    intArrayOf(Color.rgb(2, 2, 10), Color.rgb(10, 12, 40), Color.rgb(40, 20, 70), Color.rgb(4, 4, 12)),
                    floatArrayOf(0f, 0.4f, 0.5f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
                val rnd = Random(42)
                repeat(w * 2) {
                    val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h * 0.55f
                    val a = 80 + rnd.nextInt(175)
                    p.color = Color.argb(a, 220 + rnd.nextInt(35), 220 + rnd.nextInt(35), 255)
                    c.drawCircle(x, y, 0.5f + rnd.nextFloat() * 1.4f, p)
                }
                // Milky way band.
                p.shader = LinearGradient(0f, h * 0.15f, w.toFloat(), h * 0.4f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(60, 150, 120, 255), Color.TRANSPARENT), null, Shader.TileMode.MIRROR)
                c.drawRect(0f, h * 0.1f, w.toFloat(), h * 0.45f, p)
            }
            else -> c.drawColor(Color.rgb(3, 3, 6))
        }
        return b
    }

    companion object {
        private const val TAG = "TrackMR-Env"
        private val IMAGE = Regex(".*\\.(png|jpg|jpeg|webp)$", RegexOption.IGNORE_CASE)
        /** Friendly names for the panoramas that ship in the repository. */
        private val KNOWN = mapOf(
            "penthouse_sunset" to Triple("Cobertura ao Pôr do Sol", "360° • padrão", Color.rgb(255, 150, 80)),
            "neon_street_night" to Triple("Rua Neon", "360° • noite", Color.rgb(255, 60, 200)),
        )
        fun prettify(id: String) = id.replace('_', ' ').replace('-', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        fun accentFor(id: String): Int = com.trackmr.ui.UiTheme.accents[(id.hashCode() and 0x7fffffff) % com.trackmr.ui.UiTheme.accents.size]
    }
}
