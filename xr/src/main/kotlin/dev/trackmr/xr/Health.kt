package dev.trackmr.xr

/** Unsupported timing stages are null, not zeros masquerading as measurements. */
data class LatencySnapshot(val cameraAgeMs: Float?=null,val preprocessMs: Float?=null,
    val inferenceMs: Float?=null,val filterMs: Float?=null,val sampleAgeMs: Float?=null,
    val renderCpuMs: Float?=null,val gpuMs: Float?=null,val displayMs: Float?=null,
    val motionToPhotonMs: Float?=null)
class ClockDomain {
    fun ageMs(captureNs: Long,nowNs: Long,realtimeSource: Boolean): Float? {
        if(!realtimeSource||captureNs<=0)return null
        val delta=nowNs-captureNs
        return if(delta in 0L..1_000_000_000L)delta/1e6f else null
    }
}
enum class QualityMode { BATTERY, BALANCED, QUALITY }
data class Quality(val renderScale: Float,val handWidth: Int,val handIntervalMs: Long,val handsAllowed: Boolean)
class QualityScaler {
    private var ema=16.7f
    private var frames=0
    var quality=Quality(.9f,384,33,true);private set
    fun update(frameMs: Float,targetMs: Float,thermal: Int,battery: Int,mode: QualityMode): Quality {
        if(frameMs.isFinite()&&frameMs>0)ema+=.06f*(frameMs-ema)
        // Safety changes are immediate; recovery remains hysteretic.
        if(thermal>=4){quality=Quality(.6f,256,100,false);frames=0;return quality}
        if(++frames<90&&thermal<3)return quality
        frames=0
        val loaded=ema>targetMs*1.15f
        val constrained=thermal>=3||battery in 0..15||mode==QualityMode.BATTERY
        val ceiling=if(constrained).75f else if(mode==QualityMode.QUALITY)1f else .95f
        val scale=(quality.renderScale+if(loaded||constrained)-.05f else .025f).coerceIn(.6f,ceiling)
        quality=Quality(scale,if(constrained||loaded)256 else 384,if(constrained)66 else if(loaded)50 else 33,true)
        return quality
    }
}
/** Crash counter written before session start, cleared on clean shutdown. */
class RecoveryPolicy(val consecutiveUncleanStarts: Int) {
    val safeMode get()=consecutiveUncleanStarts>=2
    val restoreLayout get()=!safeMode
}
class LocalNotifications(private val max: Int=32) {
    data class Entry(val id: Long,val message: String,val error: Boolean)
    private val entries=ArrayDeque<Entry>()
    @Synchronized fun add(id: Long,message: String,error: Boolean=false) { if(entries.lastOrNull()?.message==message)return;entries.addLast(Entry(id,message.take(400),error));while(entries.size>max)entries.removeFirst() }
    @Synchronized fun snapshot()=entries.toList()
}
