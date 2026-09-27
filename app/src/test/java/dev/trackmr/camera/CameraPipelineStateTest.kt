package dev.trackmr.camera

import org.junit.Assert.*
import org.junit.Test

class CameraPipelineStateTest {
    private val start=1_000_000_000L
    @Test fun activePreviewWithoutCpuImagesCanFallBack(){
        val stream=CpuImageStream()
        repeat(8){stream.attempt(start+it*1_000_000_000L);stream.unavailable()}
        assertTrue(stream.stalled(start+7_000_000_000L,start))
        assertEquals(8L,stream.deferred.get())
        assertEquals(0L,stream.errors.get())
    }
    @Test fun warmupIsNotAnError(){
        val stream=CpuImageStream()
        repeat(4){stream.attempt(start+it*100_000_000L);stream.unavailable()}
        assertFalse(stream.stalled(start+300_000_000L,start))
    }
    @Test fun lateModelInitializationGetsItsOwnWarmup(){
        val stream=CpuImageStream()
        repeat(4){stream.attempt(start+10_000_000_000L+it*100_000_000L);stream.unavailable()}
        assertFalse(stream.stalled(start+10_300_000_000L,start))
    }
    @Test fun deliveredFramesNeverFallBackJustBecauseThereAreNoHands(){
        val stream=CpuImageStream()
        repeat(20){val t=start+it*1_000_000_000L;stream.attempt(t);stream.delivered(t)}
        assertFalse(stream.stalled(start+19_000_000_000L,start))
    }
    @Test fun stoppedDeliveryWithFreshFailedRequestsFallsBack(){
        val stream=CpuImageStream()
        stream.attempt(start);stream.delivered(start)
        repeat(8){stream.attempt(start+it*1_000_000_000L);stream.failed(IllegalStateException("acquire"))}
        assertTrue(stream.stalled(start+7_000_000_000L,start))
        assertTrue(stream.lastError!!.contains("IllegalStateException"))
    }
    @Test fun busyInferenceWithoutRecentAcquisitionAttemptsIsNotCameraFailure(){
        val stream=CpuImageStream()
        repeat(3){stream.attempt(start+it);stream.delivered(start+it)}
        assertFalse(stream.stalled(start+10_000_000_000L,start))
    }
    @Test fun cameraRestartWaitsForActualRelease(){
        val gate=CameraHandoff()
        assertTrue(gate.requestStart())
        gate.beginClose()
        assertFalse(gate.requestStart())
        assertFalse(gate.requestStart())
        assertTrue(gate.finishClose(true))
        assertTrue(gate.requestStart())
    }
    @Test fun pauseCancelsPendingRestart(){
        val gate=CameraHandoff();gate.beginClose();gate.requestStart();gate.cancelPending()
        assertFalse(gate.finishClose(true))
    }
    @Test fun failedCloseDoesNotOpenASecondOwner(){
        val gate=CameraHandoff();gate.beginClose();gate.requestStart()
        assertFalse(gate.finishClose(false));assertFalse(gate.requestStart())
    }
}
