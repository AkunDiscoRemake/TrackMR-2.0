package com.trackmr.xr.math

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mutable unit quaternion (x, y, z, w). */
class Quat(
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var z: Float = 0f,
    @JvmField var w: Float = 1f,
) {
    fun set(x: Float, y: Float, z: Float, w: Float): Quat { this.x = x; this.y = y; this.z = z; this.w = w; return this }
    fun set(o: Quat): Quat = set(o.x, o.y, o.z, o.w)
    fun identity(): Quat = set(0f, 0f, 0f, 1f)

    /** this = a * b (safe with aliasing). */
    fun setMul(a: Quat, b: Quat): Quat {
        val nx = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y
        val ny = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x
        val nz = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w
        val nw = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
        return set(nx, ny, nz, nw)
    }

    fun mul(b: Quat): Quat = setMul(this, b)
    fun conjugate(): Quat { x = -x; y = -y; z = -z; return this }

    fun normalize(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        if (l > 1e-8f) { val i = 1f / l; x *= i; y *= i; z *= i; w *= i } else identity()
        return this
    }

    fun dot(o: Quat): Float = x * o.x + y * o.y + z * o.z + w * o.w

    /** out = this ⊗ v ⊗ this* */
    fun rotate(v: Vec3, out: Vec3): Vec3 {
        val tx = 2f * (y * v.z - z * v.y)
        val ty = 2f * (z * v.x - x * v.z)
        val tz = 2f * (x * v.y - y * v.x)
        val rx = v.x + w * tx + (y * tz - z * ty)
        val ry = v.y + w * ty + (z * tx - x * tz)
        val rz = v.z + w * tz + (x * ty - y * tx)
        return out.set(rx, ry, rz)
    }

    fun rotate(vx: Float, vy: Float, vz: Float, out: Vec3): Vec3 {
        val tx = 2f * (y * vz - z * vy)
        val ty = 2f * (z * vx - x * vz)
        val tz = 2f * (x * vy - y * vx)
        return out.set(
            vx + w * tx + (y * tz - z * ty),
            vy + w * ty + (z * tx - x * tz),
            vz + w * tz + (x * ty - y * tx),
        )
    }

    fun setAxisAngle(ax: Float, ay: Float, az: Float, radians: Float): Quat {
        val l = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-8f)
        val s = sin(radians * 0.5f) / l
        return set(ax * s, ay * s, az * s, cos(radians * 0.5f))
    }

    /** Yaw (around +Y), pitch (around +X), roll (around -Z) applied Y * X * Z. */
    fun setEuler(yaw: Float, pitch: Float, roll: Float): Quat {
        val cy = cos(yaw * 0.5f); val sy = sin(yaw * 0.5f)
        val cp = cos(pitch * 0.5f); val sp = sin(pitch * 0.5f)
        val cr = cos(roll * 0.5f); val sr = sin(roll * 0.5f)
        // q = qy * qx * qz
        val qx = cy * sp * cr + sy * cp * sr
        val qy = sy * cp * cr - cy * sp * sr
        val qz = cy * cp * sr - sy * sp * cr
        val qw = cy * cp * cr + sy * sp * sr
        return set(qx, qy, qz, qw)
    }

    /** Heading around +Y of the -Z forward vector, in radians. */
    fun yaw(): Float {
        val fx = -(2f * (x * z + w * y))
        val fz = -(1f - 2f * (x * x + y * y))
        return atan2(-fx, -fz)
    }

    /** Rotation whose -Z axis points along [forward] with [up] as the approximate up vector. */
    fun setLookRotation(forward: Vec3, up: Vec3): Quat {
        // Build basis: z = -forward
        var zx = -forward.x; var zy = -forward.y; var zz = -forward.z
        var l = sqrt(zx * zx + zy * zy + zz * zz)
        if (l < 1e-8f) return identity()
        zx /= l; zy /= l; zz /= l
        // x = up × z
        var xx = up.y * zz - up.z * zy
        var xy = up.z * zx - up.x * zz
        var xz = up.x * zy - up.y * zx
        l = sqrt(xx * xx + xy * xy + xz * xz)
        if (l < 1e-8f) { xx = 1f; xy = 0f; xz = 0f } else { xx /= l; xy /= l; xz /= l }
        // y = z × x
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx
        return setFromBasis(xx, xy, xz, yx, yy, yz, zx, zy, zz)
    }

    /** From orthonormal basis columns X, Y, Z. */
    fun setFromBasis(
        m00: Float, m10: Float, m20: Float,
        m01: Float, m11: Float, m21: Float,
        m02: Float, m12: Float, m22: Float,
    ): Quat {
        val trace = m00 + m11 + m22
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            return set((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            return set(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            return set((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
        }
        val s = sqrt(1f + m22 - m00 - m11) * 2f
        return set((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
    }

    /** this = slerp(this, target, t) */
    fun slerp(target: Quat, t: Float): Quat {
        var bx = target.x; var by = target.y; var bz = target.z; var bw = target.w
        var d = dot(target)
        if (d < 0f) { d = -d; bx = -bx; by = -by; bz = -bz; bw = -bw }
        if (d > 0.9995f) {
            set(x + (bx - x) * t, y + (by - y) * t, z + (bz - z) * t, w + (bw - w) * t)
            return normalize()
        }
        val theta = acos(d.coerceIn(-1f, 1f))
        val s = sin(theta)
        val wa = sin((1f - t) * theta) / s
        val wb = sin(t * theta) / s
        return set(x * wa + bx * wb, y * wa + by * wb, z * wa + bz * wb, w * wa + bw * wb)
    }

    /** Writes a column-major 4x4 rotation matrix. */
    fun toMatrix(m: FloatArray, offset: Int = 0) {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        m[offset + 0] = 1f - 2f * (yy + zz); m[offset + 1] = 2f * (xy + wz); m[offset + 2] = 2f * (xz - wy); m[offset + 3] = 0f
        m[offset + 4] = 2f * (xy - wz); m[offset + 5] = 1f - 2f * (xx + zz); m[offset + 6] = 2f * (yz + wx); m[offset + 7] = 0f
        m[offset + 8] = 2f * (xz + wy); m[offset + 9] = 2f * (yz - wx); m[offset + 10] = 1f - 2f * (xx + yy); m[offset + 11] = 0f
        m[offset + 12] = 0f; m[offset + 13] = 0f; m[offset + 14] = 0f; m[offset + 15] = 1f
    }

    fun copy(): Quat = Quat(x, y, z, w)
}
