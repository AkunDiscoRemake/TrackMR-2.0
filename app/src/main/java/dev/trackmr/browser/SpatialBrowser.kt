package dev.trackmr.browser

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.webkit.*
import org.json.JSONObject

/** A private app-owned display, not capture/injection of someone else's application. Main thread only. */
class SpatialBrowser(private val context: Context,private val notify: (String)->Unit) : AutoCloseable {
    private var display: VirtualDisplay?=null
    private var presentation: Presentation?=null
    private var web: WebView?=null
    val width=1280;val height=720
    var url="https://example.org";private set
    fun open(surface: Surface,address: String){
        close()
        val virtual=context.getSystemService(DisplayManager::class.java).createVirtualDisplay("TrackMR private browser",width,height,160,surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            ?: error("Display privado indisponível neste Android")
        display=virtual
        presentation=object: Presentation(context,virtual.display){
            override fun onCreate(state: Bundle?){
                super.onCreate(state)
                window?.setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                web=WebView(this.context).apply{
                    setBackgroundColor(Color.WHITE)
                    settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                    settings.allowFileAccess=false;settings.allowContentAccess=false
                    settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setGeolocationEnabled(false);settings.mediaPlaybackRequiresUserGesture=true
                    webViewClient=object: WebViewClient(){
                        override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                            if(request.url.scheme!="https"){notify("Browser: navegação não HTTPS bloqueada");return true};return false
                        }
                        override fun onReceivedError(view: WebView,request: WebResourceRequest,error: WebResourceError){if(request.isForMainFrame)notify("Browser: ${error.description}")}
                    }
                    webChromeClient=object: WebChromeClient(){override fun onPermissionRequest(request: PermissionRequest){request.deny();notify("Browser não recebe câmera/microfone por padrão")}}
                    setDownloadListener{_,_,_,_,_->notify("Download web bloqueado: use um pacote verificado e confirmação do Android")}
                }
                setContentView(web!!);load(address)
            }
        }.also{it.show()}
    }
    fun load(address: String){val uri=Uri.parse(address);require(uri.scheme=="https"&&!uri.host.isNullOrBlank()){"Use uma URL HTTPS válida"};url=address;web?.loadUrl(address)}
    fun tap(u: Float,v: Float){val view=web ?: return;val now=SystemClock.uptimeMillis();val x=u.coerceIn(0f,1f)*width;val y=v.coerceIn(0f,1f)*height
        val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);val up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,x,y,0)
        try{view.dispatchTouchEvent(down);view.dispatchTouchEvent(up)}finally{down.recycle();up.recycle()}
    }
    private var handDown=0L
    fun pointer(action: Int,u: Float,v: Float){
        val view=web ?: return;val now=SystemClock.uptimeMillis()
        if(action==MotionEvent.ACTION_DOWN)handDown=now
        if(handDown==0L)return
        val event=MotionEvent.obtain(handDown,now,action,u.coerceIn(0f,1f)*width,v.coerceIn(0f,1f)*height,0)
        try{view.dispatchTouchEvent(event)}finally{event.recycle()}
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)handDown=0
    }
    fun text(value: String){
        // Deliberately no JS bridge. Quoted text goes only to the currently focused DOM field.
        val literal=JSONObject.quote(value.take(2048))
        web?.evaluateJavascript("(function(){const e=document.activeElement;if(e && ('value' in e)){e.value += $literal;e.dispatchEvent(new Event('input',{bubbles:true}));}else if(e && e.isContentEditable){e.textContent += $literal;}})()",null)
    }
    fun scroll(delta: Float){web?.scrollBy(0,(delta*height*3).toInt())}
    fun back(){if(web?.canGoBack()==true)web?.goBack()}
    override fun close(){pointer(MotionEvent.ACTION_CANCEL,.5f,.5f);web?.stopLoading();web?.destroy();web=null;presentation?.dismiss();presentation=null;display?.release();display=null}
}
