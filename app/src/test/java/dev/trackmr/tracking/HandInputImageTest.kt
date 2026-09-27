package dev.trackmr.tracking

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE)
class HandInputImageTest {
    @Test fun reproducesPreviousTrackerBugWithActualMediaPipeDependency(){
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        BitmapImageBuilder(bitmap).build().close()
        assertTrue("MPImage.close owns/recycles the supplied Bitmap",bitmap.isRecycled)
        assertThrows(IllegalStateException::class.java){bitmap.setPixels(IntArray(4),0,2,0,0,2,2)}
    }
    @Test fun twoHundredFramesReuseRgbaStorageAfterClosingEachWrapper(){
        val input=HandInputImage()
        val original=input.prepare(2,2)
        repeat(200){frame->
            val pixels=input.prepare(2,2)
            assertSame(original,pixels)
            // Red byte changes every frame; also verify G/B/A ordering.
            repeat(4){pixels.put(it,(0xff shl 24) or (0x33 shl 16) or (0x22 shl 8) or frame)}
            input.withImage{image->
                val bytes=ByteBufferExtractor.extract(image)
                assertTrue(bytes.isDirect)
                assertEquals(frame,bytes.get(0).toInt() and 255)
                assertEquals(0x22,bytes.get(1).toInt() and 255)
                assertEquals(0x33,bytes.get(2).toInt() and 255)
                assertEquals(0xff,bytes.get(3).toInt() and 255)
                assertThrows(IllegalStateException::class.java){input.prepare(2,2)}
            }
        }
        input.close()
    }
    @Test fun failureAndResizeDoNotReuseClosedBitmapOrLeaveLeaseLocked(){
        val input=HandInputImage()
        input.prepare(2,2)
        assertThrows(IllegalArgumentException::class.java){input.withImage<Unit>{throw IllegalArgumentException("inference failed")}}
        input.prepare(3,4)
        input.withImage{assertEquals(3,it.width);assertEquals(4,it.height);assertEquals(48,ByteBufferExtractor.extract(it).capacity())}
        input.close()
    }
}
