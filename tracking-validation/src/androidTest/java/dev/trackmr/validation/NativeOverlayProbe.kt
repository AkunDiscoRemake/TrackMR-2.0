package dev.trackmr.validation

/** Calls the production GLES hand geometry + shaders + drawing routine; reads pixels from a real EGL pbuffer. */
object NativeOverlayProbe {
    init{System.loadLibrary("hand_render_validation")}
    external fun pixels(points: FloatArray?,interactive: Boolean): Int
}
