package dev.trackmr.xr

import kotlin.math.*

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(b: Vec3)=Vec3(x+b.x,y+b.y,z+b.z)
    operator fun minus(b: Vec3)=Vec3(x-b.x,y-b.y,z-b.z)
    operator fun times(s: Float)=Vec3(x*s,y*s,z*s)
    fun length()=sqrt(x*x+y*y+z*z)
    fun normalized(): Vec3 { val n=length();return if(n>.00001f)this*(1/n) else Vec3(0f,0f,-1f) }
}
enum class Experience { MR, VR, SPATIAL_SAFE }
enum class CameraState { STOPPED, STARTING, ACTIVE, DENIED, ERROR }
enum class CapabilityState { AVAILABLE, UNAVAILABLE, PERMISSION_REQUIRED, UNVERIFIED }
data class Capability(val state: CapabilityState,val reason: String)
data class DeviceCapabilities(val camera: Boolean,val gyro: Boolean,val arCore: Capability,
    val depth: Capability,val openXr: Capability,val refreshHz: Float,val lowRam: Boolean)

/** Startup is ALWAYS spatial and requests MR. Never silently present a panorama as MR. */
class ExperienceState {
    var requested=Experience.MR; private set
    var active=Experience.SPATIAL_SAFE; private set
    var camera=CameraState.STOPPED; private set
    var reason="Inicializando câmera; ainda sem passthrough"; private set
    fun request(mode: Experience) { requested=mode;refresh() }
    fun camera(state: CameraState, detail: String="") { camera=state;reason=detail;refresh() }
    private fun refresh() { active=when {
        requested==Experience.VR -> Experience.VR
        camera==CameraState.ACTIVE -> Experience.MR
        else -> Experience.SPATIAL_SAFE
    } }
}

enum class DockItem(val label: String) {
    HOME("Home"), LIBRARY("Biblioteca"), STORE("Loja"), SETTINGS("Configurações"),
    MR("Realidade mista"), VR("Realidade virtual"), BROWSER("Browser"), RECENTS("Recentes"),
    ENVIRONMENTS("Ambientes"), CAPTURE("Captura"), NOTIFICATIONS("Notificações"),
    PERFORMANCE("Performance"), TRACKING("Tracking"), SYSTEM("Sistema")
}
data class DockSettings(var x: Float=0f,var y: Float=-.38f,var distance: Float=1.6f,
    var scale: Float=1f,var opacity: Float=.88f,var reducedMotion: Boolean=false) {
    fun constrain() { x=x.coerceIn(-2f,2f);y=y.coerceIn(-1.5f,1.5f);distance=distance.coerceIn(.7f,3f);scale=scale.coerceIn(.65f,1.6f);opacity=opacity.coerceIn(.35f,1f) }
}
class HoverAnimation {
    var amount=0f;private set
    fun update(hover: Boolean,dt: Float,reducedMotion: Boolean=false): Float {
        val goal=if(hover)1f else 0f
        amount=if(reducedMotion)goal else amount+(goal-amount)*(1-exp(-dt.coerceIn(0f,.1f)*16f))
        return amount
    }
}

enum class WindowKind { HOME, LIBRARY, STORE, SETTINGS, BROWSER, CAPTURE, DIAGNOSTICS, NOTIFICATIONS, TRACKING, SYSTEM, ENVIRONMENTS, ASSISTANT, APP_DETAIL, STORE_DETAIL }
data class SpatialWindow(val id: Int,val kind: WindowKind,var position: Vec3=Vec3(0f,.22f,-1.9f),
    var width: Float=1.35f,var height: Float=.78f,var yaw: Float=0f,var opacity: Float=.92f,
    var minimized: Boolean=false,var pinned: Boolean=false,var maximized: Boolean=false) {
    private var previousSize: Pair<Float,Float>?=null
    fun toggleMaximize(){
        if(!maximized){previousSize=width to height;width=2.2f;height=1.35f}
        else previousSize?.let{width=it.first;height=it.second}
        maximized=!maximized
    }
    fun resize(factor: Float) { if(!factor.isFinite())return;width=(width*factor).coerceIn(.45f,2.8f);height=(height*factor).coerceIn(.3f,1.8f) }
}
/** Session-relative layout persistence is NOT a persistent physical/spatial anchor. */
class WindowManager(private val limit: Int=5) {
    val windows=mutableListOf<SpatialWindow>()
    var focus: Int?=null;private set
    private var next=1
    fun spawn(kind: WindowKind): SpatialWindow {
        windows.firstOrNull { it.kind==kind }?.let { it.minimized=false;focus=it.id;return it }
        if(windows.size>=limit) { val victim=windows.firstOrNull { !it.pinned } ?: return windows.last();windows.remove(victim) }
        return SpatialWindow(next++,kind,Vec3((windows.size%3-1)*.12f,.22f,-1.9f-windows.size*.1f)).also { windows.add(it);focus=it.id }
    }
    fun focus(id: Int) { if(windows.any { it.id==id&&!it.minimized })focus=id }
    fun close(id: Int) { windows.removeAll { it.id==id };if(focus==id)focus=windows.lastOrNull { !it.minimized }?.id }
    fun minimize(id: Int) { windows.find { it.id==id }?.minimized=true;if(focus==id)focus=windows.lastOrNull { !it.minimized }?.id }
    fun snap(id: Int,slot: Int) { windows.find { it.id==id }?.apply { if(!pinned){position=Vec3(slot.coerceIn(-1,1)*1.3f,.2f,-2f);yaw=-slot*.35f} } }
    fun recenter() { windows.filter { !it.pinned }.forEachIndexed { i,w->w.position=Vec3(i*.12f,.22f,-1.9f-i*.15f);w.yaw=0f } }
    fun restore(w: SpatialWindow) { if(w.id>0&&windows.none { it.id==w.id }&&windows.size<limit){windows.add(w);next=maxOf(next,w.id+1);focus=w.id} }
}
