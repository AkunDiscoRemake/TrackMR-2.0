package dev.trackmr.validation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.trackmr.handtracking.CameraOrientation
import dev.trackmr.tracking.HandTracker
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Image/YUV -> production conversion -> JNI model -> filtered 21 landmarks. */
@RunWith(AndroidJUnit4::class)
class NativeHandPipelineTest {
    private fun await(message: ()->String,condition: ()->Boolean){
        val end=SystemClock.elapsedRealtime()+30_000
        var done=condition()
        while(!done&&SystemClock.elapsedRealtime()<end){Thread.sleep(10);done=condition()}
        assertTrue(message(),done) // reservations are side effects; NEVER evaluate a second time.
    }
    @Test fun referenceHandSurvivesRealYuvAndRepeatedNativeInference(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val fixture=instrumentation.context.assets.open("hand.jpg").use{BitmapFactory.decodeStream(it)}
        val upright=Bitmap.createScaledBitmap(fixture,384,384*fixture.height/fixture.width/2*2,true)
        val source=IntArray(upright.width*upright.height).also{upright.getPixels(it,0,upright.width,0,0,upright.width,upright.height)}
        for(rotation in listOf(0,90,180,270)){
            val w=if(rotation%180==0)upright.width else upright.height
            val h=if(rotation%180==0)upright.height else upright.width
            val reader=ImageReader.newInstance(w,h,ImageFormat.YUV_420_888,3)
            val writer=ImageWriter.newInstance(reader.surface,3)
            val tracker=HandTracker(instrumentation.targetContext)
            try{
                await({"Init: ${tracker.error}"}){tracker.available||tracker.error!=null}
                assertTrue("Init failed: ${tracker.error}",tracker.available)
                tracker.intervalMs=0;tracker.inputWidth=384
                repeat(6){frame->
                    val target=writer.dequeueInputImage()
                    // Sensor fixture is rotated opposite to display; production must recover it.
                    fun rgb(x:Int,y:Int):Int {
                        val dx=when(rotation){90->h-1-y;180->w-1-x;270->y;else->x}
                        val dy=when(rotation){90->x;180->h-1-y;270->w-1-x;else->y}
                        return source[dy*upright.width+dx]
                    }
                    for(y in 0 until h)for(x in 0 until w){
                        val c=rgb(x,y);val r=c ushr 16 and 255;val g=c ushr 8 and 255;val b=c and 255
                        put(target.planes[0],x,y,((66*r+129*g+25*b+128) shr 8)+16)
                        if(x%2==0&&y%2==0){
                            put(target.planes[1],x/2,y/2,((-38*r-74*g+112*b+128) shr 8)+128)
                            put(target.planes[2],x/2,y/2,((112*r-94*g-18*b+128) shr 8)+128)
                        }
                    }
                    target.timestamp=SystemClock.elapsedRealtimeNanos();writer.queueInputImage(target)
                    var input:Image?=null
                    await({"ImageReader produced no image"}){if(input==null)input=reader.acquireNextImage();input!=null}
                    val count=tracker.completed.get()
                    await({"Reservation: ${tracker.diagnostic}"}){tracker.reserve(SystemClock.elapsedRealtimeNanos())}
                    tracker.submit(input!!,CameraOrientation.imageToView(rotation,0))
                    await({"Inference: ${tracker.error} / ${tracker.diagnostic}"}){tracker.completed.get()>count||tracker.failedFrames.get()>0}
                    assertEquals("Native failure: ${tracker.error}",0L,tracker.failedFrames.get())
                    assertTrue("No hand r=$rotation frame=$frame: ${tracker.diagnostic}",tracker.rawHands>0)
                    val hand=tracker.latest.get()!!.hands.firstOrNull()
                    assertNotNull("Filter removed real hand",hand)
                    assertEquals(63,hand!!.points.size)
                    assertTrue(hand.points.all{it.isFinite()})
                }
            }finally{
                val closed=CountDownLatch(1);tracker.closeAfterDrain{closed.countDown()}
                assertTrue("Tracker failed to drain",closed.await(30,TimeUnit.SECONDS))
                writer.close();reader.close()
            }
        }
        if(upright!==fixture)upright.recycle();fixture.recycle()
    }
    private fun put(plane:Image.Plane,x:Int,y:Int,value:Int){
        plane.buffer.put(plane.buffer.position()+y*plane.rowStride+x*plane.pixelStride,value.coerceIn(0,255).toByte())
    }
}
