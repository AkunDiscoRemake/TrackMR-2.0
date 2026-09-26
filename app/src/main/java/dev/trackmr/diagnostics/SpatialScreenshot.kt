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
import kotlin.math.min

object SpatialScreenshot {
    /** Explicit action only. Bounded rendered stereo snapshot; no background camera recorder. */
    fun capture(context: Context,view: GLSurfaceView,io: Executor,result: (String)->Unit){
        if(view.width<1||view.height<1){result("Screenshot: superfície ainda não disponível");return}
        val scale=min(1f,2048f/view.width.coerceAtLeast(view.height))
        val bitmap=Bitmap.createBitmap((view.width*scale).toInt().coerceAtLeast(1),(view.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        try{PixelCopy.request(view,bitmap,{status->
            if(status!=PixelCopy.SUCCESS){bitmap.recycle();result("PixelCopy falhou: $status");return@request}
            try{io.execute{
                var uri: android.net.Uri?=null
                try{
                    val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"TrackMR-${System.currentTimeMillis()}.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/TrackMR");put(MediaStore.Images.Media.IS_PENDING,1)}
                    val created=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Não foi possível criar imagem")
                    uri=created
                    context.contentResolver.openOutputStream(created)!!.use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                    context.contentResolver.update(created,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null);result("Screenshot salvo em Pictures/TrackMR")
                }catch(e: Exception){uri?.let{runCatching{context.contentResolver.delete(it,null,null)}};result("Screenshot: ${e.message}")}finally{bitmap.recycle()}
            }}catch(e: java.util.concurrent.RejectedExecutionException){bitmap.recycle();result("Screenshot cancelado: sessão encerrada")}
        },Handler(Looper.getMainLooper()))}catch(e: IllegalArgumentException){bitmap.recycle();result("Screenshot: superfície indisponível")}
    }
}
