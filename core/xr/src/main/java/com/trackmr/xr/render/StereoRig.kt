package com.trackmr.xr.render

import android.opengl.Matrix
import com.trackmr.xr.XrSettings
import com.trackmr.xr.gl.Mesh
import com.trackmr.xr.math.MathUtil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * VR Box / Cardboard stereo model.
 *
 * Uses the Cardboard viewer convention: distortion coefficients map a point on the
 * physical screen (tan-angle units from the lens optical center) to the virtual image
 * seen by the eye: p' = p (1 + k1 r² + k2 r⁴). The lens pass is a pre-warped mesh, so
 * the per-pixel cost is a single texture fetch.
 */
class StereoRig {
    class EyeLayout {
        /** Render frustum tangent extents: left, right, bottom, top (positive). */
        val tan = FloatArray(4)
        /** Eye rectangle inside the shared eye buffer (u, v, w, h). */
        val rect = FloatArray(4)
        var mesh: Mesh? = null
    }

    val eyes = arrayOf(EyeLayout(), EyeLayout())
    var stereo = true; private set
    var eyeCount = 2; private set
    private var configKey = ""

    /** Rebuilds layouts only when relevant parameters change. */
    fun configure(s: XrSettings, screenW: Int, screenH: Int, xdpi: Float, ydpi: Float) {
        val key = "${s.stereo}|$screenW|$screenH|${s.screenToLens}|${s.interLens}|${s.k1}|${s.k2}|${s.distortion}|${s.monoFovDeg}"
        if (key == configKey) return
        configKey = key
        stereo = s.stereo
        eyeCount = if (stereo) 2 else 1
        eyes.forEach { it.mesh?.release(); it.mesh = null }

        if (!stereo) {
            val aspect = screenW.toFloat() / screenH
            val ty = tan(s.monoFovDeg * 0.5f * MathUtil.DEG2RAD)
            val tx = ty * aspect
            val e = eyes[0]
            e.tan[0] = tx; e.tan[1] = tx; e.tan[2] = ty; e.tan[3] = ty
            e.rect[0] = 0f; e.rect[1] = 0f; e.rect[2] = 1f; e.rect[3] = 1f
            e.mesh = buildIdentityMesh(-1f, 1f)
            return
        }

        val mppX = 0.0254f / max(xdpi, 100f)
        val mppY = 0.0254f / max(ydpi, 100f)
        val wm = screenW * mppX
        val hm = screenH * mppY
        val stl = s.screenToLens
        val k1 = if (s.distortion) s.k1 else 0f
        val k2 = if (s.distortion) s.k2 else 0f
        val lensCy = hm / 2f
        for (i in 0..1) {
            val e = eyes[i]
            val x0 = if (i == 0) 0f else wm / 2f
            val x1 = if (i == 0) wm / 2f else wm
            // Lens centers are symmetric around the screen center; clamp for tiny screens.
            val lensCx = (if (i == 0) wm / 2f - s.interLens / 2f else wm / 2f + s.interLens / 2f).coerceIn(x0 + 0.005f, x1 - 0.005f)
            val sL = min((lensCx - x0) / stl, 2.2f)
            val sR = min((x1 - lensCx) / stl, 2.2f)
            val sB = min(lensCy / stl, 2.2f)
            val sT = min((hm - lensCy) / stl, 2.2f)
            e.tan[0] = min(distort(sL, k1, k2), MAX_TAN)
            e.tan[1] = min(distort(sR, k1, k2), MAX_TAN)
            e.tan[2] = min(distort(sB, k1, k2), MAX_TAN)
            e.tan[3] = min(distort(sT, k1, k2), MAX_TAN)
            e.rect[0] = if (i == 0) 0f else 0.5f; e.rect[1] = 0f; e.rect[2] = 0.5f; e.rect[3] = 1f
            e.mesh = buildDistortionMesh(x0, x1, wm, hm, lensCx, lensCy, stl, k1, k2, e.tan)
        }
    }

    private fun distort(r: Float, k1: Float, k2: Float): Float {
        val r2 = r * r
        return r * (1f + k1 * r2 + k2 * r2 * r2)
    }

