package dev.trackmr.handtracking
import org.junit.Test
import org.junit.Assert.*
class HandPointerTest {
 private fun hand(t: Long,closed: Boolean,id: Int=1): HandSample {
  val p=FloatArray(63);p[15]=.3f;p[51]=.6f;p[24]=.5f;p[12]=if(closed).51f else .7f
  return HandSample(p,FloatArray(63),FloatArray(63),t,Side.RIGHT,1f,trackId=id)
 }
 @Test fun actualPinchSelectsOnlyOnceAtCurrentTarget(){
  val input=HandPointer()
  assertTrue(input.update(hand(1,true),100,0f,0f,false,-1).isEmpty())
  input.update(hand(2,false),100,0f,0f,false,-1)
  val down=input.update(hand(3,true),103,.2f,.3f,false,-1).single()
  assertEquals(HandAction.SELECT,down.action);assertEquals(103,down.target)
  assertTrue(input.update(hand(3,true),104,0f,0f,false,-1).isEmpty())
  assertTrue(input.update(hand(4,true),104,0f,0f,false,-1).isEmpty())
 }
 @Test fun dragReleaseAndLossAreCaptured(){
  val input=HandPointer();input.update(hand(1,false),8001,.2f,.3f,true,8001)
  assertEquals(HandAction.DOWN,input.update(hand(2,true),8001,.2f,.3f,true,8001).single().action)
  val move=input.update(hand(3,true),8001,.8f,.7f,true,8001).single();assertEquals(.8f,move.u,0f)
  val up=input.update(hand(4,false),100,0f,0f,false,8001).single();assertEquals(HandAction.UP,up.action);assertEquals(8001,up.target);assertEquals(.8f,up.u,0f)
  input.update(hand(5,true),8001,.1f,.1f,true,8001)
  assertEquals(HandAction.CANCEL,input.update(null,-1,0f,0f,false,8001).single().action)
  assertTrue(input.update(hand(6,true),8001,0f,0f,true,8001).isEmpty())
 }
 @Test fun secondHandCannotTakeHeldInput(){
  val input=HandPointer();input.update(hand(1,false),8001,0f,0f,true,8001);input.update(hand(2,true),8001,0f,0f,true,8001)
  assertEquals(HandAction.CANCEL,input.update(hand(3,true,2),8001,0f,0f,true,8001).single().action)
  assertTrue(input.update(hand(4,true,2),8001,0f,0f,true,8001).isEmpty())
 }
 @Test fun primarySurvivesReordering(){val s=PrimaryHand();val a=hand(1,false,1);val b=hand(1,false,2);assertSame(a,s.choose(listOf(a,b)));assertSame(a,s.choose(listOf(b,a)))}
 @Test fun closingSurfaceCancelsRatherThanClicksReplacement(){val i=HandPointer();i.update(hand(1,false),8001,0f,0f,true,8001);i.update(hand(2,true),8001,0f,0f,true,8001);assertEquals(HandAction.CANCEL,i.update(hand(3,true),8002,0f,0f,true,8002).single().action)}
}
