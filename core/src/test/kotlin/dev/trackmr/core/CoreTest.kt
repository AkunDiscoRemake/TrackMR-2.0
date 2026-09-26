package dev.trackmr.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CoreTest {
    @Test fun euroStationaryAndDuplicateTimestamp() {
        val f = OneEuro()
        repeat(500) { assertEquals(.4, f.update(.4, it / 60.0), 1e-6) }
        assertEquals(.4, f.update(10.0, 499 / 60.0), 1e-6)
        assertEquals(.4, f.update(Double.NaN, 9.0), 1e-6)
        assertEquals(3.0, f.update(3.0, 10.0), 1e-6)
    }
    @Test fun euroAttenuatesJitter() {
        val f = OneEuro(); var error = 0.0
        repeat(600) { error += abs(f.update(if (it % 2 == 0) .51 else .49, it/60.0) - .5) }
        assertTrue(error/600 < .003)
    }
    @Test fun kalmanTracksConstantVelocity() {
        val f = KalmanAxis(); var value = 0.0
        repeat(600) { value = f.update(it/600.0, it/60.0) }
        assertEquals(599/600.0, value, .02)
        assertEquals(value, f.update(2.0, 1.0), 0.0)
    }
    @Test fun missingHandResetsHistory() {
        val f = HandFilter(); val out = FloatArray(63)
        assertTrue(f.update(FloatArray(63) { .1f }, out, 1.0))
        assertFalse(f.update(FloatArray(63) { Float.NaN }, out, 1.1))
        assertTrue(f.update(FloatArray(63) { .9f }, out, 2.0))
        assertEquals(.9f, out[0], .0001f)
    }
    @Test fun thermalPolicyBoundedAndRecovers() {
        val g = PerformanceGovernor()
        repeat(10000) { g.update(28f, 4) }; assertEquals(.6f, g.scale, .001f)
        assertEquals(66L, g.handIntervalMs)
        repeat(10000) { g.update(10f, 0) }; assertEquals(1f, g.scale, .001f)
    }
    @Test fun panelGeometryAspectAndCurvature() {
        val p = PanelGeometry.mesh(2f)
        assertEquals(82, p.size)
        assertEquals(-2f, p[40].z, .0001f)
        val flat = PanelGeometry.mesh(2f, curved = false)
        assertTrue(flat.all { it.z == -2f })
        assertEquals(-1f, flat.first().x, .0001f)
    }
    @Test fun percentilesAndWrap() {
        val s = FrameStatistics(4)
        (1..8).forEach { s.add(it.toFloat()) }
        assertEquals(8f, s.percentile(1f), 0f)
        assertEquals(5f, s.percentile(0f), 0f)
    }
    @Test fun pinchHysteresis() {
        val g = PinchGesture(); val p = FloatArray(63)
        p[5*3] = .2f; p[17*3] = .6f; p[4*3] = .4f; p[8*3] = .45f
        assertTrue(g.update(p, 1.0)); assertFalse(g.update(p, 1.1))
        p[8*3] = .7f; assertFalse(g.update(p, 1.3))
        p[8*3] = .45f; assertTrue(g.update(p, 1.6))
    }
}
