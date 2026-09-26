package dev.trackmr.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dev.trackmr.R
import kotlin.math.sqrt

/** Equal-power stereo panning + distance attenuation. Not advertised as HRTF/binaural simulation. */
class SpatialAudio(context: Context) : AutoCloseable {
    private val pool=SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val loaded=java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val select: Int
    init{pool.setOnLoadCompleteListener{_,id,status->if(status==0)loaded.add(id)};select=pool.load(context,R.raw.xr_select,1)}
    fun select(pan: Float=0f,distance: Float=1f){if(select !in loaded)return;val p=pan.coerceIn(-1f,1f);val gain=.5f/(1+distance*distance);pool.play(select,gain*sqrt((1-p)/2),gain*sqrt((1+p)/2),1,0,1f)}
    override fun close(){pool.release()}
}
