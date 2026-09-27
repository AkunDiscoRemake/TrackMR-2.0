package dev.trackmr.camera

import java.util.concurrent.atomic.AtomicLong

/** Local counters only: no camera pixels or landmarks are logged. */
class CpuImageStream {
    val attempts=AtomicLong()
    val delivered=AtomicLong()
    val deferred=AtomicLong()
    val errors=AtomicLong()
    private val firstAttempt=AtomicLong()
    private val lastAttempt=AtomicLong()
    private val lastDelivery=AtomicLong()
    @Volatile var lastError: String?=null;private set
    fun attempt(now: Long){firstAttempt.compareAndSet(0,now);lastAttempt.set(now);attempts.incrementAndGet()}
    fun delivered(now: Long){lastDelivery.set(now);delivered.incrementAndGet()}
    fun unavailable(){deferred.incrementAndGet()}
    fun failed(error: Exception){lastError="${error.javaClass.simpleName}: ${error.message}";errors.incrementAndGet()}
    fun description()="YUV ${delivered.get()} • espera ${deferred.get()} • erros ${errors.get()}"
    /** Never blame ARCore for zero hands or a busy/stalled inference worker. */
    fun stalled(now: Long,started: Long): Boolean {
        val last=maxOf(started,lastDelivery.get())
        return attempts.get()>=3 && now-maxOf(started,firstAttempt.get())>6_000_000_000L &&
            now-last>4_000_000_000L && lastAttempt.get()-last>2_000_000_000L &&
            now-lastAttempt.get() in 0L..1_000_000_000L
    }
}

/** GL-thread state machine. A replacement cannot start until old camera + inference release. */
class CameraHandoff {
    private var closing=false
    private var requested=false
    fun requestStart(): Boolean {if(closing){requested=true;return false};return true}
    fun cancelPending(){requested=false}
    fun beginClose(){check(!closing);closing=true;requested=false}
    fun finishClose(released: Boolean): Boolean {
        closing=!released
        val restart=released&&requested
        requested=false
        return restart
    }
}
