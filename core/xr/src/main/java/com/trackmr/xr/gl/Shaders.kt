package com.trackmr.xr.gl

/**
 * GLSL ES 3.00 sources. Kept small and branch-light for mobile tilers.
 *
 * Occlusion: object shaders optionally compare the fragment's distance from the physical
 * camera against the environment depth map (ARCore Depth API, RG8 = 16-bit millimeters).
 * The fragment is projected with the depth camera intrinsics, so this works identically
 * for both eyes, any FOV and any render scale.
 */
object Shaders {

    private const val OCCLUSION_FS = """
uniform highp sampler2D uDepthTex;
uniform int uOcclusion;
uniform mat4 uWorldToDepthCam;
uniform vec4 uDepthIntr; // fx, fy, cx, cy normalized to [0,1] image coords
float occlusionAlpha(vec3 world) {
    if (uOcclusion == 0) return 1.0;
    vec4 c = uWorldToDepthCam * vec4(world, 1.0);
    float z = -c.z;
    if (z <= 0.05) return 1.0;
    vec2 uv = vec2(uDepthIntr.x * (c.x / z) + uDepthIntr.z, uDepthIntr.w - uDepthIntr.y * (c.y / z));
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return 1.0;
    vec2 rg = texture(uDepthTex, uv).rg;
    float real = (rg.r * 255.0 + rg.g * 65280.0) * 0.001;
    if (real <= 0.0) return 1.0;
    // Soft transition (4 cm) hides depth-map noise at contact edges.
    return clamp((real - z) / 0.04 + 0.5, 0.0, 1.0);
}
"""

    const val LIT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNrm;
layout(location=2) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uModel;
out vec3 vWorld;
out vec3 vNrm;
out vec2 vUv;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNrm = mat3(uModel) * aNrm;
    vUv = aUv;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    val LIT_FS = """#version 300 es
precision mediump float;
in vec3 vWorld;
in vec3 vNrm;
in vec2 vUv;
uniform vec4 uColor;
uniform vec3 uEmissive;
uniform vec3 uEye;
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uSpecular;
uniform float uRim;
uniform sampler2D uTex;
uniform int uUseTex;
$OCCLUSION_FS
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNrm);
    vec3 v = normalize(uEye - vWorld);
    if (!gl_FrontFacing) n = -n;
    vec4 base = uColor;
    if (uUseTex == 1) base *= texture(uTex, vUv);
    float ndl = max(dot(n, -uLightDir), 0.0);
    vec3 h = normalize(v - uLightDir);
    float spec = pow(max(dot(n, h), 0.0), 48.0) * uSpecular;
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * uRim;
    // Hemispheric ambient: sky from above, bounce from below.
    vec3 amb = uAmbient * (0.75 + 0.25 * n.y);
    vec3 col = base.rgb * (amb + uLightColor * ndl) + uLightColor * spec + rim * vec3(0.55, 0.45, 1.0) + uEmissive;
    float a = base.a * occlusionAlpha(vWorld);
    if (a < 0.01) discard;
    fragColor = vec4(col * a, a); // premultiplied
}
"""

    const val UNLIT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=2) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uModel;
out vec2 vUv;
out vec3 vWorld;
void main() {
    vUv = aUv;
    vWorld = (uModel * vec4(aPos, 1.0)).xyz;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    /** uMode: 0 texture, 1 solid color, 2 radial blob (shadows / glows), 3 rounded rect. */
    val UNLIT_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
in vec3 vWorld;
uniform sampler2D uTex;
uniform vec4 uColor;
uniform int uMode;
uniform vec4 uUvRect; // x,y offset + z,w scale
uniform float uCorner;
$OCCLUSION_FS
out vec4 fragColor;
void main() {
    vec4 c;
    if (uMode == 0) {
        c = texture(uTex, uUvRect.xy + vUv * uUvRect.zw) * vec4(uColor.rgb * uColor.a, uColor.a);
    } else if (uMode == 1) {
        c = vec4(uColor.rgb * uColor.a, uColor.a);
    } else if (uMode == 2) {
        float d = length(vUv * 2.0 - 1.0);
        float a = uColor.a * (1.0 - smoothstep(0.0, 1.0, d));
        c = vec4(uColor.rgb * a, a);
    } else {
        vec2 p = abs(vUv * 2.0 - 1.0);
        vec2 q = max(p - (1.0 - uCorner), 0.0);
        float d = length(q) / max(uCorner, 0.001);
        float a = uColor.a * (1.0 - smoothstep(0.85, 1.0, d));
        c = vec4(uColor.rgb * a, a);
    }
    c *= occlusionAlpha(vWorld);
    if (c.a < 0.004) discard;
    fragColor = c;
}
"""

    const val OES_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=2) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uTexMatrix;
uniform vec4 uUvRect;
out vec2 vUv;
void main() {
    vec2 uv = uUvRect.xy + aUv * uUvRect.zw;
    // Canvas-style UV (v down) -> GL texture space (v up) before SurfaceTexture transform.
    vUv = (uTexMatrix * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    const val OES_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vUv;
uniform samplerExternalOES uTex;
uniform vec4 uColor;
uniform float uBrightness;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTex, vUv);
    fragColor = vec4(c.rgb * uBrightness * uColor.rgb * uColor.a, uColor.a);
}
"""

    /** Passthrough camera background: vertex UVs come straight from ARCore/Camera2. */
    const val CAMERA_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=2) in vec2 aUv;
