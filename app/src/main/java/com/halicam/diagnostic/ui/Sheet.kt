package com.halicam.diagnostic.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Painel inferior deslizante e reutilizável (configurações e opções da janela PiP). */
@SuppressLint("ViewConstructor")
class Sheet(context: Context) : FrameLayout(context) {
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Palette.PANEL)
            val r = dp(22f).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        isClickable = true
    }
    private val titleView = TextView(context).apply {
        textSize = 16f
        setTextColor(Palette.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18f), 0, dp(18f), dp(24f))
    }
    private var builder: (Sheet) -> Unit = {}
    var isShowing = false
        private set
    var onDismiss: (() -> Unit)? = null

    init {
        setBackgroundColor(0x66000000)
        visibility = GONE
        setOnClickListener { dismiss() }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(10f), dp(8f), dp(6f))
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(IconButton(context, Icon.CLOSE, background = false).apply { setOnClickListener { dismiss() } },
                LinearLayout.LayoutParams(dp(42f), dp(42f)))
        }
        val handle = View(context).apply { background = pill(0x55FFFFFF, dp(3f).toFloat()) }
        panel.addView(handle, LinearLayout.LayoutParams(dp(36f), dp(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8f) })
        panel.addView(header)
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        scroll.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val max = (h * 0.72f).toInt()
        if (panel.measuredHeight > max) {
            scroll.layoutParams.height = max - (panel.measuredHeight - scroll.measuredHeight)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    fun show(title: String, build: (Sheet) -> Unit) {
        titleView.text = title
        builder = build
        rebuild()
        if (!isShowing) {
            isShowing = true
            visibility = VISIBLE
            alpha = 0f
            panel.translationY = dp(300f).toFloat()
            animate().alpha(1f).setDuration(160).start()
            panel.animate().translationY(0f).setDuration(220).start()
        }
    }

    /** Reconstrói o conteúdo mantendo a rolagem (após uma mudança de configuração). */
    fun rebuild() {
        val y = scroll.scrollY
        content.removeAllViews()
        builder(this)
        scroll.post { scroll.scrollTo(0, y) }
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        panel.animate().translationY(panel.height.toFloat()).setDuration(180).start()
        animate().alpha(0f).setDuration(180).withEndAction { visibility = GONE }.start()
        onDismiss?.invoke()
    }

    // ---------------------------------------------------------------- Linhas

    fun section(title: String) {
        content.addView(TextView(context).apply {
            text = title.uppercase()
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(Palette.ACCENT)
            setPadding(0, dp(18f), 0, dp(6f))
        })
    }

    private fun rowLabel(label: String, value: String? = null, note: String? = null, enabled: Boolean = true): LinearLayout {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8f), 0, dp(8f)) }
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(TextView(context).apply {
            text = label; textSize = 14.5f; setTextColor(if (enabled) Palette.TEXT else 0x66FFFFFF)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) line.addView(TextView(context).apply {
            text = value; textSize = 13f; setTextColor(Palette.MUTED); tag = "value"
        })
        col.addView(line)
        if (note != null) col.addView(TextView(context).apply {
            text = note; textSize = 11.5f; setTextColor(0x88FFFFFF.toInt()); setPadding(0, dp(2f), 0, 0)
        })
        return col
    }

    fun segmented(label: String, options: List<String>, selected: Int, enabled: Boolean = true, note: String? = null, onSelect: (Int) -> Unit) {
        val col = rowLabel(label, note = note, enabled = enabled)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill(0xFF242427.toInt(), dp(12f).toFloat())
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        options.forEachIndexed { i, opt ->
            bar.addView(TextView(context).apply {
                text = opt
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                val on = i == selected
                setTextColor(if (!enabled) 0x55FFFFFF else if (on) 0xFF111111.toInt() else Palette.TEXT)
                background = if (on) pill(if (enabled) Palette.TEXT else 0x55FFFFFF, dp(10f).toFloat()) else null
                if (enabled) setOnClickListener { if (i != selected) onSelect(i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (options.size <= 4) { width = 0; weight = 1f }
            })
        }
        val holder: View = if (options.size > 4) HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; addView(bar)
        } else bar
        col.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        content.addView(col)
    }

    fun toggle(label: String, checked: Boolean, enabled: Boolean = true, note: String? = null, onChange: (Boolean) -> Unit) {
        val col = rowLabel(label, note = note, enabled = enabled)
        val line = col.getChildAt(0) as LinearLayout
        line.addView(Switch(context).apply {
            isChecked = checked
            isEnabled = enabled
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.TEXT, 0xFFBDBDBD.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.ACCENT, 0xFF48484A.toInt()))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        content.addView(col)
    }

    /** Controle deslizante 0..1000. [format] gera o texto do valor; [onChange] é chamado ao arrastar. */
    fun slider(label: String, progress: Int, enabled: Boolean = true, note: String? = null,
               format: (Int) -> String, onChange: (Int) -> Unit) {
        val col = rowLabel(label, value = format(progress), note = note, enabled = enabled)
        val valueView = col.findViewWithTag<TextView>("value")
        col.addView(SeekBar(context).apply {
            max = 1000
            this.progress = progress
            isEnabled = enabled
            progressTintList = ColorStateList.valueOf(Palette.ACCENT)
            thumbTintList = ColorStateList.valueOf(Palette.TEXT)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF48484A.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    valueView?.text = format(p)
                    onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
        content.addView(col)
    }

    fun action(label: String, note: String? = null, onClick: () -> Unit) {
        val col = rowLabel(label, value = "›", note = note)
        col.isClickable = true
        col.setOnClickListener { onClick() }
        content.addView(col)
    }

    fun info(text: String) {
        content.addView(TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, dp(6f), 0, dp(6f))
            setLineSpacing(0f, 1.2f)
        })
    }
}
