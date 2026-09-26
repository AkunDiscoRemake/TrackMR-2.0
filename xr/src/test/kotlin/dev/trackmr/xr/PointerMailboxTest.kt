package dev.trackmr.xr
import org.junit.Test
import org.junit.Assert.*
class PointerMailboxTest {
 @Test fun coalescesMovesButKeepsEdges(){val q=PointerMailbox();assertTrue(q.offer(PointerPacket(0)));repeat(1000){assertFalse(q.offer(PointerPacket(2,it/1000f)))};q.offer(PointerPacket(1));assertEquals(0,q.take()!!.action);assertEquals(.999f,q.take()!!.u,0f);assertEquals(1,q.take()!!.action);assertNull(q.take());assertTrue(q.offer(PointerPacket(0)))}
 @Test fun overflowBecomesCancelNotStuckDown(){val q=PointerMailbox(2);q.offer(PointerPacket(0));q.offer(PointerPacket(1));q.offer(PointerPacket(0));assertEquals(3,q.take()!!.action);assertNull(q.take())}
 @Test fun explicitCancelDiscardsPendingDowns(){val q=PointerMailbox();q.offer(PointerPacket(0));q.offer(PointerPacket(2));q.offer(PointerPacket(3));assertEquals(3,q.take()!!.action);assertNull(q.take())}
}
