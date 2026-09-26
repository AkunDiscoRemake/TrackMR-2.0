package dev.trackmr.openxr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import java.io.File
import java.util.concurrent.Executors

/** Optional, real OpenXR client. No provider impersonation, simulated runtime or swallowed result. */
class SessionActivity : Activity() {
    private external fun create(): Long
    private external fun run(handle: Long,activity: Activity): String
    private external fun stop(handle: Long)
    private external fun destroy(handle: Long)
    private val worker=Executors.newSingleThreadExecutor { r->Thread(r,"TrackMR-OpenXR-render") }
    private val lock=Any()
    private var handle=0L
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        System.loadLibrary("trackmr_openxr")
        synchronized(lock){handle=create()}
        worker.execute{
            val result=try{run(handle,this)}catch(e: Exception){"OpenXR JNI: ${e.javaClass.simpleName}: ${e.message}"}
            synchronized(lock){destroy(handle);handle=0}
            File(filesDir,"openxr-session.txt").writeText(result.take(64000))
            runOnUiThread{if(!isDestroyed){setResult(RESULT_OK,Intent().putExtra("report",result));finish()}}
        }
    }
    override fun onBackPressed(){synchronized(lock){if(handle!=0L)stop(handle)}}
    override fun onStop(){super.onStop();if(isFinishing)synchronized(lock){if(handle!=0L)stop(handle)}}
    override fun onDestroy(){synchronized(lock){if(handle!=0L)stop(handle)};worker.shutdown();super.onDestroy()}
}
