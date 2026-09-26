package dev.trackmr.tracking

import android.content.Context
import dev.trackmr.core.PerformanceGovernor
import org.tensorflow.lite.Interpreter
import java.io.File

/** Optional experimental advisor, never replaces thermal limits or deterministic fallback. */
class NeuralGovernor(context: Context) : AutoCloseable {
    private val model: Interpreter? = runCatching {
        val f=File(context.filesDir,"performance.tflite")
        if (!f.exists()) null else Interpreter(f,Interpreter.Options().setNumThreads(1)).also {
            require(it.getInputTensor(0).shape().contentEquals(intArrayOf(1,5)))
            require(it.getOutputTensor(0).shape().contentEquals(intArrayOf(1,2)))
        }
    }.getOrNull()
    val available get() = model != null
    private val input=arrayOf(FloatArray(5))
    private val output=arrayOf(FloatArray(2))
    /** <= 1Hz; inputs normalized using the documented training contract. */
    fun advise(frameMs: Float, handMs: Float, thermal: Int, battery: Float, baseline: PerformanceGovernor): Float {
        val engine=model ?: return baseline.scale
        input[0][0]=frameMs/33.3f; input[0][1]=handMs/50; input[0][2]=thermal/6f
        input[0][3]=battery.coerceIn(0f,1f); input[0][4]=baseline.scale
        return runCatching {
            engine.run(input,output)
            val candidate=output[0][0]
            if (!candidate.isFinite()) baseline.scale else candidate.coerceIn(.6f,baseline.scale)
        }.getOrDefault(baseline.scale)
    }
    override fun close() { model?.close() }
}
