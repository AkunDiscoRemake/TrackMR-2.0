package dev.trackmr.vr

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.opengl.*
import android.os.*
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import dev.trackmr.camera.*
import dev.trackmr.core.FrameStatistics
import dev.trackmr.dock.*
import dev.trackmr.handtracking.*
import dev.trackmr.platform.*
import dev.trackmr.store.Catalog
import dev.trackmr.tracking.HandTracker
import dev.trackmr.xr.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** The launcher IS the XR surface. Android UI is restricted to system consent/file pickers. */
class VrActivity : ComponentActivity(),GLSurfaceView.Renderer {
    private val prefs by lazy{getSharedPreferences("trackmr",MODE_PRIVATE)}
    private lateinit var view: GLSurfaceView
    private lateinit var cardboardContext: CardboardContext
    private lateinit var shell: SpatialShell
    private lateinit var shizuku: ShizukuBridge
    private val experience=ExperienceState()
    private val notices=LocalNotifications()
    private val io=Executors.newSingleThreadExecutor()
    private val textures=IntArray(4)
    private val transform=FloatArray(16).apply{Matrix.setIdentityM(this,0)}
    private var handle=0L
    private var atlas: SpatialAtlas?=null
    private var feed: CameraFeed?=null
    private var hands: HandTracker?=null
    private var cameraFrame: CameraFrame?=null
    private var captureTexture: SurfaceTexture?=null
    private var captureSurface: Surface?=null
    private val captured=AtomicBoolean(false)
    @Volatile private var capturing=false
    private var captureService: CaptureService?=null
    private var bound=false
    private var width=1;private var height=1
    private var previousNs=0L;private var lastStats=0L;private var lastHandNs=0L
    private var hover=-1
    private var scene=0;private var score=0
    private var cameraStarted=0L
    private var safeMode=false
    private var failed=false
    @Volatile private var resumed=false
    @Volatile private var thermal=0
    @Volatile private var permissionAsked=false
    private var targetMs=16.67f
    private var generation=0
    private var libraryPage=0;private var storePage=0
    private var filterFavorites=false
    private var lastFrameMs=16.7f
    private var renderMs=0f
    private val stats=FrameStatistics()
    private val quality=QualityScaler()
    private val power by lazy{getSystemService(PowerManager::class.java)}
    private val thermalListener=PowerManager.OnThermalStatusChangedListener{thermal=it}
    private data class Installed(val label: String,val component: ComponentName)
    @Volatile private var installed=emptyList<Installed>()
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        permissionAsked=true
        view.queueEvent{if(granted&&resumed)startCamera()else{experience.camera(CameraState.DENIED,"Permissão de câmera negada");notify("MR indisponível sem autorização da câmera",true)}}
    }
    private val captureConsent=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        if(result.resultCode==Activity.RESULT_OK&&result.data!=null){
            try{val intent=Intent(this,CaptureService::class.java).putExtra("consent",result.data);startForegroundService(intent);bound=bindService(intent,captureConnection,Context.BIND_AUTO_CREATE)}
            catch(e: Exception){notify("Captura: ${e.message}",true)}
        }else notify("Captura cancelada pelo usuário")
    }
    private val captureConnection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?,binder: IBinder?){
            captureService=(binder as CaptureService.LocalBinder).service
            captureService?.onResize={w,h->view.queueEvent{captureTexture?.setDefaultBufferSize(w,h);if(::shell.isInitialized)shell.windows.windows.find{it.kind==WindowKind.CAPTURE}?.let{it.width=(it.height*w/h).coerceIn(.45f,2.8f)}}}
            captureService?.onStopped={capturing=false;captureService=null;view.queueEvent{if(::shell.isInitialized)shell.surfaceKind=null}}
            attachCapture()
        }
        override fun onServiceDisconnected(name: ComponentName?){capturing=false;captureService=null}
    }
    override fun onCreate(state: Bundle?){
        super.onCreate(state)
        cardboardContext=CardboardContext.from(this);shizuku=ShizukuBridge(this)
        val unclean=prefs.getInt("unclean",0);safeMode=RecoveryPolicy(unclean).safeMode
        prefs.edit().putInt("unclean",unclean+1).apply()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        view=GLSurfaceView(this).apply{
            setEGLContextClientVersion(3);setEGLConfigChooser(8,8,8,0,0,0);preserveEGLContextOnPause=true;setRenderer(this@VrActivity)
            contentDescription="TrackMR: ambiente espacial, dock por olhar ou mão"
            setOnTouchListener{_,event->if(event.action==MotionEvent.ACTION_UP)queueEvent{select()};true}
        }
        setContentView(view) // No 2D launcher, Android toolbar, TextView HUD or rasterized desktop.
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true){override fun handleOnBackPressed(){view.queueEvent{if(::shell.isInitialized){if(scene>0){scene=0;NativeBridge.scene(handle,0)}else shell.windows.focus?.let{shell.windows.close(it)}}}}})
        power.addThermalStatusListener(mainExecutor,thermalListener);thermal=power.currentThermalStatus
        @Suppress("DEPRECATION")
        targetMs=1000/windowManager.defaultDisplay.refreshRate.coerceAtLeast(30f)
        if(getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_GYROSCOPE)==null)notify("Sem giroscópio: orientação Cardboard indisponível; câmera e mãos ainda podem funcionar",true)
        if(safeMode)notify("Recuperação: tracking de mãos desligado após sessões interrompidas. MR continua sendo solicitado.",true)
        io.execute{
            @Suppress("DEPRECATION")
            val list=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            installed=list.filter{it.activityInfo.packageName!=packageName}.map{Installed(it.loadLabel(packageManager).toString(),ComponentName(it.activityInfo.packageName,it.activityInfo.name))}.sortedBy{it.label}
        }
    }
    override fun onResume(){
        super.onResume();resumed=true;view.onResume()
        view.queueEvent{previousNs=0;if(handle!=0L){NativeBridge.resume(handle);startCamera()}}
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED&&!permissionAsked){permissionAsked=true;view.post{cameraPermission.launch(Manifest.permission.CAMERA)}}
    }
    override fun onPause(){
        resumed=false
        view.queueEvent{stopCamera();if(handle!=0L)NativeBridge.pause(handle);if(::shell.isInitialized)shell.save()}
        view.onPause();super.onPause()
        if(!failed)prefs.edit().putInt("unclean",0).apply()
    }
    private fun startCamera(){
        if(!resumed||textures[3]==0||feed!=null)return
        val needsCamera=experience.requested!=Experience.VR||(!safeMode&&prefs.getBoolean("hands",true))
        if(!needsCamera)return
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){experience.camera(CameraState.DENIED,"Autorize a câmera em Sistema");return}
        hands=if(!safeMode&&prefs.getBoolean("hands",true))HandTracker(applicationContext)else null
        val primary=ArCameraFeed(this,hands)
        feed=if(primary.start(textures[3],(width/2).coerceAtLeast(1),height)){primary}else{
            notify(primary.status);primary.close()
            Camera2Feed(this,hands).also{if(!it.start(textures[3],(width/2).coerceAtLeast(1),height))notify(it.status,true)}
        }
        cameraStarted=SystemClock.elapsedRealtimeNanos();experience.camera(CameraState.STARTING,feed!!.status)
    }
    private fun stopCamera(){
        val oldFeed=feed;val oldHands=hands;feed=null;hands=null;cameraFrame=null
        oldFeed?.pause()
        if(oldHands!=null)oldHands.closeAfterDrain{oldFeed?.close()}else oldFeed?.close()
        experience.camera(CameraState.STOPPED,"Câmera desligada")
    }
    override fun onSurfaceCreated(gl: GL10?,config: EGLConfig?){
        try{
            generation++;stopCamera()
            if(handle!=0L)NativeBridge.destroy(handle)
            handle=NativeBridge.create(cardboardContext);NativeBridge.surface(handle);NativeBridge.resume(handle)
            GLES30.glGenTextures(4,textures,0)
            for(i in 0..3){val target=if(i<2)GLES30.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                GLES30.glBindTexture(target,textures[i]);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE)}
            atlas?.close();atlas=SpatialAtlas(textures[1]);shell=SpatialShell(prefs,atlas!!).apply{onDock={openDock(it)};if(!safeMode)restore()}
            val blank=android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888);blank.eraseColor(0xff171d31.toInt());GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[0]);GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,blank,0);blank.recycle()
            if(captureTexture!=null)runOnUiThread{stopService(Intent(this,CaptureService::class.java))}
            captureSurface?.release();captureTexture?.release();capturing=false
            captureTexture=SurfaceTexture(textures[2]).apply{setDefaultBufferSize(1280,720);setOnFrameAvailableListener{captured.set(true)}}
            captureSurface=Surface(captureTexture);runOnUiThread{attachCapture()}
            refreshPages();startCamera()
        }catch(e: Exception){fatal(e)}
    }
    override fun onSurfaceChanged(gl: GL10?,w: Int,h: Int){width=w;height=h;if(handle!=0L)NativeBridge.resize(handle,w,h)}
    override fun onDrawFrame(gl: GL10?){
        if(failed||handle==0L)return
        val start=SystemClock.elapsedRealtimeNanos()
        try{
            val dt=if(previousNs==0L)targetMs/1000 else ((start-previousNs)/1e9f).coerceAtMost(.1f)
            lastFrameMs=dt*1000;previousNs=start;stats.add(lastFrameMs)
            cameraFrame=feed?.frame((width/2).coerceAtLeast(1),height)
            val frame=cameraFrame
            if(frame?.active==true)experience.camera(CameraState.ACTIVE,feed!!.status)
            else if(feed!=null&&start-cameraStarted>5_000_000_000)experience.camera(CameraState.ERROR,feed!!.status)
            val batch=hands?.latest?.get()
            val sample=batch?.hands?.firstOrNull()?.takeIf{start-it.timestampNs in 0L..150_000_000L}
            var px=.5f;var py=.5f
            if(sample!=null){
                val prediction=minOf((start-sample.timestampNs)/1e9f,.018f)
                px=(sample.points[24]+sample.velocity[24]*prediction).coerceIn(0f,1f);py=(sample.points[25]+sample.velocity[25]*prediction).coerceIn(0f,1f)
                if(batch!!.timestampNs!=lastHandNs){lastHandNs=batch.timestampNs;batch.events.forEach{e->if(e.kind==GestureKind.PINCH)select()else shell.gesture(e)}}
            }
            // Do not draw a camera-normalized skeleton as fake metric 3D hands.
            NativeBridge.hands(handle,null)
            if(capturing&&captured.getAndSet(false)){captureTexture?.updateTexImage();captureTexture?.getTransformMatrix(transform)}
            val battery=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val mode=runCatching{QualityMode.valueOf(prefs.getString("quality","BALANCED")!!)}.getOrDefault(QualityMode.BALANCED)
            val q=quality.update(lastFrameMs,targetMs,thermal,battery,mode)
            hands?.let{it.intervalMs=q.handIntervalMs;it.inputWidth=q.handWidth;it.enabled=q.handsAllowed}
            NativeBridge.settings(handle,q.renderScale,prefs.getBoolean("curved",true),2.4f)
            if(start-lastStats>1_000_000_000){
                lastStats=start
                shell.cameraActive=frame?.active==true
                shell.headline=when(experience.active){Experience.MR->"MR • CÂMERA ATIVA";Experience.VR->"VR • ${if(shell.cameraActive)"CÂMERA: MÃOS" else "CÂMERA DESLIGADA"}";else->"MR INDISPONÍVEL • ESPAÇO SEGURO"}
                shell.detail=if(frame?.active==true)"${feed?.name} • ${if(frame.tracking)"6DoF" else "3DoF"} • ${if(sample!=null)"mão detectada" else "olhar + toque"}" else experience.reason
                refreshPages()
            }
            shell.hovered=hover;shell.build(dt,start)
            NativeBridge.spatial(handle,shell.packet,shell.count,px,py,sample!=null)
            NativeBridge.camera(handle,textures[3],when(experience.active){Experience.MR->1;Experience.VR->2;else->0},frame?.projection,frame?.textureTransform)
            hover=NativeBridge.draw(handle,textures[0],textures[1],textures[2],transform,capturing,frame?.pose,(targetMs*1_000_000).toLong())
            renderMs=(SystemClock.elapsedRealtimeNanos()-start)/1e6f
        }catch(e: Exception){fatal(e)}
    }
    private fun select(){if(handle==0L||!::shell.isInitialized)return;if(!shell.select(hover,SystemClock.elapsedRealtimeNanos()))score=NativeBridge.select(handle)}
    private fun openDock(item: DockItem){
        when(item){
            DockItem.MR->{experience.request(Experience.MR);startCamera();if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)runOnUiThread{cameraPermission.launch(Manifest.permission.CAMERA)}}
            DockItem.VR->{experience.request(Experience.VR);loadEnvironment(prefs.getString("environment","loft")!!);if(!prefs.getBoolean("hands",true))stopCamera()}
            else->{val kind=when(item){DockItem.HOME->WindowKind.HOME;DockItem.LIBRARY,DockItem.RECENTS->WindowKind.LIBRARY;DockItem.STORE->WindowKind.STORE;DockItem.SETTINGS->WindowKind.SETTINGS;DockItem.BROWSER->WindowKind.BROWSER;DockItem.ENVIRONMENTS->WindowKind.ENVIRONMENTS;DockItem.CAPTURE->WindowKind.CAPTURE;DockItem.NOTIFICATIONS->WindowKind.NOTIFICATIONS;DockItem.PERFORMANCE->WindowKind.DIAGNOSTICS;DockItem.TRACKING->WindowKind.TRACKING;else->WindowKind.SYSTEM};shell.windows.spawn(kind);refreshPages()}
        }
    }
    private fun refreshPages(){
        if(!::shell.isInitialized)return
        fun action(s: String,enabled: Boolean=true,block: ()->Unit)=SpatialAction(s,enabled){try{block()}catch(e: Exception){notify(e.message ?: e.javaClass.simpleName,true)}}
        fun info(s: String)=SpatialAction(s,false){}
        fun main(block: ()->Unit){runOnUiThread{try{block()}catch(e: Exception){notify(e.message ?: "Ação indisponível",true)}}}
        shell.pages[WindowKind.HOME]=listOf(action("Centralizar"){NativeBridge.recenter(handle);feed?.recenter();shell.windows.recenter()},action("Colocar objeto\nem plano real",cameraFrame?.tracking==true){notify(if(feed?.placeAtCenter()==true)"Âncora de sessão criada no plano" else "Aponte para um plano detectado")},action("Órbita • jogar"){scene=1;NativeBridge.scene(handle,scene);shell.windows.focus?.let{shell.windows.minimize(it)}},action("Reflexo • jogar"){scene=2;NativeBridge.scene(handle,scene)},action("Constelação"){scene=3;NativeBridge.scene(handle,scene)},action("Sair do jogo"){scene=0;score=0;NativeBridge.scene(handle,0)},action("Assistente local"){main{startActivity(Intent(this,dev.trackmr.ai.AssistantActivity::class.java))}},info("Passthrough monocular\nUse sentado"))
        val favorites=prefs.getStringSet("favorites",emptySet())!!.toSet()
        val apps=installed.filter{!filterFavorites||it.component.packageName in favorites}
        val chunk=apps.drop(libraryPage*4).take(4)
        shell.pages[WindowKind.LIBRARY]=chunk.map{app->action(app.label+"\nAbrir no Android"){val rec=prefs.getStringSet("recent",emptySet())!!.toMutableSet();rec+=app.component.packageName;prefs.edit().putStringSet("recent",rec.toList().takeLast(20).toSet()).apply();main{startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component))}}}+listOf(action("Página seguinte"){libraryPage=if((libraryPage+1)*4>=apps.size)0 else libraryPage+1;refreshPages()},action(if(filterFavorites)"Mostrar todos" else "Favoritos"){filterFavorites=!filterFavorites;libraryPage=0;refreshPages()},action("Favoritar página"){prefs.edit().putStringSet("favorites",favorites+chunk.map{it.component.packageName}).apply();refreshPages()},info("Apps externos saem do XR\nNão são capturas interativas"))
        shell.pages[WindowKind.STORE]=Catalog.entries.drop(storePage*6).take(6).map{e->action("${e.category}\n${e.title}"){if(e.game>0){scene=e.game;NativeBridge.scene(handle,scene)}else main{Catalog.openLink(this,e.url!!)}}}+listOf(action("Mais categorias"){storePage=if(storePage==0)1 else 0;refreshPages()},info("Catálogo local\nSem feed de APKs confiável"))
        shell.pages[WindowKind.SETTINGS]=listOf(action("Mover dock\nPinça + movimento"){shell.movingDock=!shell.movingDock;shell.movingWindow=false},action("Escala do dock +"){shell.settings.scale+=.1f;shell.save()},action("Distância do dock +"){shell.settings.distance+=.15f;if(shell.settings.distance>3)shell.settings.distance=.9f;shell.save()},action("Transparência"){shell.settings.opacity=if(shell.settings.opacity>.7f).5f else .95f;shell.save()},action("Movimento reduzido"){shell.settings.reducedMotion=!shell.settings.reducedMotion;shell.save()},action("Perfil de qualidade"){val q=QualityMode.valueOf(prefs.getString("quality","BALANCED")!!);prefs.edit().putString("quality",QualityMode.entries[(q.ordinal+1)%3].name).apply()},action("QR das lentes"){main{NativeBridge.scan()}},action("Reiniciar disposição"){shell.settings.x=0f;shell.settings.y=-.4f;shell.settings.scale=1f;shell.settings.distance=1.6f;shell.windows.recenter();shell.save()})
        shell.pages[WindowKind.ENVIRONMENTS]=listOf(action("Horizonte dourado"){loadEnvironment("loft");experience.request(Experience.VR)},action("Noite violeta"){loadEnvironment("neon");experience.request(Experience.VR)},action("Voltar a MR"){openDock(DockItem.MR)},info("Panoramas reais do projeto\nSem profundidade"))
        shell.pages[WindowKind.BROWSER]=listOf(action("Wolvic / WebXR"){main{Catalog.openLink(this,"https://wolvic.com/")}},action("WebXR samples"){main{Catalog.openLink(this,"https://immersive-web.github.io/webxr-samples/")}},info("WebXR depende de browser\ne runtime compatíveis"))
        shell.pages[WindowKind.CAPTURE]=listOf(action("Compartilhar um app"){main{captureConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())}},action("Parar captura"){main{stopService(Intent(this,CaptureService::class.java))};shell.surfaceKind=null},action("Conectar Shizuku"){main{shizuku.requestOrBind()}},info("${shizuku.status()}"),info("Sem injeção de toque\nDRM permanece protegido"))
        shell.pages[WindowKind.TRACKING]=listOf(info(hands?.status ?: "Mãos desligadas"),action(if(prefs.getBoolean("hands",true))"Desligar mãos" else "Ligar mãos"){prefs.edit().putBoolean("hands",!prefs.getBoolean("hands",true)).apply();safeMode=false;stopCamera();startCamera()},info("Planos: ${cameraFrame?.planes ?: 0}\nÂncoras: ${cameraFrame?.anchors ?: 0}"),info("${feed?.name ?: "Sem câmera"}\n${if(cameraFrame?.tracking==true)"6DoF válido" else "Sem posição métrica"}"),action("Reconectar câmera"){stopCamera();startCamera()},info("Pinça: selecionar\nPalma aberta: dock"),info("2 pinças: escala/giro\nMOVER: arrastar janela"))
        val sample=hands?.latest?.get();val ram=ActivityManager.MemoryInfo().also{getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
        shell.pages[WindowKind.DIAGNOSTICS]=listOf(info("p95 ${"%.1f".format(stats.percentile(.95f))} ms\n${"%.0f".format(1000/lastFrameMs.coerceAtLeast(1f))} callbacks/s"),info("Render CPU ${"%.1f".format(renderMs)} ms\nGPU/display: não medidos"),info("Inferência ${sample?.inferenceMs?.let{"%.1f".format(it)} ?: "—"} ms\nFiltro ${sample?.filterMs?.let{"%.1f".format(it)} ?: "—"} ms"),info("Térmico $thermal\nEscala ${"%.0f".format(quality.quality.renderScale*100)}%"),info("RAM livre ${ram.availMem/1048576} MiB\nE2E: requer medição externa"),info("${width}×$height\n${"%.0f".format(1000/targetMs)} Hz"),info("Frames de câmera pulados\n${hands?.dropped?.get() ?: 0}"),info("${feed?.name ?: "Sem backend"}\nTelemetria desligada"))
        shell.pages[WindowKind.NOTIFICATIONS]=notices.snapshot().takeLast(8).reversed().map{info((if(it.error)"ERRO\n" else "")+it.message)}.ifEmpty{listOf(info("Sem notificações"))}
        shell.pages[WindowKind.SYSTEM]=listOf(action("Autorizar câmera"){main{cameraPermission.launch(Manifest.permission.CAMERA)}},action("Desligar câmera"){stopCamera();experience.camera(CameraState.STOPPED,"Câmera desligada pelo usuário")},info("Microfone: não utilizado\nTelemetria: OFF"),action("Runtime OpenXR"){main{val intent=packageManager.getLaunchIntentForPackage("dev.trackmr.runtime") ?: error("Instale o Runtime Companion");startActivity(intent)}},action("Configuração do app"){main{startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}},action("Sair com segurança"){shell.save();main{finish()}},info("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}"))
    }
    private fun loadEnvironment(id: String){
        prefs.edit().putString("environment",id).apply();val epoch=generation
        io.execute{try{val image=assets.open("environments/$id.jpg").use{BitmapFactory.decodeStream(it)};view.queueEvent{try{if(epoch==generation){GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[0]);GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,image,0)}}finally{image.recycle()}}}catch(e: Exception){notify("Ambiente: ${e.message}",true)}}
    }
    private fun attachCapture(){val surface=captureSurface ?: return;if(capturing)return;capturing=captureService?.attach(surface,1280,720)==true;if(capturing)view.queueEvent{shell.windows.spawn(WindowKind.CAPTURE);shell.surfaceKind=WindowKind.CAPTURE}}
    private fun notify(message: String,error: Boolean=false){notices.add(SystemClock.elapsedRealtimeNanos(),message,error);if(error)android.util.Log.e("TrackMR",message)}
    private fun fatal(e: Exception){failed=true;notify(e.message ?: e.javaClass.simpleName,true);runOnUiThread{android.app.AlertDialog.Builder(this).setTitle("Falha no renderer XR").setMessage("${e.javaClass.simpleName}: ${e.message}").setPositiveButton("Encerrar"){_,_->finish()}.setCancelable(false).show()}}
    override fun onKeyDown(code: Int,event: KeyEvent): Boolean {if(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_BUTTON_A){if(event.repeatCount==0)view.queueEvent{select()};return true};return super.onKeyDown(code,event)}
    override fun onDestroy(){
        power.removeThermalStatusListener(thermalListener);shizuku.close()
        captureService?.onResize=null;captureService?.onStopped=null;if(bound)unbindService(captureConnection);stopService(Intent(this,CaptureService::class.java))
        view.queueEvent{stopCamera();atlas?.close();captureSurface?.release();captureTexture?.release();if(handle!=0L){NativeBridge.destroy(handle);handle=0}}
        io.shutdown();super.onDestroy()
    }
}
