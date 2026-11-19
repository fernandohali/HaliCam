package com.halicam.diagnostic

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Verifica abertura simultânea, sessões configuradas e frames em duas janelas sucessivas.
 * Não interpreta a mera existência de duas lentes como suporte a gravação dual.
 *
 * Testa uma lista de tentativas (par traseira/frontal + ordem de abertura) em sequência,
 * parando na primeira que funcionar.
 */
class DualCameraProbe(
    private val manager: CameraManager,
    private val log: (String) -> Unit,
    private val onFinish: (Boolean, String) -> Unit
) {
    /** Uma combinação a testar. [backFirst] define qual câmera é aberta primeiro. */
    data class Attempt(val backId: String, val frontId: String, val backFirst: Boolean) {
        val firstId get() = if (backFirst) backId else frontId
        val secondId get() = if (backFirst) frontId else backId
        override fun toString() =
            "traseira=$backId + frontal=$frontId (abre ${if (backFirst) "traseira" else "frontal"} primeiro)"
    }

    private val thread = HandlerThread("HaliCam-Camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val opened = linkedMapOf<String, CameraDevice>()
    private val sessions = linkedMapOf<String, CameraCaptureSession>()
    private val surfaces = mutableListOf<Surface>()
    private val captureCounts = mutableMapOf<String, AtomicInteger>()
    private var generation = 0
    private var active = false
    private var attempts: List<Attempt> = emptyList()
    private var attemptIndex = 0
    private val failures = mutableListOf<String>()
    private var current = Attempt("", "", true)
    private var backTexture: SurfaceTexture? = null
    private var frontTexture: SurfaceTexture? = null
    private var backSurface: Surface? = null
    private var frontSurface: Surface? = null
    private var previewCounters: () -> Pair<Int, Int> = { 0 to 0 }

    fun start(
        attempts: List<Attempt>,
        backTexture: SurfaceTexture,
        frontTexture: SurfaceTexture,
        previewCounters: () -> Pair<Int, Int>
    ) {
        handler.post {
            cleanup()
            this.attempts = attempts
            this.attemptIndex = 0
            this.failures.clear()
            this.backTexture = backTexture
            this.frontTexture = frontTexture
            this.previewCounters = previewCounters
            if (attempts.isEmpty()) {
                onFinish(false, "Nenhuma combinação de câmeras para testar.")
                return@post
            }
            runAttempt()
        }
    }

    private fun runAttempt() {
        generation++
        val token = generation
        active = true
        current = attempts[attemptIndex]
        captureCounts[current.backId] = AtomicInteger(0)
        captureCounts[current.frontId] = AtomicInteger(0)
        log("Tentativa ${attemptIndex + 1}/${attempts.size}: $current")
        try {
            val bt = backTexture ?: throw IllegalStateException("Textura traseira ausente")
            val ft = frontTexture ?: throw IllegalStateException("Textura frontal ausente")
            val backSize = choosePreviewSize(current.backId)
            val frontSize = choosePreviewSize(current.frontId)
            bt.setDefaultBufferSize(backSize.width, backSize.height)
            ft.setDefaultBufferSize(frontSize.width, frontSize.height)
            backSurface = Surface(bt).also(surfaces::add)
            frontSurface = Surface(ft).also(surfaces::add)
            log("Prévia traseira: $backSize; frontal: $frontSize")
            log("Abrindo câmera ${current.firstId}...")
            openCamera(current.firstId, token)
            // Limite de espera para abertura e configuração das duas sessões.
            handler.postDelayed({
                if (isCurrent(token) && sessions.size < 2) fail(token, "Timeout: as duas sessões não iniciaram em 10 s.")
            }, 10_000L)
        } catch (e: Exception) {
            fail(token, "Preparação: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun choosePreviewSize(id: String): Size {
        val chars = manager.getCameraCharacteristics(id)
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?: throw IllegalStateException("Sem saída SurfaceTexture para câmera $id")
        // Prioriza 640x480 para evitar exigir alta largura de banda no diagnóstico.
        return sizes.firstOrNull { it.width == 640 && it.height == 480 }
            ?: sizes.filter { it.width <= 1280 && it.height <= 720 }
                .minByOrNull { abs(it.width * it.height - 640 * 480) }
            ?: sizes.minBy { it.width.toLong() * it.height.toLong() }
    }

    private fun openCamera(id: String, token: Int) {
        try {
            // A permissão já foi verificada na Activity.
            @Suppress("MissingPermission")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!isCurrent(token)) { device.close(); return }
                    opened[id] = device
                    log("Aberta: câmera $id (${opened.size}/2)")
                    if (id == current.firstId) {
                        // Crucial: ambas as câmeras são abertas ANTES de configurar as sessões.
                        log("Abrindo câmera ${current.secondId} mantendo a $id aberta...")
                        openCamera(current.secondId, token)
                    } else if (opened.containsKey(current.firstId)) {
                        log("As duas câmeras foram abertas; configurando streams...")
                        configureBoth(token)
                    }
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (!isCurrent(token)) return
                    val evicted = id == current.firstId && sessions.isEmpty()
                    fail(token, if (evicted)
                        "O sistema fechou a câmera $id ao abrir a ${current.secondId}: o HAL não permite as duas abertas ao mesmo tempo."
                    else
                        "Câmera $id desconectada durante o teste (outro app ou limitação de concorrência).")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (isCurrent(token)) fail(token, "Falha abrindo câmera $id: ${errorLabel(error)} (código $error)")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            fail(token, "CameraAccessException em $id: reason=${e.reason}; ${e.message}")
        } catch (e: Exception) {
            fail(token, "Falha na abertura $id: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun configureBoth(token: Int) {
        val back = opened[current.backId]
        val front = opened[current.frontId]
        val rearOutput = backSurface
        val selfieOutput = frontSurface
        if (back == null || front == null || rearOutput == null || selfieOutput == null) {
            fail(token, "Referências de dispositivos/surfaces incompletas.")
            return
        }
        configureOne(token, current.backId, back, rearOutput)
        if (isCurrent(token)) configureOne(token, current.frontId, front, selfieOutput)
    }

    private fun configureOne(token: Int, id: String, device: CameraDevice, surface: Surface) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!isCurrent(token)) { session.close(); return }
                sessions[id] = session
                log("Sessão configurada: câmera $id")
                try {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }.build()
                    session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            if (isCurrent(token)) captureCounts[id]?.incrementAndGet()
                        }
                    }, handler)
                    if (sessions.size == 2) scheduleCheck(token)
                } catch (e: Exception) {
                    fail(token, "Falha iniciando fluxo da câmera $id: ${e.message}")
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                if (isCurrent(token)) fail(token, "HAL recusou a sessão da câmera $id.")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(surface)),
                        executor,
                        callback
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(surface), callback, handler)
            }
        } catch (e: Exception) {
            fail(token, "Configuração da sessão $id: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private data class Counts(val rearCapture: Int, val frontCapture: Int, val rearPreview: Int, val frontPreview: Int) {
        operator fun minus(old: Counts) = Counts(
            rearCapture - old.rearCapture, frontCapture - old.frontCapture,
            rearPreview - old.rearPreview, frontPreview - old.frontPreview
        )
        fun active() = rearCapture >= 3 && frontCapture >= 3 && rearPreview >= 3 && frontPreview >= 3
        override fun toString() = "capturas TR=$rearCapture, FR=$frontCapture; prévias TR=$rearPreview, FR=$frontPreview"
    }

    private fun snapshot(): Counts {
        val (pRear, pFront) = previewCounters()
        return Counts(
            captureCounts[current.backId]?.get() ?: 0,
            captureCounts[current.frontId]?.get() ?: 0,
            pRear, pFront
        )
    }

    private fun scheduleCheck(token: Int) {
        log("Ambas as sessões iniciadas. Medindo capturas e prévias em duas janelas de 3 s...")
        val baseline = snapshot()
        handler.postDelayed({
            if (!isCurrent(token)) return@postDelayed
            val halfway = snapshot()
            val window1 = halfway - baseline
            log("Janela 1 (3 s): $window1")
            handler.postDelayed({
                if (!isCurrent(token)) return@postDelayed
                val window2 = snapshot() - halfway
                log("Janela 2 (3 s): $window2")
                if (window1.active() && window2.active()) {
                    succeed(token, "Ambas as câmeras geraram capturas e prévias nas 2 janelas ($current).")
                } else {
                    fail(token, "Uma das câmeras deixou de entregar capturas ou prévia.")
                }
            }, 3_000L)
        }, 3_000L)
    }

    private fun errorLabel(error: Int) = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE (limite de câmeras abertas)"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
        else -> "ERRO_DESCONHECIDO"
    }

    private fun isCurrent(token: Int) = active && token == generation

    private fun succeed(token: Int, explanation: String) {
        if (!isCurrent(token)) return
        log("Tentativa ${attemptIndex + 1} OK: $explanation")
        endAttempt()
        onFinish(true, explanation)
    }

    /** Registra a falha da tentativa atual e passa para a próxima, se houver. */
    private fun fail(token: Int, explanation: String) {
        if (!isCurrent(token)) return
        log("Tentativa ${attemptIndex + 1} falhou: $explanation")
        failures += "• $current → $explanation"
        endAttempt()
        attemptIndex++
        if (attemptIndex < attempts.size) {
            // Dá tempo para o HAL liberar os recursos antes da próxima combinação.
            handler.postDelayed({ if (!active && attemptIndex < attempts.size) runAttempt() }, 800L)
        } else {
            onFinish(false, "Nenhuma das ${attempts.size} combinações manteve as duas câmeras ativas:\n" +
                failures.joinToString("\n"))
        }
    }

    private fun endAttempt() {
        generation++
        cleanup()
    }

    /** Fechamento na thread Camera2; mantém a thread viva para permitir novos testes. */
    private fun cleanup() {
        active = false
        sessions.values.forEach { try { it.close() } catch (_: Exception) {} }
        sessions.clear()
        opened.values.forEach { try { it.close() } catch (_: Exception) {} }
        opened.clear()
        surfaces.forEach { try { it.release() } catch (_: Exception) {} }
        surfaces.clear()
        backSurface = null
        frontSurface = null
        captureCounts.clear()
    }

    /** Cancela o teste em andamento (inclusive tentativas pendentes). */
    fun stop() { handler.post { attempts = emptyList(); attemptIndex = 0; generation++; cleanup() } }
    fun shutdown() { handler.post { attempts = emptyList(); generation++; cleanup(); thread.quitSafely() } }
}
