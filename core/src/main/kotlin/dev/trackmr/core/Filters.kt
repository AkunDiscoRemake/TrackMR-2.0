package dev.trackmr.core

import kotlin.math.*

/** Stateful, allocation-free scalar filter. Timestamps are monotonic seconds. */
class OneEuro(private val minCutoff: Double = 1.8, private val beta: Double = .08,
              private val derivativeCutoff: Double = 1.0) {
    private var time = Double.NaN
    private var raw = 0.0
    private var value = 0.0
    private var derivative = 0.0
    init { require(minCutoff > 0 && beta >= 0 && derivativeCutoff > 0) }
    fun reset() { time = Double.NaN }
    fun update(x: Double, t: Double): Double {
        if (!x.isFinite() || !t.isFinite()) return value
        if (time.isNaN() || t - time > .25) {
            raw = x; value = x; derivative = 0.0; time = t; return x
        }
        if (t <= time) return value // duplicates must not corrupt velocity
        val dt = (t - time).coerceAtLeast(.0001)
        val aD = alpha(derivativeCutoff, dt)
        derivative += aD * ((x - raw) / dt - derivative)
        value += alpha(minCutoff + beta * abs(derivative), dt) * (x - value)
        raw = x; time = t
        return value
    }
    private fun alpha(cutoff: Double, dt: Double) = 1.0 / (1.0 + 1.0 / (2 * PI * cutoff * dt))
}

/** Constant-velocity Kalman filter; alternative to One Euro, not stacked by default. */
class KalmanAxis(private val accelerationNoise: Double = 2.0, private val measurementNoise: Double = .002) {
    private var t = Double.NaN
    private var x = 0.0
    private var v = 0.0
    private var p00 = 1.0
    private var p01 = 0.0
    private var p11 = 1.0
    fun reset() { t = Double.NaN }
    fun update(z: Double, time: Double): Double {
        if (!z.isFinite() || !time.isFinite()) return x
        if (t.isNaN() || time - t > .25) {
            t = time; x = z; v = 0.0; p00 = 1.0; p01 = 0.0; p11 = 1.0; return x
        }
        if (time <= t) return x
        val dt = (time - t).coerceAtLeast(.0001); t = time
        x += dt * v
        val a = p00 + 2 * dt * p01 + dt * dt * p11 + accelerationNoise * dt.pow(4) / 4
        val b = p01 + dt * p11 + accelerationNoise * dt.pow(3) / 2
        val c = p11 + accelerationNoise * dt * dt
        val k0 = a / (a + measurementNoise); val k1 = b / (a + measurementNoise)
        val residual = z - x
        x += k0 * residual; v += k1 * residual
        p00 = (1 - k0) * a; p01 = (1 - k0) * b; p11 = max(1e-9, c - k1 * b)
        return x
    }
}

class HandFilter(private val kalman: Boolean = false) {
    private val euro = Array(63) { OneEuro() }
    private val kf = Array(63) { KalmanAxis() }
    private val previous = FloatArray(63)
    private var lastTime = Double.NaN
    fun reset() { euro.forEach { it.reset() }; kf.forEach { it.reset() }; lastTime = Double.NaN }
    /** Caller owns arrays. No per-landmark allocation. Invalid frames are rejected atomically. */
    fun update(input: FloatArray, output: FloatArray, seconds: Double): Boolean {
        require(input.size == 63 && output.size == 63)
        if (!seconds.isFinite() || input.any { !it.isFinite() }) return false
        if (!lastTime.isNaN() && seconds <= lastTime) return false
        val reacquired = lastTime.isNaN() || seconds - lastTime > .25
        val maxJump = if (reacquired) Float.POSITIVE_INFINITY else ((seconds - lastTime) * 8).toFloat()
        for (i in input.indices) {
            val bounded = input[i].coerceIn(previous[i] - maxJump, previous[i] + maxJump)
            output[i] = (if (kalman) kf[i].update(bounded.toDouble(), seconds)
                else euro[i].update(bounded.toDouble(), seconds)).toFloat()
            previous[i] = bounded
        }
        lastTime = seconds
        return true
    }
}

/** Pinch relative to palm width: independent of distance to camera. */
class PinchGesture {
    var pressed = false; private set
    private var last = Double.NEGATIVE_INFINITY
    fun reset() { pressed = false; last = Double.NEGATIVE_INFINITY }
    fun update(points: FloatArray, seconds: Double): Boolean {
        fun distance(a: Int, b: Int): Double {
            val x = points[a*3] - points[b*3]; val y = points[a*3+1] - points[b*3+1]
            return hypot(x.toDouble(), y.toDouble())
        }
        val palm = distance(5, 17)
        if (palm < .01) { pressed = false; return false }
        val ratio = distance(4, 8) / palm
        val old = pressed
        pressed = if (pressed) ratio < .5 else ratio < .3
        val edge = pressed && !old && seconds - last > .25
        if (edge) last = seconds
        return edge
    }
}
