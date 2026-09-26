package dev.trackmr.handtracking
import org.junit.Test
import org.junit.Assert.*
class ImageOrientationTest {
 @Test fun rotations(){
  val maps=listOf(floatArrayOf(0f,0f,1f,0f,0f,1f),floatArrayOf(1f,0f,1f,1f,0f,0f),floatArrayOf(1f,1f,0f,1f,1f,0f),floatArrayOf(0f,1f,0f,0f,1f,1f))
  maps.forEachIndexed { i,m->val r=ImageOrientation.degrees(m);assertEquals(i*90,r)
   for(u in listOf(.1f,.5f,.9f))for(v in listOf(.1f,.5f,.9f)){
    val x=ImageOrientation.sourceX(u,v,r);val y=ImageOrientation.sourceY(u,v,r)
    assertEquals(u,m[0]+x*(m[2]-m[0])+y*(m[4]-m[0]),.0001f)
    assertEquals(v,m[1]+x*(m[3]-m[1])+y*(m[5]-m[1]),.0001f)
   }
  }
 }
 @Test fun expiryIsBounded(){assertTrue(SampleFreshness.usable(210_000_000,180f));assertFalse(SampleFreshness.usable(360_000_000,999f));assertFalse(SampleFreshness.usable(-1,10f));assertFalse(SampleFreshness.usable(170_000_000,25f))}
}
