package com.trackmr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import com.trackmr.xr.gl.Texture2D

/**
 * Bitmap + Canvas rasterized into a GL texture. The 2D canvas is only a *texture source*
 * for 3D surfaces (text/vector rasterization); nothing here is shown as Android 2D UI.
 * Uploads happen on the GL thread and only when content changed.
 */
class CanvasTexture(val width: Int, val height: Int) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    private var texture: Texture2D? = null
    @Volatile var dirty = true

    fun clear() = canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

    /** GL thread. Returns texture id (0 if not yet created). */
    fun textureId(): Int {
        if (dirty) {
            dirty = false
            val t = texture
            if (t == null) texture = Texture2D.fromBitmap(bitmap, mipmaps = true)
            else t.update(bitmap, mipmaps = true)
        }
        return texture?.id ?: 0
    }

    fun release() {
        texture?.release(); texture = null
        bitmap.recycle()
    }

    companion object {
        /** Global budget: redraw at most this many canvases per frame to avoid hitches. */
        const val MAX_REDRAWS_PER_FRAME = 2
        @JvmField var redrawsThisFrame = 0
        fun beginFrame() { redrawsThisFrame = 0 }
        fun tryAcquireRedraw(): Boolean = if (redrawsThisFrame < MAX_REDRAWS_PER_FRAME) { redrawsThisFrame++; true } else false
    }
}