uniform mat4 uProj;
out vec2 vUv;
out vec2 vNdc;
void main() {
    vUv = aUv;
    vec4 p = uProj * vec4(aPos, 1.0);
    gl_Position = p.xyww; // pinned to far plane
    vNdc = p.xy / p.w;
}
"""

    const val CAMERA_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vUv;
in vec2 vNdc;
uniform samplerExternalOES uTex;
uniform float uBrightness;
uniform vec3 uTint;
uniform float uVignette;
out vec4 fragColor;
void main() {
    vec3 c = texture(uTex, vUv).rgb * uBrightness * uTint;
    // Soft edge where the physical camera FOV ends inside the lens FOV.
    vec2 e = smoothstep(vec2(1.0), vec2(1.0 - uVignette), abs(vUv * 2.0 - 1.0));
    fragColor = vec4(c * e.x * e.y, 1.0);
}
"""

    const val SKY_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=2) in vec2 aUv;
uniform mat4 uMvp;
out vec2 vUv;
void main() {
    vUv = aUv;
    vec4 p = uMvp * vec4(aPos, 1.0);
    gl_Position = p.xyww;
}
"""

    const val SKY_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTexA;
uniform sampler2D uTexB;
uniform float uMix;
uniform float uBrightness;
uniform float uOpacity;
out vec4 fragColor;
void main() {
    vec3 a = texture(uTexA, vUv).rgb;
    vec3 b = texture(uTexB, vUv).rgb;
    fragColor = vec4(mix(a, b, uMix) * uBrightness * uOpacity, uOpacity);
}
"""

    /** Lines / points with per-vertex color (hand skeleton, pointer rays, grids). */
    const val COLOR_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=3) in vec4 aColor;
uniform mat4 uViewProj;
uniform float uPointSize;
uniform float uPointScale;
out vec4 vColor;
void main() {
    vColor = aColor;
    vec4 p = uViewProj * vec4(aPos, 1.0);
    gl_Position = p;
    gl_PointSize = clamp(uPointSize * uPointScale / max(p.w, 0.05), 1.0, 64.0);
}
"""

    const val COLOR_FS = """#version 300 es
precision mediump float;
in vec4 vColor;
uniform int uRound;
uniform int uAdditive;
out vec4 fragColor;
void main() {
    float a = vColor.a;
    if (uRound == 1) {
        float d = length(gl_PointCoord * 2.0 - 1.0);
        a *= 1.0 - smoothstep(0.55, 1.0, d);
    }
    if (a < 0.01) discard;
    // vColor.a == 0 in the source color means additive (premultiplied trick).
    fragColor = vec4(vColor.rgb * a, uAdditive == 1 ? 0.0 : a);
}
"""

    const val DEPTH_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uViewProj;
void main() { gl_Position = uViewProj * vec4(aPos, 1.0); }
"""

    const val DEPTH_FS = """#version 300 es
precision lowp float;
out vec4 fragColor;
void main() { fragColor = vec4(0.0); }
"""

    /** Lens distortion pass: mesh-based (pre-warped UVs) for near-zero ALU cost. */
    const val DISTORT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=2) in vec2 aUv;
out vec2 vUv;
out float vFade;
void main() {
    vUv = aUv;
    vFade = aPos.z;
    gl_Position = vec4(aPos.xy, 0.0, 1.0);
}
"""

    const val DISTORT_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
in float vFade;
uniform sampler2D uTex;
uniform vec4 uEyeRect;
out vec4 fragColor;
void main() {
    vec2 uv = uEyeRect.xy + clamp(vUv, 0.0, 1.0) * uEyeRect.zw;
    fragColor = vec4(texture(uTex, uv).rgb * vFade, 1.0);
}
"""
}
