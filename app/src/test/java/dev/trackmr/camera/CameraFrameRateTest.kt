package dev.trackmr.camera

import org.junit.Assert.*
import org.junit.Test

class CameraFrameRateTest {
    @Test fun fixedThirtyWinsOverFiveToThirty(){
        assertEquals(30 to 30,CameraFrameRate.choose(listOf(5 to 30,15 to 30,30 to 30,30 to 60)))
    }
    @Test fun doNotInventUnsupportedFixedRange(){
        assertEquals(15 to 30,CameraFrameRate.choose(listOf(5 to 30,15 to 30)))
        assertEquals(15 to 15,CameraFrameRate.choose(listOf(5 to 15,15 to 15)))
        assertEquals(30 to 60,CameraFrameRate.choose(listOf(30 to 60,30 to 120)))
        assertNull(CameraFrameRate.choose(emptyList()))
    }
}
