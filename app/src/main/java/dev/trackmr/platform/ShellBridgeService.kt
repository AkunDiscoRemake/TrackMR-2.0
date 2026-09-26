package dev.trackmr.platform

import android.os.Binder
import android.os.Process
import dev.trackmr.xr.OwnedDisplayPolicy
import java.util.concurrent.TimeUnit

/** Shizuku shell/root process. Every command re-checks display identity and Binder caller. */
class ShellBridgeService : IShellBridge.Stub() {
    override fun uid()=Process.myUid()
    private data class Size(val width: Int,val height: Int)
    private fun owned(displayId: Int): Size {
        // Framework hidden API in shell, never in the unprivileged UI process.
        // OEM incompatibility fails closed instead of sending input to display 0.
        val global=Class.forName("android.hardware.display.DisplayManagerGlobal")
        val manager=global.getMethod("getInstance").invoke(null)
        val info=global.getMethod("getDisplayInfo",Int::class.javaPrimitiveType).invoke(manager,displayId)
            ?: error("Display encerrado")
        fun field(name: String)=info.javaClass.getField(name).get(info)
        check(OwnedDisplayPolicy.allowed(displayId,field("name") as String,field("ownerUid") as Int,Binder.getCallingUid(),field("ownerPackageName") as? String ?: "")){"Display não pertence ao TrackMR/chamador"}
        return Size(field("logicalWidth") as Int,field("logicalHeight") as Int)
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
    override fun destroy(){Process.killProcess(Process.myPid())}
}
