package com.halicam.diagnostic.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraMetadata
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import com.halicam.diagnostic.DiagnosticActivity
import com.halicam.diagnostic.core.AspectRatio
import com.halicam.diagnostic.core.AudioProcessing
import com.halicam.diagnostic.core.AudioQuality
import com.halicam.diagnostic.core.Antibanding
import com.halicam.diagnostic.core.BitrateLevel
import com.halicam.diagnostic.core.ColorProfile
import com.halicam.diagnostic.core.VideoCodec
import com.halicam.diagnostic.core.MicFocus
import com.halicam.diagnostic.core.CaptureController
import com.halicam.diagnostic.core.CaptureMode
import com.halicam.diagnostic.core.CaptureSettings
import com.halicam.diagnostic.core.DeviceStatus
import com.halicam.diagnostic.core.FlashMode
import com.halicam.diagnostic.core.PipLayout
import com.halicam.diagnostic.core.PipShape
import com.halicam.diagnostic.core.RecState
import com.halicam.diagnostic.core.WebcamSource
import com.halicam.diagnostic.record.AudioEncoder
import com.halicam.diagnostic.record.EncoderSupport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Tela principal da câmera: prévia em tela cheia, janela frontal flutuante e controles de gravação. */
class CameraActivity : Activity(), CaptureController.Listener, PipOverlayView.Callbacks, SurfaceHolder.Callback {

    private lateinit var controller: CaptureController
    private lateinit var root: FrameLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: PipOverlayView
    private lateinit var resChip: TextView
    private lateinit var fpsChip: TextView
    private lateinit var aspectChip: TextView
    private lateinit var formatChip: LinearLayout
    private lateinit var recPill: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var batteryIcon: IconDrawable
    private lateinit var batteryText: TextView
    private lateinit var storageText: TextView
    private lateinit var tempText: TextView
    private lateinit var flashBtn: IconButton
    private lateinit var pipBtn: IconButton
    private lateinit var settingsBtn: IconButton
    private lateinit var zoomBar: LinearLayout
    private lateinit var modeSelector: ModeSelector
    private lateinit var shutter: ShutterButton
    private lateinit var thumb: ImageView
    private lateinit var pauseBtn: IconButton
    private lateinit var flipBtn: IconButton
    private lateinit var webcamCard: LinearLayout
    private lateinit var webcamTitle: TextView
    private lateinit var webcamUrl: TextView
    private lateinit var webcamSources: LinearLayout
    private lateinit var banner: TextView
    private lateinit var progressBox: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var shotFlash: View
    private lateinit var sheet: Sheet