    private fun buildDistortionMesh(
        x0: Float, x1: Float, wm: Float, hm: Float, lcx: Float, lcy: Float, stl: Float,
        k1: Float, k2: Float, tan: FloatArray,
    ): Mesh {
        val n = GRID
        val v = FloatArray((n + 1) * (n + 1) * 8)
        var o = 0
        for (j in 0..n) {
            val sy = hm * j / n
            for (i in 0..n) {
                val sx = x0 + (x1 - x0) * i / n
                val tx = (sx - lcx) / stl
                val ty = (sy - lcy) / stl
                val r2 = tx * tx + ty * ty
                val f = 1f + k1 * r2 + k2 * r2 * r2
                val ex = tx * f; val ey = ty * f
                val u = (ex + tan[0]) / (tan[0] + tan[1])
                val vv = (ey + tan[2]) / (tan[2] + tan[3])
                // Fade near/outside the rendered FOV to avoid smeared clamped edges.
                val edge = min(min(u, 1f - u), min(vv, 1f - vv))
                val fade = MathUtil.smoothstep(-0.005f, 0.03f, edge)
                v[o++] = sx / wm * 2f - 1f; v[o++] = sy / hm * 2f - 1f; v[o++] = fade
                v[o++] = 0f; v[o++] = 0f; v[o++] = 1f
                v[o++] = u; v[o++] = vv
            }
        }
        return Mesh(v, gridIndices(n))
    }

    private fun buildIdentityMesh(x0: Float, x1: Float): Mesh {
        val n = 1
        val v = FloatArray(4 * 8)
        var o = 0
        for (j in 0..n) for (i in 0..n) {
            v[o++] = x0 + (x1 - x0) * i; v[o++] = -1f + 2f * j; v[o++] = 1f
            v[o++] = 0f; v[o++] = 0f; v[o++] = 1f
            v[o++] = i.toFloat(); v[o++] = j.toFloat()
        }
        return Mesh(v, gridIndices(n))
    }

    private fun gridIndices(n: Int): ShortArray {
        val idx = ShortArray(n * n * 6)
        var o = 0
        val row = n + 1
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * row + i
            idx[o++] = a.toShort(); idx[o++] = (a + 1).toShort(); idx[o++] = (a + row).toShort()
            idx[o++] = (a + 1).toShort(); idx[o++] = (a + row + 1).toShort(); idx[o++] = (a + row).toShort()
        }
        return idx
    }

    /** Off-axis projection for eye [i], optionally zoomed (MR fill: scale < 1). */
    fun projection(i: Int, near: Float, far: Float, scale: Float, out: FloatArray) {
        val t = eyes[i].tan
        Matrix.frustumM(out, 0, -t[0] * near * scale, t[1] * near * scale, -t[2] * near * scale, t[3] * near * scale, near, far)
    }

    companion object {
        const val GRID = 32
        const val MAX_TAN = 1.6f
    }
}

/**
 * Keeps frame pacing stable by trading eye-buffer resolution for time.
 * Scales down fast when frames are missed, recovers slowly, and respects thermal headroom.
 */
class DynamicResolution {
    var scale = 1f; private set
    private var ema = 16.6f
    private var overBudgetTime = 0f
    private var underBudgetTime = 0f
    var thermalCap = 1f

    fun update(frameMs: Float, targetFps: Int, base: Float, enabled: Boolean, dt: Float): Float {
        val target = 1000f / targetFps
        ema += (frameMs - ema) * 0.1f
        val maxScale = base * thermalCap
        if (!enabled) { scale = maxScale; return scale }
        if (scale > maxScale) scale = maxScale
        if (ema > target * 1.18f) {
            overBudgetTime += dt; underBudgetTime = 0f
            if (overBudgetTime > 0.4f) { scale = max(MIN_SCALE, scale - 0.07f); overBudgetTime = 0f }
        } else if (ema < target * 1.04f) {
            underBudgetTime += dt; overBudgetTime = 0f
            if (underBudgetTime > 2.5f && scale < maxScale) { scale = min(maxScale, scale + 0.05f); underBudgetTime = 0f }
        } else { overBudgetTime = 0f; underBudgetTime = 0f }
        return scale
    }

    companion object { const val MIN_SCALE = 0.45f }
}
