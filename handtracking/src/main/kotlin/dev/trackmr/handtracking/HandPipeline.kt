package dev.trackmr.handtracking

import dev.trackmr.core.OneEuro
import dev.trackmr.core.KalmanAxis
import kotlin.math.*

enum class Side { LEFT, RIGHT, UNKNOWN }
enum class FilterMode { ONE_EURO, KALMAN, EMA, RAW }
enum class BackendKind { MEDIAPIPE_GPU, MEDIAPIPE_CPU, OPENXR, CUSTOM, NONE }
data class HandObservation(val landmarks: FloatArray,val timestampNs: Long,val side: Side,
    val handednessConfidence: Float,val imageQuality: Float=1f) // quality is heuristic, not landmark probability
class HandSample(val points: FloatArray,val velocity: FloatArray,val acceleration: FloatArray,
    val timestampNs: Long,val side: Side,val confidence: Float,val predicted: Boolean=false)
interface HandBackend : AutoCloseable {
    val kind: BackendKind
    val available: Boolean
    val error: String?
}
interface GestureRecognizer { val priority: Int;fun recognize(sample: HandSample): List<GestureEvent> }

/** Small bounded temporal state, no UI/Android/backend dependency. One instance per persistent hand. */
class TemporalHandPipeline(var mode: FilterMode=FilterMode.ONE_EURO) {
    private val euro=Array(63){OneEuro(2.2,3.0,1.5)}
    private val kalman=Array(63){KalmanAxis()}
    private val previous=FloatArray(63)
    private val prevRaw=FloatArray(63)
    private val prev2Raw=FloatArray(63)
    private val velocity=FloatArray(63)
    private var time=0L
    private var last: HandSample?=null
    var predictionMs=12f
    fun reset(){time=0;last=null;euro.forEach{it.reset()};kalman.forEach{it.reset()};velocity.fill(0f)}
    fun update(o: HandObservation): HandSample? {
        if(o.landmarks.size!=63||o.landmarks.any{!it.isFinite()}||o.timestampNs<=time||!o.imageQuality.isFinite())return null
        if(o.imageQuality<.12f)return null
        val gap=time==0L||o.timestampNs-time>250_000_000
        if(gap){reset();o.landmarks.copyInto(previous);o.landmarks.copyInto(prevRaw);o.landmarks.copyInto(prev2Raw)}
        val dt=if(gap)1/30f else ((o.timestampNs-time)/1e9f).coerceIn(.001f,.25f)
        val points=FloatArray(63);val v=FloatArray(63);val acceleration=FloatArray(63)
        val confidence=o.imageQuality.coerceIn(.1f,1f)
        for(i in 0..62){
            val raw=o.landmarks[i]
            val jump=abs(raw-prevRaw[i])
            // Median is a spike guard, not an always-on three-frame delay.
            val median=raw+prevRaw[i]+prev2Raw[i]-minOf(raw,prevRaw[i],prev2Raw[i])-maxOf(raw,prevRaw[i],prev2Raw[i])
            val safe=if(!gap&&jump>maxOf(.12f,dt*7f)&&abs(prevRaw[i]-prev2Raw[i])<.02f)median else raw
            val weighted=if(gap)safe else previous[i]+(safe-previous[i])*confidence.coerceAtLeast(.55f)
            val filtered=when(mode){
                FilterMode.ONE_EURO->euro[i].update(weighted.toDouble(),o.timestampNs/1e9).toFloat()
                FilterMode.KALMAN->kalman[i].update(weighted.toDouble(),o.timestampNs/1e9).toFloat()
                FilterMode.EMA->previous[i]+(weighted-previous[i])*(1-exp(-dt*35))
                FilterMode.RAW->weighted
            }
            v[i]=if(gap)0f else ((filtered-previous[i])/dt).coerceIn(-5f,5f)
            acceleration[i]=if(gap)0f else ((v[i]-velocity[i])/dt).coerceIn(-40f,40f)
            points[i]=filtered;previous[i]=filtered;velocity[i]=v[i];prev2Raw[i]=prevRaw[i];prevRaw[i]=raw
        }
        time=o.timestampNs
        return HandSample(points,v,acceleration,time,o.side,confidence).also{last=it}
    }
    /** At most 25ms extrapolation; confidence decays. Never dispatch gestures from this sample. */
    fun predict(displayNs: Long): HandSample? {
        val s=last ?: return null
        val age=displayNs-s.timestampNs
        if(age !in 0..80_000_000L)return null
        val dt=(minOf(age/1e6f,predictionMs.coerceIn(0f,25f))/1000)
        val p=FloatArray(63){(s.points[it]+s.velocity[it]*dt).coerceIn(-.3f,1.3f)}
        return HandSample(p,s.velocity,s.acceleration,s.timestampNs,s.side,s.confidence*(1-age/100_000_000f),true)
    }
}

/** Stable slots by wrist continuity; handedness only breaks ties and is not treated as landmark confidence. */
class HandAssociation {
    private val wrists=Array(2){floatArrayOf(Float.NaN,Float.NaN)}
    private val seen=LongArray(2)
    fun associate(observations: List<HandObservation>): IntArray {
        val used=BooleanArray(2)
        return IntArray(observations.size){i->
            val o=observations[i]
            val candidates=(0..1).filter{!used[it]}
            if(candidates.isEmpty())-1 else {
                val slot=candidates.minBy { s->
                    val continuity=if(o.timestampNs-seen[s]<250_000_000&&!wrists[s][0].isNaN())
                        hypot(o.landmarks[0]-wrists[s][0],o.landmarks[1]-wrists[s][1]) else 1f
                    continuity+if((s==0&&o.side==Side.RIGHT)||(s==1&&o.side==Side.LEFT)) .04f else 0f
                }
                used[slot]=true;wrists[slot][0]=o.landmarks[0];wrists[slot][1]=o.landmarks[1];seen[slot]=o.timestampNs;slot
            }
        }
    }
}
