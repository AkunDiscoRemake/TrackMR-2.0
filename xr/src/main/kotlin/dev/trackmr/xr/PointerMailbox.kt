package dev.trackmr.xr

/** Motion actions follow Android's stable input protocol, without an Android dependency. */
data class PointerPacket(val action: Int,val u: Float=.5f,val v: Float=.5f,val scroll: Float=0f)
class PointerMailbox(private val capacity: Int=8) {
    private val queue=ArrayDeque<PointerPacket>()
    private var draining=false
    @Synchronized fun offer(packet: PointerPacket): Boolean {
        if(packet.action==3){queue.clear();queue.addLast(packet)}
        else if(packet.action==2&&queue.lastOrNull()?.action==2){queue.removeLast();queue.addLast(packet)}
        else if(queue.size>=capacity){queue.clear();queue.addLast(PointerPacket(3))}
        else queue.addLast(packet)
        if(draining)return false
        draining=true;return true
    }
    /** Checking empty and relinquishing the worker happen under the same lock as offer. */
    @Synchronized fun take(): PointerPacket? {
        val packet=queue.removeFirstOrNull()
        if(packet==null)draining=false
        return packet
    }
    @Synchronized fun abandon(){queue.clear();draining=false}
}
