package dev.trackmr.vr

import android.content.Context
/** Only the GL thread mutates the renderer; scan is an Android UI operation. */
object NativeBridge {
    init { System.loadLibrary("trackmr") }
    external fun create(context: Context): Long
    external fun destroy(handle: Long)
    external fun surface(handle: Long)
    external fun resize(handle: Long, width: Int, height: Int)
    external fun resume(handle: Long)
    external fun pause(handle: Long)
    external fun scan()
    external fun recenter(handle: Long)
    external fun settings(handle: Long, scale: Float, curved: Boolean, aspect: Float)
    external fun scene(handle: Long, scene: Int)
    external fun select(handle: Long): Int
    external fun hands(handle: Long, points: FloatArray?)
    external fun draw(handle: Long, sky: Int, panel: Int, external: Int, transform: FloatArray,
                      capture: Boolean, arPose: FloatArray?, predictionNs: Long): Int
}
