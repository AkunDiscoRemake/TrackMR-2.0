package dev.trackmr.handtracking

import kotlin.math.*

enum class GestureKind {
 PINCH, PINCH_HOLD, PINCH_RELEASE, DOUBLE_PINCH, GRAB, GRAB_HOLD, GRAB_RELEASE,
 OPEN_PALM, FIST, POINT, TWO_FINGER_POINT, THUMB_UP, THUMB_DOWN, SWIPE, AIR_TAP,
 DRAG, SCROLL, ROTATE, ZOOM, PALM_MENU, TWO_HAND_SCALE, TWO_HAND_ROTATE
}
enum class GestureCommand { SELECT, BACK, HOME, SCREENSHOT, DOCK_SUMMON, ENVIRONMENT_SWITCH, MR_VR_SWITCH, CONFIRM, CANCEL, AIR_BUTTON }
data class GestureEvent(val kind: GestureKind,val timestampNs: Long,val confidence: Float,
    val side: Side,val x: Float=0f,val y: Float=0f,val trackId: Int=-1)
class GestureBindings {
    val commands=mutableMapOf(GestureKind.PINCH to GestureCommand.SELECT,GestureKind.PALM_MENU to GestureCommand.DOCK_SUMMON)
    fun resolve(event: GestureEvent): GestureCommand?=if(event.confidence>=.6f)commands[event.kind] else null
}
class GestureEngine {
    private var lastNs=0L
    private var pinch=false
    private var grab=false
    private var lastPinch=0L
    private var palmSince=0L
    private var palmFired=false
    private var lastPose: GestureKind?=null
    private var lastPoseNs=0L
    private var previousX=0f;private var previousY=0f
    private var swipeSince=0L;private var swipeX=0f;private var swipeY=0f
    fun reset(){lastNs=0;pinch=false;grab=false;lastPinch=0;palmSince=0;palmFired=false;lastPose=null;swipeSince=0}
    fun update(s: HandSample): List<GestureEvent> {
        if(s.predicted||s.confidence<.6f||s.timestampNs<=lastNs)return emptyList()
        if(lastNs>0&&s.timestampNs-lastNs>350_000_000)reset()
        val p=s.points
        fun d(a: Int,b: Int)=hypot(p[a*3]-p[b*3],p[a*3+1]-p[b*3+1])
        val palm=d(5,17);if(palm<.025f){reset();return emptyList()}
        val ratio=d(4,8)/palm
        val pinched=ratio<if(pinch).5f else .28f
        val events=ArrayList<GestureEvent>(3)
        fun emit(k: GestureKind,x: Float=0f,y: Float=0f){events+=GestureEvent(k,s.timestampNs,s.confidence,s.side,x,y,s.trackId)}
        val x=p[8*3];val y=p[8*3+1]
        if(pinched&&!pinch){emit(GestureKind.PINCH);if(lastPinch>0&&s.timestampNs-lastPinch in 150_000_000..450_000_000)emit(GestureKind.DOUBLE_PINCH);lastPinch=s.timestampNs}
        if(!pinched&&pinch)emit(GestureKind.PINCH_RELEASE)
        if(pinched&&pinch){emit(GestureKind.PINCH_HOLD);if(abs(x-previousX)+abs(y-previousY)>.002f)emit(GestureKind.DRAG,x-previousX,y-previousY)}
        pinch=pinched
        val extended=(0..3).map { f->val tip=8+f*4;d(tip,0)>d(tip-2,0)*1.22f }
        val count=extended.count{it}
        if(!pinch){
            val closed=count==0
            if(closed&&!grab)emit(GestureKind.GRAB)
            if(closed&&grab)emit(GestureKind.GRAB_HOLD)
            if(!closed&&grab)emit(GestureKind.GRAB_RELEASE)
            grab=closed
            val thumbExtended=d(4,0)>d(3,0)*1.17f
            val pose=when {
                count==4->GestureKind.OPEN_PALM
                closed&&thumbExtended&&p[13]<p[10]-.04f->GestureKind.THUMB_UP
                closed&&thumbExtended&&p[13]>p[10]+.04f->GestureKind.THUMB_DOWN
                closed->GestureKind.FIST
                extended[0]&&!extended[1]&&!extended[2]&&!extended[3]->GestureKind.POINT
                extended[0]&&extended[1]&&!extended[2]&&!extended[3]->GestureKind.TWO_FINGER_POINT
                else->null
            }
            if(pose!=lastPose){lastPose=pose;lastPoseNs=s.timestampNs}
            else if(pose!=null&&s.timestampNs-lastPoseNs in 70_000_000..160_000_000){emit(pose);lastPoseNs=Long.MAX_VALUE/2}
            if(pose==GestureKind.OPEN_PALM){
                if(palmSince==0L)palmSince=s.timestampNs
                if(!palmFired&&s.timestampNs-palmSince>650_000_000){emit(GestureKind.PALM_MENU);palmFired=true}
            }else{palmSince=0;palmFired=false}
            if(pose==GestureKind.POINT&&s.velocity[26]<-1.2f&&s.acceleration[26]<-8f&&s.timestampNs-lastPinch>450_000_000){emit(GestureKind.AIR_TAP);lastPinch=s.timestampNs}
            if(swipeSince==0L){swipeSince=s.timestampNs;swipeX=p[0];swipeY=p[1]}
            if(s.timestampNs-swipeSince>120_000_000){
                val dx=p[0]-swipeX;val dy=p[1]-swipeY
                if(s.timestampNs-swipeSince<450_000_000&&hypot(dx,dy)>.22f){emit(GestureKind.SWIPE,dx,dy);swipeSince=s.timestampNs+400_000_000}
                else if(s.timestampNs-swipeSince>450_000_000){swipeSince=s.timestampNs;swipeX=p[0];swipeY=p[1]}
            }
            if(pose==GestureKind.TWO_FINGER_POINT&&lastNs>0)emit(GestureKind.SCROLL,x-previousX,y-previousY)
        }else{palmSince=0;palmFired=false} // Pinch owns interaction; pose/menu cannot conflict.
        previousX=x;previousY=y;lastNs=s.timestampNs
        return events
    }
}
class TwoHandGestures {
    private var distance=0f;private var angle=0f
    fun update(a: HandSample?,b: HandSample?): List<GestureEvent> {
        if(a==null||b==null||a.predicted||b.predicted||minOf(a.confidence,b.confidence)<.6f||abs(a.timestampNs-b.timestampNs)>40_000_000){distance=0f;return emptyList()}
        fun pinch(s: HandSample): Boolean { val p=s.points;val palm=hypot(p[15]-p[51],p[16]-p[52]);return hypot(p[12]-p[24],p[13]-p[25])<palm*.4f }
        if(!pinch(a)||!pinch(b)){distance=0f;return emptyList()}
        val dx=b.points[24]-a.points[24];val dy=b.points[25]-a.points[25];val d=hypot(dx,dy);val r=atan2(dy,dx)
        if(d<.08f){distance=0f;return emptyList()}
        val oldD=distance;val oldR=angle;distance=d;angle=r
        if(oldD==0f)return emptyList()
        val delta=atan2(sin(r-oldR),cos(r-oldR))
        return listOf(GestureEvent(GestureKind.TWO_HAND_SCALE,a.timestampNs,minOf(a.confidence,b.confidence),Side.UNKNOWN,(d/oldD).coerceIn(.9f,1.1f)),GestureEvent(GestureKind.TWO_HAND_ROTATE,a.timestampNs,minOf(a.confidence,b.confidence),Side.UNKNOWN,delta.coerceIn(-.15f,.15f)))
    }
}
