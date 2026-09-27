package com.trackmr.ui

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface

/**
 * TrackMR visual identity: deep violet glass, neon Joy-Con blue/red accents, soft glows.
 * Vivid and colorful, but calm enough for long VR sessions.
 */
object UiTheme {
    val violet = Color.rgb(90, 4, 189)          // icon background
    val violetDeep = Color.rgb(24, 8, 52)
    val violetGlass = Color.argb(214, 28, 14, 62)
    val violetGlassLight = Color.argb(200, 52, 30, 104)
    val neonBlue = Color.rgb(30, 190, 255)
    val neonRed = Color.rgb(255, 70, 85)
    val neonPink = Color.rgb(255, 96, 214)
    val neonMint = Color.rgb(60, 240, 190)
    val neonGold = Color.rgb(255, 200, 70)
    val textPrimary = Color.rgb(248, 246, 255)
    val textSecondary = Color.argb(200, 220, 212, 255)
    val textMuted = Color.argb(150, 200, 190, 240)
    val stroke = Color.argb(90, 190, 160, 255)
    val hover = Color.argb(70, 180, 150, 255)
    val pressed = Color.argb(120, 200, 170, 255)

    val accents = intArrayOf(neonBlue, neonPink, neonMint, neonGold, neonRed, Color.rgb(150, 110, 255))

    val bold: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun textPaint(size: Float, color: Int = textPrimary, face: Typeface = medium): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size; this.color = color; typeface = face
        }

    fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    fun strokePaint(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width
    }

    fun gradient(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(x0, y0, x1, y1, c0, c1, Shader.TileMode.CLAMP) }

    fun withAlpha(color: Int, a: Int): Int = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
        )
    }
}
