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
import dev.trackmr.ai.LocalAssistant
import dev.trackmr.audio.SpatialAudio
import dev.trackmr.browser.SpatialBrowser
import dev.trackmr.diagnostics.SpatialScreenshot
import dev.trackmr.tracking.NeuralGovernor
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
    private val textures=IntArray(5)
    private val transform=FloatArray(16).apply{Matrix.setIdentityM(this,0)}
    private var handle=0L
    private var atlas: SpatialAtlas?=null
    private lateinit var assistant: LocalAssistant
    private lateinit var audio: SpatialAudio
    private var browser: SpatialBrowser?=null
    private var neural: NeuralGovernor?=null
    private var advisedScale=1f
    private var lastDepthNs=0L
    private val hitUv=FloatArray(2)
    private var screenshotConfirmUntil=0L
    private var importTarget="assistant"
    private var appQuery=""
    private var storeQuery=""
    private var filterRecent=false
    private var advancedSettings=false
    private var selectedApp: Installed?=null
    private var selectedStore: dev.trackmr.store.CatalogEntry?=null
    private var assistantPage=0
    private var gpuMs=-1f
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
    private var cameraAllowed=true
    private var preferCamera2=false
    private var battery=100
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
    private val importDocument=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            if(importTarget=="assistant")assistant.importModel(uri) else io.execute{
                val result=runCatching{
                    val bytes=contentResolver.openInputStream(uri)!!.use{source->val sink=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(sink.size()<=1048576){val n=source.read(buffer);if(n<0)break;sink.write(buffer,0,n)};sink.toByteArray()}
                    require(bytes.size in 100..1048576){"Modelo deve ter até 1 MiB"}
                    val file=java.io.File(filesDir,"performance.import").apply{writeBytes(bytes)}
                    org.tensorflow.lite.Interpreter(file).use{require(it.getInputTensor(0).shape().contentEquals(intArrayOf(1,5)));require(it.getOutputTensor(0).shape().contentEquals(intArrayOf(1,2)));require(it.getInputTensor(0).dataType()==org.tensorflow.lite.DataType.FLOAT32);require(it.getOutputTensor(0).dataType()==org.tensorflow.lite.DataType.FLOAT32)}
                    view.queueEvent{runCatching{neural?.close();check(file.renameTo(java.io.File(filesDir,"performance.tflite")));neural=NeuralGovernor(this);notify("Modelo neural importado; habilite em Ajustes avançados")}.onFailure{file.delete();notify("Modelo neural: ${it.message}",true)}}
                }
                result.exceptionOrNull()?.let{notify("Modelo inválido: ${it.message}",true)}
            }
        }
    }
    private val openXrSession=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val report=result.data?.getStringExtra("report") ?: "Sessão OpenXR encerrada sem relatório"
        notify(report.lines().lastOrNull{it.startsWith("ERROR:")} ?: report.takeLast(350),report.contains("ERROR:"))
        view.queueEvent{if(::shell.isInitialized)shell.windows.spawn(WindowKind.NOTIFICATIONS)}
    }
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        permissionAsked=true
        view.queueEvent{if(granted&&resumed)startCamera()else{experience.camera(CameraState.DENIED,"Permissão de câmera negada");notify("MR indisponível sem autorização da câmera",true)}}
    }
    private val captureConsent=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        if(result.resultCode==Activity.RESULT_OK&&result.data!=null){
            try{stopContent();val intent=Intent(this,CaptureService::class.java).putExtra("consent",result.data);startForegroundService(intent);bound=bindService(intent,captureConnection,Context.BIND_AUTO_CREATE)}
            catch(e: Exception){notify("Captura: ${e.message}",true)}
        }else notify("Captura cancelada pelo usuário")
    }
    private val captureConnection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?,binder: IBinder?){
            if(!bound||!resumed)return
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
        assistant=LocalAssistant(applicationContext){notify(it.take(160))};audio=SpatialAudio(applicationContext)
        val unclean=prefs.getInt("unclean",0);safeMode=RecoveryPolicy(unclean).safeMode
        prefs.edit().putInt("unclean",unclean+1).apply()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        stopContent()
        view.queueEvent{stopCamera();if(handle!=0L)NativeBridge.pause(handle);if(::shell.isInitialized){shell.surfaceKind=null;shell.save()}}
        view.onPause();super.onPause()
        if(!failed)prefs.edit().putInt("unclean",0).apply()
    }
    private fun startCamera(){
        if(!resumed||!cameraAllowed||textures[3]==0||feed!=null)return
        val needsCamera=experience.requested!=Experience.VR||(!safeMode&&prefs.getBoolean("hands",true))
        if(!needsCamera)return
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){experience.camera(CameraState.DENIED,"Autorize a câmera em Sistema");return}
        hands=if(!safeMode&&prefs.getBoolean("hands",true))HandTracker(applicationContext,prefs.getBoolean("handsGpu",false))else null
        val primary=ArCameraFeed(this,hands,prefs.getBoolean("depth",false))
        feed=if(!preferCamera2&&primary.start(textures[3],(width/2).coerceAtLeast(1),height)){primary}else{
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
            GLES30.glGenTextures(5,textures,0)
            for(i in 0..4){val target=if(i<2||i==4)GLES30.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                GLES30.glBindTexture(target,textures[i]);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE)}
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[4]);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_R16UI,1,1,0,GLES30.GL_RED_INTEGER,GLES30.GL_UNSIGNED_SHORT,java.nio.ByteBuffer.allocateDirect(2));lastDepthNs=0
            neural?.close();neural=NeuralGovernor(this)
            atlas?.close();atlas=SpatialAtlas(textures[1]);shell=SpatialShell(prefs,atlas!!).apply{onDock={openDock(it)};if(!safeMode)restore()}
            val blank=android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888);blank.eraseColor(0xff171d31.toInt());GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[0]);GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,blank,0);blank.recycle()
            val oldSurface=captureSurface;val oldTexture=captureTexture
            if(oldTexture!=null)runOnUiThread{stopContent();oldSurface?.release();oldTexture.release()}
            capturing=false
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
            var frame=cameraFrame
            if(feed is ArCameraFeed&&frame?.active!=true&&start-cameraStarted>2_000_000_000){
                notify("ARCore sem imagens: alternando para Camera2",true);stopCamera();preferCamera2=true;startCamera();frame=null
            }
            if(frame?.active==true)experience.camera(CameraState.ACTIVE,feed!!.status)
            else if(feed!=null&&start-cameraStarted>5_000_000_000)experience.camera(CameraState.ERROR,feed!!.status)
            val batch=hands?.latest?.get()
            val sample=batch?.hands?.firstOrNull()?.takeIf{SampleFreshness.usable(start-it.timestampNs,batch.preprocessMs+batch.inferenceMs+batch.filterMs)}
            var px=.5f;var py=.5f
            if(sample!=null){
                val prediction=minOf((start-sample.timestampNs)/1e9f,.018f)
                px=(sample.points[24]+sample.velocity[24]*prediction).coerceIn(0f,1f);py=(sample.points[25]+sample.velocity[25]*prediction).coerceIn(0f,1f)
                if(batch!!.timestampNs!=lastHandNs){lastHandNs=batch.timestampNs;batch.events.forEach{e->
                    if(e.kind==GestureKind.PINCH)select()else if(e.kind==GestureKind.SCROLL&&shell.surfaceKind==WindowKind.BROWSER)runOnUiThread{browser?.scroll(e.y)}else shell.gesture(e)
                }}
            }
            val fillMr=prefs.getBoolean("fillMr",true)
            NativeBridge.viewOptions(handle,fillMr)
            // Explicit camera overlay, not a metric hand or opaque fake hand mesh.
            val overlay=if(sample!=null&&prefs.getBoolean("handOverlay",true))batch!!.hands.flatMap{it.points.asList()}.toFloatArray() else null
            NativeBridge.hands(handle,overlay)
            if(capturing&&shell.surfaceKind!=null&&shell.windows.windows.none{it.kind==shell.surfaceKind&&!it.minimized}){
                capturing=false;shell.surfaceKind=null;runOnUiThread{stopContent()}
            }
            if(capturing&&captured.getAndSet(false)){captureTexture?.updateTexImage();captureTexture?.getTransformMatrix(transform)}
            val mode=runCatching{QualityMode.valueOf(prefs.getString("quality","QUALITY")!!)}.getOrDefault(QualityMode.BALANCED)
            val q=quality.update(lastFrameMs,targetMs,thermal,battery,mode)
            hands?.let{it.intervalMs=q.handIntervalMs;it.inputWidth=q.handWidth;it.enabled=q.handsAllowed}
            NativeBridge.settings(handle,minOf(q.renderScale,advisedScale),prefs.getBoolean("curved",true),2.4f)
            if(thermal>=5&&feed!=null){stopCamera();frame=null;cameraAllowed=false;notify("Câmera desligada por temperatura crítica. Reative após resfriar.",true)}
            if(frame?.depthData!=null&&frame.depthTimestampNs!=lastDepthNs){
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[4]);GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,1)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_R16UI,frame.depthWidth,frame.depthHeight,0,GLES30.GL_RED_INTEGER,GLES30.GL_UNSIGNED_SHORT,frame.depthData!!.apply{rewind()});lastDepthNs=frame.depthTimestampNs
            }
            NativeBridge.depth(handle,textures[4],!fillMr&&frame?.depthData!=null&&start-frame.depthTimestampNs in 0L..150_000_000L,frame?.depthTransform)
            NativeBridge.anchors(handle,feed?.anchorPositions() ?: FloatArray(0),frame?.light ?: 1f)
            if(start-lastStats>1_000_000_000){
                lastStats=start
                battery=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                shell.cameraActive=frame?.active==true
                shell.headline=when(experience.active){Experience.MR->"MR • CÂMERA ATIVA";Experience.VR->"VR • ${if(shell.cameraActive)"CÂMERA: MÃOS" else "CÂMERA DESLIGADA"}";else->"MR INDISPONÍVEL • ESPAÇO SEGURO"}
                shell.detail=if(frame?.active==true)"${feed?.name} • ${if(frame.tracking)"6DoF" else "3DoF"} • ${if(sample!=null)"mão detectada" else "olhar + toque"}" else experience.reason
                gpuMs=NativeBridge.gpuTime(handle)
                advisedScale=if(prefs.getBoolean("neural",false))neural?.advise(stats.percentile(.5f),batch?.inferenceMs ?: 0f,thermal,battery/100f,q.renderScale) ?: q.renderScale else q.renderScale
                refreshPages()
            }
            shell.hovered=hover;shell.build(dt,start)
            NativeBridge.spatial(handle,shell.packet,shell.count,px,py,sample!=null)
            NativeBridge.camera(handle,textures[3],when(experience.active){Experience.MR->1;Experience.VR->2;else->0},frame?.projection,frame?.textureTransform)
            hover=NativeBridge.draw(handle,textures[0],textures[1],textures[2],transform,capturing,frame?.pose,(targetMs*1_000_000).toLong())
            renderMs=(SystemClock.elapsedRealtimeNanos()-start)/1e6f
        }catch(e: Exception){fatal(e)}
    }
    private fun select(){
        if(handle==0L||!::shell.isInitialized)return
        if(prefs.getBoolean("sound",false))audio.select()
        if(hover==8000+WindowKind.BROWSER.ordinal){NativeBridge.hitPoint(handle,hitUv);val u=hitUv[0];val v=hitUv[1];runOnUiThread{browser?.tap(u,v)};return}
        if(hover==8000+WindowKind.CAPTURE.ordinal){notify("Captura é somente visual. Não injeta toque em outros apps.");return}
        if(!shell.select(hover,SystemClock.elapsedRealtimeNanos()))score=NativeBridge.select(handle)
    }
    private fun openDock(item: DockItem){
        when(item){
            DockItem.MR->{cameraAllowed=true;experience.request(Experience.MR);startCamera();if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)runOnUiThread{cameraPermission.launch(Manifest.permission.CAMERA)}}
            DockItem.VR->{experience.request(Experience.VR);loadEnvironment(prefs.getString("environment","loft")!!);if(!prefs.getBoolean("hands",true))stopCamera()}
            else->{if(item==DockItem.RECENTS||item==DockItem.LIBRARY){filterRecent=item==DockItem.RECENTS;libraryPage=0};val kind=when(item){DockItem.HOME->WindowKind.HOME;DockItem.LIBRARY,DockItem.RECENTS->WindowKind.LIBRARY;DockItem.STORE->WindowKind.STORE;DockItem.SETTINGS->WindowKind.SETTINGS;DockItem.BROWSER->WindowKind.BROWSER;DockItem.ENVIRONMENTS->WindowKind.ENVIRONMENTS;DockItem.CAPTURE->WindowKind.CAPTURE;DockItem.NOTIFICATIONS->WindowKind.NOTIFICATIONS;DockItem.PERFORMANCE->WindowKind.DIAGNOSTICS;DockItem.TRACKING->WindowKind.TRACKING;else->WindowKind.SYSTEM};shell.windows.spawn(kind);refreshPages()}
        }
    }
    private fun refreshPages(){
        if(!::shell.isInitialized)return
        fun action(s: String,enabled: Boolean=true,block: ()->Unit)=SpatialAction(s,enabled){try{block()}catch(e: Exception){notify(e.message ?: e.javaClass.simpleName,true)}}
        fun info(s: String)=SpatialAction(s,false){}
        fun main(block: ()->Unit){runOnUiThread{try{block()}catch(e: Exception){notify(e.message ?: "Ação indisponível",true)}}}
        shell.pages[WindowKind.HOME]=listOf(action("Centralizar"){NativeBridge.recenter(handle);feed?.recenter();shell.windows.recenter()},action("Colocar objeto\nem plano real",cameraFrame?.tracking==true){notify(if(feed?.placeAtCenter()==true)"Âncora de sessão criada no plano" else "Aponte para um plano detectado")},action("Órbita • jogar"){scene=1;NativeBridge.scene(handle,scene);shell.windows.focus?.let{shell.windows.minimize(it)}},action("Reflexo • jogar"){scene=2;NativeBridge.scene(handle,scene)},action("Constelação"){scene=3;NativeBridge.scene(handle,scene)},action("Sair do jogo"){scene=0;score=0;NativeBridge.scene(handle,0)},action("Assistente local"){shell.windows.spawn(WindowKind.ASSISTANT)},info("Passthrough monocular\nUse sentado"))
        val favorites=prefs.getStringSet("favorites",emptySet())!!.toSet()
        val recent=prefs.getString("recentOrdered","")!!.split('|')
        val pinned=prefs.getStringSet("pinnedApps",emptySet())!!.toSet()
        val apps=installed.filter{(!filterFavorites||it.component.packageName in favorites)&&(!filterRecent||it.component.packageName in recent)&&it.label.contains(appQuery,true)}.sortedWith(compareBy<Installed>{if(it.component.packageName in pinned)0 else 1}.thenBy{if(filterRecent)recent.indexOf(it.component.packageName)else 0})
        val chunk=apps.drop(libraryPage*4).take(4)
        shell.pages[WindowKind.LIBRARY]=chunk.map{app->action(app.label){selectedApp=app;shell.windows.spawn(WindowKind.APP_DETAIL);refreshPages()}}+listOf(
            action("Página seguinte"){libraryPage=if((libraryPage+1)*4>=apps.size)0 else libraryPage+1;refreshPages()},
            action(if(filterFavorites)"Mostrar todos" else "Favoritos"){filterFavorites=!filterFavorites;libraryPage=0;refreshPages()},
            action("Pesquisar apps"){shell.keyboard(appQuery){appQuery=it;libraryPage=0;refreshPages()}},
            info(if(filterRecent)"Apps recentes" else "${apps.size} apps instalados"))
        selectedApp?.let{app->
            shell.pages[WindowKind.APP_DETAIL]=listOf(action("Abrir no Android"){val name=app.component.packageName;val ordered=(listOf(name)+recent.filter{it!=name&&it.isNotBlank()}).take(20);prefs.edit().putString("recentOrdered",ordered.joinToString("|")).apply();main{startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component))}},
                action(if(app.component.packageName in favorites)"Remover favorito" else "Favoritar"){val set=favorites.toMutableSet();if(!set.add(app.component.packageName))set.remove(app.component.packageName);prefs.edit().putStringSet("favorites",set).apply();refreshPages()},
                action("Fixar / desafixar"){val set=pinned.toMutableSet();if(!set.add(app.component.packageName))set.remove(app.component.packageName);prefs.edit().putStringSet("pinnedApps",set).apply()},
                action("Permissões / sistema"){main{startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.component.packageName}")))}},
                action("Desinstalar • confirmar"){main{startActivity(Intent(Intent.ACTION_DELETE,Uri.parse("package:${app.component.packageName}")))}},info("${app.label}\nLançamento externo, não VR"))
        }
        val entries=Catalog.entries.filter{it.title.contains(storeQuery,true)||it.category.contains(storeQuery,true)}.sortedByDescending{prefs.getInt("uses-${it.title}",0)}
        shell.pages[WindowKind.STORE]=entries.drop(storePage*4).take(4).map{e->action("${e.category}\n${e.title}"){selectedStore=e;shell.windows.spawn(WindowKind.STORE_DETAIL);refreshPages()}}+listOf(action("Próxima página"){storePage=if((storePage+1)*4>=entries.size)0 else storePage+1;refreshPages()},action("Busca / categoria"){shell.keyboard(storeQuery){storeQuery=it;storePage=0;refreshPages()}},info("Recomendações locais\nSem telemetria"),info("Sem feed de APKs confiável\nInstalação automática: OFF"))
        selectedStore?.let{e->shell.pages[WindowKind.STORE_DETAIL]=listOf(info(e.title),info(e.subtitle.chunked(22).take(4).joinToString("\n")),info(if(e.game>0)"Incluído • gratuito\nVersão ${dev.trackmr.BuildConfig.VERSION_NAME}" else "Projeto externo\nCompatibilidade não garantida"),action(if(e.game>0)"Jogar" else "Projeto oficial"){prefs.edit().putInt("uses-${e.title}",prefs.getInt("uses-${e.title}",0)+1).apply();if(e.game>0){scene=e.game;NativeBridge.scene(handle,scene)}else main{Catalog.openLink(this,e.url!!)}},info("Trailer / screenshots\nNão fornecidos pelo catálogo"))}
        shell.pages[WindowKind.SETTINGS]=listOf(action("Mover dock\nPinça + movimento"){shell.movingDock=!shell.movingDock;shell.movingWindow=false},action("Escala do dock +"){shell.settings.scale+=.1f;shell.save()},action("Distância do dock +"){shell.settings.distance+=.15f;if(shell.settings.distance>3)shell.settings.distance=.9f;shell.save()},action("Transparência"){shell.settings.opacity=if(shell.settings.opacity>.7f).5f else .95f;shell.save()},action("Movimento reduzido"){shell.settings.reducedMotion=!shell.settings.reducedMotion;shell.save()},action("Perfil de qualidade"){val q=QualityMode.valueOf(prefs.getString("quality","QUALITY")!!);prefs.edit().putString("quality",QualityMode.entries[(q.ordinal+1)%3].name).apply()},action("QR das lentes"){main{NativeBridge.scan()}},action("Mais ajustes"){advancedSettings=!advancedSettings;refreshPages()})
        if(advancedSettings)shell.pages[WindowKind.SETTINGS]=listOf(action("Sons de seleção"){prefs.edit().putBoolean("sound",!prefs.getBoolean("sound",false)).apply()},action("Alto contraste"){shell.highContrast(!prefs.getBoolean("contrast",false))},action("UI grande"){shell.settings.scale=if(shell.settings.scale>1.2f)1f else 1.4f;shell.save()},action("Importar policy neural"){main{importTarget="neural";importDocument.launch(arrayOf("*/*"))}},action("Consultor neural ON/OFF"){prefs.edit().putBoolean("neural",!prefs.getBoolean("neural",false)).apply()},action("Snap janela à esquerda"){shell.windows.focus?.let{shell.windows.snap(it,-1)};shell.save()},action("Reiniciar disposição"){shell.settings.x=0f;shell.settings.y=-.4f;shell.settings.scale=1f;shell.settings.distance=1.6f;shell.windows.recenter();shell.save()},action("Ajustes principais"){advancedSettings=false;refreshPages()})
        shell.pages[WindowKind.ENVIRONMENTS]=listOf(action("Horizonte dourado"){loadEnvironment("loft");experience.request(Experience.VR);if(!prefs.getBoolean("hands",true))stopCamera()},action("Noite violeta"){loadEnvironment("neon");experience.request(Experience.VR);if(!prefs.getBoolean("hands",true))stopCamera()},action("Voltar a MR"){openDock(DockItem.MR)},info("Panoramas reais do projeto\nSem profundidade"))
        shell.pages[WindowKind.BROWSER]=listOf(action("Abrir página HTTPS"){shell.keyboard(prefs.getString("browserUrl","https://example.org")!!){url->main{startBrowser(url)}}},action("Voltar na página"){main{browser?.back()}},action("Digitar no campo web"){shell.keyboard{value->main{browser?.text(value)}}},action("Fechar página"){main{stopContent()};shell.surfaceKind=null},action("Wolvic / WebXR externo"){main{Catalog.openLink(this,"https://wolvic.com/")}},info("WebView privado espacial\nSem promessa de WebXR"))
        val answer=assistant.answer.chunked(80)
        shell.pages[WindowKind.ASSISTANT]=listOf(action(if(assistant.busy.get())"Processando localmente" else "Nova pergunta",!assistant.busy.get()){shell.keyboard{question->assistant.ask(question)}},action("Importar modelo",!assistant.busy.get()){main{importTarget="assistant";importDocument.launch(arrayOf("*/*"))}},action("Ler próxima parte"){assistantPage=if(assistantPage+1>=answer.size)0 else assistantPage+1;refreshPages()},info("${assistantPage+1}/${answer.size.coerceAtLeast(1)} • sem nuvem"))+answer.drop(assistantPage).take(4).map{info(it.chunked(22).joinToString("\n"))}
        shell.pages[WindowKind.CAPTURE]=listOf(action("Compartilhar um app"){main{stopContent();captureConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())}},action("Parar captura"){main{stopContent()};shell.surfaceKind=null},action("Conectar Shizuku"){main{shizuku.requestOrBind()}},info("${shizuku.status()}"),info("Sem injeção de toque\nDRM permanece protegido"))
        shell.pages[WindowKind.TRACKING]=listOf(info(hands?.status ?: "Mãos desligadas"),action(if(prefs.getBoolean("hands",true))"Desligar mãos" else "Ligar mãos"){prefs.edit().putBoolean("hands",!prefs.getBoolean("hands",true)).apply();safeMode=false;stopCamera();startCamera()},info("Planos: ${cameraFrame?.planes ?: 0}\nÂncoras: ${cameraFrame?.anchors ?: 0}"),action(if(prefs.getBoolean("depth",false))"Desligar oclusão depth" else "Oclusão depth opcional",cameraFrame?.depthAvailable==true){prefs.edit().putBoolean("depth",!prefs.getBoolean("depth",false)).apply();stopCamera();startCamera()},action("Reconectar câmera"){cameraAllowed=true;preferCamera2=false;stopCamera();startCamera()},action(if(prefs.getBoolean("handsGpu",false))"Backend: GPU → CPU" else "Backend: CPU → GPU"){prefs.edit().putBoolean("handsGpu",!prefs.getBoolean("handsGpu",false)).apply();stopCamera();startCamera()},action(if(prefs.getBoolean("handOverlay",true))"Ocultar esqueleto" else "Mostrar esqueleto"){prefs.edit().putBoolean("handOverlay",!prefs.getBoolean("handOverlay",true)).apply()})
        val sample=hands?.latest?.get();val ram=ActivityManager.MemoryInfo().also{getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
        shell.pages[WindowKind.DIAGNOSTICS]=listOf(info("p95 ${"%.1f".format(stats.percentile(.95f))} ms\n${"%.0f".format(1000/lastFrameMs.coerceAtLeast(1f))} callbacks/s"),info("CPU frame ${"%.1f".format(renderMs)} ms\nGPU ${if(gpuMs<0)"N/D" else "%.1f ms".format(gpuMs)}"),info("Inferência ${sample?.inferenceMs?.let{"%.1f".format(it)} ?: "—"} ms\nFiltro ${sample?.filterMs?.let{"%.1f".format(it)} ?: "—"} ms"),info("Térmico $thermal\nEscala ${"%.0f".format(quality.quality.renderScale*100)}%"),info("RAM livre ${ram.availMem/1048576} MiB\nE2E: requer medição externa"),info("${width}×$height\n${"%.0f".format(1000/targetMs)} Hz"),info("Frames de câmera pulados\n${hands?.dropped?.get() ?: 0}"),info("Pré ${sample?.preprocessMs?.let{"%.1f".format(it)} ?: "—"} ms\nChegada ${if(sample?.clockKnown==true)((sample.receivedNs-sample.sensorTimestampNs)/1e6).toInt().toString()+" ms" else "N/D"}"))
        shell.pages[WindowKind.NOTIFICATIONS]=notices.snapshot().takeLast(8).reversed().map{info((if(it.error)"ERRO\n" else "")+it.message)}.ifEmpty{listOf(info("Sem notificações"))}
        shell.pages[WindowKind.SYSTEM]=listOf(action("Autorizar câmera"){main{cameraPermission.launch(Manifest.permission.CAMERA)}},action("Desligar câmera"){cameraAllowed=false;stopCamera();experience.camera(CameraState.STOPPED,"Câmera desligada pelo usuário")},action(if(SystemClock.elapsedRealtimeNanos()<screenshotConfirmUntil)"CONFIRMAR screenshot" else "Screenshot (inclui MR)"){
            val now=SystemClock.elapsedRealtimeNanos();if(now<screenshotConfirmUntil){screenshotConfirmUntil=0;main{SpatialScreenshot.capture(this,view,io){notify(it)}}}else{screenshotConfirmUntil=now+5_000_000_000;notify("A imagem incluirá o passthrough. Toque de novo em até 5 s para confirmar.");refreshPages()}
        },action("Sessão OpenXR real"){main{openXrSession.launch(Intent(this,dev.trackmr.openxr.SessionActivity::class.java))}},action("Configuração do app"){main{startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}},action("Sair com segurança"){shell.save();main{finish()}},action(if(prefs.getBoolean("fillMr",true))"MR: preencher → óptico" else "MR: óptico → preencher"){prefs.edit().putBoolean("fillMr",!prefs.getBoolean("fillMr",true)).apply();notify("Preencher amplia a câmera para cada olho, sem adicionar campo de visão real. Depth exige modo óptico.")})
    }
    private fun startBrowser(url: String){
        require(Uri.parse(url).scheme=="https"&&!Uri.parse(url).host.isNullOrBlank()){"Use uma URL HTTPS válida"}
        stopContent()
        browser=SpatialBrowser(this){notify(it,true)}
        captureTexture?.setDefaultBufferSize(1280,720)
        browser!!.open(captureSurface ?: error("Surface indisponível"),url)
        prefs.edit().putString("browserUrl",url).apply();capturing=true
        view.queueEvent{shell.windows.spawn(WindowKind.BROWSER);shell.surfaceKind=WindowKind.BROWSER}
    }
    private fun loadEnvironment(id: String){
        prefs.edit().putString("environment",id).apply();val epoch=generation
        io.execute{try{val image=assets.open("environments/$id.jpg").use{BitmapFactory.decodeStream(it)};view.queueEvent{try{if(epoch==generation){GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[0]);GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,image,0)}}finally{image.recycle()}}}catch(e: Exception){notify("Ambiente: ${e.message}",true)}}
    }
    /** Main-thread owner: a bound service must release projection even before onDestroy. */
    private fun stopContent(){
        capturing=false;browser?.close();browser=null
        captureService?.onStopped=null;captureService?.onResize=null;captureService?.stopCapture();captureService=null
        if(bound){unbindService(captureConnection);bound=false}
        stopService(Intent(this,CaptureService::class.java))
    }
    private fun attachCapture(){if(!resumed||browser!=null)return;val surface=captureSurface ?: return;if(capturing)return;capturing=captureService?.attach(surface,1280,720)==true;if(capturing)view.queueEvent{shell.windows.spawn(WindowKind.CAPTURE);shell.surfaceKind=WindowKind.CAPTURE}}
    private fun notify(message: String,error: Boolean=false){notices.add(SystemClock.elapsedRealtimeNanos(),message,error);if(error)android.util.Log.e("TrackMR",message)}
    private fun fatal(e: Exception){failed=true;notify(e.message ?: e.javaClass.simpleName,true);runOnUiThread{android.app.AlertDialog.Builder(this).setTitle("Falha no renderer XR").setMessage("${e.javaClass.simpleName}: ${e.message}").setPositiveButton("Encerrar"){_,_->finish()}.setCancelable(false).show()}}
    override fun onKeyDown(code: Int,event: KeyEvent): Boolean {if(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_BUTTON_A){if(event.repeatCount==0)view.queueEvent{select()};return true};return super.onKeyDown(code,event)}
    override fun onDestroy(){
        power.removeThermalStatusListener(thermalListener);shizuku.close();assistant.close();audio.close();stopContent()
        view.queueEvent{stopCamera();neural?.close();atlas?.close();captureSurface?.release();captureTexture?.release();if(handle!=0L){NativeBridge.destroy(handle);handle=0}}
        io.shutdown();super.onDestroy()
    }
}
