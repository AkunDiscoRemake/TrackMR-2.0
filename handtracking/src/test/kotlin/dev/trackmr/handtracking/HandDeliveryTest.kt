package dev.trackmr.handtracking

import org.junit.Assert.*
import org.junit.Test

class HandDeliveryTest {
    private val start=1_000_000_000L
    private fun state(nowMs:Long,processing:Float=200f,hasHands:Boolean=true,enabled:Boolean=true)=
        HandDelivery.state(start+nowMs*1_000_000,start,start+(processing*1_000_000).toLong(),processing,33,hasHands,enabled)
    private fun hand(t:Long,pinch:Boolean):HandSample {
        val p=FloatArray(63);p[15]=.3f;p[51]=.6f;p[24]=.5f;p[12]=if(pinch).51f else .7f
        return HandSample(p,FloatArray(63),FloatArray(63),t,Side.RIGHT,1f,trackId=1)
    }
    @Test fun reproducesOldGapThenKeepsCompletedSamplesThroughNextInference(){
        assertFalse(SampleFreshness.usable(300_000_000,200f)) // alpha06 hid a 100ms-old result!
        for(ms in 200L..430L step 16)assertEquals(HandPresentation.LIVE,state(ms))
    }
    @Test fun rendererCadenceDoesNotDisarmOpenPinchHoldReleaseAtSlowInferenceRates(){
        for(process in listOf(100L,150L,200L,280L)){
            val pointer=HandPointer();val actions=mutableListOf<HandAction>()
            val cadence=process+33
            for(frame in 0..3){
                val captured=start+frame*cadence*1_000_000
                val completed=captured+process*1_000_000
                val sample=hand(captured,frame==1||frame==2)
                for(offset in 0 until cadence step 16){
                    val visible=HandDelivery.state(completed+offset*1_000_000,captured,completed,process.toFloat(),33,true)
                    assertEquals("proc=$process gap=$offset",HandPresentation.LIVE,visible)
                    actions+=pointer.update(sample,8001,.4f,.5f,true,8001).map{it.action}
                }
            }
            assertEquals(listOf(HandAction.DOWN,HandAction.MOVE,HandAction.UP),actions)
        }
    }
    @Test fun slowNativeResultIsVisibleButCannotInteract(){
        assertEquals(HandPresentation.SLOW,state(450,450f))
        assertEquals(HandPresentation.SLOW,state(650,450f))
        assertEquals(HandPresentation.EXPIRED,state(801,450f))
    }
    @Test fun noHandsPauseAndTimeoutCancelImmediately(){
        assertEquals(HandPresentation.NONE,state(220,hasHands=false))
        assertEquals(HandPresentation.NONE,state(220,enabled=false))
        assertEquals(HandPresentation.EXPIRED,state(481))
        val p=HandPointer();p.update(hand(start,false),8001,0f,0f,true,8001)
        p.update(hand(start+1,true),8001,0f,0f,true,8001)
        assertEquals(HandAction.CANCEL,p.update(null,8001,0f,0f,true,8001).single().action)
    }
    @Test fun invalidClocksAndUnboundedSourceAgeNeverBecomeLive(){
        assertEquals(HandPresentation.EXPIRED,state(199))
        assertEquals(HandPresentation.EXPIRED,state(1_600,1_600f))
        assertEquals(HandPresentation.EXPIRED,state(200,Float.NaN))
    }
}
