package dev.trackmr.core

/** Bounded, hysteretic controller. No claim of motion-to-photon from CPU timing. */
class PerformanceGovernor {
    var scale = 1f; private set
    var handIntervalMs = 33L; private set
    private var ema = 16.7f
    private var frames = 0
    fun update(frameMs: Float, thermalStatus: Int, targetMs: Float = 16.67f) {
        if (!frameMs.isFinite() || frameMs <= 0 || targetMs <= 0) return
        ema += .05f * (frameMs - ema)
        if (++frames < 120) return
        frames = 0
        if (thermalStatus >= 3 || ema > targetMs * 1.15f) scale -= .05f
        else if (thermalStatus <= 1 && ema < targetMs * 1.04f) scale += .025f
        scale = scale.coerceIn(.6f, 1f)
        handIntervalMs = if (thermalStatus >= 3) 66 else if (ema > targetMs * 1.3f) 50 else 33
    }
}

class FrameStatistics(private val capacity: Int = 240) {
    private val samples = FloatArray(capacity)
    private var next = 0
    private var count = 0
    init { require(capacity > 0) }
    fun add(milliseconds: Float) {
        if (!milliseconds.isFinite() || milliseconds <= 0) return
        samples[next] = milliseconds; next = (next + 1) % capacity; count = minOf(count + 1, capacity)
    }
    /** Called by diagnostics at <= 1 Hz, never on the critical draw path. */
    fun percentile(p: Float): Float {
        require(p in 0f..1f)
        if (count == 0) return 0f
        val sorted = samples.copyOf(count).apply { sort() }
        return sorted[((count - 1) * p).toInt()]
    }
}
