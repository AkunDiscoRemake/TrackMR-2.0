package dev.trackmr.tracking

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File

/** Optional experimental advisor, never replaces thermal limits or deterministic fallback. */
class NeuralGovernor(context: Context) : AutoCloseable {
    private val model: Interpreter? = runCatching {
        val f=File(context.filesDir,"performance.tflite")
        if (!f.exists()) null else {
            val engine=Interpreter(f,Interpreter.Options().setNumThreads(1))
            try {
                require(engine.getInputTensor(0).shape().contentEquals(intArrayOf(1,5)))
                require(engine.getOutputTensor(0).shape().contentEquals(intArrayOf(1,2)))
                require(engine.getInputTensor(0).dataType()==org.tensorflow.lite.DataType.FLOAT32)
                require(engine.getOutputTensor(0).dataType()==org.tensorflow.lite.DataType.FLOAT32)
                engine
            } catch(e: Exception) { engine.close();throw e }
        }
    }.getOrNull()
    val available get() = model != null
    private val input=arrayOf(FloatArray(5))
    private val output=arrayOf(FloatArray(2))
    /** <= 1Hz; inputs normalized using the documented training contract. */
    fun advise(frameMs: Float, handMs: Float, thermal: Int, battery: Float, baseline: Float): Float {
        val engine=model ?: return baseline
        input[0][0]=frameMs/33.3f; input[0][1]=handMs/50; input[0][2]=thermal/6f
        input[0][3]=battery.coerceIn(0f,1f); input[0][4]=baseline
        return runCatching {
            engine.run(input,output)
            val candidate=output[0][0]
            if (!candidate.isFinite()) baseline else candidate.coerceIn(.6f,baseline)
        }.getOrDefault(baseline)
    }
    override fun close() { model?.close() }
}
