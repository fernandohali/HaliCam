package com.halicam.diagnostic.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.*
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Rational
import android.util.Size
import kotlin.math.abs

/** Capacidades reais de uma câmera, lidas de CameraCharacteristics. A UI só oferece o que estiver aqui. */
class CameraCaps(val id: String, chars: CameraCharacteristics) {
    val facing: Int = chars.get(LENS_FACING) ?: -1
    val isFront: Boolean get() = facing == CameraMetadata.LENS_FACING_FRONT
    val isBack: Boolean get() = facing == CameraMetadata.LENS_FACING_BACK
    val sensorOrientation: Int = chars.get(SENSOR_ORIENTATION) ?: 90
    val hardwareLevel: Int = chars.get(INFO_SUPPORTED_HARDWARE_LEVEL) ?: 0

    private val map = chars.get(SCALER_STREAM_CONFIGURATION_MAP)
    val textureSizes: List<Size> = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
    val jpegSizes: List<Size> = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
    val fpsRanges: List<Range<Int>> = chars.get(CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()

    private val capabilities: IntArray = chars.get(REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
    private val manualSensor = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    val afModes: IntArray = chars.get(CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
    val aeModes: IntArray = chars.get(CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0)
    val minFocusDistance: Float = chars.get(LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val supportsManualFocus: Boolean = manualSensor && minFocusDistance > 0f &&
        afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)
    val isoRange: Range<Int>? = chars.get(SENSOR_INFO_SENSITIVITY_RANGE)
    val exposureRange: Range<Long>? = chars.get(SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val supportsManualExposure: Boolean = manualSensor && isoRange != null && exposureRange != null &&
        aeModes.contains(CameraMetadata.CONTROL_AE_MODE_OFF)
    val aeCompRange: Range<Int> = chars.get(CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val aeCompStep: Rational = chars.get(CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 1)
    val awbModes: IntArray = chars.get(CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    val maxZoom: Float = chars.get(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    val activeArray: Rect = chars.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 1, 1)
    val hasFlash: Boolean = chars.get(FLASH_INFO_AVAILABLE) == true
    private val videoStabModes: IntArray = chars.get(CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
    private val oisModes: IntArray = chars.get(LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
    val hasVideoStabilization: Boolean = videoStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
    val hasOis: Boolean = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
    val hasStabilization: Boolean get() = hasVideoStabilization || hasOis
    val maxAfRegions: Int = chars.get(CONTROL_MAX_REGIONS_AF) ?: 0
    val maxAeRegions: Int = chars.get(CONTROL_MAX_REGIONS_AE) ?: 0

    val facingLabel: String get() = if (isFront) "Frontal" else if (isBack) "Traseira" else "Externa"

    /** FPS só é oferecido se existir uma faixa de AE cujo teto seja exatamente esse valor. */
    fun supportsFps(fps: Int) = fpsRanges.any { it.upper == fps }

    /** Prefere faixa fixa [fps, fps] (vídeo estável); senão a de maior piso com o mesmo teto. */
    fun fpsRange(fps: Int): Range<Int>? =
        fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower } ?: fpsRanges.maxByOrNull { it.upper }

    /**
     * Tamanho de stream (orientação do sensor, paisagem) com a proporção [aspect] (>= 1) e lado maior
     * de pelo menos [minLong]. Se não houver, usa o maior disponível nessa proporção.
     */
    fun streamSize(aspect: Float, minLong: Int, maxLong: Int = 4096): Size? {
        val candidates = textureSizes.filter {
            abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= maxLong
        }.sortedBy { it.width }
        return candidates.firstOrNull { it.width >= minLong } ?: candidates.lastOrNull()
            ?: textureSizes.filter { it.width <= maxLong }.minByOrNull { abs(it.width - minLong) }
    }

    fun largestJpeg(aspect: Float): Size? =
        jpegSizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: jpegSizes.maxByOrNull { it.width.toLong() * it.height }

    fun describe(): String = "ID=$id $facingLabel · nível=$hardwareLevel · zoom≤${"%.1f".format(maxZoom)}× · " +
        "flash=${if (hasFlash) "sim" else "não"} · foco manual=${if (supportsManualFocus) "sim" else "não"} · " +
        "exposição manual=${if (supportsManualExposure) "sim" else "não"} · " +
        "FPS=${fpsRanges.joinToString { "[${it.lower},${it.upper}]" }}"
}

object CameraCatalog {
    fun load(manager: CameraManager): List<CameraCaps> =
        manager.cameraIdList.mapNotNull { id ->
            try { CameraCaps(id, manager.getCameraCharacteristics(id)) } catch (_: Exception) { null }
        }
}
