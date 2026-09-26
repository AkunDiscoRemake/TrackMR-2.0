package dev.trackmr.platform

import android.os.Binder
import android.os.Process
import dev.trackmr.xr.OwnedDisplayPolicy
import java.util.concurrent.TimeUnit

/** Shizuku shell/root process. Every command re-checks display identity and Binder caller. */
class ShellBridgeService : IShellBridge.Stub() {
    override fun uid()=Process.myUid()
    private data class Size(val width: Int,val height: Int)
    private fun owned(displayId: Int,callerUid: Int=Binder.getCallingUid()): Size {
        try {
        // Framework hidden API in shell, never in the unprivileged UI process.
        // OEM incompatibility fails closed instead of sending input to display 0.
        val global=Class.forName("android.hardware.display.DisplayManagerGlobal")
        val manager=global.getMethod("getInstance").invoke(null)
        val info=global.getMethod("getDisplayInfo",Int::class.javaPrimitiveType).invoke(manager,displayId)
            ?: error("Display encerrado")
        fun field(name: String)=info.javaClass.getField(name).get(info)
        check(OwnedDisplayPolicy.allowed(displayId,field("name") as String,field("ownerUid") as Int,callerUid,field("ownerPackageName") as? String ?: "")){"Display não pertence ao TrackMR/chamador"}
        return Size(field("logicalWidth") as Int,field("logicalHeight") as Int)
        }catch(e: Exception){throw IllegalStateException("Display Shizuku: ${e.cause?.message ?: e.message}",e)}
    }
    private data class Contact(val display: Int,val owner: Int,val down: Long,var u: Float,var v: Float,var expires: Long)
    private var contact: Contact?=null
    private val timer=java.util.concurrent.ScheduledThreadPoolExecutor(1){r->Thread(r,"TrackMR-input-expiry").apply{isDaemon=true}}.apply{setRemoveOnCancelPolicy(true)}
    private var expiry: java.util.concurrent.ScheduledFuture<*>?=null
    private val inputClass by lazy{runCatching{Class.forName("android.hardware.input.InputManagerGlobal")}.getOrElse{Class.forName("android.hardware.input.InputManager")}}
    private val inputManager by lazy{inputClass.getMethod("getInstance").invoke(null)}
    private val injectMethod by lazy{inputClass.getMethod("injectInputEvent",android.view.InputEvent::class.java,Int::class.javaPrimitiveType)}
    private val displayMethod by lazy{android.view.InputEvent::class.java.getMethod("setDisplayId",Int::class.javaPrimitiveType)}
    private fun inject(display: Int,action: Int,u: Float,v: Float,scroll: Float,down: Long,size: Size): Boolean {
        val now=android.os.SystemClock.uptimeMillis()
        val properties=android.view.MotionEvent.PointerProperties().apply{id=0;toolType=if(action==8)android.view.MotionEvent.TOOL_TYPE_MOUSE else android.view.MotionEvent.TOOL_TYPE_FINGER}
        val coords=android.view.MotionEvent.PointerCoords().apply{
            x=u.coerceIn(0f,1f)*(size.width-1);y=v.coerceIn(0f,1f)*(size.height-1)
            pressure=if(action==1||action==3)0f else 1f;this.size=1f
            if(action==8)setAxisValue(android.view.MotionEvent.AXIS_VSCROLL,scroll.coerceIn(-3f,3f))
        }
        val source=if(action==8)android.view.InputDevice.SOURCE_MOUSE else android.view.InputDevice.SOURCE_TOUCHSCREEN
        val event=android.view.MotionEvent.obtain(down,now,action,1,arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,source,0)
        try{displayMethod.invoke(event,display);return injectMethod.invoke(inputManager,event,0) as Boolean}
        finally{event.recycle()}
    }
    private fun cancelContact(){
        val c=contact;contact=null;expiry?.cancel(false);expiry=null
        if(c!=null)runCatching{inject(c.display,3,c.u,c.v,0f,c.down,owned(c.display,c.owner))}
    }
    /** Fast Binder input: no `input tap/swipe` process for a hand frame. Never target display 0. */
    @Synchronized override fun handPointer(displayId: Int,action: Int,u: Float,v: Float,scroll: Float): Boolean {
        try{
            require(action==0||action==1||action==2||action==3||action==8)
            require(u.isFinite()&&v.isFinite()&&scroll.isFinite())
            val size=owned(displayId)
            val now=android.os.SystemClock.uptimeMillis()
            if(action==3){if(contact?.display==displayId)cancelContact();return true}
            if(action==8)return inject(displayId,action,u,v,scroll,now,size)
            if(action==0){cancelContact();contact=Contact(displayId,Binder.getCallingUid(),now,u,v,now+800)}
            val c=contact ?: return false
            check(c.display==displayId&&c.owner==Binder.getCallingUid()){"Outro gesto já controla a entrada"}
            if(!inject(displayId,action,u,v,0f,c.down,size)){cancelContact();return false}
            c.u=u;c.v=v;c.expires=now+800
            expiry?.cancel(false)
            if(action==1){contact=null;expiry=null}else{
                expiry=timer.schedule({synchronized(this@ShellBridgeService){
                    if(contact===c&&android.os.SystemClock.uptimeMillis()>=c.expires)cancelContact()
                }},800,TimeUnit.MILLISECONDS)
            }
            return true
        }catch(e: Exception){cancelContact();throw IllegalStateException("Entrada de mão Shizuku: ${e.cause?.message ?: e.message}",e)}
    }
    override fun launchOnDisplay(component: String,displayId: Int): String {
        owned(displayId);require(OwnedDisplayPolicy.component(component))
        return command("am","start","--display",displayId.toString(),"-n",component)
    }
    override fun resizeDisplay(displayId: Int,width: Int,height: Int): String {
        owned(displayId);require(width in 320..1920&&height in 240..1200)
        return command("wm","size","${width}x$height","-d",displayId.toString())
    }
    override fun tap(displayId: Int,x: Int,y: Int): String {
        val size=owned(displayId);require(x in 0 until size.width&&y in 0 until size.height)
        return command("input","-d",displayId.toString(),"tap",x.toString(),y.toString())
    }
    override fun swipe(displayId: Int,x: Int,y: Int,endX: Int,endY: Int): String {
        val size=owned(displayId)
        require(x in 0 until size.width&&endX in 0 until size.width&&y in 0 until size.height&&endY in 0 until size.height)
        return command("input","-d",displayId.toString(),"swipe",x.toString(),y.toString(),endX.toString(),endY.toString(),"180")
    }
    override fun back(displayId: Int): String {owned(displayId);return command("input","-d",displayId.toString(),"keyevent","4")}
    override fun text(displayId: Int,value: String): String {
        owned(displayId);require(value.length<=512&&value.all{it in ' '..'~'}){"Teclado Shizuku: somente ASCII neste backend"}
        return command("input","-d",displayId.toString(),"text",value.replace(" ","%s"))
    }
    private fun command(vararg args: String): String {
        val output=java.io.File.createTempFile("trackmr-command-",".log",java.io.File("/data/local/tmp"))
        try{
            val process=ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(output).start()
            if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();error("Timeout do serviço Shizuku")}
            val message=output.inputStream().use{val data=ByteArray(4096);val n=it.read(data);if(n>0)String(data,0,n)else ""}
            check(process.exitValue()==0&&!message.contains("Error:",true)&&!message.contains("SecurityException")){message.ifBlank{"Comando recusado"}}
            return message
        }finally{output.delete()}
    }
    @Synchronized override fun destroy(){cancelContact();timer.shutdownNow();Process.killProcess(Process.myPid())}
}
