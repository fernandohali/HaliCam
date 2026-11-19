package com.halicam.diagnostic.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt

/** Parâmetros de controle aplicados ao pedido repetitivo de uma câmera. */
data class UnitParams(
    val template: Int = CameraDevice.TEMPLATE_RECORD,
    val fpsRange: Range<Int>? = null,
    val zoom: Float = 1f,
    val aeCompensation: Int = 0,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val manualFocus: Float? = null,
    val manualExposure: Pair<Int, Long>? = null,
    val stabilization: Boolean = false,
    val torch: Boolean = false,
    val photoFlash: com.halicam.diagnostic.core.FlashMode = com.halicam.diagnostic.core.FlashMode.OFF,
    val meteringPoint: Pair<Float, Float>? = null   // ponto normalizado no sensor (0..1)
)

/** Uma câmera física aberta via Camera2. Todos os métodos devem rodar na thread de [handler]. */
class CameraUnit(
    private val manager: CameraManager,
    val caps: CameraCaps,
    private val handler: Handler,
    private val onLost: (CameraUnit, String) -> Unit
) {
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var closed = false
    private var opening = false
    var params = UnitParams()
        private set

    val isOpen: Boolean get() = device != null && !closed

    @SuppressLint("MissingPermission")
    fun open(onOpened: () -> Unit, onFailed: (String) -> Unit) {
        opening = true
        try {
            manager.openCamera(caps.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    if (closed) { d.close(); return }
                    device = d
                    onOpened()
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    val wasOpening = opening
                    opening = false
                    device = null
                    if (closed) return
                    if (wasOpening) onFailed("câmera ${caps.id} desconectada ao abrir")
                    else onLost(this@CameraUnit, "O sistema fechou a câmera ${caps.id} (${caps.facingLabel}).")
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    val wasOpening = opening
                    opening = false
                    device = null
                    if (closed) return
                    val msg = "câmera ${caps.id}: ${errorLabel(error)}"
                    if (wasOpening) onFailed(msg) else onLost(this@CameraUnit, "Erro na $msg")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            opening = false
            onFailed("câmera ${caps.id}: acesso negado (${e.reason})")
        } catch (e: Exception) {
            opening = false
            onFailed("câmera ${caps.id}: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    /** Cria a sessão com a saída de prévia (SurfaceTexture do renderizador) e, opcionalmente, JPEG. */
    fun startSession(preview: Surface, jpegSize: Size?, initial: UnitParams, onReady: () -> Unit, onFailed: (String) -> Unit) {
        val d = device ?: return onFailed("câmera ${caps.id} não está aberta")
        previewSurface = preview
        params = initial
        jpegReader?.close()
        jpegReader = jpegSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 2) }
        val outputs = listOfNotNull(preview, jpegReader?.surface)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed || device !== d) { s.close(); return }
                session = s
                try {
                    applyRepeating()
                    onReady()
                } catch (e: Exception) {
                    onFailed("câmera ${caps.id}: falha ao iniciar fluxo (${e.message})")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                s.close()
                if (!closed) onFailed("HAL recusou a sessão da câmera ${caps.id}")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                d.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs.map { OutputConfiguration(it) }, executor, callback)
                )
            } else {
                @Suppress("DEPRECATION")
                d.createCaptureSession(outputs, callback, handler)
            }
        } catch (e: Exception) {
            onFailed("câmera ${caps.id}: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    fun update(newParams: UnitParams) {
        params = newParams
        try { applyRepeating() } catch (e: Exception) { Log.w(TAG, "update ${caps.id}: ${e.message}") }
    }

    private fun applyRepeating() {
        val s = session ?: return
        val d = device ?: return
        val surface = previewSurface ?: return
        val builder = d.createCaptureRequest(params.template)
        builder.addTarget(surface)
        fill(builder)
        s.setRepeatingRequest(builder.build(), null, handler)
    }

    /** Toque para focar: define regiões de AF/AE e dispara um ciclo de foco. */
    fun focusAt(sensorX: Float, sensorY: Float) {
        params = params.copy(meteringPoint = sensorX to sensorY, manualFocus = null)
        val s = session ?: return
        val d = device ?: return
        val surface = previewSurface ?: return
        try {
            applyRepeating()
            if (caps.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
                val b = d.createCaptureRequest(params.template)
                b.addTarget(surface)
                fill(b)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
            }
        } catch (e: Exception) {
            Log.w(TAG, "focusAt: ${e.message}")
        }
    }

    fun clearMetering() {
        if (params.meteringPoint != null) update(params.copy(meteringPoint = null))
    }

    private fun fill(b: CaptureRequest.Builder) {
        val p = params
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        p.fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        b.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(p.zoom))

        val manualExp = p.manualExposure
        if (manualExp != null && caps.supportsManualExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, manualExp.first)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExp.second)
            val frameNs = 1_000_000_000L / max(1, p.fpsRange?.upper ?: 30)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(frameNs, manualExp.second))
        } else {
            val aeMode = when {
                p.template != CameraDevice.TEMPLATE_PREVIEW || !caps.hasFlash -> CameraMetadata.CONTROL_AE_MODE_ON
                p.photoFlash == com.halicam.diagnostic.core.FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                p.photoFlash == com.halicam.diagnostic.core.FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                p.aeCompensation.coerceIn(caps.aeCompRange.lower, caps.aeCompRange.upper))
        }
        val torch = p.torch && caps.hasFlash && p.template != CameraDevice.TEMPLATE_PREVIEW
        b.set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)

        if (caps.awbModes.contains(p.awbMode)) b.set(CaptureRequest.CONTROL_AWB_MODE, p.awbMode)

        val mf = p.manualFocus
        if (mf != null && caps.supportsManualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, mf.coerceIn(0f, caps.minFocusDistance))
        } else {
            val preferred = when {
                p.meteringPoint != null -> CameraMetadata.CONTROL_AF_MODE_AUTO
                p.template == CameraDevice.TEMPLATE_RECORD -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            val af = listOf(preferred, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                .firstOrNull { caps.afModes.contains(it) } ?: CameraMetadata.CONTROL_AF_MODE_OFF
            b.set(CaptureRequest.CONTROL_AF_MODE, af)
        }
        p.meteringPoint?.let { (x, y) ->
            val region = meteringRect(x, y)
            if (caps.maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            if (caps.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }

        if (caps.hasVideoStabilization) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (p.stabilization && p.template == CameraDevice.TEMPLATE_RECORD) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
        if (caps.hasOis) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (p.stabilization) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
    }

    fun cropRegion(zoom: Float): Rect {
        val a = caps.activeArray
        val z = zoom.coerceIn(1f, max(1f, caps.maxZoom))
        val w = (a.width() / z).roundToInt()
        val h = (a.height() / z).roundToInt()
        val l = a.left + (a.width() - w) / 2
        val t = a.top + (a.height() - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    private fun meteringRect(x: Float, y: Float): MeteringRectangle {
        val crop = cropRegion(params.zoom)
        val cx = crop.left + (x * crop.width()).roundToInt()
        val cy = crop.top + (y * crop.height()).roundToInt()
        val half = (crop.width().coerceAtMost(crop.height()) * 0.08f).roundToInt()
        val l = (cx - half).coerceIn(crop.left, crop.right - 1)
        val t = (cy - half).coerceIn(crop.top, crop.bottom - 1)
        val r = (cx + half).coerceIn(l + 1, crop.right)
        val btm = (cy + half).coerceIn(t + 1, crop.bottom)
        return MeteringRectangle(Rect(l, t, r, btm), MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    /** Foto JPEG em resolução total (modo Foto). Com flash, executa a sequência de pré-captura de AE. */
    fun capturePhoto(jpegOrientation: Int, onResult: (ByteArray?, String?) -> Unit) {
        val s = session
        val d = device
        val reader = jpegReader
        val surface = previewSurface
        if (s == null || d == null || reader == null || surface == null) {
            onResult(null, "Câmera não está pronta para foto.")
            return
        }
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireNextImage() } catch (_: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            val bytes = try {
                val buf = image.planes[0].buffer
                ByteArray(buf.remaining()).also { buf.get(it) }
            } finally { image.close() }
            onResult(bytes, null)
        }, handler)

        fun still() {
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(reader.surface)
                b.addTarget(surface)
                fill(b)
                b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                s.capture(b.build(), null, handler)
            } catch (e: Exception) {
                onResult(null, "Falha na captura: ${e.message}")
            }
        }

        val usesFlash = caps.hasFlash && params.photoFlash != com.halicam.diagnostic.core.FlashMode.OFF
        if (usesFlash) {
            try {
                val pre = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                pre.addTarget(surface)
                fill(pre)
                pre.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                s.capture(pre.build(), null, handler)
                handler.postDelayed({ still() }, 700L)
            } catch (e: Exception) {
                still()
            }
        } else {
            still()
        }
    }

    fun close() {
        closed = true
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { jpegReader?.close() } catch (_: Exception) {}
        jpegReader = null
        previewSurface = null
    }

    companion object {
        private const val TAG = "HaliCam"
        fun errorLabel(error: Int) = when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "em uso por outro app"
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "limite de câmeras abertas atingido"
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "desativada pela política do aparelho"
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "erro fatal do dispositivo"
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "erro do serviço de câmera"
            else -> "erro desconhecido ($error)"
        }
    }
}
