package dev.trackmr.store

import android.app.Activity
import android.content.Intent
import android.net.Uri

data class CatalogEntry(val title: String,val subtitle: String,val category: String,val game: Int=0,val url: String?=null)
object Catalog {
    val entries=listOf(
        CatalogEntry("Órbita","Acerte os orbes com o olhar e o gatilho.","JOGO INCLUÍDO",1),
        CatalogEntry("Reflexo","Alvos em movimento. Treine tempo de reação.","JOGO INCLUÍDO",2),
        CatalogEntry("Constelação","Encontre o próximo orbe verde, sem errar.","JOGO INCLUÍDO",3),
        CatalogEntry("Wolvic","Navegador WebXR independente • exige port para este headset.","PROJETO EXTERNO",url="https://wolvic.com/"),
        CatalogEntry("Shizuku","Autorize recursos avançados do Android.","FERRAMENTA",url="https://shizuku.rikka.app/"),
        CatalogEntry("Monado","Runtime OpenXR • integração TrackMR em desenvolvimento.","RUNTIME",url="https://monado.freedesktop.org/"),
        CatalogEntry("WebXR Samples","Exemplos oficiais; abra num navegador compatível.","WEB",url="https://immersive-web.github.io/webxr-samples/")
    )
    /** Catalog links only. Never download/install arbitrary APKs or claim compatibility. */
    fun openLink(activity: Activity,url: String) {
        val uri=Uri.parse(url); require(uri.scheme=="https")
        activity.startActivity(Intent(Intent.ACTION_VIEW,uri))
    }
}
