package com.trackmr.xr.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

object GlUtil {
    private const val TAG = "TrackMR-GL"

    fun floatBuffer(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fun shortBuffer(n: Int): ShortBuffer = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw IllegalStateException("Shader compile failed: $log\n$src")
        }
        return s
    }

    fun program(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            throw IllegalStateException("Program link failed: $log")
        }
        return p
    }

    fun genTexture(target: Int = GLES30.GL_TEXTURE_2D): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(target, t[0])
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    fun deleteTexture(id: Int) { if (id > 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0) }

    fun checkError(where: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) Log.w(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
    }
}

/** Linked GLSL program with a uniform location cache. */
class ShaderProgram(vs: String, fs: String) {
    val id: Int = GlUtil.program(vs, fs)
    private val locs = HashMap<String, Int>(32)

    fun use() = GLES30.glUseProgram(id)
    fun loc(name: String): Int = locs.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun mat4(name: String, m: FloatArray, off: Int = 0) { val l = loc(name); if (l >= 0) GLES30.glUniformMatrix4fv(l, 1, false, m, off) }
    fun mat3(name: String, m: FloatArray) { val l = loc(name); if (l >= 0) GLES30.glUniformMatrix3fv(l, 1, false, m, 0) }
    fun f1(name: String, v: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform1f(l, v) }
    fun f2(name: String, a: Float, b: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform2f(l, a, b) }
    fun f3(name: String, a: Float, b: Float, c: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform3f(l, a, b, c) }
    fun f4(name: String, a: Float, b: Float, c: Float, d: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform4f(l, a, b, c, d) }
    fun f4(name: String, v: FloatArray) { val l = loc(name); if (l >= 0) GLES30.glUniform4fv(l, 1, v, 0) }
    fun i1(name: String, v: Int) { val l = loc(name); if (l >= 0) GLES30.glUniform1i(l, v) }

    fun release() = GLES30.glDeleteProgram(id)
}

/**
 * Static mesh with interleaved layout: position(3) normal(3) uv(2) = 8 floats per vertex.
 * Attribute locations: 0 = position, 1 = normal, 2 = uv.
 */
class Mesh(vertices: FloatArray, indices: ShortArray, val mode: Int = GLES30.GL_TRIANGLES) {
    private val vao = IntArray(1)
    private val buffers = IntArray(2)
    val indexCount = indices.size
    /** Local-space bounding sphere radius (for picking / culling). */
    val boundingRadius: Float

    init {
        var r2 = 0f
        var i = 0
        while (i < vertices.size) {
            val d = vertices[i] * vertices[i] + vertices[i + 1] * vertices[i + 1] + vertices[i + 2] * vertices[i + 2]
            if (d > r2) r2 = d
            i += STRIDE_FLOATS
        }
        boundingRadius = kotlin.math.sqrt(r2)
        val vb = GlUtil.floatBuffer(vertices.size).put(vertices).also { it.position(0) }
        val ib = GlUtil.shortBuffer(indices.size).put(indices).also { it.position(0) }
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GLES30.GL_STATIC_DRAW)
        val stride = STRIDE_FLOATS * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawElements(mode, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }

    companion object { const val STRIDE_FLOATS = 8 }
}

/**
 * Streaming vertex buffer (position(3) + color(4)) for lines, points and small dynamic
 * geometry. Storage is allocated once; only the used range is uploaded each frame.
 */
class DynamicGeometry(private val maxVertices: Int) {
    private val vao = IntArray(1)
    private val vbo = IntArray(1)
    val data = FloatArray(maxVertices * 7)
    private val fb = GlUtil.floatBuffer(maxVertices * 7)
    var count = 0
        private set

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxVertices * 28, null, GLES30.GL_STREAM_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 28, 0)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 28, 12)
        GLES30.glBindVertexArray(0)
    }

    fun begin() { count = 0 }

    fun vertex(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float): Boolean {
        if (count >= maxVertices) return false
        val o = count * 7
        data[o] = x; data[o + 1] = y; data[o + 2] = z
        data[o + 3] = r; data[o + 4] = g; data[o + 5] = b; data[o + 6] = a
        count++
        return true
    }

    fun upload() {
        if (count == 0) return
        fb.position(0)
        fb.put(data, 0, count * 7)
        fb.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 28, fb)
    }

    fun draw(mode: Int) {
        if (count == 0) return
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(mode, 0, count)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(1, vbo, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }
}

