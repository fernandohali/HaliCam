package com.halicam.diagnostic.core

import android.Manifest
import android.media.MicrophoneDirection
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import com.halicam.diagnostic.camera.CameraCaps
import com.halicam.diagnostic.camera.CameraCatalog
import com.halicam.diagnostic.camera.CameraRig
import com.halicam.diagnostic.camera.CameraUnit
import com.halicam.diagnostic.camera.RigRequest
import com.halicam.diagnostic.camera.UnitParams
import com.halicam.diagnostic.gl.Renderer
import com.halicam.diagnostic.gl.Scene
import com.halicam.diagnostic.gl.SourceMode
import com.halicam.diagnostic.gl.WebcamTarget
import com.halicam.diagnostic.record.EncoderSupport
import com.halicam.diagnostic.record.MediaOutput
import com.halicam.diagnostic.record.RecordingSession
import com.halicam.diagnostic.record.VideoOutputSpec
import com.halicam.diagnostic.record.videoBitrate
import com.halicam.diagnostic.stream.WebcamServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class RecState { IDLE, RECORDING, PAUSED, FINALIZING }

/**
 * Orquestra câmeras (Camera2), composição (OpenGL), gravação (MediaCodec/MediaMuxer) e webcam (HTTP).
 * Métodos públicos devem ser chamados na thread principal. A interface não acessa as camadas abaixo diretamente.
 */
