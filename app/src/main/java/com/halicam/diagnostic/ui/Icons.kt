package com.halicam.diagnostic.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class Icon {
    FLASH_OFF, FLASH_ON, FLASH_AUTO, SETTINGS, FLIP, PIP, PAUSE, PLAY, CLOSE, GRID,
    SWAP, MIRROR, EYE, EYE_OFF, WEBCAM, BATTERY, STORAGE, THERMO, LINK
}

/** Ícones vetoriais desenhados em código numa grade 24x24 (traço fino, estilo câmera profissional). */
class IconDrawable(icon: Icon, color: Int = Color.WHITE) : Drawable() {
    var icon: Icon = icon
        set(v) { field = v; invalidateSelf() }
    var color: Int = color
        set(v) { field = v; invalidateSelf() }
    /** Nível de 0..1 para o ícone de bateria. */
    var level01: Float = 1f
        set(v) { field = v; invalidateSelf() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        stroke.color = color; fill.color = color; text.color = color
        canvas.save()
        canvas.translate(b.exactCenterX() - 12 * s, b.exactCenterY() - 12 * s)
        canvas.scale(s, s)
        when (icon) {
            Icon.FLASH_ON -> bolt(canvas, true)
            Icon.FLASH_OFF -> { bolt(canvas, false); canvas.drawLine(4f, 3f, 20f, 21f, stroke) }
            Icon.FLASH_AUTO -> { bolt(canvas, false); canvas.drawText("A", 19.5f, 21.5f, text) }
            Icon.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 3f, stroke)
                canvas.drawCircle(12f, 12f, 7f, stroke)
                stroke.strokeWidth = 2.6f
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    canvas.drawLine(12 + 7.6f * cos(a).toFloat(), 12 + 7.6f * sin(a).toFloat(),
                        12 + 9.6f * cos(a).toFloat(), 12 + 9.6f * sin(a).toFloat(), stroke)
                }
                stroke.strokeWidth = 1.7f
            }
            Icon.FLIP -> {
                rect.set(4.5f, 4.5f, 19.5f, 19.5f)
                canvas.drawArc(rect, 200f, 130f, false, stroke)
                canvas.drawArc(rect, 20f, 130f, false, stroke)
                arrowHead(canvas, 330f, 7.5f, true)
                arrowHead(canvas, 150f, 7.5f, true)
            }
            Icon.PIP -> {
                rect.set(3f, 5f, 21f, 19f); canvas.drawRoundRect(rect, 2.5f, 2.5f, stroke)
                rect.set(12f, 11f, 18.5f, 16.5f); canvas.drawRoundRect(rect, 1.5f, 1.5f, fill)
            }
            Icon.PAUSE -> {
                rect.set(6.5f, 5f, 10f, 19f); canvas.drawRoundRect(rect, 1.2f, 1.2f, fill)
                rect.set(14f, 5f, 17.5f, 19f); canvas.drawRoundRect(rect, 1.2f, 1.2f, fill)
            }
            Icon.PLAY -> {
                path.reset(); path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close()
                canvas.drawPath(path, fill)
            }
            Icon.CLOSE -> { canvas.drawLine(6f, 6f, 18f, 18f, stroke); canvas.drawLine(18f, 6f, 6f, 18f, stroke) }
            Icon.GRID -> {
                rect.set(4f, 4f, 20f, 20f); canvas.drawRoundRect(rect, 2f, 2f, stroke)
                canvas.drawLine(9.3f, 4f, 9.3f, 20f, stroke); canvas.drawLine(14.7f, 4f, 14.7f, 20f, stroke)
                canvas.drawLine(4f, 9.3f, 20f, 9.3f, stroke); canvas.drawLine(4f, 14.7f, 20f, 14.7f, stroke)
            }
            Icon.SWAP -> {
                canvas.drawLine(5f, 8.5f, 19f, 8.5f, stroke); canvas.drawLine(15.5f, 5f, 19f, 8.5f, stroke); canvas.drawLine(15.5f, 12f, 19f, 8.5f, stroke)
                canvas.drawLine(19f, 15.5f, 5f, 15.5f, stroke); canvas.drawLine(8.5f, 12f, 5f, 15.5f, stroke); canvas.drawLine(8.5f, 19f, 5f, 15.5f, stroke)
            }
            Icon.MIRROR -> {
                for (y in listOf(3f, 7f, 11f, 15f, 19f)) canvas.drawLine(12f, y, 12f, y + 2f, stroke)
                path.reset(); path.moveTo(9.5f, 6f); path.lineTo(3f, 18f); path.lineTo(9.5f, 18f); path.close(); canvas.drawPath(path, fill)
                path.reset(); path.moveTo(14.5f, 6f); path.lineTo(21f, 18f); path.lineTo(14.5f, 18f); path.close(); canvas.drawPath(path, stroke)
            }
            Icon.EYE, Icon.EYE_OFF -> {
                path.reset(); path.moveTo(2.5f, 12f); path.quadTo(12f, 2f, 21.5f, 12f); path.quadTo(12f, 22f, 2.5f, 12f); path.close()
                canvas.drawPath(path, stroke)
                canvas.drawCircle(12f, 12f, 3f, stroke)
                if (icon == Icon.EYE_OFF) canvas.drawLine(4f, 4f, 20f, 20f, stroke)
            }
            Icon.WEBCAM -> {
                canvas.drawCircle(12f, 10f, 6.5f, stroke)
                canvas.drawCircle(12f, 10f, 2.4f, fill)
                canvas.drawLine(12f, 16.5f, 12f, 20f, stroke)
                canvas.drawLine(7f, 20.5f, 17f, 20.5f, stroke)
            }
            Icon.BATTERY -> {
                rect.set(2f, 7f, 19f, 17f); canvas.drawRoundRect(rect, 2.2f, 2.2f, stroke)
                rect.set(20f, 10f, 22f, 14f); canvas.drawRoundRect(rect, 0.8f, 0.8f, fill)
                rect.set(4f, 9f, 4f + 13f * level01.coerceIn(0.05f, 1f), 15f); canvas.drawRoundRect(rect, 1f, 1f, fill)
            }
            Icon.STORAGE -> {
                path.reset(); path.moveTo(9f, 3f); path.lineTo(17f, 3f); path.quadTo(19f, 3f, 19f, 5f); path.lineTo(19f, 19f)
                path.quadTo(19f, 21f, 17f, 21f); path.lineTo(7f, 21f); path.quadTo(5f, 21f, 5f, 19f); path.lineTo(5f, 7f); path.close()
                canvas.drawPath(path, stroke)
                canvas.drawLine(10f, 6.5f, 10f, 9.5f, stroke); canvas.drawLine(13f, 6.5f, 13f, 9.5f, stroke); canvas.drawLine(16f, 6.5f, 16f, 9.5f, stroke)
            }
            Icon.THERMO -> {
                rect.set(10f, 3f, 14f, 15f); canvas.drawRoundRect(rect, 2f, 2f, stroke)
                canvas.drawCircle(12f, 17.5f, 3.5f, stroke)
                canvas.drawCircle(12f, 17.5f, 1.8f, fill)
            }
            Icon.LINK -> {
                rect.set(3f, 8f, 13f, 16f); canvas.drawRoundRect(rect, 4f, 4f, stroke)
                rect.set(11f, 8f, 21f, 16f); canvas.drawRoundRect(rect, 4f, 4f, stroke)
            }
        }
        canvas.restore()
    }

    private fun bolt(canvas: Canvas, filled: Boolean) {
        path.reset()
        path.moveTo(13.5f, 2.5f); path.lineTo(5f, 13.5f); path.lineTo(11f, 13.5f)
        path.lineTo(10f, 21.5f); path.lineTo(19f, 10f); path.lineTo(13f, 10f); path.close()
        canvas.drawPath(path, if (filled) fill else stroke)
    }

    private fun arrowHead(canvas: Canvas, angleDeg: Float, r: Float, clockwise: Boolean) {
        val a = Math.toRadians(angleDeg.toDouble())
        val x = 12 + r * cos(a).toFloat()
        val y = 12 + r * sin(a).toFloat()
        val t = a + if (clockwise) Math.PI / 2 else -Math.PI / 2
        val tx = cos(t).toFloat(); val ty = sin(t).toFloat()
        val nx = -ty; val ny = tx
        canvas.drawLine(x, y, x - 3f * tx + 2.5f * nx, y - 3f * ty + 2.5f * ny, stroke)
        canvas.drawLine(x, y, x - 3f * tx - 2.5f * nx, y - 3f * ty - 2.5f * ny, stroke)
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha; text.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { stroke.colorFilter = colorFilter; fill.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
