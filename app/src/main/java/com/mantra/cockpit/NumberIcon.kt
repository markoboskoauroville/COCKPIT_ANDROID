package com.mantra.cockpit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat

/**
 * THE NUMBER IN THE STATUS BAR.
 *
 * A notification's small icon is drawn by Android as a shape: only the alpha of the bitmap is
 * used, tinted in the bar's own colour. So the digits are painted white on transparent, bold, as
 * large as three characters allow in a square, and the bar shows "37" (or "100") the way a
 * battery-percentage app does. The bitmap is 96 px; the bar scales it to its own size.
 */
object NumberIcon {

    private const val SIZE = 96

    fun of(value: Int?): IconCompat {
        val text = if (value == null) "–" else value.coerceIn(0, 100).toString()
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            // three digits must fit the width; two digits get to be taller
            textSize = if (text.length >= 3) SIZE * 0.62f else SIZE * 0.86f
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val y = SIZE / 2f + bounds.height() / 2f - bounds.bottom
        canvas.drawText(text, SIZE / 2f, y, paint)
        return IconCompat.createWithBitmap(bmp)
    }
}
