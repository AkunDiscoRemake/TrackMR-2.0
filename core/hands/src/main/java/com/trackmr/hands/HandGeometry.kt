package com.trackmr.hands

import kotlin.math.abs

/**
 * Pure geometry used by the hand pipeline (unit tested on the JVM).
 */
object HandGeometry {

    /** Palm joints are the most rigid → weighted higher in the depth solve. */
    private val WEIGHTS = FloatArray(21) { i ->
        when (i) { 0, 1, 5, 9, 13, 17 -> 3f; 2, 6, 10, 14, 18 -> 1.5f; else -> 1f }
    }

    /**
     * Metric hand translation from 2D landmarks + MediaPipe world landmarks (translation-only PnP).
     *
     * For each landmark i with normalized image coords (a, b) = ((u-cx)/fx, (v-cy)/fy) and
     * hand-relative metric coords P = (X, Y, Z) in the camera-aligned CV frame
     * (x right, y down, z forward), find t minimizing Σ w |π(P + t) − (a, b)|² using the
     * linearized constraints  tx − a·tz = a·Z − X   and   ty − b·tz = b·Z − Y.
     *
     * @param ab 21×2 normalized image coordinates
     * @param world 21×3 hand-relative metric coordinates (CV frame)
     * @param out receives (tx, ty, tz)
     * @return true when a plausible solution (0.08 m < tz < 2 m) was found
     */
    fun solveTranslation(ab: FloatArray, world: FloatArray, out: FloatArray): Boolean {
        var sw = 0.0; var sa = 0.0; var sb = 0.0; var sab2 = 0.0
        var r1s = 0.0; var r2s = 0.0; var rz = 0.0
        for (i in 0 until 21) {
            val w = WEIGHTS[i].toDouble()
            val a = ab[i * 2].toDouble(); val b = ab[i * 2 + 1].toDouble()
            val x = world[i * 3].toDouble(); val y = world[i * 3 + 1].toDouble(); val z = world[i * 3 + 2].toDouble()
            val r1 = a * z - x
            val r2 = b * z - y
            sw += w; sa += w * a; sb += w * b; sab2 += w * (a * a + b * b)
            r1s += w * r1; r2s += w * r2; rz += w * (a * r1 + b * r2)
        }
        // Normal equations M t = c
        val m00 = sw; val m02 = -sa
        val m11 = sw; val m12 = -sb
        val m22 = sab2
        val c0 = r1s; val c1 = r2s; val c2 = -rz
        // Solve by eliminating tx, ty: tx = (c0 - m02 tz)/m00, ty = (c1 - m12 tz)/m11
        val denom = m22 - (m02 * m02) / m00 - (m12 * m12) / m11
        if (abs(denom) < 1e-12) return false
        val tz = (c2 - (m02 * c0) / m00 - (m12 * c1) / m11) / denom
        val tx = (c0 - m02 * tz) / m00
        val ty = (c1 - m12 * tz) / m11
        if (!(tz > 0.08 && tz < 2.0)) return false
        out[0] = tx.toFloat(); out[1] = ty.toFloat(); out[2] = tz.toFloat()
        return true
    }

    /** Fallback depth from apparent palm size (wrist→middle MCP ≈ 9 cm on adults). */
    fun depthFromPalm(ab: FloatArray, palmMeters: Float = 0.09f): Float {
        val dx = ab[18] - ab[0]; val dy = ab[19] - ab[1]
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        return if (len > 1e-4f) (palmMeters / len).coerceIn(0.1f, 2f) else 0.45f
    }
}
