package com.trackmr.xr.render

import com.trackmr.xr.math.Vec3

class SceneLighting {
    @JvmField val lightDir = Vec3(-0.35f, -0.85f, -0.4f).normalize()
    @JvmField val lightColor = Vec3(1.0f, 0.97f, 0.92f)
    @JvmField val ambient = Vec3(0.42f, 0.42f, 0.5f)

    fun applyTo(e: EyeContext) {
        e.lightDir.set(lightDir); e.lightColor.set(lightColor); e.ambient.set(ambient)
    }
}

/** Environment-depth occlusion state shared with every eye (written by the depth module). */
class OcclusionState {
    @JvmField var enabled = false
    @JvmField var depthTexture = 0
    @JvmField val worldToDepthCamera = FloatArray(16)
    @JvmField val intrinsics = FloatArray(4)

    fun applyTo(e: EyeContext) {
        e.occlusionEnabled = enabled && depthTexture > 0
        e.depthTexture = depthTexture
        if (e.occlusionEnabled) {
            System.arraycopy(worldToDepthCamera, 0, e.worldToDepthCamera, 0, 16)
            System.arraycopy(intrinsics, 0, e.depthIntrinsics, 0, 4)
        }
    }
}
