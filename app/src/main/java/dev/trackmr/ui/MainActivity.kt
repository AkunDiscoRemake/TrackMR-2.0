package dev.trackmr.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.trackmr.R
import dev.trackmr.ai.AssistantActivity
import dev.trackmr.platform.ShizukuBridge
import dev.trackmr.store.Catalog
import dev.trackmr.vr.VrActivity
import java.io.File

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("trackmr",MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var shizuku: ShizukuBridge
    private var tab=0
    private val purple=Color.rgb(162,139,255)
    private val muted=Color.rgb(169,171,193)
    private val importPolicy=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            // Tiny advisory model only. No arbitrary executable model or unbounded upload.
            Thread {
                val result=runCatching {
                    val bytes=contentResolver.openInputStream(uri)!!.use { source ->
                        val destination=java.io.ByteArrayOutputStream()
                        val buffer=ByteArray(8192)
                        while(destination.size()<=1_048_576) { val n=source.read(buffer);if(n<0)break;destination.write(buffer,0,n) }
                        destination.toByteArray()
                    }
                    require(bytes.size in 100..1_048_576) { "Modelo deve ter no máximo 1 MiB" }
                    val temp=File(filesDir,"performance.tmp").apply { writeBytes(bytes) }
                    org.tensorflow.lite.Interpreter(temp).use {
                        require(it.getInputTensor(0).shape().contentEquals(intArrayOf(1,5)))
                        require(it.getOutputTensor(0).shape().contentEquals(intArrayOf(1,2)))
                    }
                    check(temp.renameTo(File(filesDir,"performance.tflite")))
                }
                runOnUiThread { toast(if(result.isSuccess) "Modelo experimental importado" else "Modelo inválido: ${result.exceptionOrNull()?.message}") }
            }.start()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); shizuku=ShizukuBridge(this)
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(16),dp(20),dp(10))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xff1c1832.toInt(),0xff0c101b.toInt(),0xff111624.toInt()))
            fitsSystemWindows=true
        }
        setContentView(root)
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); contentDescription="TrackMR" },LinearLayout.LayoutParams(dp(44),dp(44)))
        header.addView(text("TRACKMR  2.0",18,true).apply { setPadding(dp(12),0,0,0) },LinearLayout.LayoutParams(0,dp(50),1f))
        header.addView(text("ALPHA 01",10,true,purple).apply { background=rounded(0xff302643.toInt()); setPadding(dp(12),dp(7),dp(12),dp(7)) })
        root.addView(header)
        val scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false }
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(24),0,dp(20)) }
        scroll.addView(body); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        navigation=LinearLayout(this).apply { gravity=Gravity.CENTER; background=rounded(0xff242538.toInt()); setPadding(dp(4),dp(4),dp(4),dp(4)) }
        root.addView(navigation,LinearLayout.LayoutParams(-1,dp(62)))
        showTab(savedInstanceState?.getInt("tab") ?: 0)
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab",tab) }
    private fun showTab(index: Int) {
        tab=index; body.removeAllViews(); navigation.removeAllViews()
        listOf("⌂\nInício","◉\nAmbientes","▦\nLoja","▣\nApps","⚙\nAjustes").forEachIndexed { i,label ->
            navigation.addView(text(label,11,i==index,if(i==index)Color.WHITE else muted).apply {
                gravity=Gravity.CENTER; if(i==index)background=rounded(0xff514275.toInt()); setOnClickListener { showTab(i) }
            },LinearLayout.LayoutParams(0,-1,1f))
        }
        when(index) { 0->home();1->environments();2->store();3->apps();else->settings() }
    }
    private fun home() {
        eyebrow("SEU UNIVERSO ESPACIAL")
        title("Um novo jeito\nde estar aqui.")
        paragraph("Coloque seu Cardboard. O próximo espaço é seu.")
        environmentCard(prefs.getString("environment","loft")!!,true)
        rowActions(listOf("◈  Entrar em VR" to { enterVr() },"◎  Assistente local" to { startActivity(Intent(this,AssistantActivity::class.java)) }))
        section("Explore o TrackMR","FEITO PARA CELULAR")
        card("Mundo ao seu redor","Dois ambientes 360° • visualização 3DoF", "Escolher ambiente") { showTab(1) }
        card("Uma pausa para jogar","3 minijogos com geometria procedural própria", "Abrir biblioteca") { showTab(2) }
        card("Seus apps, outro ponto de vista","Captura autorizada em painel plano ou curvo", "Janelas espaciais") { showTab(3) }
        paragraph("Construção experimental. Tire o celular do visor para autorizar câmera, captura e serviços externos.",11)
    }
    private fun environments() {
        eyebrow("ESCOLHA ONDE ESTAR");title("Seus ambientes")
        paragraph("Panoramas do projeto, renderizados ao redor de você. Fotos 360° não possuem profundidade nem parallax 6DoF.")
        environmentCard("loft",false);environmentCard("neon",false)
    }
    private fun environmentCard(id: String,hero: Boolean) {
        val frame=FrameLayout(this)
        val image=ImageView(this).apply {
            setImageBitmap(assets.open("environments/$id.jpg").use { BitmapFactory.decodeStream(it) })
            scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription=if(id=="loft")"Loft panorâmico ao pôr do sol" else "Rua neon violeta à noite"
        }
        frame.addView(image,FrameLayout.LayoutParams(-1,-1))
        frame.addView(View(this).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x00000000,0xee0c101b.toInt())) },FrameLayout.LayoutParams(-1,-1))
        val caption=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(16),dp(20),dp(16)) }
        caption.addView(text(if(hero)"SEU AMBIENTE ATUAL" else "360°  /  3DOF",10,true,0xffd0c5ff.toInt()))
        caption.addView(text(if(id=="loft")"Horizonte dourado" else "Noite violeta",25,true))
        caption.addView(text(if(id=="loft")"Um lugar para desacelerar." else "A cidade em outra frequência.",12))
        if(!hero)caption.addView(button(if(prefs.getString("environment","loft")==id)"Selecionado  ✓" else "Usar ambiente") {
            prefs.edit().putString("environment",id).apply();showTab(1)
        })
        frame.addView(caption,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        frame.background=rounded(0xff272438.toInt());frame.clipToOutline=true
        body.addView(frame,LinearLayout.LayoutParams(-1,dp(if(hero)230 else 260)).apply { topMargin=dp(18);bottomMargin=dp(12) })
    }
    private fun store() {
        eyebrow("BIBLIOTECA & DESCOBERTAS");title("Mais para explorar")
        paragraph("Jogos incluídos rodam no TrackMR. Links externos abrem os projetos oficiais — não há instalação automática nem compatibilidade garantida.")
        Catalog.entries.forEach { entry ->
            section(entry.category)
            card(entry.title,entry.subtitle,if(entry.game>0)"Jogar agora" else "Visitar projeto ↗") {
                if(entry.game>0)enterVr(entry.game) else safe { Catalog.openLink(this,entry.url!!) }
            }
        }
    }
    private fun apps() {
        eyebrow("MULTITAREFA ESPACIAL");title("Abra mais espaço")
        paragraph("Veja uma captura autorizada do Android em VR. No Android 14+, escolha um app na caixa do sistema; versões anteriores compartilham a tela inteira.")
        card("Janela ${if(prefs.getBoolean("curved",true))"curva" else "plana"}","Auto-resize da captura no Android 14+. Conteúdo DRM/FLAG_SECURE permanece protegido. Controle remoto por toque ainda não implementado.","Compartilhar em VR") {
            android.app.AlertDialog.Builder(this).setTitle("Compartilhar um app")
                .setMessage("Na próxima tela, escolha um app específico quando disponível. Compartilhar a tela inteira pode produzir espelhamento recursivo ao voltar ao VR. A captura não permite controlar o app remoto.")
                .setPositiveButton("Continuar") { _,_->enterVr(capture=true) }.setNegativeButton("Cancelar",null).show()
        }
        card("Shizuku",shizuku.status()+". O serviço shell inclui lançamento/resize em display virtual existente; a criação de displays independentes ainda está em desenvolvimento.","Autorizar / conectar") {
            safe { shizuku.requestOrBind(); toast("Após autorizar, toque novamente para conectar.") }
        }
        card("Navegador WebXR","Wolvic é open source da Igalia. O port para o runtime TrackMR ainda é necessário; um Android WebView comum NÃO oferece este navegador VR.","Conhecer Wolvic") {
            safe { Catalog.openLink(this,"https://wolvic.com/") }
        }
        section("Apps instalados","ABREM FORA DO VR")
        @Suppress("DEPRECATION")
        val apps=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .filter { it.activityInfo.packageName!=packageName }.sortedBy { it.loadLabel(packageManager).toString() }
        apps.forEach { info ->
            val launch=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(info.activityInfo.packageName,info.activityInfo.name)
            body.addView(button(info.loadLabel(packageManager).toString()) { safe { startActivity(launch) } })
        }
    }
    private fun settings() {
        eyebrow("AJUSTADO PARA VOCÊ");title("Controle da experiência")
        section("Tracking & conforto")
        toggle("Tracking 6DoF","ARCore opcional. Em dispositivos sem suporte, usa Cardboard 3DoF.","sixdof",false)
        toggle("Hand tracking experimental","MediaPipe • 1 mão • One Euro • gesto de pinça. Visualização 2D, não mãos métricas 3D.","hands",false)
        toggle("Painéis curvos","Desligue para uma janela plana.","curved",true)
        toggle("Qualidade adaptativa","Reduz resolução sob carga e temperatura, com histerese.","adaptive",true)
        toggle("Consultor neural experimental","Só funciona após importar modelo TFLite. Nunca ignora limites térmicos.","neural",false)
        body.addView(button("Importar modelo de desempenho (.tflite)") { importPolicy.launch(arrayOf("application/octet-stream","*/*")) })
        section("Visor & runtime")
        card("Calibrar seu Cardboard","Use o QR code do seu visor dentro do menu VR. Sem QR, utiliza perfil Cardboard V1 — pode não corresponder às suas lentes.","Entrar e calibrar") { enterVr() }
        card("TrackMR Runtime Companion","Segundo APK: diagnóstico de Monado e Runtime Broker. Não se anuncia como runtime OpenXR pronto.","Abrir companion") {
            safe { val launch=packageManager.getLaunchIntentForPackage("dev.trackmr.runtime")
                ?: error("Instale também o APK runtime-debug gerado pelo Actions.");startActivity(launch) }
        }
        card("API de jogos v1","Contrato Kotlin de cenas, pose e entrada. Consulte dev-api/ e docs/DEV_API.md; não é um loader OpenXR.","Abrir código-fonte") {
            safe { Catalog.openLink(this,"https://github.com/AkunDiscoRemake/TrackMR-2.0") }
        }
        paragraph("PRIVACIDADE\nCâmera e inferência são locais. Sem analytics, contas, gravação de câmera ou upload de conversas. Modelos de IA são importados manualmente.\n\nCONFORTO\nUse sentado, com área livre. Pare se sentir enjoo, calor excessivo ou desconforto visual. Não use ao caminhar.",12)
    }
    private fun toggle(label: String,description: String,key: String,default: Boolean) {
        val switch=Switch(this).apply { text=label; textSize=15f; setTextColor(Color.WHITE);isChecked=prefs.getBoolean(key,default)
            setOnCheckedChangeListener { _,checked->prefs.edit().putBoolean(key,checked).apply() } }
        body.addView(switch,LinearLayout.LayoutParams(-1,dp(56)));paragraph(description,12)
    }
    private fun enterVr(game: Int=0,capture: Boolean=false) { startActivity(Intent(this,VrActivity::class.java).putExtra("game",game).putExtra("capture",capture)) }
    private fun rowActions(actions: List<Pair<String,()->Unit>>) {
        actions.forEachIndexed { i,p -> body.addView(button(p.first,i==0,p.second)) }
    }
    private fun card(title: String,description: String,action: String,onClick: ()->Unit) {
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(12));background=rounded(0xff222537.toInt()) }
        layout.addView(text(title,18,true));layout.addView(text(description,13,false,muted).apply { setPadding(0,dp(8),0,dp(14)) })
        layout.addView(button(action,false,onClick))
        body.addView(layout,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10);bottomMargin=dp(6) })
    }
    private fun section(title: String,right: String="") {
        body.addView(text(if(right.isEmpty())title else "$title   ·   $right",12,true,muted).apply { setPadding(0,dp(26),0,dp(8)) })
    }
    private fun title(s: String) { body.addView(text(s,32,true).apply { setPadding(0,dp(8),0,dp(12)) }) }
    private fun eyebrow(s: String) { body.addView(text(s,10,true,purple).apply { letterSpacing=.18f }) }
    private fun paragraph(s: String,size: Int=14) { body.addView(text(s,size,false,muted).apply { setPadding(0,dp(4),0,dp(12));setLineSpacing(dp(3).toFloat(),1f) }) }
    private fun text(s: String,size: Int,bold: Boolean=false,color: Int=Color.WHITE)=TextView(this).apply { text=s;textSize=size.toFloat();setTextColor(color);if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);gravity=Gravity.CENTER_VERTICAL }
    private fun button(label: String,primary: Boolean=false,action: ()->Unit)=Button(this).apply {
        text=label;isAllCaps=false;textSize=14f;setTextColor(Color.WHITE);background=rounded(if(primary)0xff7955e8.toInt() else 0xff343449.toInt())
        minHeight=dp(48);setPadding(dp(12),dp(10),dp(12),dp(10));setOnClickListener { action() }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8);bottomMargin=dp(4) }
    }
    private fun rounded(color: Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(18).toFloat() }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(message: String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    private fun safe(action: ()->Unit) { try { action() } catch(e: Exception) { toast(e.message ?: "Ação indisponível") } }
    override fun onDestroy() { shizuku.close();super.onDestroy() }
}
