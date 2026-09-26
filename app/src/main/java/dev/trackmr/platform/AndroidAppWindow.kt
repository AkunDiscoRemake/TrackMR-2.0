package dev.trackmr.platform

import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One owned app display. Public for shell launch, OWN_CONTENT_ONLY to prohibit mirroring. */
class AndroidAppWindow(private val context: Context,private val bridge: ShizukuBridge,private val report: (String,Boolean)->Unit,private val onLaunchFailure: ()->Unit) : AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(2),ThreadPoolExecutor.AbortPolicy())
    private var display: VirtualDisplay?=null
    private var closed=false
    var width=1280;private set
    var height=800;private set
    private var lastResize=0L
    private fun command(fatal: Boolean=false,action: (IShellBridge,Int)->Unit){
        if(closed)return
        val id=display?.display?.displayId ?: return
        val service=bridge.shell ?: run{if(fatal)error("Shizuku desconectou antes do lançamento");report("Shizuku desconectado. Reconecte antes de controlar o app.",true);return}
        try{worker.execute{runCatching{action(service,id)}.onFailure{e->main.post{
            if(!closed){report("App Shizuku: ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}",true);if(fatal){close();onLaunchFailure()}}
        }}}}
        catch(_: java.util.concurrent.RejectedExecutionException){report("Entrada ocupada; aguarde o comando anterior",false)}
    }
    // Intentionally uses AOSP destroy-on-removal flag, absent only from the public @IntDef.
    @android.annotation.SuppressLint("WrongConstant")
    fun open(surface: Surface,component: ComponentName){
        check(bridge.shell!=null){"Conecte/autorize Shizuku primeiro em Captura"}
        check(display==null&&!closed)
        display=context.getSystemService(DisplayManager::class.java).createVirtualDisplay("TrackMR-app-${android.os.SystemClock.uptimeMillis()}",width,height,240,surface,
            // AOSP API29+: DESTROY_CONTENT_ON_REMOVAL is hidden from android.jar (1 << 8).
            // Without it a PUBLIC display moves the app onto the phone when closed.
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 8))
            ?: error("Android recusou display de app")
        command(fatal=true){shell,id->val message=shell.launchOnDisplay(component.flattenToString(),id);main.post{if(!closed)report("App no display $id. ${message.take(120)}",false)}}
    }
    fun tap(u: Float,v: Float){val x=(u*width).toInt().coerceIn(0,width-1);val y=(v*height).toInt().coerceIn(0,height-1);command{s,id->s.tap(id,x,y)}}
    fun scroll(delta: Float){if(kotlin.math.abs(delta)<.01f)return;val w=width;val h=height;command{s,id->s.swipe(id,w/2,h/2,w/2,(h/2+delta*h*5).toInt().coerceIn(0,h-1))}}
    fun back(){command{s,id->s.back(id)}}
    fun text(value: String){command{s,id->s.text(id,value)}}
    /** Main thread; window ratio updates the producer, not merely the sampled texture. */
    fun resize(ratio: Float,buffers: (Int,Int)->Unit){
        val now=android.os.SystemClock.uptimeMillis();if(now-lastResize<500||closed)return
        val w=((ratio*800).toInt()/16*16).coerceIn(640,1920)
        if(kotlin.math.abs(w-width)<32)return
        lastResize=now;buffers(w,800);display?.resize(w,800,240);width=w;height=800
    }
    override fun close(){if(closed)return;closed=true;worker.shutdownNow();display?.release();display=null}
}
