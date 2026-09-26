package dev.trackmr.dock

import android.content.SharedPreferences
import dev.trackmr.xr.*
import dev.trackmr.handtracking.*
import kotlin.math.*

class SpatialAction(val title: String,val enabled: Boolean=true,val run: ()->Unit)
class SpatialShell(private val prefs: SharedPreferences,val atlas: SpatialAtlas) {
    val settings=DockSettings(prefs.getFloat("dockX",0f),prefs.getFloat("dockY",-.4f),prefs.getFloat("dockDistance",1.6f),prefs.getFloat("dockScale",1f),prefs.getFloat("dockOpacity",.88f),prefs.getBoolean("reducedMotion",false))
    val windows=WindowManager()
    val pages=mutableMapOf<WindowKind,List<SpatialAction>>()
    val packet=FloatArray(SpatialBudget.PACKET_ITEMS*20)
    var count=0;private set
    var visible=true
    var movingDock=false
    var movingWindow=false
    var hovered=-1
    var cameraActive=false
    var headline="TRACKMR • INICIANDO MR"
    var detail="Câmera ainda não disponível"
    var onDock: (DockItem)->Unit={}
    var surfaceKind: WindowKind?=null
    var keyboardText="";private set
    private var keyboardSubmit: ((String)->Unit)?=null
    private val keys=SpatialBudget.KEYBOARD_KEYS
    fun keyboard(initial: String="",submit: (String)->Unit){keyboardText=initial.take(1200);keyboardSubmit=submit}
    fun highContrast(enabled: Boolean){highContrast=enabled;prefs.edit().putBoolean("contrast",enabled).apply()}
    private val animation=Array(14){HoverAnimation()}
    private var selected=-1
    private var selectedUntil=0L
    private var highContrast=prefs.getBoolean("contrast",false)
    fun build(dt: Float,now: Long){
        atlas.begin();count=0;settings.constrain()
        // Render background windows first. Each header, control, icon and card is its own 3D object.
        windows.windows.filter{!it.minimized}.sortedBy{it.position.z}.forEach{w->
            val bg=if(highContrast).04f else .07f
            add(0,atlas.tile("empty"),w.position.x,w.position.y,w.position.z,w.width,w.height,w.yaw,w.opacity,0f,bg,.08f,.15f)
            fun child(id: Int,tile: Int,x: Float,y: Float,width: Float,height: Float,disabled: Boolean=false,kind: Int=0){
                val cy=cos(w.yaw);val sy=sin(w.yaw)
                add(id,tile,w.position.x+cy*x,w.position.y+y,w.position.z-sy*x+.006f,width,height,w.yaw,w.opacity,if(hovered==id)1f else 0f,.1f,.13f,.22f,if(disabled)2 else kind,if(selected==id&&now<selectedUntil)1f else 0f)
            }
            child(0,atlas.tile("title-${w.kind}",w.kind.name),-.15f,w.height*.42f,w.width*.48f,.12f,kind=1)
            listOf("−","×",if(w.pinned)"FIXO" else "FIXAR","MOVER","±","GIRAR").forEachIndexed{i,label->child(1000+w.kind.ordinal*10+i,atlas.tile("chrome-$i-${w.pinned}",label),w.width*(-.4f+i*.16f),-w.height*.42f,w.width*.145f,.085f)}
            if(surfaceKind==w.kind){
                child(8000+w.kind.ordinal,atlas.tile("empty"),0f,-w.height*.045f,w.width*.95f,w.height*.49f,kind=3)
                val controls=if(w.kind==WindowKind.BROWSER)listOf("URL","VOLTAR","DIGITAR","PARAR") else listOf("", "PARAR CAPTURA")
                controls.forEachIndexed{i,t->if(t.isNotEmpty())child(2000+w.kind.ordinal*100+i,atlas.tile("surface-${w.kind}-$i",t),(i-(controls.size-1)/2f)*w.width*.23f,w.height*.28f,w.width*.22f,.09f)}
            }
            else {
                val actions=pages[w.kind].orEmpty().take(8)
                actions.forEachIndexed { i,a->
                    val x=if(i%2==0)-w.width*.235f else w.width*.235f
                    val y=w.height*(.22f-(i/2)*.145f)
                    child(2000+w.kind.ordinal*100+i,atlas.tile("action-${w.kind}-$i",a.title),x,y,w.width*.445f,w.height*.135f,!a.enabled)
                }
            }
        }
        if(visible){
            var fadingLabels=0
            DockItem.entries.forEachIndexed { i,item->
                val a=animation[i].update(hovered==100+i,dt,settings.reducedMotion)
                val angle=(i%7-3)*.16f*settings.scale
                val x=settings.x+sin(angle)*settings.distance
                val y=settings.y-(i/7)*.19f*settings.scale
                val z=-cos(angle)*settings.distance
                val color=when(i%4){0->floatArrayOf(.3f,.18f,.58f);1->floatArrayOf(.08f,.32f,.45f);2->floatArrayOf(.42f,.16f,.3f);else->floatArrayOf(.11f,.36f,.28f)}
                add(100+i,atlas.tile("icon-$i",icon=i),x,y,z,.205f*settings.scale,.155f*settings.scale,-angle,settings.opacity,a,color[0],color[1],color[2],selected=if(selected==100+i&&now<selectedUntil)1f else 0f)
                if(a>.01f&&(hovered==100+i||fadingLabels++<SpatialBudget.FADING_LABELS))add(0,atlas.tile("label-$i",item.label),x,y-.105f*settings.scale,z+.012f,.32f*settings.scale*(.92f+a*.08f),.06f*settings.scale,-angle,a,0f,0f,0f,0f,1)
            }
        }
        if(keyboardSubmit!=null){
            add(0,atlas.tile("typing",keyboardText.takeLast(55)),0f,.23f,-1.15f,1.14f,.13f,0f,.96f,0f,.1f,.15f,.22f)
            keys.forEachIndexed{i,char->add(9000+i,atlas.tile("key-$char",char.toString()),(i%10-4.5f)*.108f,.09f-(i/10)*.105f,-1.15f,.097f,.09f,0f,.96f,if(hovered==9000+i)1f else 0f,.13f,.16f,.27f)}
            listOf("ESPAÇO","APAGAR","ENVIAR","FECHAR").forEachIndexed{i,t->add(9100+i,atlas.tile("keyboard-$i",t),(i-1.5f)*.28f,-.36f,-1.14f,.26f,.09f,0f,.97f,if(hovered==9100+i)1f else 0f,.22f,.18f,.4f)}
        }
        add(0,atlas.tile("headline",headline),0f,.75f,-2f,1.45f,.18f,0f,.94f,0f,.04f,if(cameraActive).25f else .08f,.16f)
        add(0,atlas.tile("detail",detail),0f,.60f,-2f,1.5f,.14f,0f,.9f,0f,.07f,.09f,.16f)
    }
    private fun add(id: Int,tile: Int,x: Float,y: Float,z: Float,w: Float,h: Float,yaw: Float,opacity: Float,hover: Float,r: Float,g: Float,b: Float,kind: Int=0,selected: Float=0f){
        if(count>=SpatialBudget.PACKET_ITEMS)return
        val o=count++*20
        packet[o]=x;packet[o+1]=y;packet[o+2]=z;packet[o+3]=w
        packet[o+4]=h;packet[o+5]=yaw;packet[o+6]=opacity;packet[o+7]=hover
        packet[o+8]=(tile%8)/8f;packet[o+9]=(tile/8)/16f;packet[o+10]=(tile%8+1)/8f;packet[o+11]=(tile/8+1)/16f
        packet[o+12]=r;packet[o+13]=g;packet[o+14]=b;packet[o+15]=kind.toFloat()
        packet[o+16]=selected;packet[o+17]=0f;packet[o+18]=id.toFloat();packet[o+19]=.1f
    }
    fun select(id: Int,now: Long): Boolean {
        if(id<0)return false
        selected=id;selectedUntil=now+180_000_000
        if(id in 9000 until 9000+keys.length){keyboardText=(keyboardText+keys[id-9000].lowercaseChar()).take(1200);return true}
        if(id in 9100..9103){when(id){9100->keyboardText=(keyboardText+" ").take(1200);9101->keyboardText=keyboardText.dropLast(1);9102->{val callback=keyboardSubmit;keyboardSubmit=null;callback?.invoke(keyboardText)};9103->keyboardSubmit=null};return true}
        if(id in 100..113){onDock(DockItem.entries[id-100]);return true}
        if(id in 1000..1199){val k=(id-1000)/10;val action=(id-1000)%10
            val w=windows.windows.find{it.kind.ordinal==k} ?: return false;windows.focus(w.id)
            when(action){0->windows.minimize(w.id);1->windows.close(w.id);2->w.pinned=!w.pinned;3->{movingWindow=!movingWindow;movingDock=false};4->{w.toggleMaximize()};5->if(!w.pinned)w.yaw+=.15f};save();return true
        }
        if(id>=2000){val kind=WindowKind.entries.getOrNull((id-2000)/100) ?: return false;val index=(id-2000)%100
            windows.windows.find{it.kind==kind}?.let{windows.focus(it.id)}
            pages[kind]?.getOrNull(index)?.let{if(it.enabled)it.run()};return true}
        return false
    }
    fun gesture(e: GestureEvent){
        val w=windows.windows.find{it.id==windows.focus}
        when(e.kind){
            GestureKind.PALM_MENU->visible=!visible
            GestureKind.DRAG->{if(movingDock){settings.x+=e.x*3;settings.y-=e.y*3}else if(movingWindow&&w!=null&&!w.pinned)w.position=w.position+Vec3(e.x*3,-e.y*3,0f)}
            GestureKind.TWO_HAND_SCALE->if(w!=null&&!w.pinned)w.resize(e.x)
            GestureKind.TWO_HAND_ROTATE->if(w!=null&&!w.pinned)w.yaw+=e.x
            GestureKind.PINCH_RELEASE->save()
            else->Unit
        }
    }
    fun save(){
        settings.constrain();prefs.edit().putFloat("dockX",settings.x).putFloat("dockY",settings.y).putFloat("dockDistance",settings.distance).putFloat("dockScale",settings.scale).putFloat("dockOpacity",settings.opacity).putBoolean("reducedMotion",settings.reducedMotion).apply()
        val encoded=windows.windows.joinToString(";"){"${it.id},${it.kind.name},${it.position.x},${it.position.y},${it.position.z},${it.width},${it.height},${it.yaw},${it.pinned},${it.minimized}"}
        prefs.edit().putString("spatialLayout",encoded).apply()
    }
    fun restore(){
        prefs.getString("spatialLayout","")!!.split(';').filter{it.isNotBlank()}.take(SpatialBudget.WINDOWS).forEach{line->runCatching{
            val p=line.split(',');val coords=(2..7).map{p[it].toFloat().also{v->require(v.isFinite())}}
            windows.restore(SpatialWindow(p[0].toInt(),WindowKind.valueOf(p[1]),Vec3(coords[0].coerceIn(-3f,3f),coords[1].coerceIn(-2f,2f),coords[2].coerceIn(-4f,-.5f)),coords[3].coerceIn(.45f,2.8f),coords[4].coerceIn(.3f,1.8f),coords[5],pinned=p[8].toBoolean(),minimized=p[9].toBoolean()))
        }}
    }
}
