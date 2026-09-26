package dev.trackmr.handtracking
import org.junit.Test
import org.junit.Assert.*
class YuvSamplingPlanTest {
 @Test fun offsetsMatchRotationWithCropAndPaddedStrides(){
  for(r in listOf(0,90,180,270))for(pixel in listOf(1,2)){
   val p=YuvSamplingPlan(4,6,640,480,320,r,704,1,704,pixel,736,pixel)
   for(y in 0 until p.height)for(x in 0 until p.width){
    // Exact half-pixel reference for 2:1 downsampling (no floating-point truncation).
    val sx=4+when(r){90->2*y+1;180->640-(2*x+1);270->640-(2*y+1);else->2*x+1}
    val sy=6+when(r){90->480-(2*x+1);180->480-(2*y+1);270->2*x+1;else->2*y+1}
    assertEquals(sy*704+sx,p.yColumns[x]+p.yRows[y])
    assertEquals(sy/2*704+sx/2*pixel,p.uColumns[x]+p.uRows[y])
    assertEquals(sy/2*736+sx/2*pixel,p.vColumns[x]+p.vRows[y])
   }
  }
 }
}
