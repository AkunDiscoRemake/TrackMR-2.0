package com.trackmr.xr.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.trackmr.xr.gl.Mesh
import com.trackmr.xr.gl.MeshFactory
import com.trackmr.xr.gl.ShaderProgram
import com.trackmr.xr.gl.Shaders
import com.trackmr.xr.math.Pose

/**
 * GL-thread render kit: compiled programs, shared primitive meshes and helpers that bind
 * the common uniforms. Created once when the EGL context is ready.
 */
class Gfx private constructor() {
    val lit = ShaderProgram(Shaders.LIT_VS, Shaders.LIT_FS)
    val unlit = ShaderProgram(Shaders.UNLIT_VS, Shaders.UNLIT_FS)
    val oes = ShaderProgram(Shaders.OES_VS, Shaders.OES_FS)
    val camera = ShaderProgram(Shaders.CAMERA_VS, Shaders.CAMERA_FS)
    val sky = ShaderProgram(Shaders.SKY_VS, Shaders.SKY_FS)
    val color = ShaderProgram(Shaders.COLOR_VS, Shaders.COLOR_FS)
    val depth = ShaderProgram(Shaders.DEPTH_VS, Shaders.DEPTH_FS)
    val distort = ShaderProgram(Shaders.DISTORT_VS, Shaders.DISTORT_FS)

    val unitQuad: Mesh = MeshFactory.quad(1f, 1f)
    val unitBox: Mesh = MeshFactory.box(1f, 1f, 1f)
    val unitSphere: Mesh = MeshFactory.sphere(0.5f, 24, 12)
    val unitCylinder: Mesh = MeshFactory.cylinder(0.5f, 1f, 24)
    val unitDisc: Mesh = MeshFactory.disc(0.5f, 32)
    val skySphere: Mesh = MeshFactory.sphere(50f, 64, 32, inside = true)

    private val mvp = FloatArray(16)
    private val model = FloatArray(16)

    private fun bindOcclusion(p: ShaderProgram, ctx: EyeContext, enabled: Boolean) {
        val on = enabled && ctx.occlusionEnabled && ctx.depthTexture > 0
        p.i1("uOcclusion", if (on) 1 else 0)
        if (on) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ctx.depthTexture)
            p.i1("uDepthTex", 3)
            p.mat4("uWorldToDepthCam", ctx.worldToDepthCamera)
            p.f4("uDepthIntr", ctx.depthIntrinsics)
        }
    }

    /** Draws a lit mesh. [color] rgba, [emissive] rgb. */
    fun drawLit(
        ctx: EyeContext, mesh: Mesh, modelMatrix: FloatArray,
        r: Float, g: Float, b: Float, a: Float = 1f,
        er: Float = 0f, eg: Float = 0f, eb: Float = 0f,
        texture: Int = 0, specular: Float = 0.35f, rim: Float = 0.25f, occlusion: Boolean = true,
    ) {
        val p = lit
        p.use()
        ctx.mvp(modelMatrix, mvp)
        p.mat4("uMvp", mvp)
        p.mat4("uModel", modelMatrix)
        p.f4("uColor", r, g, b, a)
        p.f3("uEmissive", er, eg, eb)
        p.f3("uEye", ctx.eyePos.x, ctx.eyePos.y, ctx.eyePos.z)
        p.f3("uLightDir", ctx.lightDir.x, ctx.lightDir.y, ctx.lightDir.z)
        p.f3("uLightColor", ctx.lightColor.x, ctx.lightColor.y, ctx.lightColor.z)
        p.f3("uAmbient", ctx.ambient.x, ctx.ambient.y, ctx.ambient.z)
        p.f1("uSpecular", specular)
        p.f1("uRim", rim)
        if (texture > 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            p.i1("uTex", 0)
            p.i1("uUseTex", 1)
        } else p.i1("uUseTex", 0)
        bindOcclusion(p, ctx, occlusion)
        mesh.draw()
    }

    fun drawLit(ctx: EyeContext, mesh: Mesh, pose: Pose, sx: Float, sy: Float, sz: Float,
                r: Float, g: Float, b: Float, a: Float = 1f, er: Float = 0f, eg: Float = 0f, eb: Float = 0f) {
        pose.toMatrix(model, sx, sy, sz)
        drawLit(ctx, mesh, model, r, g, b, a, er, eg, eb)
    }

    /** Textured / colored unlit geometry (panels, icons, glows, blob shadows). */
    fun drawUnlit(
        ctx: EyeContext, mesh: Mesh, modelMatrix: FloatArray, mode: Int,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
        texture: Int = 0, corner: Float = 0.1f, occlusion: Boolean = false,
        uvX: Float = 0f, uvY: Float = 0f, uvW: Float = 1f, uvH: Float = 1f,
    ) {
        val p = unlit
        p.use()
        ctx.mvp(modelMatrix, mvp)
        p.mat4("uMvp", mvp)
        p.mat4("uModel", modelMatrix)
        p.i1("uMode", mode)
        p.f4("uColor", r, g, b, a)
        p.f4("uUvRect", uvX, uvY, uvW, uvH)
        p.f1("uCorner", corner)
        if (texture > 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            p.i1("uTex", 0)
        }
        bindOcclusion(p, ctx, occlusion)
        mesh.draw()
    }

    /** External (OES) texture on a mesh: video, web, Android windows. */
    fun drawExternal(
        ctx: EyeContext, mesh: Mesh, modelMatrix: FloatArray, textureId: Int, texMatrix: FloatArray,
        brightness: Float = 1f, alpha: Float = 1f,
        uvX: Float = 0f, uvY: Float = 0f, uvW: Float = 1f, uvH: Float = 1f,
    ) {
        val p = oes
        p.use()
        ctx.mvp(modelMatrix, mvp)
        p.mat4("uMvp", mvp)
        p.mat4("uTexMatrix", texMatrix)
        p.f4("uUvRect", uvX, uvY, uvW, uvH)
        p.f4("uColor", 1f, 1f, 1f, alpha)
        p.f1("uBrightness", brightness)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        p.i1("uTex", 0)
        mesh.draw()
    }

    fun modelMatrix(pose: Pose, sx: Float, sy: Float, sz: Float): FloatArray {
        pose.toMatrix(model, sx, sy, sz); return model
    }

    companion object {
        @Volatile private var instance: Gfx? = null
        val get: Gfx get() = instance ?: error("Gfx not initialized (GL thread not ready)")
        fun init(): Gfx = Gfx().also { instance = it }
        fun isReady() = instance != null
        fun reset() { instance = null }

        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }
}
