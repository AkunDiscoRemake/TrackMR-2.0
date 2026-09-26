package dev.trackmr.ai

import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.util.concurrent.Executors

/** Text-only local conversation. No fabricated canned responses and no cloud fallback. */
class AssistantActivity : ComponentActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private var engine: LlmInference?=null
    private lateinit var history: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var load: Button
    private val messages=ArrayDeque<String>()
    private var busy=false
    private val document=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            setBusy(true);append("Sistema","Importando modelo para o armazenamento privado…")
            worker.execute {
                val result=runCatching {
                    val temp=File(filesDir,"assistant.tmp")
                    try {
                        contentResolver.openInputStream(uri)!!.use { source -> temp.outputStream().use { destination ->
                            val buffer=ByteArray(1024*1024);var total=0L
                            while(true) { val n=source.read(buffer);if(n<0)break;total+=n
                                require(total<2_147_483_648L && filesDir.usableSpace>128L*1024*1024) { "Modelo muito grande ou pouco espaço" }
                                destination.write(buffer,0,n)
                            }
                        } }
                        engine?.close();engine=null
                        check(temp.renameTo(File(filesDir,"assistant.bin"))) { "Não foi possível salvar modelo" }
                        createEngine()
                    } finally { temp.delete() }
                }
                runOnUiThread { if(!isDestroyed) { setBusy(false);append("Sistema",result.fold({"Modelo local pronto. O primeiro pedido pode demorar."},{"Não foi possível carregar: ${it.message}"})) } }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24);setBackgroundColor(0xff111522.toInt());fitsSystemWindows=true }
        root.addView(TextView(this).apply { text="Assistente local";textSize=27f;setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply { text="Conversa por texto • sem upload\nImporte um modelo compatível com MediaPipe LLM Inference (.bin/.task). GGUF não é suportado. Recomendado: 8 GB de RAM. A IA pode errar.";setTextColor(0xffbbb6d1.toInt()) })
        load=Button(this).apply { text="Importar modelo";setOnClickListener { document.launch(arrayOf("*/*")) } };root.addView(load)
        history=TextView(this).apply { textSize=16f;setTextColor(Color.WHITE);setTextIsSelectable(true) }
        val scroll=ScrollView(this).apply { addView(history) };root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        input=EditText(this).apply { hint="Converse com seu assistente…";setTextColor(Color.WHITE);maxLines=4 };root.addView(input)
        send=Button(this).apply { text="Enviar no dispositivo";setOnClickListener { generate() } };root.addView(send)
        setContentView(root)
        if(File(filesDir,"assistant.bin").exists()) {
            setBusy(true);worker.execute { val result=runCatching { createEngine() }
                runOnUiThread { if(!isDestroyed){setBusy(false);append("Sistema",if(result.isSuccess)"Modelo local carregado." else "Modelo incompatível ou memória insuficiente. Importe outro modelo.")} }
            }
        } else append("Sistema","Nenhum modelo instalado. Os pesos não vêm no APK; verifique licença e origem antes de importar.")
    }
    private fun createEngine() {
        engine=LlmInference.createFromOptions(this,LlmInference.LlmInferenceOptions.builder()
            .setModelPath(File(filesDir,"assistant.bin").absolutePath).setMaxTokens(1024).build())
        messages.clear()
    }
    private fun generate() {
        if(busy)return
        val query=input.text.toString().trim().take(1500)
        if(query.isEmpty())return
        setBusy(true);input.setText("");append("Você",query)
        worker.execute {
            val answer=runCatching {
                val llm=engine ?: error("Importe primeiro um modelo compatível.")
                messages.addLast("Usuário: $query")
                while(messages.size>6)messages.removeFirst()
                val prompt="Responda em português. Você é um assistente local. Não invente acesso a sensores ou apps.\n"+messages.joinToString("\n").takeLast(2500)+"\nAssistente:"
                llm.generateResponse(prompt).also { messages.addLast("Assistente: ${it.take(2000)}") }
            }.getOrElse { "Erro local: ${it.message}. Não foi enviada nenhuma solicitação à nuvem." }
            runOnUiThread { if(!isDestroyed){append("TrackMR",answer);setBusy(false)} }
        }
    }
    private fun append(role: String,text: String) { history.append("\n$role\n$text\n") }
    private fun setBusy(value: Boolean) { busy=value;send.isEnabled=!value;load.isEnabled=!value;send.text=if(value)"Processando localmente…" else "Enviar no dispositivo" }
    override fun onDestroy() { worker.execute { engine?.close();engine=null };worker.shutdown();super.onDestroy() }
}
