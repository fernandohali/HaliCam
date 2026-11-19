package com.halicam.diagnostic.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.halicam.diagnostic.core.PipLayout
import kotlin.math.abs

/**
 * Camada transparente sobre a prévia. A imagem da janela PiP é desenhada pelo OpenGL (igual ao vídeo);
 * esta view só trata gestos e desenha indicadores (contorno ao arrastar, quadro de foco).
 */
class PipOverlayView(context: Context, private val cb: Callbacks) : View(context) {

    interface Callbacks {
        fun pip(): PipLayout
        fun frameAspect(): Float
        fun pipInteractive(): Boolean
        fun onPipChanged(p: PipLayout, done: Boolean)
        fun onPipDoubleTap()
        fun onTapFocus(fx: Float, fy: Float): Boolean
        fun onZoomScale(factor: Float)
        fun onSwipe(direction: Int)
    }

    private var dragging = false
    private var pinchOnPip = false
    private var grabDx = 0f
    private var grabDy = 0f
    private var focusX = -1f
    private var focusY = -1f
    private var focusAlpha = 0f
    private var focusAnim: ValueAnimator? = null
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xCCFFFFFF.toInt() }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.ACCENT }
    private val tmp = RectF()

    private fun pipRectPx(): RectF? {
        if (!cb.pipInteractive() || width == 0) return null
        val p = cb.pip()
        if (!p.visible) return null
        val r = p.rect(width.toFloat() / height)
        return RectF(r.left * width, r.top * height, r.right * width, r.bottom * height)
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            val r = pipRectPx()
            pinchOnPip = dragging || (r != null && r.contains(d.focusX, d.focusY))
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (pinchOnPip) {
                val p = cb.pip()
                val w = (p.widthFrac * d.scaleFactor).coerceIn(PipLayout.MIN_SIZE, PipLayout.MAX_SIZE)
                cb.onPipChanged(p.copy(widthFrac = w), false)
                invalidate()
            } else {
                cb.onZoomScale(d.scaleFactor)
            }
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            if (pinchOnPip) cb.onPipChanged(cb.pip(), true)
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val r = pipRectPx()
            if (r != null && r.contains(e.x, e.y)) return true
            if (cb.onTapFocus(e.x / width, e.y / height)) showFocus(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val r = pipRectPx()
            if (r != null && r.contains(e.x, e.y)) cb.onPipDoubleTap()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (dragging || scale.isInProgress) return false
            if (abs(vx) > abs(vy) * 1.5f && abs(vx) > 900) cb.onSwipe(if (vx < 0) 1 else -1)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = pipRectPx()
                if (r != null && r.contains(e.x, e.y)) {
                    dragging = true
                    grabDx = e.x - r.centerX()
                    grabDy = e.y - r.centerY()
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging && e.pointerCount == 1 && !scale.isInProgress) {
                val p = cb.pip()
                cb.onPipChanged(p.copy(cx = (e.x - grabDx) / width, cy = (e.y - grabDy) / height), false)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                cb.onPipChanged(cb.pip(), true)
                invalidate()
            }
        }
        if (!scale.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    private fun showFocus(x: Float, y: Float) {
        focusX = x; focusY = y
        focusAnim?.cancel()
        focusAnim = ValueAnimator.ofFloat(1f, 1f, 0f).apply {
            duration = 1400
            addUpdateListener { focusAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (dragging || pinchOnPip && scale.isInProgress) {
            pipRectPx()?.let { r ->
                outline.strokeWidth = dp(2f).toFloat()
                val p = cb.pip()
                val radius = if (p.shape.circle) r.width() / 2 else minOf(r.width(), r.height()) * 0.14f
                canvas.drawRoundRect(r, radius, radius, outline)
            }
        }
        if (focusAlpha > 0f && focusX >= 0) {
            val s = dp(36f).toFloat()
            focusPaint.strokeWidth = dp(1.5f).toFloat()
            focusPaint.alpha = (focusAlpha * 255).toInt()
            tmp.set(focusX - s, focusY - s, focusX + s, focusY + s)
            canvas.drawRect(tmp, focusPaint)
        }
    }
}
