package com.trackmr.xr.math

/** Rigid transform: position + orientation. OpenGL convention (+Y up, -Z forward). */
class Pose(@JvmField val p: Vec3 = Vec3(), @JvmField val q: Quat = Quat()) {

    fun set(o: Pose): Pose { p.set(o.p); q.set(o.q); return this }
    fun identity(): Pose { p.zero(); q.identity(); return this }

    fun transformPoint(v: Vec3, out: Vec3): Vec3 { q.rotate(v, out); return out.add(p) }
    fun transformPoint(x: Float, y: Float, z: Float, out: Vec3): Vec3 { q.rotate(x, y, z, out); return out.add(p) }
    fun transformDir(v: Vec3, out: Vec3): Vec3 = q.rotate(v, out)

    /** out = inverse(this) applied to world point v. */
    fun inverseTransformPoint(v: Vec3, out: Vec3): Vec3 {
        val dx = v.x - p.x; val dy = v.y - p.y; val dz = v.z - p.z
        // rotate by conjugate
        val cx = -q.x; val cy = -q.y; val cz = -q.z; val w = q.w
        val tx = 2f * (cy * dz - cz * dy)
        val ty = 2f * (cz * dx - cx * dz)
        val tz = 2f * (cx * dy - cy * dx)
        return out.set(dx + w * tx + (cy * tz - cz * ty), dy + w * ty + (cz * tx - cx * tz), dz + w * tz + (cx * ty - cy * tx))
    }

    fun inverseTransformDir(v: Vec3, out: Vec3): Vec3 {
        val cx = -q.x; val cy = -q.y; val cz = -q.z; val w = q.w
        val tx = 2f * (cy * v.z - cz * v.y)
        val ty = 2f * (cz * v.x - cx * v.z)
        val tz = 2f * (cx * v.y - cy * v.x)
        return out.set(v.x + w * tx + (cy * tz - cz * ty), v.y + w * ty + (cz * tx - cx * tz), v.z + w * tz + (cx * ty - cy * tx))
    }

    /** this = a ∘ b (apply b first, then a). Safe when this aliases b but not a. */
    fun setCompose(a: Pose, b: Pose): Pose {
        val nx: Float; val ny: Float; val nz: Float
        run {
            val tx = 2f * (a.q.y * b.p.z - a.q.z * b.p.y)
            val ty = 2f * (a.q.z * b.p.x - a.q.x * b.p.z)
            val tz = 2f * (a.q.x * b.p.y - a.q.y * b.p.x)
            nx = b.p.x + a.q.w * tx + (a.q.y * tz - a.q.z * ty) + a.p.x
            ny = b.p.y + a.q.w * ty + (a.q.z * tx - a.q.x * tz) + a.p.y
            nz = b.p.z + a.q.w * tz + (a.q.x * ty - a.q.y * tx) + a.p.z
        }
        q.setMul(a.q, b.q)
        p.set(nx, ny, nz)
        return this
    }

    fun setInverse(src: Pose): Pose {
        q.set(src.q).conjugate()
        q.rotate(-src.p.x, -src.p.y, -src.p.z, p)
        return this
    }

    /** Column-major model matrix. */
    fun toMatrix(m: FloatArray, offset: Int = 0) {
        q.toMatrix(m, offset)
        m[offset + 12] = p.x; m[offset + 13] = p.y; m[offset + 14] = p.z
    }

    /** Model matrix with non-uniform scale. */
    fun toMatrix(m: FloatArray, sx: Float, sy: Float, sz: Float) {
        q.toMatrix(m, 0)
        for (i in 0..2) { m[i] *= sx; m[4 + i] *= sy; m[8 + i] *= sz }
        m[12] = p.x; m[13] = p.y; m[14] = p.z
    }

    /** View matrix = inverse of this pose as a camera. */
    fun toViewMatrix(m: FloatArray) {
        val inv = SCRATCH.get()!!
        inv.setInverse(this)
        inv.toMatrix(m, 0)
    }

    fun forward(out: Vec3): Vec3 = q.rotate(0f, 0f, -1f, out)
    fun up(out: Vec3): Vec3 = q.rotate(0f, 1f, 0f, out)
    fun right(out: Vec3): Vec3 = q.rotate(1f, 0f, 0f, out)

    fun copy(): Pose = Pose(p.copy(), q.copy())

    override fun toString(): String = "Pose(p=$p q=(${q.x},${q.y},${q.z},${q.w}))"

    companion object {
        private val SCRATCH = object : ThreadLocal<Pose>() { override fun initialValue() = Pose() }
    }
}

class Ray(@JvmField val origin: Vec3 = Vec3(), @JvmField val dir: Vec3 = Vec3(0f, 0f, -1f)) {
    fun pointAt(t: Float, out: Vec3): Vec3 = out.set(origin).addScaled(dir, t)
    fun set(o: Ray): Ray { origin.set(o.origin); dir.set(o.dir); return this }
}
