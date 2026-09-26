package dev.trackmr.vr

import android.graphics.*

/** Rasterize only when content changes; uploaded once, never drawn into a bitmap each frame. */
object SpatialPanel {
    fun create(): Bitmap {
        val image=Bitmap.createBitmap(1200,500,Bitmap.Config.ARGB_8888)
        val c=Canvas(image);val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.color=0xf51c2032.toInt();c.drawRoundRect(0f,0f,1200f,500f,45f,45f,p)
        fun label(text: String,x: Float,y: Float,size: Float,color: Int=Color.WHITE) {
            p.color=color;p.textSize=size;p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);c.drawText(text,x,y,p)
        }
        label("TRACKMR 2.0",48f,65f,25f,0xffbeaaff.toInt())
        label("Seu espaço começa aqui.",48f,132f,48f)
        label("Olhe para um cartão e toque no visor ou use volume +.",48f,192f,25f,0xffbdc2d6.toInt())
        val titles=listOf("Horizonte","Noite neon","Órbita","Reflexo","Constelação")
        val details=listOf("360° · loft","360° · cidade","Explorar alvos","Em movimento","Siga o verde")
        for(i in 0..4){
            val left=i*240f+14
            p.color=if(i<2)0xff39354f.toInt() else 0xff3f3264.toInt()
            c.drawRoundRect(left,260f,left+212,448f,24f,24f,p)
            p.color=if(i<2)0xffad94ff.toInt() else 0xff7fe1c1.toInt()
            c.drawCircle(left+35,303f,12f,p)
            label(titles[i],left+18,364f,25f)
            label(details[i],left+18,405f,18f,0xffc0bcd5.toInt())
        }
        return image
    }
}
