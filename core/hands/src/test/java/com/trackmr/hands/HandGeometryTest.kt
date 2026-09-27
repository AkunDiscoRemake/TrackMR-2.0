package com.trackmr.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HandGeometryTest {

    private fun syntheticHand(rnd: Random): FloatArray {
        // Roughly hand-shaped point cloud (±9 cm), CV frame, centered at origin.
        val w = FloatArray(63)
        for (i in 0 until 21) {
            w[i * 3] = (rnd.nextFloat() - 0.5f) * 0.12f
            w[i * 3 + 1] = (rnd.nextFloat() - 0.5f) * 0.16f
            w[i * 3 + 2] = (rnd.nextFloat() - 0.5f) * 0.05f
        }
        return w
    }

    @Test
    fun recoversTranslationExactly() {
        val rnd = Random(7)
        repeat(50) {
            val world = syntheticHand(rnd)
            val t = floatArrayOf((rnd.nextFloat() - 0.5f) * 0.4f, (rnd.nextFloat() - 0.5f) * 0.3f, 0.25f + rnd.nextFloat() * 0.5f)
            val ab = FloatArray(42)
            for (i in 0 until 21) {
                val z = world[i * 3 + 2] + t[2]
                ab[i * 2] = (world[i * 3] + t[0]) / z
                ab[i * 2 + 1] = (world[i * 3 + 1] + t[1]) / z
            }
            val out = FloatArray(3)
            assertTrue(HandGeometry.solveTranslation(ab, world, out))
            assertEquals(t[0], out[0], 1e-3f)
            assertEquals(t[1], out[1], 1e-3f)
            assertEquals(t[2], out[2], 1e-3f)
        }
    }

    @Test
    fun robustToPixelNoise() {
        val rnd = Random(11)
        val world = syntheticHand(rnd)
        val t = floatArrayOf(0.05f, -0.02f, 0.45f)
        val ab = FloatArray(42)
        for (i in 0 until 21) {
            val z = world[i * 3 + 2] + t[2]
            // ±1.5 px noise at f=500
            ab[i * 2] = (world[i * 3] + t[0]) / z + (rnd.nextFloat() - 0.5f) * 0.006f
            ab[i * 2 + 1] = (world[i * 3 + 1] + t[1]) / z + (rnd.nextFloat() - 0.5f) * 0.006f
        }
        val out = FloatArray(3)
        assertTrue(HandGeometry.solveTranslation(ab, world, out))
        assertEquals(t[2], out[2], 0.03f)
    }

    @Test
    fun rejectsImplausibleDepth() {
        val world = FloatArray(63)
        val ab = FloatArray(42)
        val out = FloatArray(3)
        // Degenerate input (all zeros) must not produce a "valid" depth.
        assertTrue(!HandGeometry.solveTranslation(ab, world, out) || out[2] in 0.08f..2f)
    }
}
