package dev.trackmr.platform

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Surface

/** Android user-consented capture; NOT Shizuku and NOT permission bypass. */
class CaptureService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@CaptureService }
    private val binder=LocalBinder()
    private var projection: MediaProjection?=null
    private var display: VirtualDisplay?=null
    private var surface: Surface?=null
    var onResize: ((Int,Int)->Unit)?=null
    var onStopped: (() -> Unit)?=null
    override fun onBind(intent: Intent) = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("capture","Janela espacial",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,0,Intent(this,CaptureService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("TrackMR • compartilhamento ativo").setContentText("Sua tela está na janela VR. Toque em Parar para encerrar.")
            .addAction(Notification.Action.Builder(null,"Parar",stop).build()).setOngoing(true).build()
        startForeground(20,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (intent?.action=="stop") { stopSelf(); return START_NOT_STICKY }
        if (projection!=null) return START_NOT_STICKY
        @Suppress("DEPRECATION") val data=intent?.getParcelableExtra<Intent>("consent")
        if (data==null) { stopSelf(); return START_NOT_STICKY }
        try {
            projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if(width<=0||height<=0)return
                    display?.resize(width,height,resources.displayMetrics.densityDpi)
                    onResize?.invoke(width,height)
                }
            },Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) { stopSelf() }
        return START_NOT_STICKY
    }
    fun attach(target: Surface, width: Int, height: Int): Boolean {
        val p=projection ?: return false
        if (display!=null) return false // each consent creates at most ONE virtual display
        surface=target
        return try {
            display=p.createVirtualDisplay("TrackMR app capture",width,height,resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,target,null,null)
            true
        } catch (_: Exception) { stopSelf(); false }
    }
    override fun onDestroy() {
        display?.release(); display=null; projection?.stop(); projection=null
        // Surface is owned by the GL activity, not by the service.
        surface=null; onStopped?.invoke(); onStopped=null; onResize=null
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
