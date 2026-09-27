package com.trackmr.xr.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

object MathUtil {
    const val DEG2RAD = (PI / 180.0).toFloat()
    const val RAD2DEG = (180.0 / PI).toFloat()
    const val TAU = (PI * 2.0).toFloat()

    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
    fun clamp01(v: Float): Float = clamp(v, 0f, 1f)
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun inverseLerp(a: Float, b: Float, v: Float): Float = if (abs(b - a) < 1e-9f) 0f else (v - a) / (b - a)
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp01((x - e0) / (e1 - e0)); return t * t * (3f - 2f * t)
    }

    /** Frame-rate independent exponential smoothing factor. */
    fun dampFactor(lambda: Float, dt: Float): Float = 1f - exp(-lambda * dt)

    fun damp(current: Float, target: Float, lambda: Float, dt: Float): Float =
        lerp(current, target, dampFactor(lambda, dt))

    fun wrapAngle(a: Float): Float {
        var r = a
        while (r > PI) r -= TAU
        while (r < -PI) r += TAU
        return r
    }

    /** Shortest-arc angle interpolation. */
    fun lerpAngle(a: Float, b: Float, t: Float): Float = a + wrapAngle(b - a) * t

    /** Ray-plane intersection. Plane given by point + normal. Returns t or NaN. */
    fun rayPlane(ro: Vec3, rd: Vec3, pp: Vec3, pn: Vec3): Float {
        val denom = rd.dot(pn)
        if (abs(denom) < 1e-6f) return Float.NaN
        val t = ((pp.x - ro.x) * pn.x + (pp.y - ro.y) * pn.y + (pp.z - ro.z) * pn.z) / denom
        return if (t >= 0f) t else Float.NaN
    }

    /** Ray-sphere intersection returning nearest t >= 0 or NaN. */
    fun raySphere(ro: Vec3, rd: Vec3, c: Vec3, r: Float): Float {
        val ox = ro.x - c.x; val oy = ro.y - c.y; val oz = ro.z - c.z
        val b = ox * rd.x + oy * rd.y + oz * rd.z
        val cc = ox * ox + oy * oy + oz * oz - r * r
        val disc = b * b - cc
        if (disc < 0f) return Float.NaN
        val s = kotlin.math.sqrt(disc)
        val t0 = -b - s
        if (t0 >= 0f) return t0
        val t1 = -b + s
        return if (t1 >= 0f) t1 else Float.NaN
    }
}

/**
 * One Euro filter (Casiez et al.) — adaptive low-pass: heavy smoothing when still,
 * low latency when moving fast. Allocation free.
 */
class OneEuroFilter(
    @JvmField var minCutoff: Float = 1.2f,
    @JvmField var beta: Float = 0.02f,
    @JvmField var dCutoff: Float = 1.0f,
) {
    private var initialized = false
    private var xPrev = 0f
    private var dxPrev = 0f
    private var tPrev = 0.0

    fun reset() { initialized = false }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (MathUtil.TAU * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun filter(x: Float, tSeconds: Double): Float {
        if (!initialized) {
            initialized = true; xPrev = x; dxPrev = 0f; tPrev = tSeconds; return x
        }
        var dt = (tSeconds - tPrev).toFloat()
        if (dt <= 1e-5f) dt = 1f / 120f
        tPrev = tSeconds
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = dxPrev + aD * (dx - dxPrev)
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = xPrev + a * (x - xPrev)
        xPrev = xHat; dxPrev = dxHat
        return xHat
    }

    /** Derivative estimate (units/second) from the last update. */
    val velocity: Float get() = dxPrev
}

/** Three filters bundled for a Vec3. */
class OneEuroFilter3(minCutoff: Float = 1.2f, beta: Float = 0.02f) {
    @JvmField val fx = OneEuroFilter(minCutoff, beta)
    @JvmField val fy = OneEuroFilter(minCutoff, beta)
    @JvmField val fz = OneEuroFilter(minCutoff, beta)

    fun reset() { fx.reset(); fy.reset(); fz.reset() }
    fun configure(minCutoff: Float, beta: Float) {
        fx.minCutoff = minCutoff; fy.minCutoff = minCutoff; fz.minCutoff = minCutoff
        fx.beta = beta; fy.beta = beta; fz.beta = beta
    }
    fun filter(v: Vec3, t: Double, out: Vec3): Vec3 = out.set(fx.filter(v.x, t), fy.filter(v.y, t), fz.filter(v.z, t))
    fun velocity(out: Vec3): Vec3 = out.set(fx.velocity, fy.velocity, fz.velocity)
}
