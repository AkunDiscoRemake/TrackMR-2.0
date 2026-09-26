package dev.trackmr.ai

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** No Activity, screen or UI dependency. Explicit model import, local text inference only. */
class LocalAssistant(private val context: Context,private val update: (String)->Unit) : AutoCloseable {
    private val worker=Executors.newSingleThreadExecutor()
    val busy=AtomicBoolean(false)
    private val closed=AtomicBoolean(false)
    private var engine: LlmInference?=null
    @Volatile var answer="Importe um modelo MediaPipe LLM. GGUF não é suportado.";private set
    private val messages=ArrayDeque<String>()
    @Synchronized private fun perform(job: ()->String){if(closed.get()||!busy.compareAndSet(false,true))return
        worker.execute{val result=runCatching(job).getOrElse{"IA local: ${it.javaClass.simpleName}: ${it.message}"};answer=result;busy.set(false);if(!closed.get())update(result)}
    }
    fun importModel(uri: Uri)=perform{
        val temp=File(context.filesDir,"assistant.tmp")
        try{
            context.contentResolver.openInputStream(uri)!!.use{source->temp.outputStream().use{sink->val buffer=ByteArray(1024*1024);var total=0L
                while(true){val n=source.read(buffer);if(n<0)break;total+=n;require(total<=2_147_483_648&&context.filesDir.usableSpace>134217728){"Modelo grande demais ou pouco espaço"};sink.write(buffer,0,n)}
            }}
            engine?.close();engine=null;check(temp.renameTo(File(context.filesDir,"assistant.bin")));messages.clear();"Modelo importado. Carregamento ocorre na primeira pergunta."
        }finally{temp.delete()}
    }
    fun ask(question: String)=perform{
        if(engine==null){val model=File(context.filesDir,"assistant.bin");check(model.exists()){"Importe primeiro um modelo compatível"}
            engine=LlmInference.createFromOptions(context,LlmInference.LlmInferenceOptions.builder().setModelPath(model.absolutePath).setMaxTokens(512).build())}
        messages.addLast("Usuário: ${question.take(1000)}");while(messages.size>6)messages.removeFirst()
        val prompt="Responda em português. Não invente acesso a câmera, sensores ou controle de apps.\n"+messages.joinToString("\n").takeLast(1800)+"\nAssistente:"
        engine!!.generateResponse(prompt).also{messages.addLast("Assistente: ${it.take(1400)}")}
    }
    @Synchronized override fun close(){if(!closed.compareAndSet(false,true))return;worker.execute{engine?.close();engine=null};worker.shutdown()}
}
