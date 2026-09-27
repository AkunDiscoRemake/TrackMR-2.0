package dev.trackmr.tracking

import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/** Single inference worker owns storage. MPImage owns only each short-lived wrapper, not a Bitmap. */
class HandInputImage : AutoCloseable {
    private var rgba: ByteBuffer?=null
    private var pixels: IntBuffer?=null
    private var width=0
    private var height=0
    private var inUse=false
    private var prepared=false
    fun prepare(w: Int,h: Int): IntBuffer {
        check(!inUse){"Cannot overwrite an image during inference"}
        require(w>0&&h>0&&w.toLong()*h<=512L*1024)
        if(w!=width||h!=height||rgba==null){
            width=w;height=h
            // Explicit RGBA bytes on all host/device byte orders, not Android ARGB Bitmap storage.
            rgba=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.LITTLE_ENDIAN)
            pixels=rgba!!.asIntBuffer()
        }
        pixels!!.clear();prepared=true
        return pixels!!
    }
    fun <T> withImage(block: (MPImage)->T): T {
        check(prepared&&!inUse)
        prepared=false;inUse=true
        try{
            val bytes=rgba!!.duplicate().apply{clear()}
            val image=ByteBufferImageBuilder(bytes,width,height,MPImage.IMAGE_FORMAT_RGBA).build()
            try{return block(image)}finally{image.close()}
        }finally{inUse=false}
    }
    override fun close(){check(!inUse);prepared=false;pixels=null;rgba=null;width=0;height=0}
}
