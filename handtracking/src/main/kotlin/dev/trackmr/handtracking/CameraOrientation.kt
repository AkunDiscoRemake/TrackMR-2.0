package dev.trackmr.handtracking

/** Rear camera only. ImageReader pixels and SurfaceTexture have DIFFERENT orientation contracts. */
object CameraOrientation {
    fun imageRotation(sensor: Int,displayDegrees: Int): Int = (sensor-displayDegrees+360)%360
    fun imageToView(sensor: Int,displayDegrees: Int): FloatArray = when(imageRotation(sensor,displayDegrees)){
        90->floatArrayOf(1f,0f,1f,1f,0f,0f)
        180->floatArrayOf(1f,1f,0f,1f,1f,0f)
        270->floatArrayOf(0f,1f,0f,0f,1f,1f)
        else->floatArrayOf(0f,0f,1f,0f,0f,1f)
    }
    /** SurfaceTexture already contains sensor rotation AND the buffer's Y flip. Undo display only. */
    fun displayToSurface(displayDegrees: Int): FloatArray {
        val r=(360-displayDegrees)%360
        val c=when(r){0->1f;180->-1f;else->0f}
        val s=when(r){90->1f;270->-1f;else->0f}
        return floatArrayOf(c,s,0f,0f,-s,c,0f,0f,0f,0f,1f,0f,.5f-.5f*c+.5f*s,.5f-.5f*s-.5f*c,0f,1f)
    }
}
