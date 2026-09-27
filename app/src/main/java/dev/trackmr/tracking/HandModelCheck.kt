package dev.trackmr.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/** Known local fixture, NOT a camera frame. Never returns landmarks to the renderer/gestures. */
object HandModelCheck {
    fun verify(context: Context,model: HandLandmarker,mode: HandInputImage.Mode){
        val decoded=context.assets.open("hand_self_test.jpg").use{BitmapFactory.decodeStream(it)}
            ?: error("Imagem de autoteste inválida")
        val bitmap=Bitmap.createScaledBitmap(decoded,384,(384*decoded.height/decoded.width).coerceAtLeast(1),true)
        try{
            val colors=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(colors,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            HandInputImage().use{input->
                input.mode=mode
                repeat(3){frame->
                    val pixels=input.prepare(bitmap.width,bitmap.height)
                    for(i in colors.indices){val c=colors[i];pixels.put(i,(c and 0xff00ff00.toInt()) or ((c ushr 16) and 255) or ((c and 255) shl 16))}
                    val detected=input.withImage{model.detectForVideo(it,frame*33L+1)}
                    check(detected.landmarks().any{it.size==21}){"Autoteste $mode: modelo não detectou a mão de referência"}
                }
            }
        }finally{if(bitmap!==decoded)bitmap.recycle();decoded.recycle()}
    }
}