    private val ui = Handler(Looper.getMainLooper())
    private var orientationListener: OrientationEventListener? = null
    private var deviceRotation = 0
    private var uiRotation = 0f
    private val rotatables = mutableListOf<View>()
    private var askedPermissions = false
    private var zoomKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        controller = CaptureController(applicationContext, this)
        buildUi()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val d = o % 90
                if (d in 30..60) return   // histerese entre posições
                val snapped = ((o + 45) / 90 * 90) % 360
                if (snapped != deviceRotation) {
                    deviceRotation = snapped
                    controller.setDeviceRotation(snapped)
                    rotateIcons(snapped)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        orientationListener?.enable()
        val hasCam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasCam) {
            controller.resume()
            if (!hasMic && !askedPermissions) { askedPermissions = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ) }
        } else if (!askedPermissions) {
            askedPermissions = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQ)
        } else {
            showBanner("Permissão de câmera necessária. Toque aqui para abrir as configurações do app.", true) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        loadThumbnail()
        ui.post(ticker)
        render()
    }

    override fun onPause() {
        cameraPopup?.dismiss()
        super.onPause()
        controller.pause()
        orientationListener?.disable()
        ui.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (sheet.isShowing) sheet.dismiss() else super.onBackPressed()
    }

    // ---------------------------------------------------------------- Construção da interface

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        previewBox = FrameLayout(this)
        surfaceView = SurfaceView(this).apply { holder.addCallback(this@CameraActivity) }
        overlay = PipOverlayView(this, this)
        previewBox.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        previewBox.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewBox, FrameLayout.LayoutParams(1, 1))
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) relayoutPreview()
        }

        // Barra superior
        val top = FrameLayout(this).apply { setPadding(dp(14f), dp(14f), dp(14f), 0) }
        formatChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(Palette.CHIP, dp(100f).toFloat())
            setPadding(dp(6f), 0, dp(6f), 0)
        }
        resChip = chipText().apply { setOnClickListener { showCameraSelect() } }
        fpsChip = chipText().apply { setOnClickListener { showCameraSelect() } }
        aspectChip = chipText().apply { setOnClickListener { cycleAspect() } }
        formatChip.addView(resChip); formatChip.addView(fpsChip); formatChip.addView(aspectChip)
        top.addView(formatChip, FrameLayout.LayoutParams(-2, dp(40f), Gravity.START or Gravity.TOP))

        recPill = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = pill(Palette.REC, dp(7f).toFloat())
            visibility = View.GONE
        }
        top.addView(recPill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(6f) })

        val rightGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(Palette.CHIP, dp(100f).toFloat())
            setPadding(dp(2f), 0, dp(2f), 0)
        }
        flashBtn = IconButton(this, Icon.FLASH_OFF, background = false).apply { setOnClickListener { cycleFlash() } }
        pipBtn = IconButton(this, Icon.PIP, background = false).apply { setOnClickListener { showPipSheet() } }
        settingsBtn = IconButton(this, Icon.SETTINGS, background = false).apply { setOnClickListener { showSettings() } }
        for (b in listOf(flashBtn, pipBtn, settingsBtn)) {
            rightGroup.addView(b, LinearLayout.LayoutParams(dp(40f), dp(40f)))
            rotatables += b
        }
        top.addView(rightGroup, FrameLayout.LayoutParams(-2, dp(40f), Gravity.END or Gravity.TOP))

        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            alpha = 0.85f
        }
        batteryIcon = IconDrawable(Icon.BATTERY)
        batteryText = statusText()
        storageText = statusText()
        tempText = statusText()
        statusRow.addView(statusIcon(batteryIcon)); statusRow.addView(batteryText)
        statusRow.addView(statusIcon(IconDrawable(Icon.STORAGE))); statusRow.addView(storageText)
        statusRow.addView(statusIcon(IconDrawable(Icon.THERMO))); statusRow.addView(tempText)
        top.addView(statusRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(46f) })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        banner = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = pill(0xE61C1C1E.toInt(), dp(14f).toFloat())
            visibility = View.GONE
            setLineSpacing(0f, 1.15f)
        }
        root.addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = dp(84f); leftMargin = dp(16f); rightMargin = dp(16f)
        })

        progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(0xCC1C1C1E.toInt(), dp(100f).toFloat())
            setPadding(dp(14f), dp(8f), dp(16f), dp(8f))
            addView(ProgressBar(this@CameraActivity).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(18f), dp(18f)))
            progressText = TextView(this@CameraActivity).apply { textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(10f), 0, 0, 0) }
            addView(progressText)
            visibility = View.GONE
        }
        root.addView(progressBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        shotFlash = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f; isClickable = false }
        root.addView(shotFlash, FrameLayout.LayoutParams(-1, -1))

        // Área inferior
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000))
            setPadding(0, dp(24f), 0, dp(22f))
        }
        webcamCard = buildWebcamCard()
        bottom.addView(webcamCard, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(16f); rightMargin = dp(16f); bottomMargin = dp(12f) })
        zoomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = pill(0x661C1C1E, dp(100f).toFloat())
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        bottom.addView(zoomBar, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12f) })
        modeSelector = ModeSelector(this) { m -> selectMode(m) }
        bottom.addView(modeSelector, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(16f) })

        val controls = FrameLayout(this)
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = pill(0xFF2C2C2E.toInt(), dp(12f).toFloat(), 0x99FFFFFF.toInt(), dp(1.5f))
            setPadding(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(12f).toFloat()) }
            }
            setOnClickListener { openLastMedia() }
        }
        rotatables += thumb
        controls.addView(thumb, FrameLayout.LayoutParams(dp(50f), dp(50f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(32f) })
        pauseBtn = IconButton(this, Icon.PAUSE).apply {
            visibility = View.GONE
            setOnClickListener { if (controller.recState == RecState.RECORDING) controller.pauseRecording() else controller.resumeRecording() }
        }
        controls.addView(pauseBtn, FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(31f) })
        shutter = ShutterButton(this).apply { setOnClickListener { onShutter() } }
        controls.addView(shutter, FrameLayout.LayoutParams(dp(80f), dp(80f), Gravity.CENTER))
        flipBtn = IconButton(this, Icon.FLIP).apply { setOnClickListener { controller.flipCamera() } }
        rotatables += flipBtn
        controls.addView(flipBtn, FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(31f) })
        bottom.addView(controls, LinearLayout.LayoutParams(-1, dp(88f)))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        sheet = Sheet(this)
        root.addView(sheet, FrameLayout.LayoutParams(-1, -1))
    }

    private fun chipText() = TextView(this).apply {
        textSize = 12.5f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
    }

    private fun statusText() = TextView(this).apply {
        textSize = 11.5f; setTextColor(Color.WHITE); setPadding(dp(3f), 0, dp(12f), 0)
    }

    private fun statusIcon(d: IconDrawable) = ImageView(this).apply {
        setImageDrawable(d)
        layoutParams = LinearLayout.LayoutParams(dp(15f), dp(15f))
    }

    private fun buildWebcamCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(0xCC1C1C1E.toInt(), dp(16f).toFloat())
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            visibility = View.GONE
        }
        webcamTitle = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        webcamUrl = TextView(this).apply {
            textSize = 12f; setTextColor(Palette.ACCENT); typeface = Typeface.MONOSPACE; setPadding(0, dp(4f), 0, dp(8f))
            setOnClickListener {
                val url = controller.webcamUrl ?: return@setOnClickListener
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("HaliCam", url))
                showBanner("Endereço copiado. No OBS: fonte Navegador com /obs ou Fonte de mídia com /stream.mjpg.")
            }
        }
        webcamSources = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(webcamTitle); card.addView(webcamUrl); card.addView(webcamSources)
        return card
    }

    // ---------------------------------------------------------------- Layout da prévia

    private fun relayoutPreview() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val a = controller.frameAspect()
        var bw = w
        var bh = (w / a).roundToInt()
        if (bh > h) { bh = h; bw = (h * a).roundToInt() }
        val topZone = dp(64f)
        val bottomZone = dp(196f)
        val y = when {
            bh + topZone + bottomZone <= h -> topZone + (h - topZone - bottomZone - bh) / 2
            bh + topZone <= h -> topZone
            else -> (h - bh) / 2
        }
        previewBox.layoutParams = FrameLayout.LayoutParams(bw, bh).apply { leftMargin = (w - bw) / 2; topMargin = y }
        previewBox.requestLayout()
    }

    // ---------------------------------------------------------------- Renderização do estado

    private fun render() {
        val c = controller
        val rec = c.recState
        val idle = rec == RecState.IDLE
        val s = c.settings

        modeSelector.selected = c.mode
        modeSelector.visibility = if (idle) View.VISIBLE else View.INVISIBLE
        modeSelector.setModeEnabled(CaptureMode.DUAL, c.dualAvailable != false)

        shutter.photoStyle = c.mode == CaptureMode.PHOTO
        shutter.state = when (rec) {
            RecState.IDLE -> ShutterButton.State.IDLE
            RecState.RECORDING -> ShutterButton.State.RECORDING
            RecState.PAUSED -> ShutterButton.State.PAUSED
            RecState.FINALIZING -> ShutterButton.State.FINALIZING
        }
        shutter.isEnabled = (c.camerasReady && rec != RecState.FINALIZING) || rec == RecState.RECORDING || rec == RecState.PAUSED

        val recording = rec == RecState.RECORDING || rec == RecState.PAUSED
        thumb.visibility = if (recording) View.GONE else View.VISIBLE
        pauseBtn.visibility = if (recording) View.VISIBLE else View.GONE
        pauseBtn.icon = if (rec == RecState.PAUSED) Icon.PLAY else Icon.PAUSE
        flipBtn.visibility = if (idle || c.dualActive) View.VISIBLE else View.INVISIBLE
        flipBtn.icon = if (c.dualActive) Icon.SWAP else Icon.FLIP

        pipBtn.visibility = if (c.dualActive) View.VISIBLE else View.GONE
        flashBtn.visibility = if (c.flashAvailable) View.VISIBLE else View.GONE
        flashBtn.icon = when (s.flash) {
            FlashMode.OFF -> Icon.FLASH_OFF
            FlashMode.ON -> Icon.FLASH_ON
            FlashMode.AUTO -> if (c.mode == CaptureMode.PHOTO) Icon.FLASH_AUTO else Icon.FLASH_OFF
        }
        flashBtn.setTint(if (s.flash == FlashMode.ON) Palette.PAUSED else Color.WHITE)

        if (c.mode == CaptureMode.PHOTO) {
            resChip.text = "FOTO"
            fpsChip.visibility = View.GONE
            aspectChip.text = "4:3"
        } else {
            resChip.text = resLabel(s.resolution)
            fpsChip.visibility = View.VISIBLE
            fpsChip.text = "${s.fps} FPS ▾"
            aspectChip.text = c.worldAspect.label
        }
        resChip.alpha = if (idle) 1f else 0.5f
        fpsChip.alpha = resChip.alpha
        aspectChip.alpha = if (idle && (c.mode == CaptureMode.VIDEO || c.mode == CaptureMode.DUAL)) 1f else 0.5f

        recPill.visibility = if (recording) View.VISIBLE else View.GONE
        formatChip.visibility = if (recording) View.INVISIBLE else View.VISIBLE
        statusRow.visibility = if (recording) View.INVISIBLE else View.VISIBLE

        val prog = c.progress ?: if (!c.camerasReady && rec == RecState.IDLE && hasCamera()) "Abrindo câmera…" else null
        progressBox.visibility = if (prog != null) View.VISIBLE else View.GONE
        progressText.text = prog ?: ""

        webcamCard.visibility = if (c.mode == CaptureMode.WEBCAM) View.VISIBLE else View.GONE
        if (c.mode == CaptureMode.WEBCAM) renderWebcamCard()

        renderZoom()
        overlay.invalidate()
        tick()
    }

    private fun renderWebcamCard() {
        val c = controller
        val url = c.webcamUrl
        val n = c.webcamClients
        webcamTitle.text = if (url == null) "Webcam: conecte o celular a uma rede Wi-Fi"
        else "● Webcam no ar · $n ${if (n == 1) "conexão" else "conexões"} · ${c.settings.webcamHeight}p"
        webcamTitle.setTextColor(if (n > 0) 0xFF34C759.toInt() else Color.WHITE)
        webcamUrl.text = url?.let { "$it   (tocar para copiar)" } ?: "Sem endereço IP local"
        webcamSources.removeAllViews()
        for (src in WebcamSource.values()) {
            val on = c.settings.webcamSource == src
            val enabled = src != WebcamSource.DUAL || c.dualAvailable != false
            webcamSources.addView(TextView(this).apply {
                text = src.label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                setTextColor(if (on) 0xFF111111.toInt() else if (enabled) Color.WHITE else 0x66FFFFFF)
                background = if (on) pill(Color.WHITE, dp(100f).toFloat()) else pill(0x33FFFFFF, dp(100f).toFloat())
                setOnClickListener {
                    if (c.recState != RecState.IDLE) showBanner("Pare a gravação para trocar a fonte.")
                    else if (!on) c.updateSettings(c.settings.copy(webcamSource = src))
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8f) })
        }
    }

    private fun renderZoom() {
        val caps = controller.mainCaps
        val max = caps?.maxZoom ?: 1f
        val stops = listOf(1f, 2f, 4f, 8f).filter { it <= max + 0.01f }
        val z = controller.settings.zoom
        val key = "${stops}_${"%.1f".format(z)}"
        zoomBar.visibility = if (stops.size > 1) View.VISIBLE else View.GONE
        if (key == zoomKey) return
        zoomKey = key
        zoomBar.removeAllViews()
        val active = stops.lastOrNull { z >= it - 0.01f } ?: 1f
        for (v in stops) {
            val on = v == active
            zoomBar.addView(TextView(this).apply {
                text = if (on) zoomLabel(z) else zoomLabel(v)
                textSize = if (on) 12.5f else 11.5f
                gravity = Gravity.CENTER
                setTextColor(if (on) Palette.ACCENT else Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = if (on) pill(0xCC2C2C2E.toInt(), dp(100f).toFloat()) else null
                setOnClickListener { controller.setZoom(v) }
            }, LinearLayout.LayoutParams(dp(if (on) 44f else 36f), dp(36f)))
        }
    }

    private fun zoomLabel(z: Float): String =
        if (abs(z - z.roundToInt()) < 0.05f) "${z.roundToInt()}×" else String.format(Locale("pt", "BR"), "%.1f×", z)

    private fun resLabel(r: Int) = when (r) { 2160 -> "UHD"; 1440 -> "QHD"; 1080 -> "FHD"; 720 -> "HD"; else -> "${r}p" }

    /** Atualizações periódicas: cronômetro, bateria, armazenamento, temperatura e FPS medido. */
    private val ticker = object : Runnable {
        override fun run() { tick(); ui.postDelayed(this, 500) }
    }

    private fun tick() {
        val c = controller
        if (c.recState == RecState.RECORDING || c.recState == RecState.PAUSED) {
            val t = c.elapsedMs() / 1000
            val txt = String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
            recPill.text = if (c.recState == RecState.PAUSED) "❚❚ $txt" else txt
            recPill.background = pill(if (c.recState == RecState.PAUSED) Palette.PAUSED else Palette.REC, dp(7f).toFloat())
        }
        val st = DeviceStatus.read(this)
        batteryIcon.level01 = st.batteryPct / 100f
        batteryIcon.color = if (st.batteryPct in 0..15 && !st.charging) Palette.REC else Color.WHITE
        batteryText.text = if (st.batteryPct >= 0) "${st.batteryPct}%${if (st.charging) "+" else ""}" else "—"
        val mins = DeviceStatus.minutesLeft(st.freeBytes, c.estimatedBitrate())
        storageText.text = "${DeviceStatus.formatBytes(st.freeBytes)} · ~${mins} min"
        tempText.text = String.format(Locale("pt", "BR"), "%.0f °C", st.temperatureC)
        tempText.setTextColor(if (st.temperatureC >= 43f) Palette.PAUSED else Color.WHITE)
        if (c.mode == CaptureMode.WEBCAM) renderWebcamCard()
    }

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- Ações

    private fun onShutter() {
        val c = controller
        when {
            c.mode == CaptureMode.PHOTO -> {
                c.takePhoto()
                shotFlash.alpha = 0.9f
                shotFlash.animate().alpha(0f).setDuration(220).start()
            }
            c.recState == RecState.IDLE -> c.startRecording()
            c.recState == RecState.RECORDING || c.recState == RecState.PAUSED -> c.stopRecording()
        }
    }

    private fun selectMode(m: CaptureMode) {
        if (controller.recState != RecState.IDLE) return
        if (m == CaptureMode.DUAL && controller.dualAvailable == false) {
            showBanner("Dual Capture não funcionou neste aparelho na última tentativa. Tentando novamente…")
        }
        controller.setMode(m)
    }

    private fun cycleFlash() {
        val c = controller
        val next = if (c.mode == CaptureMode.PHOTO) when (c.settings.flash) {
            FlashMode.OFF -> FlashMode.AUTO; FlashMode.AUTO -> FlashMode.ON; FlashMode.ON -> FlashMode.OFF
        } else if (c.settings.flash == FlashMode.ON) FlashMode.OFF else FlashMode.ON
        c.updateSettings(c.settings.copy(flash = next))
        showBanner("Flash: ${next.label.lowercase()}${if (c.mode != CaptureMode.PHOTO && next == FlashMode.ON) " (lanterna)" else ""}")
    }

    private var cameraPopup: PopupWindow? = null

    /** Select da barra superior: escolha da câmera (no VÍDEO) e do modo resolução/fps. */
    private fun showCameraSelect() {
        val c = controller
        if (c.mode == CaptureMode.PHOTO) return
        if (c.recState != RecState.IDLE) { showBanner("Pare a gravação para trocar a câmera ou o modo."); return }
        cameraPopup?.dismiss()

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(Palette.PANEL, dp(18f).toFloat(), Palette.DIVIDER, dp(1f))
            setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
            elevation = dp(8f).toFloat()
        }
        val popup = PopupWindow(list, dp(250f), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            isOutsideTouchable = true
            elevation = dp(8f).toFloat()
            setOnDismissListener { cameraPopup = null }
        }

        fun header(text: String) = list.addView(TextView(this).apply {
            this.text = text.uppercase(Locale("pt", "BR"))
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(Palette.ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
        })

        fun option(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = if (selected) pill(0xFF2C2C2E.toInt(), dp(12f).toFloat()) else null
                isClickable = true
                setOnClickListener { popup.dismiss(); if (!selected) onClick() }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(if (selected) Palette.ACCENT else Palette.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            subtitle?.let {
                texts.addView(TextView(this).apply { text = it; textSize = 12f; setTextColor(Palette.MUTED) })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) row.addView(TextView(this).apply { text = "✓"; textSize = 16f; setTextColor(Palette.ACCENT) })
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        val s = c.settings
        val front = c.mainCaps?.isFront == true
        if (c.mode == CaptureMode.VIDEO) {
            header("Câmera")
            option("Traseira", "UHD · QHD · FHD", !front) { c.flipCamera() }
            option("Frontal", "QHD · FHD", front) { c.flipCamera() }
        }

        val sizes = mapOf(2160 to "3840×2160", 1440 to "2560×1440", 1080 to "1920×1080", 720 to "1280×720")
        val modes = if (c.mode == CaptureMode.VIDEO) c.videoModes()
            else c.resolutionOptions().flatMap { r -> c.fpsOptions().map { r to it } }
        header(if (c.mode == CaptureMode.VIDEO) "Modo de vídeo" else "Resolução (${c.mode.label.lowercase().replaceFirstChar { it.uppercase() }})")
        for ((r, f) in modes) {
            option("${resLabel(r)} $f fps", sizes[r] ?: "${r}p", r == s.resolution && f == s.fps) {
                c.updateSettings(c.settings.copy(resolution = r, fps = f))
            }
        }
        if (c.uhd60Unavailable) {
            list.addView(TextView(this).apply {
                text = "UHD 60 fps indisponível neste aparelho (a câmera só anuncia até 30 fps para apps de terceiros)."
                textSize = 11.5f
                setTextColor(Palette.MUTED)
                setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
            })
        }

        cameraPopup = popup
        popup.showAsDropDown(formatChip, 0, dp(8f))
    }

    private fun cycleAspect() {
        val c = controller
        if (c.recState != RecState.IDLE || (c.mode != CaptureMode.VIDEO && c.mode != CaptureMode.DUAL)) return
        val order = listOf(AspectRatio.R9_16, AspectRatio.R16_9, AspectRatio.R1_1)
        val next = order[(order.indexOf(c.settings.aspect) + 1) % order.size]
        c.updateSettings(c.settings.copy(aspect = next))
        if (next == AspectRatio.R16_9 && deviceRotation % 180 == 0) {
            showBanner("16:9 com o aparelho em pé recorta a imagem. Gire o celular para gravar na horizontal.")
        }
    }

    private fun rotateIcons(rotation: Int) {
        val target = -rotation.toFloat()
        val delta = ((target - uiRotation) % 360f + 540f) % 360f - 180f
        uiRotation += delta
        rotatables.forEach { it.animate().rotation(uiRotation).setDuration(250).start() }
    }

    private fun openLastMedia() {
        val s = controller.prefs.lastMediaUri ?: return
        val type = if (controller.prefs.lastMediaIsVideo) "video/mp4" else "image/jpeg"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(s), type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            showBanner("Nenhum app para abrir a mídia.")
        }
    }

    private fun loadThumbnail() {
        val s = controller.prefs.lastMediaUri ?: return
        val isVideo = controller.prefs.lastMediaIsVideo
        Thread {
            val uri = Uri.parse(s)
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
                    contentResolver.loadThumbnail(uri, Size(240, 240), null)
                } else if (isVideo) {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(uri.path ?: "", MediaStore.Images.Thumbnails.MINI_KIND)
                } else {
                    android.graphics.BitmapFactory.decodeFile(uri.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                }
            } catch (_: Exception) { null }
            runOnUiThread { thumb.setImageBitmap(bmp) }
        }.start()
    }

    private val hideBanner = Runnable { banner.animate().alpha(0f).setDuration(200).withEndAction { banner.visibility = View.GONE }.start() }

    private fun showBanner(text: String, long: Boolean = false, onClick: (() -> Unit)? = null) {
        banner.text = text
        banner.visibility = View.VISIBLE
        banner.alpha = 1f
        banner.setOnClickListener { onClick?.invoke(); hideBanner.run() }
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, if (long) 8000 else 3500)
    }

    // ---------------------------------------------------------------- Painéis

    private fun showPipSheet() {
        sheet.show("Janela frontal") { sh -> buildPipSheet(sh) }
    }

    private fun buildPipSheet(sh: Sheet) {
        val c = controller
        val p = c.pip
        sh.segmented("Formato", PipShape.values().map { it.label }, p.shape.ordinal) {
            c.setPip(p.copy(shape = PipShape.values()[it]), true); sh.rebuild()
        }
        sh.segmented("Posição", listOf("↖ Sup. esq.", "↗ Sup. dir.", "↙ Inf. esq.", "↘ Inf. dir."), -1) { i ->
            val (x, y) = when (i) { 0 -> 0f to 0f; 1 -> 1f to 0f; 2 -> 0f to 1f; else -> 1f to 1f }
            c.setPip(p.copy(cx = x, cy = y), true); sh.rebuild()
        }
        sh.slider("Tamanho", (((p.widthFrac - PipLayout.MIN_SIZE) / (PipLayout.MAX_SIZE - PipLayout.MIN_SIZE)) * 1000).toInt(),
            format = { "${(PipLayout.MIN_SIZE + (PipLayout.MAX_SIZE - PipLayout.MIN_SIZE) * it / 1000f).times(100).roundToInt()}%" }) {
            c.setPip(c.pip.copy(widthFrac = PipLayout.MIN_SIZE + (PipLayout.MAX_SIZE - PipLayout.MIN_SIZE) * it / 1000f), true)
        }
        sh.segmented("Câmera principal (fundo)", listOf("Traseira", "Frontal"), if (p.backIsMain) 0 else 1) {
            c.setPip(p.copy(backIsMain = it == 0), true); sh.rebuild()
        }
        sh.toggle("Espelhar câmera frontal", c.settings.mirrorFront, note = "Aplicado na prévia e no vídeo final.") {
            c.updateSettings(c.settings.copy(mirrorFront = it))
        }
        sh.toggle("Mostrar janela", p.visible,
            note = "Ocultar remove a janela da prévia e do vídeo, mas a câmera continua capturando: ao mostrar de novo, volta na hora.") {
            c.setPip(c.pip.copy(visible = it), true)
        }
        sh.info("Dicas: arraste a janela para movê-la, use dois dedos sobre ela para redimensionar e toque duas vezes para trocar a câmera principal. Mudanças durante a gravação aparecem no vídeo final.")
    }

    private fun showSettings() {
        sheet.show("Configurações") { sh -> buildSettings(sh) }
    }

    private fun buildSettings(sh: Sheet) {
        val c = controller
        val s = c.settings
        val idle = c.recState == RecState.IDLE
        val caps = c.mainCaps
        fun set(n: CaptureSettings) { c.updateSettings(n); sh.post { sh.rebuild() } }

        if (c.mode != CaptureMode.PHOTO) {
            sh.section("Vídeo")
            if (c.mode == CaptureMode.VIDEO) {
                val modes = c.videoModes()
                val sizes = mapOf(2160 to "3840×2160", 1440 to "2560×1440", 1080 to "1920×1080")
                val cur = modes.firstOrNull { it.first == s.resolution && it.second == s.fps }
                val note = buildString {
                    append(if (caps?.isFront == true) "Câmera frontal" else "Câmera traseira")
                    cur?.let { append(" · ${sizes[it.first] ?: "${it.first}p"} a ${it.second} fps") }
                    if (c.uhd60Unavailable) append("\nUHD 60 fps indisponível: a câmera traseira deste aparelho só anuncia até 30 fps " +
                        "para apps de terceiros (o 4K60 do app da Samsung usa uma API privada).")
                }
                sh.segmented("Modo de vídeo", modes.map { "${resLabel(it.first)} ${it.second}" }, modes.indexOf(cur), enabled = idle, note = note) {
                    set(s.copy(resolution = modes[it].first, fps = modes[it].second))
                }
            } else {
                val res = c.resolutionOptions()
                sh.segmented("Resolução", res.map(::resLabel), res.indexOf(s.resolution), enabled = idle,
                    note = if (c.mode == CaptureMode.DUAL) "No Dual o limite é FHD (duas câmeras + composição em tempo real)." else null) {
                    set(s.copy(resolution = res[it]))
                }
                val fps = c.fpsOptions()
                sh.segmented("Quadros por segundo", fps.map { "$it fps" }, fps.indexOf(s.fps), enabled = idle) {
                    set(s.copy(fps = fps[it]))
                }
            }
            if (c.mode == CaptureMode.VIDEO || c.mode == CaptureMode.DUAL) {
                val aspects = listOf(AspectRatio.R9_16, AspectRatio.R16_9, AspectRatio.R1_1)
                sh.segmented("Proporção", listOf("9:16", "16:9", "1:1"), aspects.indexOf(s.aspect), enabled = idle,
                    note = "9:16 Reels/Shorts · 16:9 YouTube · 1:1 feed. A orientação do arquivo segue a posição do celular no início da gravação.") {
                    set(s.copy(aspect = aspects[it]))
                }
            }
            val stabNote = when {
                caps == null -> null
                caps.hasVideoStabilization && caps.hasOis -> "Óptica (OIS) + eletrônica (EIS)."
                caps.hasOis -> "Óptica (OIS)."
                caps.hasVideoStabilization -> "Eletrônica (EIS)."
                else -> "Não suportada pela câmera atual."
            }
            sh.toggle("Estabilização", s.stabilization && caps?.hasStabilization == true, enabled = caps?.hasStabilization == true, note = stabNote) {
                set(s.copy(stabilization = it))
            }

            sh.section("Qualidade do arquivo")
            val hevc = EncoderSupport.hasEncoder(VideoCodec.HEVC.mime)
            sh.segmented("Codec", VideoCodec.values().map { it.label }, c.effectiveCodec().ordinal, enabled = idle && hevc,
                note = if (hevc) s.codec.note else "Este aparelho não tem encoder H.265.") {
                set(s.copy(codec = VideoCodec.values()[it]))
            }
            val mbps = c.estimatedBitrate() / 1_000_000.0
            sh.segmented("Bitrate", BitrateLevel.values().map { it.label }, s.bitrate.ordinal, enabled = idle,
                note = String.format(Locale("pt", "BR"), "≈ %.0f Mbps no total · ≈ %.0f MB por minuto de gravação.", mbps, mbps * 60 / 8)) {
                set(s.copy(bitrate = BitrateLevel.values()[it]))
            }
        }

        sh.section("Cor e imagem")
        sh.segmented("Perfil de cor", ColorProfile.values().map { it.label }, s.colorProfile.ordinal,
            note = s.colorProfile.note + if (c.mode == CaptureMode.PHOTO) " (Vale para vídeo e webcam; fotos saem como a câmera entrega.)" else "") {
            set(s.copy(colorProfile = ColorProfile.values()[it]))
        }
        sh.segmented("Anti-cintilação", Antibanding.values().map { it.label }, s.antibanding.ordinal,
            note = "Remove faixas e piscadas sob lâmpadas. No Brasil a rede elétrica é 60 Hz. Não se aplica com exposição manual.") {
            set(s.copy(antibanding = Antibanding.values()[it]))
        }
        sh.toggle("Grade de composição", s.showGrid, note = "Só na prévia; não aparece no vídeo.") { set(s.copy(showGrid = it)) }

        sh.section("Câmera · ${caps?.facingLabel ?: "—"} (ID ${caps?.id ?: "?"})")
        if (caps != null) {
            val mf = caps.supportsManualFocus
            sh.segmented("Foco", listOf("Automático", "Manual"), if (s.manualFocus && mf) 1 else 0, enabled = mf,
                note = if (mf) "Toque na prévia para focar num ponto." else "Foco manual não suportado nesta câmera.") {
                set(s.copy(manualFocus = it == 1)); sh.rebuild()
            }
            if (s.manualFocus && mf) {
                val maxD = caps.minFocusDistance
                sh.slider("Distância de foco", (s.focusDistance / maxD * 1000).roundToInt(), format = {
                    val d = maxD * it / 1000f
                    if (d < 0.05f) "∞" else String.format(Locale("pt", "BR"), "%.2f m", 1f / d)
                }) { c.updateSettings(c.settings.copy(focusDistance = maxD * it / 1000f)) }
            }
            val me = caps.supportsManualExposure
            sh.segmented("Exposição", listOf("Automática", "Manual"), if (s.manualExposure && me) 1 else 0, enabled = me,
                note = if (me) null else "Exposição manual (ISO/obturador) não suportada nesta câmera.") {
                set(s.copy(manualExposure = it == 1)); sh.rebuild()
            }
            if (s.manualExposure && me) {
                val iso = caps.isoRange!!
                sh.slider("ISO", logProgress(s.iso.toDouble(), iso.lower.toDouble(), iso.upper.toDouble()),
                    format = { "${logValue(it, iso.lower.toDouble(), iso.upper.toDouble()).roundToInt()}" }) {
                    c.updateSettings(c.settings.copy(iso = logValue(it, iso.lower.toDouble(), iso.upper.toDouble()).roundToInt()))
                }
                val ex = caps.exposureRange!!
                val maxNs = minOf(ex.upper, 1_000_000_000L / s.fps).toDouble()
                val minNs = ex.lower.toDouble()
                sh.slider("Obturador", logProgress(s.exposureNs.toDouble(), minNs, maxNs),
                    format = { "1/${(1e9 / logValue(it, minNs, maxNs)).roundToInt()} s" }) {
                    c.updateSettings(c.settings.copy(exposureNs = logValue(it, minNs, maxNs).toLong()))
                }
            }
            val ae = caps.aeCompRange
            if (ae.upper > ae.lower) {
                val step = caps.aeCompStep.toFloat()
                sh.slider("Compensação de exposição", ((s.aeCompensation - ae.lower) * 1000f / (ae.upper - ae.lower)).roundToInt(),
                    enabled = !(s.manualExposure && me),
                    format = { String.format(Locale("pt", "BR"), "%+.1f EV", (ae.lower + (ae.upper - ae.lower) * it / 1000f).roundToInt() * step) }) {
                    c.updateSettings(c.settings.copy(aeCompensation = (ae.lower + (ae.upper - ae.lower) * it / 1000f).roundToInt()))
                }
            }
            val awb = caps.awbModes.filter { it != CameraMetadata.CONTROL_AWB_MODE_OFF }
            sh.segmented("Balanço de branco", awb.map(::awbLabel), awb.indexOf(s.awbMode).coerceAtLeast(0)) {
                set(s.copy(awbMode = awb[it]))
            }
            if (caps.maxZoom > 1f) {
                val singleBack = c.catalog.count { it.isBack } == 1
                sh.slider("Zoom", ((s.zoom - 1f) / (caps.maxZoom - 1f) * 1000).roundToInt(),
                    note = if (singleBack) "Zoom digital: este aparelho expõe só uma câmera traseira ao Camera2 (a teleobjetiva não fica acessível a apps de terceiros)." else null,
                    format = { zoomLabel(1f + (caps.maxZoom - 1f) * it / 1000f) }) {
                    c.setZoom(1f + (caps.maxZoom - 1f) * it / 1000f)
                }
            }
        }

        sh.section("Dual Capture")
        sh.info(when (c.dualAvailable) {
            true -> "Disponível neste aparelho (par ${c.prefs.dualPair?.replace(",", " + ") ?: "—"}). Traseira e frontal capturam ao mesmo tempo e são compostas em um único MP4." +
                (c.frontStreamSize?.let { "\nFrontal capturando em ${it.width}x${it.height}." } ?: "")
            false -> "Indisponível: ${c.dualFailure ?: "o aparelho não manteve as duas câmeras ativas."}"
            null -> "Ainda não testado. Entre no modo DUAL para verificar."
        })
        sh.toggle("Espelhar câmera frontal", s.mirrorFront) { set(s.copy(mirrorFront = it)) }
        sh.toggle("Salvar originais separados", s.saveSeparate, enabled = idle,
            note = "Além da composição, grava traseira e frontal em arquivos próprios. Aumenta o consumo e o aquecimento.") {
            set(s.copy(saveSeparate = it))
        }
        if (s.saveSeparate) {
            val sepRes = listOf(720, 1080)
            sh.segmented("Resolução dos originais", sepRes.map { "${it}p" }, sepRes.indexOf(s.separateHeight).coerceAtLeast(0), enabled = idle,
                note = "A frontal fica limitada à resolução que o hardware entrega no Dual.") {
                set(s.copy(separateHeight = sepRes[it]))
            }
        }
        sh.action("Opções da janela frontal") { showPipSheet() }

        sh.section("Áudio")
        val mics = AudioEncoder.inputDevices(this)
        val micIds = listOf(0) + mics.map { it.id }
        sh.segmented("Microfone", listOf("Padrão") + mics.map(AudioEncoder::deviceLabel),
            micIds.indexOf(s.micDeviceId).coerceAtLeast(0), enabled = idle) { set(s.copy(micDeviceId = micIds[it])) }
        sh.segmented("Qualidade de áudio", listOf("Padrão", "Alta", "Máxima"), s.audio.ordinal, enabled = idle, note = s.audio.label) {
            set(s.copy(audio = AudioQuality.values()[it]))
        }
        sh.segmented("Processamento do microfone", AudioProcessing.values().map { it.label }, s.audioProcessing.ordinal,
            enabled = idle, note = s.audioProcessing.note) {
            set(s.copy(audioProcessing = AudioProcessing.values()[it]))
        }
        sh.segmented("Foco do microfone", MicFocus.values().map { it.label }, s.micFocus.ordinal, note = s.micFocus.note) {
            set(s.copy(micFocus = MicFocus.values()[it]))
        }
        sh.toggle("Zoom de áudio", s.audioZoom,
            note = "Ao dar zoom no vídeo, o microfone estreita a captação para o que está sendo filmado (Android 10+, se o aparelho suportar).") {
            set(s.copy(audioZoom = it))
        }

        sh.section("Webcam (OBS Studio)")
        val heights = listOf(540, 720, 1080)
        sh.segmented("Resolução do stream", heights.map { "${it}p" }, heights.indexOf(s.webcamHeight)) { set(s.copy(webcamHeight = heights[it])) }
        sh.info("No modo WEBCAM o celular publica MJPEG na rede Wi-Fi local. No OBS, adicione uma fonte \"Navegador\" com o endereço exibido + /obs, " +
            "ou \"Fonte de mídia\" (sem arquivo local) com /stream.mjpg. O mesmo endereço abre um painel de controle remoto (gravar, pausar, trocar câmeras). " +
            "Token de acesso: ${c.prefs.webcamToken}.")

        sh.section("Aparelho")
        val st = DeviceStatus.read(this)
        sh.info("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Bateria ${st.batteryPct}% · ${String.format(Locale("pt", "BR"), "%.1f", st.temperatureC)} °C · " +
            "memória livre ${st.availMemMb} MB${if (st.lowMemory) " (baixa)" else ""}\n" +
            "Armazenamento livre ${DeviceStatus.formatBytes(st.freeBytes)} · FPS medido ${String.format(Locale.US, "%.1f", c.measuredFps)}\n\n" +
            c.catalog.joinToString("\n") { it.describe() })
        sh.action("Diagnóstico de câmeras", note = "Teste técnico original de frente + traseira simultâneas.") {
            sheet.dismiss()
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }
    }

    private fun logProgress(v: Double, min: Double, max: Double): Int =
        ((ln(v.coerceIn(min, max) / min) / ln(max / min)) * 1000).roundToInt()

    private fun logValue(p: Int, min: Double, max: Double): Double = min * exp(ln(max / min) * p / 1000.0)

    private fun awbLabel(mode: Int) = when (mode) {
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "Auto"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "Incandesc."
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "Fluoresc."
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Fluor. quente"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "Dia"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Nublado"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "Crepúsculo"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "Sombra"
        else -> "Modo $mode"
    }

    // ---------------------------------------------------------------- CaptureController.Listener

    override fun onStateChanged() = render()

    override fun onMessage(text: String, long: Boolean) = showBanner(text, long)

    override fun onMediaSaved(uri: Uri, isVideo: Boolean) {
        loadThumbnail()
        showBanner(if (isVideo) "Vídeo salvo em Filmes/HaliCam" else "Foto salva em Imagens/HaliCam")
    }

    override fun onFrameAspectChanged() {
        relayoutPreview()
        overlay.invalidate()
    }

    // ---------------------------------------------------------------- PipOverlayView.Callbacks

    override fun pip(): PipLayout = controller.pip
    override fun frameAspect(): Float = controller.frameAspect()
    override fun pipInteractive(): Boolean = controller.dualActive
    override fun onPipChanged(p: PipLayout, done: Boolean) = controller.setPip(p, done)
    override fun onPipDoubleTap() = controller.swapMain()
    override fun onTapFocus(fx: Float, fy: Float): Boolean = controller.focusAt(fx, fy)
    override fun onZoomScale(factor: Float) = controller.setZoom(controller.settings.zoom * factor)
    override fun onSwipe(direction: Int) {
        if (controller.recState != RecState.IDLE) return
        val modes = CaptureMode.values()
        val i = modes.indexOf(controller.mode) + direction
        if (i in modes.indices) selectMode(modes[i])
    }

    // ---------------------------------------------------------------- SurfaceHolder.Callback

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        controller.setDisplay(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        controller.clearDisplay()
    }

    companion object { private const val REQ = 42 }
}
