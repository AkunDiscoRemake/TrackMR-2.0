package dev.trackmr.diagnostics

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import java.util.concurrent.Executor

object SpatialScreenshot {
    /** Explicit action only. Saves the rendered stereo surface; no background camera recorder. */
    fun capture(context: Context,view: GLSurfaceView,io: Executor,result: (String)->Unit){
        if(view.width<1||view.height<1){result("Screenshot: superfície ainda não disponível");return}
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        PixelCopy.request(view,bitmap,{status->
            if(status!=PixelCopy.SUCCESS){bitmap.recycle();result("PixelCopy falhou: $status");return@request}
            io.execute{
                var uri: android.net.Uri?=null
                try{
                    val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"TrackMR-${System.currentTimeMillis()}.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/TrackMR");put(MediaStore.Images.Media.IS_PENDING,1)}
                    val created=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Não foi possível criar imagem")
                    uri=created
                    context.contentResolver.openOutputStream(created)!!.use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                    context.contentResolver.update(created,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null);result("Screenshot salvo em Pictures/TrackMR")
                }catch(e: Exception){uri?.let{context.contentResolver.delete(it,null,null)};result("Screenshot: ${e.message}")}finally{bitmap.recycle()}
            }
        },Handler(Looper.getMainLooper()))
    }
}
