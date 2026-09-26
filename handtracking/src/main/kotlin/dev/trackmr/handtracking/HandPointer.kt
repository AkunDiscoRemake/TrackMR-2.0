package dev.trackmr.handtracking

import kotlin.math.hypot

/** Primary identity remains stable when the detector reorders its two results. */
class PrimaryHand {
    private var id=-1
    fun choose(hands: List<HandSample>): HandSample? {
        hands.firstOrNull{it.trackId==id}?.let{return it}
        val next=hands.firstOrNull{it.side==Side.RIGHT} ?: hands.firstOrNull()
        id=next?.trackId ?: -1
        return next
    }
}
enum class HandAction { SELECT, DOWN, MOVE, UP, CANCEL }
data class HandCommand(val action: HandAction,val target: Int,val u: Float,val v: Float)

/** A real observed pinch drives input. Never accept a new/lost hand already held closed. */
class HandPointer {
    private var owner=-1
    private var stamp=0L
    private var armed=false
    var pressed=false;private set
    var captured=-1;private set
    private var x=.5f;private var y=.5f
    fun cancel(): List<HandCommand> {
        val result=if(captured>=0)listOf(HandCommand(HandAction.CANCEL,captured,x,y)) else emptyList()
        captured=-1;pressed=false;armed=false;owner=-1;stamp=0
        return result
    }
    fun update(sample: HandSample?,target: Int,u: Float,v: Float,isSurface: Boolean,liveSurface: Int): List<HandCommand> {
        if(sample==null||sample.predicted||sample.confidence<.6f)return cancel()
        if(captured>=0&&captured!=liveSurface)return cancel()
        val result=mutableListOf<HandCommand>()
        if(owner!=sample.trackId){result.addAll(cancel());owner=sample.trackId}
        if(sample.timestampNs<=stamp)return result
        stamp=sample.timestampNs
        val p=sample.points
        val palm=hypot(p[15]-p[51],p[16]-p[52])
        if(palm<.025f){result.addAll(cancel());return result}
        val pinched=hypot(p[12]-p[24],p[13]-p[25])/palm < if(pressed).5f else .28f
        if(captured>=0&&target==captured&&u.isFinite()&&v.isFinite()){x=u.coerceIn(0f,1f);y=v.coerceIn(0f,1f)}
        if(!pinched){
            if(captured>=0)result+=HandCommand(HandAction.UP,captured,x,y)
            captured=-1;pressed=false;armed=true
        }else if(!pressed&&armed){
            pressed=true;armed=false
            if(target>=0&&target!=9999){
                x=u.coerceIn(0f,1f);y=v.coerceIn(0f,1f)
                if(isSurface){captured=target;result+=HandCommand(HandAction.DOWN,target,x,y)}
                else result+=HandCommand(HandAction.SELECT,target,x,y)
            }
        }else if(pressed&&captured>=0){
            // Keep the original receiver until release. Outside the panel keep the last valid UV.
            result+=HandCommand(HandAction.MOVE,captured,x,y)
        }
        return result
    }
}
