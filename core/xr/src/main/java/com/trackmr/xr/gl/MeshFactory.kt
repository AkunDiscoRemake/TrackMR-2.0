package com.trackmr.xr.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural geometry used by the spatial UI, environments and the TrackMR games.
 * Returned meshes share the interleaved layout of [Mesh] (pos3 nrm3 uv2).
 */
object MeshFactory {

    private class Builder {
        val v = ArrayList<Float>(1024)
        val i = ArrayList<Short>(1024)
        val count get() = v.size / 8
        fun vert(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, t: Float): Int {
            v.add(x); v.add(y); v.add(z); v.add(nx); v.add(ny); v.add(nz); v.add(u); v.add(t)
            return count - 1
        }
        fun tri(a: Int, b: Int, c: Int) { i.add(a.toShort()); i.add(b.toShort()); i.add(c.toShort()) }
        fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }
        fun build(): Mesh = Mesh(v.toFloatArray(), i.toShortArray())
    }

    /** Flat quad in the XY plane facing +Z, centered at origin. */
    fun quad(w: Float = 1f, h: Float = 1f): Mesh {
        val b = Builder()
        val hw = w / 2; val hh = h / 2
        val a = b.vert(-hw, -hh, 0f, 0f, 0f, 1f, 0f, 1f)
        val c = b.vert(hw, -hh, 0f, 0f, 0f, 1f, 1f, 1f)
        val d = b.vert(hw, hh, 0f, 0f, 0f, 1f, 1f, 0f)
        val e = b.vert(-hw, hh, 0f, 0f, 0f, 1f, 0f, 0f)
        b.quad(a, c, d, e)
        return b.build()
    }

    /** Horizontal plane (XZ) facing +Y. */
    fun plane(w: Float = 1f, d: Float = 1f, uvScale: Float = 1f): Mesh {
        val b = Builder()
        val hw = w / 2; val hd = d / 2
        val a = b.vert(-hw, 0f, hd, 0f, 1f, 0f, 0f, 0f)
        val c = b.vert(hw, 0f, hd, 0f, 1f, 0f, uvScale, 0f)
        val e = b.vert(hw, 0f, -hd, 0f, 1f, 0f, uvScale, uvScale)
        val f = b.vert(-hw, 0f, -hd, 0f, 1f, 0f, 0f, uvScale)
        b.quad(a, c, e, f)
        return b.build()
    }

    fun box(w: Float = 1f, h: Float = 1f, d: Float = 1f): Mesh {
        val b = Builder()
        val x = w / 2; val y = h / 2; val z = d / 2
        fun face(nx: Float, ny: Float, nz: Float, p: Array<FloatArray>) {
            val i0 = b.vert(p[0][0], p[0][1], p[0][2], nx, ny, nz, 0f, 1f)
            val i1 = b.vert(p[1][0], p[1][1], p[1][2], nx, ny, nz, 1f, 1f)
            val i2 = b.vert(p[2][0], p[2][1], p[2][2], nx, ny, nz, 1f, 0f)
            val i3 = b.vert(p[3][0], p[3][1], p[3][2], nx, ny, nz, 0f, 0f)
            b.quad(i0, i1, i2, i3)
        }
        face(0f, 0f, 1f, arrayOf(floatArrayOf(-x, -y, z), floatArrayOf(x, -y, z), floatArrayOf(x, y, z), floatArrayOf(-x, y, z)))
        face(0f, 0f, -1f, arrayOf(floatArrayOf(x, -y, -z), floatArrayOf(-x, -y, -z), floatArrayOf(-x, y, -z), floatArrayOf(x, y, -z)))
        face(1f, 0f, 0f, arrayOf(floatArrayOf(x, -y, z), floatArrayOf(x, -y, -z), floatArrayOf(x, y, -z), floatArrayOf(x, y, z)))
        face(-1f, 0f, 0f, arrayOf(floatArrayOf(-x, -y, -z), floatArrayOf(-x, -y, z), floatArrayOf(-x, y, z), floatArrayOf(-x, y, -z)))
        face(0f, 1f, 0f, arrayOf(floatArrayOf(-x, y, z), floatArrayOf(x, y, z), floatArrayOf(x, y, -z), floatArrayOf(-x, y, -z)))
        face(0f, -1f, 0f, arrayOf(floatArrayOf(-x, -y, -z), floatArrayOf(x, -y, -z), floatArrayOf(x, -y, z), floatArrayOf(-x, -y, z)))
        return b.build()
    }

    /** UV sphere. [inside] flips winding/normals for skyboxes (equirectangular UVs). */
    fun sphere(radius: Float = 0.5f, segments: Int = 32, rings: Int = 16, inside: Boolean = false): Mesh {
        val b = Builder()
        for (r in 0..rings) {
            val v = r.toFloat() / rings
            val phi = v * PI
            for (s in 0..segments) {
                val u = s.toFloat() / segments
                val theta = u * 2 * PI
                // u=0.5 looks down -Z so the panorama center faces the user by default.
                val x = (-sin(phi) * sin(theta)).toFloat()
                val y = cos(phi).toFloat()
                val z = (sin(phi) * cos(theta)).toFloat()
                val n = if (inside) -1f else 1f
                b.vert(x * radius, y * radius, z * radius, x * n, y * n, z * n, if (inside) u else 1f - u, v)
            }
        }
        val row = segments + 1
        for (r in 0 until rings) for (s in 0 until segments) {
            val a = r * row + s; val c = a + row
            if (inside) { b.tri(a, a + 1, c); b.tri(a + 1, c + 1, c) } else { b.tri(a, c, a + 1); b.tri(a + 1, c, c + 1) }
        }
        return b.build()
    }

    /** Cylinder along Y centered at origin, with caps. */
    fun cylinder(radius: Float = 0.5f, height: Float = 1f, segments: Int = 24, caps: Boolean = true): Mesh {
        val b = Builder()
        val hh = height / 2
        for (s in 0..segments) {
            val u = s.toFloat() / segments
            val a = u * 2 * PI
            val x = cos(a).toFloat(); val z = sin(a).toFloat()
            b.vert(x * radius, -hh, z * radius, x, 0f, z, u, 1f)
            b.vert(x * radius, hh, z * radius, x, 0f, z, u, 0f)
        }
        for (s in 0 until segments) {
            val i0 = s * 2
            b.tri(i0, i0 + 1, i0 + 2); b.tri(i0 + 2, i0 + 1, i0 + 3)
        }
        if (caps) for (top in listOf(true, false)) {
            val y = if (top) hh else -hh
            val ny = if (top) 1f else -1f
            val center = b.vert(0f, y, 0f, 0f, ny, 0f, 0.5f, 0.5f)
            val start = b.count
            for (s in 0..segments) {
                val a = s.toFloat() / segments * 2 * PI
                val x = cos(a).toFloat(); val z = sin(a).toFloat()
                b.vert(x * radius, y, z * radius, 0f, ny, 0f, 0.5f + x * 0.5f, 0.5f + z * 0.5f)
            }
            for (s in 0 until segments) {
                if (top) b.tri(center, start + s + 1, start + s) else b.tri(center, start + s, start + s + 1)
            }
        }
        return b.build()
    }

    /** Flat disc in XZ plane facing +Y (blob shadows, paddles, targets). */
    fun disc(radius: Float = 0.5f, segments: Int = 32): Mesh {
        val b = Builder()
        val c = b.vert(0f, 0f, 0f, 0f, 1f, 0f, 0.5f, 0.5f)
        for (s in 0..segments) {
            val a = s.toFloat() / segments * 2 * PI
            val x = cos(a).toFloat(); val z = sin(a).toFloat()
            b.vert(x * radius, 0f, z * radius, 0f, 1f, 0f, 0.5f + x * 0.5f, 0.5f + z * 0.5f)
        }
        for (s in 0 until segments) b.tri(c, s + 2, s + 1)
        return b.build()
    }

    fun torus(major: Float = 0.4f, minor: Float = 0.1f, segU: Int = 32, segV: Int = 12): Mesh {
        val b = Builder()
        for (i in 0..segU) {
            val u = i.toFloat() / segU * 2 * PI
            val cu = cos(u).toFloat(); val su = sin(u).toFloat()
            for (j in 0..segV) {
                val v = j.toFloat() / segV * 2 * PI
                val cv = cos(v).toFloat(); val sv = sin(v).toFloat()
                val x = (major + minor * cv) * cu
                val z = (major + minor * cv) * su
                val y = minor * sv
                b.vert(x, y, z, cv * cu, sv, cv * su, i.toFloat() / segU, j.toFloat() / segV)
            }
        }
        val row = segV + 1
        for (i in 0 until segU) for (j in 0 until segV) {
            val a = i * row + j; val c = a + row
            b.tri(a, a + 1, c); b.tri(a + 1, c + 1, c)
        }
        return b.build()
    }

    fun cone(radius: Float = 0.5f, height: Float = 1f, segments: Int = 24): Mesh {
        val b = Builder()
        val hh = height / 2
        for (s in 0 until segments) {
            val a0 = s.toFloat() / segments * 2 * PI
            val a1 = (s + 1).toFloat() / segments * 2 * PI
            val am = (a0 + a1) / 2
            val nx = cos(am).toFloat(); val nz = sin(am).toFloat()
            val ny = radius / height
            val t = b.vert(0f, hh, 0f, nx, ny, nz, 0.5f, 0f)
            val p0 = b.vert(cos(a0).toFloat() * radius, -hh, sin(a0).toFloat() * radius, nx, ny, nz, 0f, 1f)
            val p1 = b.vert(cos(a1).toFloat() * radius, -hh, sin(a1).toFloat() * radius, nx, ny, nz, 1f, 1f)
            b.tri(t, p1, p0)
            val c = b.vert(0f, -hh, 0f, 0f, -1f, 0f, 0.5f, 0.5f)
            val q0 = b.vert(cos(a0).toFloat() * radius, -hh, sin(a0).toFloat() * radius, 0f, -1f, 0f, 0f, 0f)
            val q1 = b.vert(cos(a1).toFloat() * radius, -hh, sin(a1).toFloat() * radius, 0f, -1f, 0f, 1f, 0f)
            b.tri(c, q0, q1)
        }
        return b.build()
    }

    /** Capsule along Y (for hands/physics debug and game props). */
    fun capsule(radius: Float = 0.1f, height: Float = 0.4f, segments: Int = 16, rings: Int = 8): Mesh {
        val b = Builder()
        val half = (height / 2 - radius).coerceAtLeast(0f)
        val totalRings = rings * 2 + 1
        for (r in 0..totalRings) {
            val top = r <= rings
            val rr = if (top) r else r - 1
            val phi = rr.toFloat() / (rings * 2) * PI
            val y0 = cos(phi).toFloat() * radius + if (top) half else -half
            val sr = sin(phi).toFloat()
            for (s in 0..segments) {
                val th = s.toFloat() / segments * 2 * PI
                val x = cos(th).toFloat() * sr; val z = sin(th).toFloat() * sr
                b.vert(x * radius, y0, z * radius, x, cos(phi).toFloat(), z, s.toFloat() / segments, r.toFloat() / totalRings)
            }
        }
        val row = segments + 1
        for (r in 0 until totalRings) for (s in 0 until segments) {
            val a = r * row + s; val c = a + row
            b.tri(a, c, a + 1); b.tri(a + 1, c, c + 1)
        }
        return b.build()
    }

    /**
     * Cylindrical panel section facing +Z (viewer at +Z). The panel is concave toward the
     * viewer: the curve axis is the vertical line (0, y, radius). radius <= 0 means flat.
     * UV (0,0) = top-left, like Android Canvas.
     */
    fun curvedPanel(w: Float, h: Float, radius: Float, segments: Int = 32): Mesh {
        val b = Builder()
        val flat = radius <= 0f || radius > 1000f
        val segs = if (flat) 1 else segments
        val span = if (flat) 0f else w / radius
        for (s in 0..segs) {
            val u = s.toFloat() / segs
            val x: Float; val z: Float; val nx: Float; val nz: Float
            if (flat) { x = (u - 0.5f) * w; z = 0f; nx = 0f; nz = 1f } else {
                val a = (u - 0.5f) * span
                x = sin(a) * radius; z = radius - cos(a) * radius
                nx = -sin(a); nz = cos(a)
            }
            b.vert(x, -h / 2, z, nx, 0f, nz, u, 1f)
            b.vert(x, h / 2, z, nx, 0f, nz, u, 0f)
        }
        for (s in 0 until segs) {
            val i0 = s * 2
            b.tri(i0, i0 + 2, i0 + 1); b.tri(i0 + 1, i0 + 2, i0 + 3)
        }
        return b.build()
    }

    /** Rounded box approximation using a scaled sphere-mapped cube (soft UI buttons, dock). */
    fun roundedSlab(w: Float, h: Float, d: Float): Mesh = box(w, h, d)
}
