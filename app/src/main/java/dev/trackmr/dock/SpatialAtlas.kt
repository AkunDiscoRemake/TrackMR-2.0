package dev.trackmr.dock

import android.graphics.*
import android.opengl.GLES30
import android.opengl.GLUtils
import dev.trackmr.xr.DockItem
import dev.trackmr.xr.SpatialBudget

/** Bounded LRU atlas. Only changed 256x128 tiles upload; never an Android screen in a headset. */
class SpatialAtlas(val texture: Int) : AutoCloseable {
    private data class Tile(val index: Int,var text: String,var icon: Int,var stamp: Long,var aspect: Float=2f,var image: Bitmap?=null)
    private val cache=LinkedHashMap<String,Tile>()
    private var stamp=0L
    private val bitmap=Bitmap.createBitmap(256,128,Bitmap.Config.ARGB_8888)
    private val canvas=Canvas(bitmap)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA,2048,2048,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null)
    }
    fun begin(){stamp++}
    fun tile(key: String,text: String="",icon: Int=-1,aspect: Float=2f,image: Bitmap?=null): Int {
        var tile=cache[key]
        if(tile==null){
            val slot=if(cache.size<SpatialBudget.ATLAS_TILES)cache.size else {
                val old=cache.entries.filter{it.value.stamp!=stamp}.minByOrNull{it.value.stamp} ?: error("Atlas visible budget exceeded")
                cache.remove(old.key);old.value.index
            }
            tile=Tile(slot,"\u0000",-2,stamp);cache[key]=tile
        }
        tile.stamp=stamp
        if(tile.text!=text||tile.icon!=icon||tile.aspect!=aspect||tile.image!==image){tile.text=text;tile.icon=icon;tile.aspect=aspect.coerceIn(.4f,20f);tile.image=image;draw(tile)}
        return tile.index
    }
    private fun draw(t: Tile){
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
        paint.color=Color.WHITE;paint.strokeWidth=5f;paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.ROUND
        canvas.save()
        val logicalWidth=128*t.aspect
        canvas.scale(256/logicalWidth,1f)
        if(t.icon>=0){canvas.translate(logicalWidth/2-128,0f);icon(t.icon)} else {
            paint.style=Paint.Style.FILL;paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            if(t.image!=null){
                canvas.drawBitmap(t.image!!,null,RectF(logicalWidth/2-30,7f,logicalWidth/2+30,67f),null)
            }
            paint.textSize=if(t.image!=null)19f else if(t.text.length<=2)44f else 25f
            val available=logicalWidth-16
            val rows=mutableListOf<String>()
            for(paragraph in t.text.split('\n')){
                var rest=paragraph
                if(rest.isEmpty())rows.add("")
                while(rest.isNotEmpty()){
                    var end=paint.breakText(rest,true,available,null).coerceAtLeast(1)
                    if(end<rest.length){val space=rest.lastIndexOf(' ',end);if(space>0)end=space}
                    rows.add(rest.take(end));rest=rest.drop(end).trimStart()
                }
            }
            val maximum=if(t.image!=null)2 else 4
            val visible=rows.take(maximum).toMutableList()
            if(rows.size>maximum&&visible.isNotEmpty()){
                var last=visible.last();while(last.isNotEmpty()&&paint.measureText(last+"…")>available)last=last.dropLast(1)
                visible[visible.lastIndex]=last+"…"
            }
            val lineHeight=paint.textSize*1.12f
            val center=if(t.image!=null)96f else 64f
            val baseline=center-(visible.size-1)*lineHeight/2-(paint.ascent()+paint.descent())/2
            visible.forEachIndexed{i,line->canvas.drawText(line,(logicalWidth-paint.measureText(line))/2,baseline+i*lineHeight,paint)}
        }
        canvas.restore()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture)
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D,0,(t.index%8)*256,(t.index/8)*128,bitmap)
    }
    private fun line(x: Float,y: Float,xx: Float,yy: Float)=canvas.drawLine(x,y,xx,yy,paint)
    private fun icon(i: Int){
        val p=Path()
        when(DockItem.entries[i]){
            DockItem.HOME->{p.moveTo(87f,61f);p.lineTo(128f,27f);p.lineTo(169f,61f);p.moveTo(98f,53f);p.lineTo(98f,99f);p.lineTo(158f,99f);p.lineTo(158f,53f);canvas.drawPath(p,paint);canvas.drawRect(120f,72f,136f,99f,paint)}
            DockItem.LIBRARY->{for(x in 0..2)canvas.drawRoundRect(92f+x*26,32f,110f+x*26,99f,4f,4f,paint)}
            DockItem.STORE->{canvas.drawRoundRect(94f,49f,162f,101f,8f,8f,paint);canvas.drawArc(109f,25f,147f,72f,180f,180f,false,paint)}
            DockItem.SETTINGS->{canvas.drawCircle(128f,64f,24f,paint);canvas.drawCircle(128f,64f,9f,paint);for(n in 0..7){val a=n*Math.PI/4;line(128+(kotlin.math.cos(a)*28).toFloat(),64+(kotlin.math.sin(a)*28).toFloat(),128+(kotlin.math.cos(a)*38).toFloat(),64+(kotlin.math.sin(a)*38).toFloat())}}
            DockItem.MR->{canvas.drawRoundRect(85f,34f,171f,95f,12f,12f,paint);canvas.drawCircle(128f,65f,19f,paint);line(115f,26f,141f,26f)}
            DockItem.VR->{canvas.drawRoundRect(84f,38f,172f,91f,17f,17f,paint);canvas.drawCircle(108f,64f,12f,paint);canvas.drawCircle(148f,64f,12f,paint)}
            DockItem.BROWSER->{canvas.drawCircle(128f,64f,37f,paint);canvas.drawOval(111f,27f,145f,101f,paint);line(93f,51f,163f,51f);line(93f,78f,163f,78f)}
            DockItem.RECENTS->{canvas.drawCircle(128f,64f,34f,paint);line(128f,64f,128f,39f);line(128f,64f,147f,77f)}
            DockItem.ENVIRONMENTS->{p.moveTo(84f,99f);p.lineTo(114f,47f);p.lineTo(136f,79f);p.lineTo(149f,56f);p.lineTo(174f,99f);p.close();canvas.drawPath(p,paint);canvas.drawCircle(154f,31f,10f,paint)}
            DockItem.CAPTURE->{canvas.drawRoundRect(88f,41f,168f,96f,8f,8f,paint);canvas.drawCircle(128f,68f,19f,paint);line(105f,33f,143f,33f)}
            DockItem.NOTIFICATIONS->{canvas.drawArc(104f,31f,152f,101f,180f,180f,false,paint);line(104f,66f,96f,92f);line(152f,66f,160f,92f);line(96f,92f,160f,92f);canvas.drawArc(119f,90f,137f,107f,0f,180f,false,paint)}
            DockItem.PERFORMANCE->{for(n in 0..3)line(95f+n*22,99f,95f+n*22,75f-n*15)}
            DockItem.TRACKING->{canvas.drawRoundRect(109f,54f,152f,99f,16f,16f,paint);for(n in 0..3)line(111f+n*13,62f,111f+n*13,31f+(if(n==1)0 else 8));line(113f,82f,96f,64f)}
            DockItem.SYSTEM->{canvas.drawArc(94f,30f,162f,99f,-55f,290f,false,paint);line(128f,24f,128f,62f)}
        }
    }
    override fun close(){bitmap.recycle()}
}
