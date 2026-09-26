package dev.trackmr.api

/** In-process SDK v1. This is NOT an OpenXR loader or an external APK ABI. SI units. */
interface TrackMrGame {
    val metadata: GameMetadata
    fun onStart(host: GameHost)
    fun onFrame(frame: XrFrame)
    fun onInput(input: XrInput)
    fun onStop()
}
data class GameMetadata(val id: String, val title: String, val version: Int = 1)
data class Pose(val x: Float, val y: Float, val z: Float,
                val qx: Float, val qy: Float, val qz: Float, val qw: Float)
data class XrFrame(val predictedDisplayTimeNs: Long, val head: Pose, val positionalTracking: Boolean)
sealed interface XrInput {
    data class Select(val pressed: Boolean) : XrInput
    data class Hand(val side: Int, val cameraLandmarks: FloatArray, val timestampNs: Long) : XrInput
    data object Recenter : XrInput
}
interface GameHost {
    fun setEnvironment(id: String)
    fun haptic(durationMs: Int)
    fun submit(scene: Scene)
    fun exit()
}
data class Scene(val objects: List<Primitive>)
data class Primitive(val shape: Shape, val pose: Pose, val scale: Float, val colorArgb: Int)
enum class Shape { SPHERE, CUBE, RING }
