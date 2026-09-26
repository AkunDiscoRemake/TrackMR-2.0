package dev.trackmr.vr

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.opengl.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.trackmr.core.FrameStatistics
import dev.trackmr.core.PerformanceGovernor
import dev.trackmr.platform.CaptureService
import dev.trackmr.tracking.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VrActivity : ComponentActivity(), GLSurfaceView.Renderer {
    private val prefs by lazy { getSharedPreferences("trackmr",MODE_PRIVATE) }
    private lateinit var view: GLSurfaceView
    private lateinit var status: TextView
    private var handle=0L // GL-thread-owned
    private lateinit var cardboardContext: CardboardContext
    private val textures=IntArray(4)
    private val transform=FloatArray(16).apply { Matrix.setIdentityM(this,0) }
    private var surfaceTexture: SurfaceTexture?=null
    @Volatile private var captureSurface: Surface?=null
    private val captureFrame=AtomicBoolean(false)
    @Volatile private var capturing=false
    @Volatile private var captureAspect=2.4f
    private var captureService: CaptureService?=null
    private var bound=false
    @Volatile private var ar: ArTracking?=null
    @Volatile private var arReady=false
    private var neural: NeuralGovernor?=null
    private val governor=PerformanceGovernor()
    private val stats=FrameStatistics()
    private var width=0;private var height=0
    private var previousNs=0L;private var lastStatsNs=0L
    private var lastHandNs=0L
    private var hover=-1
    private var scene=0
    private var score=0
    private var environment="loft"
    private var resumed=false
    private var failed=false
    @Volatile private var thermal=0
    private val thermalListener=PowerManager.OnThermalStatusChangedListener { thermal=it }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private var targetFrameMs=16.67f
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted && resumed)resumeTracking() else toast("Sem câmera: continua em Cardboard 3DoF.")
    }
    private val captureConsent=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK && result.data!=null) {
            val intent=Intent(this,CaptureService::class.java).putExtra("consent",result.data)
            startForegroundService(intent)
            bound=bindService(intent,captureConnection,Context.BIND_AUTO_CREATE)
        } else toast("Captura cancelada. Nenhuma tela está sendo compartilhada.")
    }
    private val captureConnection=object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            captureService=(binder as CaptureService.LocalBinder).service
            captureService?.onResize={ w,h ->
                captureAspect=w.toFloat()/h
                view.queueEvent { surfaceTexture?.setDefaultBufferSize(w,h) }
            }
            captureService?.onStopped={ capturing=false;captureService=null }
            attachCapture()
        }
        override fun onServiceDisconnected(name: ComponentName?) { capturing=false;captureService=null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cardboardContext=CardboardContext.from(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        environment=prefs.getString("environment","loft")!!;scene=intent.getIntExtra("game",0)
        val root=FrameLayout(this)
        view=GLSurfaceView(this).apply {
            setEGLContextClientVersion(3);setEGLConfigChooser(8,8,8,0,0,0)
            preserveEGLContextOnPause=true;setRenderer(this@VrActivity)
            setOnTouchListener { _,event->if(event.action==MotionEvent.ACTION_UP){select();true}else true }
        }
        root.addView(view,FrameLayout.LayoutParams(-1,-1))
        // Operator controls are deliberately outside the stereo content: use before putting on viewer.
        val controls=LinearLayout(this).apply { gravity=Gravity.CENTER;setBackgroundColor(0xaa101020.toInt()) }
        fun control(label: String,action: ()->Unit) { controls.addView(Button(this).apply { text=label;textSize=10f;isAllCaps=false;setOnClickListener { action() } },LinearLayout.LayoutParams(-2,48)) }
        control("Sair") { finish() }
        control("Centralizar") { view.queueEvent { if(handle!=0L)NativeBridge.recenter(handle);ar?.recenter() } }
        control("Lentes / QR") { if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)NativeBridge.scan() else cameraPermission.launch(Manifest.permission.CAMERA) }
        control("Menu") { view.queueEvent { scene=0;score=0;if(handle!=0L)NativeBridge.scene(handle,0) } }
        root.addView(controls,FrameLayout.LayoutParams(-2,48,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        status=TextView(this).apply { textSize=10f;setTextColor(Color.WHITE);setBackgroundColor(0x99000000.toInt());gravity=Gravity.CENTER }
        root.addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(root)
        power.addThermalStatusListener(mainExecutor,thermalListener)
        thermal=power.currentThermalStatus
        @Suppress("DEPRECATION")
        targetFrameMs=1000f/windowManager.defaultDisplay.refreshRate.coerceAtLeast(30f)
        if(intent.getBooleanExtra("capture",false)&&savedInstanceState==null) {
            captureConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }
    private fun resumeTracking() {
        if(!prefs.getBoolean("sixdof",false)&&!prefs.getBoolean("hands",false))return
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA);return
        }
        if(ar==null)ar=ArTracking(this,prefs.getBoolean("sixdof",false),if(prefs.getBoolean("hands",false))HandTracker(this) else null)
        arReady=ar!!.resume()
    }
    override fun onResume() {
        super.onResume();resumed=true;resumeTracking();view.onResume()
        view.queueEvent { if(handle!=0L)NativeBridge.resume(handle);previousNs=0L }
    }
    override fun onPause() {
        resumed=false
        view.queueEvent { if(handle!=0L)NativeBridge.pause(handle) }
        view.onPause() // waits for render thread; never pause/close ARCore concurrently with update()
        arReady=false;ar?.pause();super.onPause()
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            if(handle!=0L)NativeBridge.destroy(handle)
            handle=NativeBridge.create(cardboardContext);NativeBridge.surface(handle);NativeBridge.resume(handle);NativeBridge.scene(handle,scene)
            ar?.contextLost()
            neural?.close();neural=if(prefs.getBoolean("neural",false))NeuralGovernor(this) else null
            GLES30.glGenTextures(4,textures,0)
            for(i in 0..3) {
                val target=if(i<2)GLES30.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                GLES30.glBindTexture(target,textures[i]);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR)
                GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR)
                GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE)
            }
            loadEnvironment()
            val panel=SpatialPanel.create();GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[1]);GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,panel,0);panel.recycle()
            // A context loss invalidates capture Surface. Consent is one-use; stop instead of reusing it.
            if(surfaceTexture!=null)runOnUiThread { stopService(Intent(this,CaptureService::class.java)) }
            captureSurface?.release();surfaceTexture?.release();capturing=false
            surfaceTexture=SurfaceTexture(textures[2]).apply {
                setDefaultBufferSize(1280,720);setOnFrameAvailableListener { captureFrame.set(true) }
            }
            captureSurface=Surface(surfaceTexture);runOnUiThread { attachCapture() }
        } catch(e: Exception) { fail(e) }
    }
    private fun attachCapture() {
        val surface=captureSurface ?: return
        if(capturing)return
        capturing=captureService?.attach(surface,1280,720)==true
        if(capturing)captureAspect=1280f/720
    }
    private fun loadEnvironment() {
        val bitmap=assets.open("environments/$environment.jpg").use { BitmapFactory.decodeStream(it) }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[0]);GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,bitmap,0);bitmap.recycle()
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width=width;this.height=height;if(handle!=0L)NativeBridge.resize(handle,width,height)
    }
    override fun onDrawFrame(gl: GL10?) {
        if(failed||handle==0L)return
        try {
            val now=SystemClock.elapsedRealtimeNanos()
            if(previousNs!=0L) {
                val ms=(now-previousNs)/1e6f;stats.add(ms)
                governor.update(ms,thermal,targetFrameMs)
            }
            previousNs=now
            val pose=if(arReady)ar?.frame(textures[3],width,height) else null
            ar?.hands?.intervalMs=governor.handIntervalMs
            val sample=ar?.hands?.latest?.get()
            if(sample!=null&&now-sample.captureNs in 0L..150_000_000L) {
                NativeBridge.hands(handle,sample.points)
                if(sample.captureNs!=lastHandNs){lastHandNs=sample.captureNs;if(sample.pinch)selectOnGl()}
            }else NativeBridge.hands(handle,null)
            if(capturing&&captureFrame.getAndSet(false)) { surfaceTexture?.updateTexImage();surfaceTexture?.getTransformMatrix(transform) }
            // Advisor called at most once/sec; baseline always enforces thermal bounds.
            if(now-lastStatsNs>1_000_000_000) {
                lastStatsNs=now
                val battery=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)/100f
                val advised=neural?.advise(stats.percentile(.5f),sample?.inferenceMs ?: 0f,thermal,battery,governor) ?: governor.scale
                val scale=if(!prefs.getBoolean("adaptive",true)&&thermal<3)1f else advised
                NativeBridge.settings(handle,scale,prefs.getBoolean("curved",true),if(capturing)captureAspect else 2.4f)
                val label="${ar?.status ?: "3DoF • Cardboard"}  |  frame p95 ${"%.1f".format(stats.percentile(.95f))} ms  |  escala ${"%.0f".format(scale*100)}%  |  térmico $thermal  |  pontos $score"
                runOnUiThread { status.text=label }
            }
            hover=NativeBridge.draw(handle,textures[0],textures[1],textures[2],transform,capturing,pose,(targetFrameMs*1_000_000).toLong())
        } catch(e: Exception) { fail(e) }
    }
    private fun select() { view.queueEvent { selectOnGl() } }
    private fun selectOnGl() {
        if(handle==0L)return
        if(scene==0&&hover>=100) {
            when(hover-100) {
                0,1->{environment=if(hover==100)"loft" else "neon";loadEnvironment();prefs.edit().putString("environment",environment).apply()}
                2,3,4->{scene=hover-101;NativeBridge.scene(handle,scene);score=0}
            }
        }else score=NativeBridge.select(handle)
    }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if(keyCode==KeyEvent.KEYCODE_VOLUME_UP||keyCode==KeyEvent.KEYCODE_BUTTON_A){if(event.repeatCount==0)select();return true}
        return super.onKeyDown(keyCode,event)
    }
    private fun fail(e: Exception) {
        failed=true;runOnUiThread {
            android.app.AlertDialog.Builder(this).setTitle("Não foi possível iniciar VR").setMessage(e.message ?: e.javaClass.simpleName)
                .setPositiveButton("Voltar") { _,_->finish() }.setCancelable(false).show()
        }
    }
    private fun toast(s: String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    override fun onDestroy() {
        power.removeThermalStatusListener(thermalListener)
        captureService?.onResize=null;captureService?.onStopped=null
        if(bound)unbindService(captureConnection)
        stopService(Intent(this,CaptureService::class.java))
        ar?.close();ar=null
        view.queueEvent {
            neural?.close();neural=null
            captureSurface?.release();surfaceTexture?.release()
            if(handle!=0L){NativeBridge.destroy(handle);handle=0}
        }
        super.onDestroy()
    }
}
