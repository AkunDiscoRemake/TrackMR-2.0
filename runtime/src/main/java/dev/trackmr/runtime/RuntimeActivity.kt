package dev.trackmr.runtime

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.widget.*
import java.util.concurrent.Executors

class RuntimeActivity : Activity() {
    private external fun probe(activity: Activity): String
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var report: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(28,32,28,28);setBackgroundColor(0xff131626.toInt());fitsSystemWindows=true }
        val scroll=ScrollView(this).apply { addView(column) };setContentView(scroll)
        column.addView(TextView(this).apply { text="TRACKMR\nRuntime Companion";textSize=29f;setTextColor(Color.WHITE) })
        column.addView(TextView(this).apply { text="Preparação e diagnóstico OpenXR\n\nEste APK não contém o Monado nem se anuncia como runtime. Instale um build confiável do Monado out-of-process e o Runtime Broker oficial. O driver Cardboard/ARCore integrado ao compositor Monado ainda precisa ser desenvolvido e validado.\n";setTextColor(0xffc5bfdc.toInt()) })
        fun button(title: String,action: ()->Unit) { column.addView(Button(this).apply { text=title;isAllCaps=false;setOnClickListener { runCatching(action).onFailure { Toast.makeText(this@RuntimeActivity,it.message,Toast.LENGTH_LONG).show() } } }) }
        button("1. Obter Runtime Broker oficial") { open("https://github.com/KhronosGroup/OpenXR-Android-Broker/releases") }
        button("2. Projeto Monado / build Android") { open("https://monado.freedesktop.org/") }
        button("3. Escolher runtime no Broker") {
            val intent=packageManager.getLaunchIntentForPackage("org.khronos.openxr.runtime_broker") ?: error("Runtime Broker não instalado")
            startActivity(intent)
        }
        button("Consultar Broker e testar loader OpenXR") {
            report.text="Consultando…"
            worker.execute {
                val diagnostics=brokerReport()+"\n\n"+runCatching { System.loadLibrary("trackmr_probe");probe(this) }.getOrElse { "Loader: ${it.message}" }
                runOnUiThread { if(!isDestroyed)report.text=diagnostics }
            }
        }
        report=TextView(this).apply { text="Nenhum teste executado.\nNão é uma implementação OpenXR certificada.";setTextColor(Color.WHITE);setTextIsSelectable(true);setPadding(0,28,0,0) };column.addView(report)
    }
    private fun brokerReport(): String = listOf("org.khronos.openxr.runtime_broker","org.khronos.openxr.system_runtime_broker").joinToString("\n\n") { authority ->
        runCatching {
            contentResolver.query(Uri.parse("content://$authority/openxr/1/abi/arm64-v8a/runtimes/active"),null,null,null,null)?.use { cursor ->
                val rows=mutableListOf<String>()
                while(cursor.moveToNext()) rows+=listOf("package_name","so_filename").joinToString(" • ") { column ->
                    val i=cursor.getColumnIndex(column);if(i>=0)cursor.getString(i) ?: "—" else "$column ausente"
                }
                "$authority\n"+if(rows.isEmpty())"Nenhum runtime selecionado" else rows.joinToString("\n")
            } ?: "$authority: não instalado"
        }.getOrElse { "$authority: ${it.javaClass.simpleName}" }
    }
    private fun open(url: String) { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
    override fun onDestroy() { worker.shutdown();super.onDestroy() }
}
