package dev.trackmr.handtracking
import org.junit.Test
import org.junit.Assert.*
class YuvSamplingPlanTest {
 @Test fun offsetsMatchRotationWithCropAndPaddedStrides(){
  for(r in listOf(0,90,180,270))for(pixel in listOf(1,2)){
   val p=YuvSamplingPlan(4,6,640,480,320,r,704,1,704,pixel,736,pixel)
   for(y in 0 until p.height)for(x in 0 until p.width){
    val u=(x+.5f)/p.width;val v=(y+.5f)/p.height
    val sx=4+(ImageOrientation.sourceX(u,v,r)*640).toInt()
    val sy=6+(ImageOrientation.sourceY(u,v,r)*480).toInt()
    assertEquals(sy*704+sx,p.yColumns[x]+p.yRows[y])
    assertEquals(sy/2*704+sx/2*pixel,p.uColumns[x]+p.uRows[y])
    assertEquals(sy/2*736+sx/2*pixel,p.vColumns[x]+p.vRows[y])
   }
  }
 }
}
