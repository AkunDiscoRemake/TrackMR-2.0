package dev.trackmr.core

import kotlin.math.*

data class PanelVertex(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float)
object PanelGeometry {
    /** Cylinder arc or flat plane, consistent physical height when capture aspect changes. */
    fun mesh(aspect: Float, height: Float = 1f, radius: Float = 2f, curved: Boolean = true,
             segments: Int = 40): List<PanelVertex> {
        require(aspect > 0 && aspect.isFinite() && height > 0 && radius > 0 && segments in 1..256)
        val width = (aspect * height).coerceAtMost(radius * 2.5f)
        return (0..segments).flatMap { i ->
            val u = i.toFloat() / segments; val offset = (u - .5f) * width
            val angle = offset / radius
            val x = if (curved) sin(angle) * radius else offset
            val z = if (curved) -cos(angle) * radius else -radius
            listOf(PanelVertex(x, height/2, z, u, 0f), PanelVertex(x, -height/2, z, u, 1f))
        }
    }
}
