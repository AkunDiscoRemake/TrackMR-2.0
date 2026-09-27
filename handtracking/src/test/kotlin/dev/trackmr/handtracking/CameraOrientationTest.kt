package dev.trackmr.handtracking

import org.junit.Assert.*
import org.junit.Test

class CameraOrientationTest {
    @Test fun oesAndYuvAgreeForEveryRearSensorAndDisplayRotation(){
        for(sensor in listOf(0,90,180,270))for(display in listOf(0,90,180,270)){
            val correction=CameraOrientation.displayToSurface(display)
            val map=CameraOrientation.imageToView(sensor,display)
            val r=CameraOrientation.imageRotation(sensor,display)
            assertEquals(r,ImageOrientation.degrees(map))
            // AOSP GLConsumer: producer sensor rotation followed by GL->top-left buffer flip.
            for(u in listOf(.03f,.35f,.97f))for(v in listOf(.06f,.6f,.94f)){
                val x=correction[0]*u+correction[4]*v+correction[12]
                val y=correction[1]*u+correction[5]*v+correction[13]
                val sx=when(sensor){90->1-y;180->1-x;270->y;else->x}
                val sy=1-when(sensor){90->x;180->1-y;270->1-x;else->y}
                // u/v are screen GL bottom-left. The tracker/CPU convention is top-left.
                assertEquals("sensor=$sensor display=$display x",ImageOrientation.sourceX(u,1-v,r),sx,.00001f)
                assertEquals("sensor=$sensor display=$display y",ImageOrientation.sourceY(u,1-v,r),sy,.00001f)
                assertEquals(u,map[0]+sx*(map[2]-map[0])+sy*(map[4]-map[0]),.00001f)
                assertEquals(1-v,map[1]+sx*(map[3]-map[1])+sy*(map[5]-map[1]),.00001f)
            }
        }
    }
    @Test fun landscapePhoneDoesNotApplySensorRotationTwice(){
        val m=CameraOrientation.displayToSurface(90)
        assertEquals(0f,m[0],0f);assertEquals(-1f,m[1],0f)
        assertEquals(0,CameraOrientation.imageRotation(90,90))
        assertEquals(180,CameraOrientation.imageRotation(270,90))
    }
}
