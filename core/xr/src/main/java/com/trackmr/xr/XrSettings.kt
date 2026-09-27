package com.trackmr.xr

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

enum class XrMode { MR, VR }

enum class ResolutionPreset(val scale: Float, val label: String) {
    LOW(0.6f, "Baixa"), MEDIUM(0.8f, "Média"), HIGH(1.0f, "Alta"), MAX(1.25f, "Máxima"), CUSTOM(1.0f, "Personalizada")
}

enum class XrBackend(val label: String) { NATIVE("TrackMR nativo"), OPENXR("OpenXR (Monado)"), AUTO("Automático") }

/**
 * Persistent platform settings. Reads are plain field accesses (safe on the GL thread);
 * writes persist asynchronously and notify listeners.
 */
class XrSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("trackmr_xr", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    // ---- Mode ----
    @Volatile var mode: XrMode = XrMode.valueOf(prefs.getString("mode", XrMode.MR.name)!!)
        set(v) { field = v; put("mode", v.name) }
    @Volatile var stereo: Boolean = prefs.getBoolean("stereo", true)
        set(v) { field = v; put("stereo", v) }
    @Volatile var backend: XrBackend = runCatching { XrBackend.valueOf(prefs.getString("backend", XrBackend.NATIVE.name)!!) }.getOrDefault(XrBackend.NATIVE)
        set(v) { field = v; put("backend", v.name) }

    // ---- Resolution / performance ----
    @Volatile var resolution: ResolutionPreset = runCatching { ResolutionPreset.valueOf(prefs.getString("resolution", ResolutionPreset.HIGH.name)!!) }.getOrDefault(ResolutionPreset.HIGH)
        set(v) { field = v; put("resolution", v.name) }
    @Volatile var customScale: Float = prefs.getFloat("customScale", 0.9f)
        set(v) { field = v.coerceIn(0.35f, 1.5f); put("customScale", field) }
    @Volatile var dynamicResolution: Boolean = prefs.getBoolean("dynamicResolution", true)
        set(v) { field = v; put("dynamicResolution", v) }
    @Volatile var targetFps: Int = prefs.getInt("targetFps", 60)
        set(v) { field = v; put("targetFps", v) }

    val baseRenderScale: Float get() = if (resolution == ResolutionPreset.CUSTOM) customScale else resolution.scale

    // ---- Headset / lenses (VR Box / Cardboard) ----
    @Volatile var ipd: Float = prefs.getFloat("ipd", 0.063f)
        set(v) { field = v.coerceIn(0.05f, 0.08f); put("ipd", field) }
    @Volatile var screenToLens: Float = prefs.getFloat("screenToLens", 0.042f)
        set(v) { field = v.coerceIn(0.025f, 0.07f); put("screenToLens", field) }
    @Volatile var interLens: Float = prefs.getFloat("interLens", 0.064f)
        set(v) { field = v.coerceIn(0.05f, 0.08f); put("interLens", field) }
    @Volatile var k1: Float = prefs.getFloat("k1", 0.34f)
        set(v) { field = v; put("k1", v) }
    @Volatile var k2: Float = prefs.getFloat("k2", 0.55f)
        set(v) { field = v; put("k2", v) }
    @Volatile var distortion: Boolean = prefs.getBoolean("distortion", true)
        set(v) { field = v; put("distortion", v) }
    @Volatile var monoFovDeg: Float = prefs.getFloat("monoFov", 70f)
        set(v) { field = v; put("monoFov", v) }

    // ---- MR / passthrough ----
    @Volatile var passthroughFill: Float = prefs.getFloat("passthroughFill", 0.55f)
        set(v) { field = v.coerceIn(0f, 1f); put("passthroughFill", field) }
    @Volatile var passthroughBrightness: Float = prefs.getFloat("passthroughBrightness", 1f)
        set(v) { field = v.coerceIn(0.1f, 1.6f); put("passthroughBrightness", field) }
    @Volatile var occlusion: Boolean = prefs.getBoolean("occlusion", true)
        set(v) { field = v; put("occlusion", v) }
    @Volatile var depthApi: Boolean = prefs.getBoolean("depthApi", true)
        set(v) { field = v; put("depthApi", v) }
    @Volatile var planeVisualization: Boolean = prefs.getBoolean("planeViz", false)
        set(v) { field = v; put("planeViz", v) }

    // ---- Hand tracking ----
    @Volatile var handTracking: Boolean = prefs.getBoolean("handTracking", true)
        set(v) { field = v; put("handTracking", v) }
    @Volatile var maxHands: Int = prefs.getInt("maxHands", 2)
        set(v) { field = v.coerceIn(1, 2); put("maxHands", field) }
    @Volatile var handInferenceHz: Int = prefs.getInt("handHz", 30)
        set(v) { field = v.coerceIn(10, 60); put("handHz", field) }
    @Volatile var handSkeleton: Boolean = prefs.getBoolean("handSkeleton", true)
        set(v) { field = v; put("handSkeleton", v) }
    @Volatile var handRoi: Boolean = prefs.getBoolean("handRoi", true)
        set(v) { field = v; put("handRoi", v) }
    @Volatile var handLowLightBoost: Boolean = prefs.getBoolean("handLowLight", true)
        set(v) { field = v; put("handLowLight", v) }
    @Volatile var handGpu: Boolean = prefs.getBoolean("handGpu", false)
        set(v) { field = v; put("handGpu", v) }
    @Volatile var handPredictionMs: Float = prefs.getFloat("handPrediction", 24f)
        set(v) { field = v.coerceIn(0f, 60f); put("handPrediction", field) }
    @Volatile var handInputSize: Int = prefs.getInt("handInputSize", 256)
        set(v) { field = v.coerceIn(128, 512); put("handInputSize", field) }

    // ---- Environment ----
    @Volatile var environmentId: String = prefs.getString("environment", "penthouse_sunset")!!
        set(v) { field = v; put("environment", v) }

    fun getString(key: String, def: String): String = prefs.getString(key, def) ?: def
    fun getFloat(key: String, def: Float): Float = prefs.getFloat(key, def)
    fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    fun getBool(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)

    fun put(key: String, value: Any) {
        val e = prefs.edit()
        when (value) {
            is String -> e.putString(key, value)
            is Float -> e.putFloat(key, value)
            is Int -> e.putInt(key, value)
            is Boolean -> e.putBoolean(key, value)
            is Long -> e.putLong(key, value)
            else -> e.putString(key, value.toString())
        }
        e.apply()
        for (l in listeners) l(key)
    }

    fun addListener(l: (String) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }
}