class Texture2D private constructor(val id: Int, var width: Int, var height: Int) {

    fun bind(unit: Int = 0) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
    }

    /** Re-uploads bitmap contents (same size uses glTexSubImage2D, no reallocation). */
    fun update(bitmap: Bitmap, mipmaps: Boolean = false) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        if (bitmap.width == width && bitmap.height == height) {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        } else {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            width = bitmap.width; height = bitmap.height
        }
        if (mipmaps) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    fun release() = GlUtil.deleteTexture(id)

    /** Approximate GPU bytes. */
    val bytes: Long get() = width.toLong() * height * 4L * 4 / 3

    companion object {
        fun fromBitmap(bitmap: Bitmap, mipmaps: Boolean = true, repeat: Boolean = false): Texture2D {
            val id = GlUtil.genTexture()
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            if (mipmaps) {
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            }
            if (repeat) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
            }
            return Texture2D(id, bitmap.width, bitmap.height)
        }

        fun empty(w: Int, h: Int, internalFormat: Int, format: Int, type: Int, nearest: Boolean = false): Texture2D {
            val id = GlUtil.genTexture()
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, null)
            if (nearest) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            }
            return Texture2D(id, w, h)
        }

        fun wrap(id: Int, w: Int, h: Int) = Texture2D(id, w, h)
    }
}

/**
 * GL_TEXTURE_EXTERNAL_OES texture fed by a SurfaceTexture: used for video (cinema), web pages
 * (browser), Android app windows (virtual displays) and the Camera2 passthrough fallback.
 * Frames are latched on the GL thread only when a new one is available.
 */
class ExternalSurfaceTexture(width: Int, height: Int) {
    val textureId: Int = GlUtil.genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
    val surfaceTexture = SurfaceTexture(textureId)
    val surface = Surface(surfaceTexture)
    val transform = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    var width = width; private set
    var height = height; private set
    @Volatile private var frameAvailable = false
    var hasFrame = false; private set
    var frameCounter = 0L; private set

    init {
        surfaceTexture.setDefaultBufferSize(width, height)
        surfaceTexture.setOnFrameAvailableListener { frameAvailable = true }
    }

    fun resize(w: Int, h: Int) {
        width = w; height = h
        surfaceTexture.setDefaultBufferSize(w, h)
    }

    /** Call on the GL thread once per frame. Returns true when a new frame was latched. */
    fun latch(): Boolean {
        if (!frameAvailable) return false
        frameAvailable = false
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transform)
            hasFrame = true
            frameCounter++
        } catch (e: RuntimeException) {
            Log.w("TrackMR-GL", "updateTexImage failed: ${e.message}")
            return false
        }
        return true
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
        GlUtil.deleteTexture(textureId)
    }
}

/** Color + depth render target. */
class FrameBuffer(var width: Int, var height: Int, private val withDepth: Boolean = true) {
    private val fbo = IntArray(1)
    private val depthRb = IntArray(1)
    var colorTex = 0; private set

    init { allocate() }

    private fun allocate() {
        colorTex = GlUtil.genTexture()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, colorTex, 0)
        if (withDepth) {
            GLES30.glGenRenderbuffers(1, depthRb, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb[0])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthRb[0])
        }
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) Log.e("TrackMR-GL", "FBO incomplete: 0x${Integer.toHexString(status)}")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        release()
        width = w; height = h
        allocate()
    }

    fun bind() = GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])

    /** Tells tiled GPUs the depth contents need not be written back to memory. */
    fun invalidateDepth() {
        if (!withDepth) return
        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, intArrayOf(GLES30.GL_DEPTH_ATTACHMENT), 0)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        if (withDepth) GLES30.glDeleteRenderbuffers(1, depthRb, 0)
        GlUtil.deleteTexture(colorTex)
    }

    companion object {
        /** Wraps an externally owned color texture (e.g. an OpenXR swapchain image). */
        fun attachTexture(fboId: Int, tex: Int) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        }
    }
}
