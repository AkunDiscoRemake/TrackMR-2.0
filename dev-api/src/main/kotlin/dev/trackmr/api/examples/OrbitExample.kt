package dev.trackmr.api.examples

import dev.trackmr.api.*
import kotlin.math.sin

/** Compiled SDK contract example. A renderer implementing GameHost is still required. */
class OrbitExample : TrackMrGame {
    override val metadata = GameMetadata("dev.trackmr.example.orbit", "Orbit API example")
    private var host: GameHost? = null
    private var startNs = 0L
    override fun onStart(host: GameHost) { this.host=host; startNs=0; host.setEnvironment("loft") }
    override fun onFrame(frame: XrFrame) {
        if(startNs==0L)startNs=frame.predictedDisplayTimeNs
        val t=(frame.predictedDisplayTimeNs-startNs)/1e9
        host?.submit(Scene(listOf(Primitive(Shape.SPHERE,
            Pose(sin(t).toFloat()*.5f,0f,-2f,0f,0f,0f,1f),.16f,0xff82efcc.toInt()))))
    }
    override fun onInput(input: XrInput) { if(input is XrInput.Select && input.pressed) host?.haptic(10) }
    override fun onStop() { host=null }
}
