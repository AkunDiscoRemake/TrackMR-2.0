package dev.trackmr.handtracking

import kotlin.math.atan2
import kotlin.math.roundToInt

/** Rotation inferred from IMAGE_NORMALIZED -> VIEW_NORMALIZED. Crop/scale do not change it. */
object ImageOrientation {
    fun degrees(map: FloatArray): Int = (((atan2(map[3]-map[1],map[2]-map[0])*180/Math.PI).roundToInt()+360+45)/90*90)%360
    fun sourceX(u: Float,v: Float,rotation: Int)=when(rotation){90->v;180->1-u;270->1-v;else->u}
    fun sourceY(u: Float,v: Float,rotation: Int)=when(rotation){90->1-u;180->1-v;270->u;else->v}
}
object SampleFreshness {
    // Do not silently hide every result on devices whose real inference exceeds 150ms.
    // Still reject old results; this is a usability bound, not a low-latency claim.
    fun usable(ageNs: Long,processingMs: Float): Boolean = ageNs>=0 &&
        ageNs<=((processingMs+65f).coerceIn(150f,350f)*1_000_000).toLong()
}
