package com.trackmr.xr.math

import kotlin.math.sqrt

/**
 * Mutable 3D vector. All operations mutate `this` and return it so hot paths can run
 * without allocating (the render loop and hand pipeline reuse instances).
 */
class Vec3(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f) {

    fun set(x: Float, y: Float, z: Float): Vec3 { this.x = x; this.y = y; this.z = z; return this }
    fun set(o: Vec3): Vec3 { x = o.x; y = o.y; z = o.z; return this }
    fun zero(): Vec3 = set(0f, 0f, 0f)

    fun add(o: Vec3): Vec3 { x += o.x; y += o.y; z += o.z; return this }
    fun add(ax: Float, ay: Float, az: Float): Vec3 { x += ax; y += ay; z += az; return this }
    fun sub(o: Vec3): Vec3 { x -= o.x; y -= o.y; z -= o.z; return this }
    fun scale(s: Float): Vec3 { x *= s; y *= s; z *= s; return this }
    fun addScaled(o: Vec3, s: Float): Vec3 { x += o.x * s; y += o.y * s; z += o.z * s; return this }

    /** this = a - b */
    fun setSub(a: Vec3, b: Vec3): Vec3 { x = a.x - b.x; y = a.y - b.y; z = a.z - b.z; return this }
    /** this = a + b */
    fun setAdd(a: Vec3, b: Vec3): Vec3 { x = a.x + b.x; y = a.y + b.y; z = a.z + b.z; return this }

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    /** this = a × b (safe when this aliases a or b). */
    fun setCross(a: Vec3, b: Vec3): Vec3 {
        val cx = a.y * b.z - a.z * b.y
        val cy = a.z * b.x - a.x * b.z
        val cz = a.x * b.y - a.y * b.x
        x = cx; y = cy; z = cz; return this
    }

    fun lengthSq(): Float = x * x + y * y + z * z
    fun length(): Float = sqrt(lengthSq())

    fun normalize(): Vec3 {
        val l = length()
        if (l > 1e-8f) { val inv = 1f / l; x *= inv; y *= inv; z *= inv }
        return this
    }

    fun distance(o: Vec3): Float {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun distanceSq(o: Vec3): Float {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return dx * dx + dy * dy + dz * dz
    }

    fun lerp(o: Vec3, t: Float): Vec3 { x += (o.x - x) * t; y += (o.y - y) * t; z += (o.z - z) * t; return this }

    fun copy(): Vec3 = Vec3(x, y, z)

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    override fun toString(): String = "(%.3f, %.3f, %.3f)".format(x, y, z)
}
