package dev.trackmr.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

class ShizukuBridge(context: Context) : AutoCloseable {
    private val args=Shizuku.UserServiceArgs(ComponentName(context,ShellBridgeService::class.java))
        .daemon(false).processNameSuffix("windows").debuggable(false).version(1)
    var shell: IShellBridge? = null; private set
    private var bound=false
    private val connection=object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { shell=IShellBridge.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName?) { shell=null }
    }
    fun status(): String = when {
        !Shizuku.pingBinder() -> "Shizuku não está em execução"
        Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED -> "Shizuku precisa de autorização"
        shell==null -> "Shizuku autorizado • serviço desconectado"
        else -> "Shizuku conectado • uid ${runCatching { shell!!.uid() }.getOrDefault(-1)}"
    }
    fun requestOrBind() {
        check(Shizuku.pingBinder()) { "Instale e inicie o Shizuku por depuração sem fio ou root." }
        if (Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(200)
        else if (!bound) { Shizuku.bindUserService(args,connection); bound=true }
    }
    override fun close() { if(bound)runCatching { Shizuku.unbindUserService(args,connection,true) }; bound=false; shell=null }
}
