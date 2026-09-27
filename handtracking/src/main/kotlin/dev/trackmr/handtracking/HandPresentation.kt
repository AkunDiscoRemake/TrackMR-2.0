package dev.trackmr.handtracking

/** The model's completion clock is not the image acquisition clock. Never conflate the two. */
enum class HandPresentation { NONE, LIVE, SLOW, EXPIRED }
object HandDelivery {
    fun state(nowNs: Long,capturedNs: Long,completedNs: Long,processingMs: Float,
              intervalMs: Long,hasHands: Boolean,enabled: Boolean=true): HandPresentation {
        if(!enabled||!hasHands)return HandPresentation.NONE
        if(!processingMs.isFinite()||processingMs<0||capturedNs<=0||completedNs<capturedNs||nowNs<completedNs)
            return HandPresentation.EXPIRED
        val sourceAge=nowNs-capturedNs
        val resultAge=nowNs-completedNs
        // Keep the result through ONE expected next inference + acquisition jitter, never indefinitely.
        val holdMs=(maxOf(processingMs,intervalMs.toFloat())+80f).coerceIn(120f,350f)
        if(resultAge>(holdMs*1_000_000).toLong()||sourceAge>1_500_000_000L)return HandPresentation.EXPIRED
        // Slow observed hands remain visible in amber, but cannot start/hold a contact.
        return if(processingMs<=300f&&sourceAge<=650_000_000L)HandPresentation.LIVE else HandPresentation.SLOW
    }
}
