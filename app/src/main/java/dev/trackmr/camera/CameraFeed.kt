package dev.trackmr.camera

import android.media.Image
import android.opengl.Matrix

/** Camera ownership boundary. No UI or renderer knows about MediaPipe. */
interface CameraConsumer {
    fun reserve(nowNs: Long): Boolean
    fun cancelReservation()
    fun submit(image: Image, viewTransform: FloatArray, clockKnown: Boolean=true)
}
class CameraFrame {
    val textureTransform=FloatArray(16).apply{Matrix.setIdentityM(this,0)}
    val depthTransform=FloatArray(16).apply{Matrix.setIdentityM(this,0)}
    val projection=FloatArray(16).apply{Matrix.perspectiveM(this,0,60f,4/3f,.05f,100f)}
    var pose: FloatArray?=null
    var timestampNs=0L
    var realtimeClock=true
    var active=false
    var tracking=false
    var planes=0
    var anchors=0
    var light=1f
    var depthAvailable=false
    var depthData: java.nio.ByteBuffer?=null
    var depthWidth=0;var depthHeight=0;var depthTimestampNs=0L
    var width=0;var height=0
}
interface CameraFeed : AutoCloseable {
    val name: String
    val status: String
    fun start(texture: Int,width: Int,height: Int): Boolean // GL thread, permission already granted
    fun frame(width: Int,height: Int): CameraFrame
    fun pause()
    fun recenter() {}
    fun placeAtCenter(): Boolean=false
    fun anchorPositions(): FloatArray=FloatArray(0)
}
