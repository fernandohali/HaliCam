package com.halicam.diagnostic.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.halicam.diagnostic.core.CaptureMode
import kotlin.math.min

object Palette {
    const val ACCENT = 0xFFFF6A2B.toInt()     // laranja HaliCam
    const val REC = 0xFFFF3B30.toInt()
    const val PAUSED = 0xFFFFB020.toInt()
    const val TEXT = 0xFFF5F5F5.toInt()
    const val MUTED = 0xB3FFFFFF.toInt()
    const val CHIP = 0x8C1C1C1E.toInt()       // cinza translúcido
    const val CHIP_SOLID = 0xFF1C1C1E.toInt()
    const val PANEL = 0xF2141416.toInt()
    const val DIVIDER = 0x22FFFFFF
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Float): Int = context.dp(v)

fun pill(color: Int, radius: Float, strokeColor: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radius
    setColor(color)
    if (strokePx > 0) setStroke(strokePx, strokeColor)
}

/** Botão redondo com ícone vetorial e fundo translúcido. */
@SuppressLint("ViewConstructor")
class IconButton(context: Context, icon: Icon, background: Boolean = true) : ImageView(context) {
    val iconDrawable = IconDrawable(icon)

    init {
        setImageDrawable(iconDrawable)
        val pad = dp(9f)
        setPadding(pad, pad, pad, pad)
        if (background) setBackground(pill(Palette.CHIP, dp(100f).toFloat()))
        isClickable = true
        isFocusable = true
    }

    var icon: Icon
        get() = iconDrawable.icon
        set(v) { iconDrawable.icon = v }

    fun setTint(color: Int) { iconDrawable.color = color }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        return super.onTouchEvent(event)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }
}

/** Botão principal: anel branco; interior vermelho (vídeo) ou branco (foto); quadrado ao gravar; giro ao finalizar. */
class ShutterButton(context: Context) : View(context) {
    enum class State { IDLE, RECORDING, PAUSED, FINALIZING }

    var photoStyle = false
        set(v) { field = v; invalidate() }
    var state = State.IDLE
        set(v) {
            if (field == v) return
            field = v
            animateMorph(if (v == State.IDLE || v == State.FINALIZING) 0f else 1f)
            spinner.apply { if (v == State.FINALIZING) start() else cancel() }
            invalidate()
        }

    private var morph = 0f
    private var spin = 0f
    private var pressedScale = 1f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.ACCENT; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private var morphAnim: ValueAnimator? = null
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidate() }
    }

    init { isClickable = true; isFocusable = true }

    private fun animateMorph(target: Float) {
        morphAnim?.cancel()
        morphAnim = ValueAnimator.ofFloat(morph, target).apply {
            duration = 220
            addUpdateListener { morph = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - dp(2f)
        val ringW = dp(4f).toFloat()
        ring.strokeWidth = ringW
        ring.color = if (state == State.FINALIZING) 0x66FFFFFF else Color.WHITE
        canvas.drawCircle(cx, cy, outer - ringW / 2, ring)
        if (state == State.FINALIZING) {
            arc.strokeWidth = ringW
            r.set(cx - outer + ringW / 2, cy - outer + ringW / 2, cx + outer - ringW / 2, cy + outer - ringW / 2)
            canvas.drawArc(r, spin, 90f, false, arc)
        }
        val maxInner = (outer - ringW - dp(4f)) * pressedScale
        val minInner = outer * 0.42f
        val half = maxInner + (minInner - maxInner) * morph
        val corner = half + (dp(7f) - half) * morph
        inner.color = when {
            state == State.FINALIZING -> 0xFF5A5A5E.toInt()
            photoStyle -> Color.WHITE
            state == State.PAUSED -> Palette.PAUSED
            else -> Palette.REC
        }
        r.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(r, corner, corner, inner)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedScale = 0.9f; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressedScale = 1f; invalidate() }
        }
        return super.onTouchEvent(event)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }
}

/** Seletor de modos estilo câmera: pílula translúcida com o modo ativo destacado. */
class ModeSelector(context: Context, private val onSelect: (CaptureMode) -> Unit) : LinearLayout(context) {
    private val labels = mutableMapOf<CaptureMode, TextView>()
    var selected: CaptureMode = CaptureMode.VIDEO
        set(v) { field = v; refresh() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = pill(Palette.CHIP, dp(100f).toFloat())
        setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        for (m in CaptureMode.values()) {
            val tv = TextView(context).apply {
                text = m.label
                textSize = 12.5f
                letterSpacing = 0.06f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
                setOnClickListener { onSelect(m) }
            }
            labels[m] = tv
            addView(tv)
        }
        refresh()
    }

    private fun refresh() {
        labels.forEach { (m, tv) ->
            val on = m == selected
            tv.setTextColor(if (on) Palette.ACCENT else Palette.TEXT)
            tv.background = if (on) pill(0xFF2C2C2E.toInt(), dp(100f).toFloat()) else null
        }
    }

    fun setModeEnabled(m: CaptureMode, enabled: Boolean) {
        labels[m]?.alpha = if (enabled) 1f else 0.4f
    }
}
