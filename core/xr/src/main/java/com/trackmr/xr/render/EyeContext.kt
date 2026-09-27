package com.trackmr.xr.render

import android.opengl.Matrix
import com.trackmr.xr.math.Pose
import com.trackmr.xr.math.Vec3

/** Render passes, executed in order for every eye. */
enum class RenderPass { BACKGROUND, OCCLUDERS, OPAQUE, TRANSPARENT, OVERLAY }

/**
 * Per-eye rendering state. One instance per eye is reused every frame (no allocations).
 */
class EyeContext {
    @JvmField var eyeIndex = 0
    @JvmField val view = FloatArray(16)
    @JvmField val proj = FloatArray(16)
    @JvmField val viewProj = FloatArray(16)
    /** Rotation-only view (for backgrounds pinned at infinity). */
    @JvmField val viewRot = FloatArray(16)
    @JvmField val eyePose = Pose()
    @JvmField val headPose = Pose()
    @JvmField val eyePos = Vec3()
    @JvmField var viewportW = 1
    @JvmField var viewportH = 1
    /** Pixels per unit of tan-angle; used for point sprite sizing. */
    @JvmField var pixelsPerTan = 500f
    /** Tangent half-extents of this eye frustum: left, right, bottom, top. */
    @JvmField val tanExtents = FloatArray(4)

    // Lighting (updated from ARCore light estimation in MR, from the environment in VR)
    @JvmField val lightDir = Vec3(-0.35f, -0.85f, -0.4f).normalize()
    @JvmField val lightColor = Vec3(1.0f, 0.97f, 0.92f)
    @JvmField val ambient = Vec3(0.42f, 0.42f, 0.5f)

    // Occlusion (environment depth)
    @JvmField var depthTexture = 0
    @JvmField var occlusionEnabled = false
    @JvmField val worldToDepthCamera = FloatArray(16)
    @JvmField val depthIntrinsics = FloatArray(4)

    @JvmField var timeSeconds = 0.0
    @JvmField var mixedReality = true

    private val tmp = FloatArray(16)

    fun update() {
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        System.arraycopy(view, 0, viewRot, 0, 16)
        viewRot[12] = 0f; viewRot[13] = 0f; viewRot[14] = 0f
        eyePos.set(eyePose.p)
    }

    fun mvp(model: FloatArray, out: FloatArray) = Matrix.multiplyMM(out, 0, viewProj, 0, model, 0)

    /** MVP for geometry locked to the head (HUD/background) given a head-space model. */
    fun headLockedMvp(localModel: FloatArray, out: FloatArray) {
        Matrix.multiplyMM(tmp, 0, proj, 0, localModel, 0)
        System.arraycopy(tmp, 0, out, 0, 16)
    }
}