class CaptureController(private val context: Context, private val listener: Listener) :
    CameraRig.Listener, WebcamServer.RemoteControl {

    interface Listener {
        fun onStateChanged()
        fun onMessage(text: String, long: Boolean = false)
        fun onMediaSaved(uri: Uri, isVideo: Boolean)
        fun onFrameAspectChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    val prefs = Prefs(context)
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val catalog: List<CameraCaps> = CameraCatalog.load(manager)
    private val renderer = Renderer()
    private val rig = CameraRig(manager, catalog, renderer, this)

    var settings: CaptureSettings = prefs.loadSettings(); private set
    var pip: PipLayout = prefs.loadPip(); private set
    var mode: CaptureMode = prefs.mode; private set
    var useFront: Boolean = prefs.useFront; private set
    var deviceRotation = 0; private set
    private var lockedRotation: Int? = null

    /** null = ainda não testado; false = o aparelho recusou as duas câmeras simultâneas. */
    var dualAvailable: Boolean? = if (prefs.dualPair != null) true else null; private set
    var dualFailure: String? = null; private set
    var camerasReady = false; private set
    var dualActive = false; private set
    var progress: String? = null; private set
    private var activeCaps: List<CameraCaps> = emptyList()
    private var mainContentAspect = 9f / 16f
    private var mainStreamSize = Size(1920, 1080)

    var recState = RecState.IDLE; private set
    private var session: RecordingSession? = null
    private var server: WebcamServer? = null
    private var resumed = false
    private var lostRetries = 0
    private var dualRetries = 0

    private val backIds = catalog.filter { it.isBack }.map { it.id }
    private val frontIds = catalog.filter { it.isFront }.map { it.id }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.addThermalStatusListener(context.mainExecutor) { status ->
                when {
                    status >= PowerManager.THERMAL_STATUS_CRITICAL && recState != RecState.IDLE -> {
                        stopRecording()
                        listener.onMessage("Temperatura crítica: gravação encerrada para proteger o aparelho.", true)
                    }
                    status >= PowerManager.THERMAL_STATUS_SEVERE ->
                        listener.onMessage("Aparelho muito quente. Considere reduzir resolução/FPS ou desligar o Dual.", true)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Ciclo de vida

    fun resume() {
        resumed = true
        lostRetries = 0
        pushScene()
        configure()
        if (mode == CaptureMode.WEBCAM) startServer()
    }

    fun pause() {
        resumed = false
        if (recState == RecState.RECORDING || recState == RecState.PAUSED) stopRecording()
        stopServer()
        rig.stop()
        camerasReady = false
        dualActive = false
    }

    fun release() {
        stopServer()
        rig.release()
        renderer.release()
    }

    fun setDisplay(surface: Surface, width: Int, height: Int) = renderer.setDisplay(surface, width, height)
    fun clearDisplay() = renderer.clearDisplay()

    // ---------------------------------------------------------------- Geometria

    val worldAspect: AspectRatio
        get() = when (mode) {
            CaptureMode.PHOTO -> AspectRatio.R3_4
            CaptureMode.WEBCAM -> AspectRatio.R16_9
            else -> settings.aspect
        }

    /** Rotação usada para o quadro: travada durante a gravação; no modo Foto a orientação vai no EXIF. */
    val effectiveRotation: Int
        get() = lockedRotation ?: if (mode == CaptureMode.PHOTO) 0 else deviceRotation

    fun frameAspect(): Float = screenAspect(worldAspect, effectiveRotation)

    fun setDeviceRotation(r: Int) {
        if (r == deviceRotation) return
        deviceRotation = r
        if (lockedRotation == null) {
            pip = pip.clamped(frameAspect())
            pushScene()
            updateWebcamTarget()
            listener.onFrameAspectChanged()
        }
    }

    private fun pushScene() {
        renderer.scene = Scene(
            dual = dualActive,
            pip = pip,
            frameAspect = frameAspect(),
            mirrorFront = settings.mirrorFront,
            showGrid = settings.showGrid,
            saturation = settings.colorProfile.saturation,
            contrast = settings.colorProfile.contrast
        )
    }

    // ---------------------------------------------------------------- Câmeras

    val mainCaps: CameraCaps?
        get() = when {
            dualActive && activeCaps.size == 2 -> if (pip.backIsMain) activeCaps[0] else activeCaps[1]
            activeCaps.isNotEmpty() -> activeCaps[0]
            else -> catalog.firstOrNull { it.id == singleCameraId() }
        }

    /** Câmera que tem flash entre as abertas (normalmente a traseira). */
    val flashAvailable: Boolean
        get() = activeCaps.any { it.hasFlash } && (mode != CaptureMode.PHOTO || mainCaps?.hasFlash == true)

    private fun singleCameraId(): String {
        val wantFront = when (mode) {
            CaptureMode.WEBCAM -> settings.webcamSource == WebcamSource.FRONT
            else -> useFront
        }
        // Para a frontal simples, usa a primeira listada (a "selfie" padrão).
        return (if (wantFront) frontIds.firstOrNull() else backIds.firstOrNull())
            ?: catalog.firstOrNull()?.id ?: "0"
    }

    private fun wantsDual(): Boolean =
        mode == CaptureMode.DUAL || (mode == CaptureMode.WEBCAM && settings.webcamSource == WebcamSource.DUAL)

    private fun dualPairs(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        prefs.dualPair?.split(",")?.takeIf { it.size == 2 }?.let { (b, f) ->
            if (b in backIds && f in frontIds) list += b to f
        }
        // Tenta primeiro as frontais secundárias (no S9+ a ID 2 funciona com a traseira; a ID 1 não).
        for (b in backIds) for (f in frontIds.reversed()) if ((b to f) !in list) list += b to f
        return list
    }

    private fun configure() {
        if (!resumed) return
        camerasReady = false
        dualActive = false
        activeCaps = emptyList()
        normalizeVideoMode()
        val longSide = settings.resolution * 16 / 9
        if (wantsDual() && backIds.isNotEmpty() && frontIds.isNotEmpty()) {
            progress = "Iniciando Dual Capture…"
            val back = catalog.first { it.id == backIds.first() }
            val levels = mutableListOf<Pair<Size, Size>>()
            val b1 = back.streamSize(16f / 9, minOf(longSide, 1920), 1920) ?: Size(1920, 1080)
            val front = catalog.first { it.id == frontIds.last() }
            // Frontal em 1080p primeiro; se o hardware não sustentar as duas, cai para 720p e depois 480p.
            val fHd = front.streamSize(16f / 9, 1920, 1920)
            val f720 = front.streamSize(16f / 9, 1280, 1280) ?: Size(1280, 720)
            if (fHd != null && fHd.width > f720.width) levels += b1 to fHd
            levels += b1 to f720
            levels += (back.streamSize(16f / 9, 1280, 1280) ?: Size(1280, 720)) to Size(640, 480)
            mainStreamSize = b1
            rig.start(RigRequest(dual = true, pairs = dualPairs(), sizeLevels = levels, params = { c, _ -> paramsFor(c, true) }))
        } else {
            progress = null
            val id = singleCameraId()
            val caps = catalog.first { it.id == id }
            val photo = mode == CaptureMode.PHOTO
            val size = if (photo) caps.streamSize(4f / 3, 1440, 1920) else
                caps.streamSize(16f / 9, longSide, if (settings.resolution > 1080) 4096 else 1920)
            val jpeg = if (photo) caps.largestJpeg(4f / 3) else null
            mainStreamSize = size ?: Size(1920, 1080)
            rig.start(RigRequest(dual = false, singleId = id, sizeLevels = listOf(mainStreamSize to mainStreamSize),
                jpegSize = jpeg, params = { c, _ -> paramsFor(c, false) }))
        }
        listener.onStateChanged()
    }

    private fun paramsFor(caps: CameraCaps, dualRequest: Boolean): UnitParams {
        val s = settings
        val template = if (mode == CaptureMode.PHOTO) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD
        val fpsRange = caps.fpsRange(s.fps)
        val isMain = if (dualRequest) caps.isBack == pip.backIsMain else true
        val torch = s.flash == FlashMode.ON && caps.hasFlash && mode != CaptureMode.PHOTO
        val antibanding = s.antibanding.mode
        if (!isMain) return UnitParams(template = template, fpsRange = fpsRange, torch = torch, antibanding = antibanding)
        return UnitParams(
            template = template,
            fpsRange = fpsRange,
            zoom = s.zoom.coerceIn(1f, maxOf(1f, caps.maxZoom)),
            aeCompensation = s.aeCompensation,
            awbMode = s.awbMode,
            manualFocus = if (s.manualFocus && caps.supportsManualFocus) s.focusDistance else null,
            manualExposure = if (s.manualExposure && caps.supportsManualExposure)
                s.iso.coerceIn(caps.isoRange!!.lower, caps.isoRange.upper) to
                    s.exposureNs.coerceIn(caps.exposureRange!!.lower, caps.exposureRange.upper) else null,
            stabilization = s.stabilization,
            torch = torch,
            photoFlash = s.flash,
            antibanding = antibanding
        )
    }

    private fun applyParams() {
        val dual = dualActive
        rig.withUnits { u0, u1 ->
            listOfNotNull(u0, u1).forEach { u ->
                u.update(paramsFor(u.caps, dual).copy(meteringPoint = u.params.meteringPoint))
            }
        }
    }

    override fun onRigReady(units: List<CameraUnit>, dual: Boolean, pair: Pair<String, String>?) {
        val caps = units.map { it.caps }
        main.post {
            camerasReady = true
            lostRetries = 0
            dualActive = dual
            activeCaps = caps
            progress = null
            if (dual && pair != null) {
                dualRetries = 0
                dualAvailable = true
                dualFailure = null
                prefs.dualPair = "${pair.first},${pair.second}"
            }
            val m = mainCaps
            val rotated = (m?.sensorOrientation ?: 90) % 180 != 0
            val sz = if (dual && !pip.backIsMain) units.getOrNull(1)?.bufferSize ?: Size(1280, 720) else mainStreamSize
            if (dual) frontStreamSize = units.getOrNull(1)?.bufferSize
            mainContentAspect = if (rotated) sz.height.toFloat() / sz.width else sz.width.toFloat() / sz.height
            pushScene()
            applyParams()
            updateWebcamTarget()
            listener.onStateChanged()
        }
    }

    override fun onRigFailed(message: String, dualUnavailable: Boolean) {
        main.post {
            progress = null
            if (dualUnavailable && dualRetries == 0 && resumed) {
                // A câmera pode ainda estar sendo liberada por outro processo: tenta mais uma vez.
                dualRetries++
                progress = "Tentando o Dual Capture novamente…"
                listener.onStateChanged()
                main.postDelayed({ configure() }, 1500)
                return@post
            }
            if (dualUnavailable) {
                dualAvailable = false
                dualFailure = message
                prefs.dualPair = null
                listener.onMessage("$message\n\nOs modos Vídeo, Foto e Webcam continuam disponíveis.", true)
                if (mode == CaptureMode.WEBCAM) {
                    settings = settings.copy(webcamSource = WebcamSource.BACK).also(prefs::saveSettings)
                    configure()
                } else {
                    setMode(CaptureMode.VIDEO)
                }
            } else {
                listener.onMessage(message, true)
                listener.onStateChanged()
            }
        }
    }

    override fun onRigLost(message: String) {
        main.post {
            camerasReady = false
            if (recState == RecState.RECORDING || recState == RecState.PAUSED) stopRecording()
            if (lostRetries++ < 2 && resumed) {
                listener.onMessage("$message Reiniciando a câmera…")
                main.postDelayed({ configure() }, 800)
            } else {
                listener.onMessage("$message Feche outros apps de câmera e reabra o HaliCam.", true)
            }
            listener.onStateChanged()
        }
    }

    override fun onRigProgress(message: String) {
        main.post { progress = message; listener.onStateChanged() }
    }

    // ---------------------------------------------------------------- Ações da interface

    fun setMode(m: CaptureMode) {
        if (recState != RecState.IDLE || m == mode) return
        val wasWebcam = mode == CaptureMode.WEBCAM
        mode = m
        prefs.mode = m
        dualRetries = 0
        settings = settings.copy(zoom = 1f)
        if (m == CaptureMode.VIDEO) loadVideoMode()
        pip = pip.clamped(frameAspect())
        pushScene()
        listener.onFrameAspectChanged()
        configure()
        if (m == CaptureMode.WEBCAM) startServer() else if (wasWebcam) stopServer()
        listener.onStateChanged()
    }

    fun updateSettings(new: CaptureSettings) {
        val old = settings
        settings = new
        prefs.saveSettings(new)
        if (mode == CaptureMode.VIDEO && (old.resolution != new.resolution || old.fps != new.fps))
            prefs.saveVideoMode(useFront, new.resolution, new.fps)
        val reconfigure = old.resolution != new.resolution ||
            (mode == CaptureMode.WEBCAM && old.webcamSource != new.webcamSource)
        if (old.aspect != new.aspect) { pip = pip.clamped(frameAspect()); listener.onFrameAspectChanged() }
        pushScene()
        if (old.webcamHeight != new.webcamHeight) updateWebcamTarget()
        if (old.micFocus != new.micFocus || old.audioZoom != new.audioZoom) updateMicFocus()
        if (reconfigure && recState == RecState.IDLE) configure() else applyParams()
        listener.onStateChanged()
    }

    fun setZoom(z: Float) {
        val max = mainCaps?.maxZoom ?: 1f
        val v = z.coerceIn(1f, maxOf(1f, max))
        if (v == settings.zoom) return
        settings = settings.copy(zoom = v)
        applyParams()
        updateMicFocus()
        listener.onStateChanged()
    }

    /** Aplica direção/zoom do microfone conforme a câmera principal e o zoom atual. */
    private fun updateMicFocus(): Boolean {
        val s = session ?: return true
        val front = mainCaps?.isFront == true
        val direction = when (settings.micFocus) {
            MicFocus.AUTO -> MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED
            MicFocus.CAMERA -> if (front) MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER else MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER
            MicFocus.AMBIENT -> MicrophoneDirection.MIC_DIRECTION_EXTERNAL
        }
        val field = when {
            settings.micFocus == MicFocus.AMBIENT -> -1f
            settings.audioZoom -> {
                val max = mainCaps?.maxZoom ?: 1f
                if (max > 1f) ((settings.zoom - 1f) / (max - 1f)).coerceIn(0f, 1f) else 0f
            }
            else -> 0f
        }
        return s.setMicFocus(direction, field)
    }

    fun setPip(new: PipLayout, persist: Boolean) {
        val swapped = new.backIsMain != pip.backIsMain
        pip = new.clamped(frameAspect())
        pushScene()
        if (persist) prefs.savePip(pip)
        if (swapped) {
            settings = settings.copy(zoom = 1f)
            applyParams()
            updateMicFocus()
            listener.onStateChanged()
        }
    }

    fun swapMain() = setPip(pip.copy(backIsMain = !pip.backIsMain), true)

    /** Botão de alternância: no Dual troca a principal; nos outros modos alterna traseira/frontal. */
    fun flipCamera() {
        if (dualActive) { swapMain(); return }
        if (recState != RecState.IDLE) return
        useFront = !useFront
        prefs.useFront = useFront
        settings = settings.copy(zoom = 1f)
        if (mode == CaptureMode.VIDEO) loadVideoMode()
        configure()
    }

    /** Toque para focar, em coordenadas normalizadas do quadro (espaço de tela). */
    fun focusAt(fx: Float, fy: Float): Boolean {
        val caps = mainCaps ?: return false
        if (caps.maxAfRegions == 0 && caps.maxAeRegions == 0) return false
        val fa = frameAspect()
        val ca = mainContentAspect
        var u = fx
        var v = fy
        if (ca > fa) { val k = fa / ca; u = 0.5f - k / 2 + fx * k } else { val k = ca / fa; v = 0.5f - k / 2 + fy * k }
        if (caps.isFront && settings.mirrorFront) u = 1f - u
        // Conteúdo em pé → coordenadas do sensor (paisagem)
        var sx: Float
        var sy: Float
        when (caps.sensorOrientation) {
            270 -> { sx = 1f - v; sy = u }
            180 -> { sx = 1f - u; sy = 1f - v }
            0 -> { sx = u; sy = v }
            else -> { sx = v; sy = 1f - u }
        }
        // O stream (16:9) é um recorte central da área ativa (4:3).
        val a = caps.activeArray
        val ar = a.width().toFloat() / a.height()
        val sa = mainStreamSize.width.toFloat() / mainStreamSize.height
        if (sa > ar) sy = 0.5f + (sy - 0.5f) * (ar / sa) else sx = 0.5f + (sx - 0.5f) * (sa / ar)
        val mainIsSlot0 = !dualActive || pip.backIsMain
        rig.withUnits { u0, u1 -> (if (mainIsSlot0) u0 else u1)?.focusAt(sx.coerceIn(0f, 1f), sy.coerceIn(0f, 1f)) }
        return true
    }

    fun takePhoto() {
        if (mode != CaptureMode.PHOTO || !camerasReady) return
        val caps = activeCaps.firstOrNull() ?: return
        val o = deviceRotation
        val orientation = if (caps.isFront) (caps.sensorOrientation - o + 360) % 360 else (caps.sensorOrientation + o) % 360
        rig.withUnits { u0, _ ->
            u0?.capturePhoto(orientation) { bytes, err ->
                val uri = bytes?.let { MediaOutput.saveJpeg(context, it) }
                main.post {
                    if (uri != null) {
                        prefs.lastMediaUri = uri.toString(); prefs.lastMediaIsVideo = false
                        listener.onMediaSaved(uri, false)
                    } else listener.onMessage(err ?: "Não foi possível salvar a foto.")
                }
            }
        }
    }

    // ---------------------------------------------------------------- Gravação

    val canRecord: Boolean get() = mode != CaptureMode.PHOTO && camerasReady && recState == RecState.IDLE

    fun elapsedMs(): Long = session?.elapsedMs() ?: 0L

    /** Bitrate total aproximado (para estimar o tempo restante de armazenamento). */
    fun estimatedBitrate(): Long {
        val (w, h) = outputSize(worldAspect, settings.resolution)
        var total = videoBitrate(w, h, settings.fps, settings.bitrate.bitsPerPixel, effectiveCodec().mime).toLong()
        if (dualActive && settings.saveSeparate) {
            val (sw, sh) = outputSize(worldAspect, settings.separateHeight)
            total += 2 * videoBitrate(sw, sh, settings.fps, settings.bitrate.bitsPerPixel, effectiveCodec().mime)
        }
        return total + settings.audio.bitrate
    }

    /** Codec escolhido, ou H.264 se o aparelho não tiver encoder HEVC. */
    fun effectiveCodec(): VideoCodec =
        if (settings.codec == VideoCodec.HEVC && !EncoderSupport.hasEncoder(VideoCodec.HEVC.mime)) VideoCodec.H264 else settings.codec

    /** Tamanho real do stream da frontal no Dual (1080p, 720p ou 480p conforme o hardware aceitou). */
    var frontStreamSize: Size? = null
        private set

    fun startRecording() {
        if (!canRecord) return
        val rotation = deviceRotation
        lockedRotation = rotation
        val world = worldAspect
        var res = settings.resolution
        var (w, h) = outputSize(world, res)
        var codec = effectiveCodec()
        if (codec != settings.codec) listener.onMessage("Este aparelho não tem encoder H.265; gravando em H.264.")
        if (codec == VideoCodec.HEVC && !EncoderSupport.supportsAnyOrientation(w, h, settings.fps, codec.mime)) {
            codec = VideoCodec.H264
            listener.onMessage("O encoder H.265 não aceita ${res}p a ${settings.fps} fps; gravando em H.264.")
        }
        if (!EncoderSupport.supportsAnyOrientation(w, h, settings.fps, codec.mime)) {
            res = listOf(1080, 720).first { it < settings.resolution || it == 720 }
            val s = outputSize(world, res); w = s.first; h = s.second
            listener.onMessage("O encoder não aceita ${settings.resolution}p a ${settings.fps} fps nesta proporção; gravando em ${res}p.")
        }
        val outputs = mutableListOf(VideoOutputSpec("", SourceMode.COMPOSITE, w, h))
        if (dualActive && settings.saveSeparate) {
            val (bw, bh) = outputSize(world, minOf(settings.separateHeight, res))
            outputs += VideoOutputSpec("_traseira", SourceMode.SLOT0, bw, bh)
            // A frontal não ganha nada acima da resolução do próprio stream.
            val frontShort = frontStreamSize?.let { minOf(it.width, it.height) } ?: 720
            val (fw, fh) = outputSize(world, minOf(settings.separateHeight, res, frontShort))
            outputs += VideoOutputSpec("_frontal", SourceMode.SLOT1, fw, fh)
        }
        val audio = if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            settings.audio else null
        if (audio == null) listener.onMessage("Sem permissão de microfone: gravando sem áudio.")
        val s = RecordingSession(context, renderer, outputs, rotation, settings.fps, codec.mime,
            settings.bitrate.bitsPerPixel, audio, settings.audioProcessing, settings.micDeviceId)
        try {
            s.start()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar gravação", e)
            lockedRotation = null
            listener.onMessage("Não foi possível iniciar a gravação: ${e.message}", true)
            return
        }
        s.audioError?.let { listener.onMessage(it) }
        session = s
        if (audio != null && settings.micFocus != MicFocus.AUTO && !updateMicFocus())
            listener.onMessage("Este aparelho não aplicou o foco do microfone; usando a captação padrão.")
        recState = RecState.RECORDING
        pushScene()
        listener.onStateChanged()
    }

    fun pauseRecording() {
        if (recState != RecState.RECORDING) return
        session?.pause(); recState = RecState.PAUSED; listener.onStateChanged()
    }

    fun resumeRecording() {
        if (recState != RecState.PAUSED) return
        session?.resume(); recState = RecState.RECORDING; listener.onStateChanged()
    }

    fun stopRecording() {
        val s = session ?: return
        if (recState == RecState.FINALIZING) return
        recState = RecState.FINALIZING
        listener.onStateChanged()
        Thread({
            val uri = try { s.stop() } catch (e: Exception) { Log.e(TAG, "stop", e); null }
            main.post {
                session = null
                recState = RecState.IDLE
                lockedRotation = null
                pip = pip.clamped(frameAspect())
                pushScene()
                updateWebcamTarget()
                listener.onFrameAspectChanged()
                if (uri != null) {
                    prefs.lastMediaUri = uri.toString(); prefs.lastMediaIsVideo = true
                    listener.onMediaSaved(uri, true)
                } else listener.onMessage("A gravação não gerou um arquivo válido.", true)
                listener.onStateChanged()
            }
        }, "HaliCam-StopRec").start()
    }

    // ---------------------------------------------------------------- Webcam

    private fun startServer() {
        if (server == null) server = WebcamServer(prefs.webcamToken, this).also { it.start() }
        updateWebcamTarget()
    }

    private fun stopServer() {
        renderer.setWebcam(null)
        server?.release()
        server = null
    }

    private fun updateWebcamTarget() {
        val s = server ?: return
        val h = settings.webcamHeight
        val w = (h * 16 / 9 + 15) / 16 * 16
        renderer.setWebcam(WebcamTarget(s, w, h, effectiveRotation, 30))
    }

    val webcamUrl: String?
        get() = server?.let { s -> WebcamServer.localIp()?.let { "http://$it:${s.port}/?k=${prefs.webcamToken}" } }

    val webcamClients: Int get() = server?.clientCount ?: 0

    val measuredFps: Float get() = renderer.measuredFps

    override fun statusJson(): String {
        val st = DeviceStatus.read(context)
        val source = when {
            mode != CaptureMode.WEBCAM -> mode.name.lowercase()
            dualActive -> "dual"
            settings.webcamSource == WebcamSource.FRONT -> "front"
            else -> "back"
        }
        return "{\"mode\":\"${mode.name.lowercase()}\",\"recording\":${recState == RecState.RECORDING || recState == RecState.PAUSED}," +
            "\"paused\":${recState == RecState.PAUSED},\"elapsedMs\":${elapsedMs()},\"source\":\"$source\"," +
            "\"dualAvailable\":${dualAvailable ?: "null"},\"resolution\":\"${settings.webcamHeight}p\"," +
            "\"fps\":${"%.0f".format(java.util.Locale.US, measuredFps)},\"battery\":${st.batteryPct}," +
            "\"temperature\":${"%.1f".format(java.util.Locale.US, st.temperatureC)}}"
    }

    override fun command(name: String, arg: String?): String {
        var result = "ok"
        val latch = CountDownLatch(1)
        main.post {
            try {
                when (name) {
                    "record" -> if (canRecord) startRecording() else result = "não é possível gravar agora"
                    "stop" -> stopRecording()
                    "pause" -> pauseRecording()
                    "resume" -> resumeRecording()
                    "swap" -> if (dualActive) swapMain() else result = "dual inativo"
                    "source" -> {
                        val src = when (arg) { "front" -> WebcamSource.FRONT; "dual" -> WebcamSource.DUAL; else -> WebcamSource.BACK }
                        if (recState != RecState.IDLE) result = "pare a gravação antes de trocar a fonte"
                        else if (mode == CaptureMode.WEBCAM) updateSettings(settings.copy(webcamSource = src))
                    }
                    else -> result = "comando desconhecido"
                }
            } finally { latch.countDown() }
        }
        latch.await(2, TimeUnit.SECONDS)
        return result
    }

    // ---------------------------------------------------------------- Opções reais do hardware

    /**
     * Resoluções (lado menor) oferecidas.
     * VÍDEO profissional: traseira UHD/QHD/FHD, frontal QHD/FHD. Dual e Webcam: até 1080p.
     */
    fun resolutionOptions(): List<Int> {
        val caps = mainCaps ?: return listOf(1080)
        val candidates = when {
            mode == CaptureMode.VIDEO && caps.isFront -> listOf(1440, 1080)
            mode == CaptureMode.VIDEO -> listOf(2160, 1440, 1080)
            else -> listOf(720, 1080)
        }
        return candidates.filter { r ->
            val (w, h) = outputSize(worldAspect, r)
            EncoderSupport.supportsAnyOrientation(w, h, 30) &&
                caps.textureSizes.any { it.width >= r * 16 / 9 && it.height >= r }
        }
    }

    /** FPS para a resolução [res]. No VÍDEO tudo é 30 fps; 60 só no UHD traseiro, se a câmera anunciar. */
    fun fpsOptions(res: Int = settings.resolution): List<Int> {
        if (mode == CaptureMode.VIDEO) {
            val caps = mainCaps ?: return listOf(30)
            val list = mutableListOf(30)
            val (w, h) = outputSize(worldAspect, res)
            if (!caps.isFront && res == 2160 && caps.supportsFps(60) && EncoderSupport.supportsAnyOrientation(w, h, 60)) list += 60
            return list
        }
        val list = activeCaps.ifEmpty { listOfNotNull(mainCaps) }
        return listOf(24, 30, 60).filter { f -> list.isNotEmpty() && list.all { it.supportsFps(f) } }
    }

    /** Modos de vídeo (resolução, fps) oferecidos no VÍDEO, do maior para o menor. */
    fun videoModes(): List<Pair<Int, Int>> =
        resolutionOptions().flatMap { r -> fpsOptions(r).sortedDescending().map { r to it } }

    /** O UHD 60 foi pedido mas a câmera não anuncia 60 fps ao Camera2. */
    val uhd60Unavailable: Boolean
        get() = mode == CaptureMode.VIDEO && mainCaps?.isFront == false && 2160 in resolutionOptions() && 60 !in fpsOptions(2160)

    private fun loadVideoMode() {
        prefs.videoMode(useFront)?.let { (r, f) -> settings = settings.copy(resolution = r, fps = f) }
    }

    /** Ajusta resolução/fps para valores que a câmera atual oferece no modo atual. */
    private fun normalizeVideoMode() {
        if (mode == CaptureMode.PHOTO) return
        val res = resolutionOptions()
        if (res.isEmpty()) return
        val s = settings
        val r = if (s.resolution in res) s.resolution else res.firstOrNull { it <= s.resolution } ?: res.last()
        val fps = fpsOptions(r)
        val f = if (s.fps in fps) s.fps else if (30 in fps) 30 else fps.firstOrNull() ?: s.fps
        if (r != s.resolution || f != s.fps) {
            settings = s.copy(resolution = r, fps = f)
            prefs.saveSettings(settings)
        }
    }

    companion object { private const val TAG = "HaliCam" }
}
