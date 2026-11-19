package com.halicam.diagnostic.core

import android.content.Context
import android.graphics.RectF
import android.hardware.camera2.CameraMetadata
import kotlin.math.max
import kotlin.math.min

/** Modos da barra inferior. */
enum class CaptureMode(val label: String) {
    VIDEO("VÍDEO"), DUAL("DUAL"), PHOTO("FOTO"), WEBCAM("WEBCAM")
}

/** Proporção do quadro final, sempre descrita "em pé no mundo" (já considerando a rotação do aparelho). */
enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    R9_16("9:16", 9, 16), R16_9("16:9", 16, 9), R1_1("1:1", 1, 1), R3_4("3:4", 3, 4);
    val value: Float get() = w.toFloat() / h
}

enum class PipShape(val label: String, val aspect: Float, val circle: Boolean) {
    SQUARE("Quadrado", 1f, false), RECT("Retângulo", 0.75f, false), CIRCLE("Círculo", 1f, true)
}

enum class FlashMode(val label: String) { OFF("Desligado"), ON("Ligado"), AUTO("Automático") }

enum class WebcamSource(val label: String) { BACK("Traseira"), FRONT("Frontal"), DUAL("Dual") }

enum class AudioQuality(val label: String, val sampleRate: Int, val bitrate: Int, val stereo: Boolean) {
    STANDARD("Padrão · 44,1 kHz · 128 kbps", 44_100, 128_000, false),
    HIGH("Alta · 48 kHz · 192 kbps estéreo", 48_000, 192_000, true),
    MAX("Máxima · 48 kHz · 256 kbps estéreo", 48_000, 256_000, true)
}

/** Configurações de captura persistidas. */
data class CaptureSettings(
    val resolution: Int = 1080,          // lado menor do vídeo final
    val fps: Int = 30,
    val aspect: AspectRatio = AspectRatio.R9_16,
    val stabilization: Boolean = true,
    val manualFocus: Boolean = false,
    val focusDistance: Float = 0f,       // dioptrias (0 = infinito)
    val manualExposure: Boolean = false,
    val iso: Int = 200,
    val exposureNs: Long = 16_666_666L,
    val aeCompensation: Int = 0,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val zoom: Float = 1f,
    val flash: FlashMode = FlashMode.OFF,
    val mirrorFront: Boolean = true,
    val micDeviceId: Int = 0,            // 0 = microfone padrão
    val audio: AudioQuality = AudioQuality.HIGH,
    val saveSeparate: Boolean = false,
    val webcamHeight: Int = 720,
    val webcamSource: WebcamSource = WebcamSource.DUAL,
    val showGrid: Boolean = true
)

/** Janela flutuante (Picture-in-Picture), em coordenadas normalizadas do quadro em espaço de tela. */
data class PipLayout(
    val cx: Float = 0.80f,
    val cy: Float = 0.15f,
    val widthFrac: Float = 0.32f,        // largura relativa ao menor lado do quadro
    val shape: PipShape = PipShape.RECT,
    val visible: Boolean = true,
    val backIsMain: Boolean = true
) {
    /** Retângulo normalizado (0..1) da janela num quadro com proporção [frameAspect] (largura/altura). */
    fun rect(frameAspect: Float): RectF {
        val fw = frameAspect
        val fh = 1f
        val base = min(fw, fh)
        val wPx = widthFrac * base
        val hPx = wPx / shape.aspect
        val w = min(wPx / fw, 0.95f)
        val h = min(hPx / fh, 0.95f)
        val m = 0.025f
        val x = cx.coerceIn(w / 2 + m * base / fw, 1f - w / 2 - m * base / fw)
        val y = cy.coerceIn(h / 2 + m * base / fh, 1f - h / 2 - m * base / fh)
        return RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
    }

    fun clamped(frameAspect: Float): PipLayout {
        val r = rect(frameAspect)
        return copy(cx = r.centerX(), cy = r.centerY(), widthFrac = widthFrac.coerceIn(MIN_SIZE, MAX_SIZE))
    }

    companion object {
        const val MIN_SIZE = 0.18f
        const val MAX_SIZE = 0.60f
    }
}

/** Proporção (largura/altura) do quadro em espaço de tela para uma proporção de mundo e rotação do aparelho. */
fun screenAspect(world: AspectRatio, rotation: Int): Float =
    if (rotation == 90 || rotation == 270) 1f / world.value else world.value

/** Dimensões do vídeo final (largura x altura, já em pé), alinhadas a 8 px (1080 continua 1080). */
fun outputSize(world: AspectRatio, shortSide: Int): Pair<Int, Int> {
    fun align(v: Int) = max(16, (v + 4) / 8 * 8)
    return if (world.w >= world.h) {
        align(shortSide * world.w / world.h) to align(shortSide)
    } else {
        align(shortSide) to align(shortSide * world.h / world.w)
    }
}

/** Persistência simples em SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("halicam", Context.MODE_PRIVATE)

    fun loadSettings(): CaptureSettings {
        val d = CaptureSettings()
        return CaptureSettings(
            resolution = sp.getInt("resolution", d.resolution),
            fps = sp.getInt("fps", d.fps),
            aspect = enumOr(sp.getString("aspect", null), d.aspect),
            stabilization = sp.getBoolean("stab", d.stabilization),
            manualFocus = sp.getBoolean("mf", d.manualFocus),
            focusDistance = sp.getFloat("focusDist", d.focusDistance),
            manualExposure = sp.getBoolean("me", d.manualExposure),
            iso = sp.getInt("iso", d.iso),
            exposureNs = sp.getLong("expNs", d.exposureNs),
            aeCompensation = sp.getInt("aeComp", d.aeCompensation),
            awbMode = sp.getInt("awb", d.awbMode),
            zoom = 1f,
            flash = enumOr(sp.getString("flash", null), d.flash),
            mirrorFront = sp.getBoolean("mirror", d.mirrorFront),
            micDeviceId = sp.getInt("mic", d.micDeviceId),
            audio = enumOr(sp.getString("audio", null), d.audio),
            saveSeparate = sp.getBoolean("separate", d.saveSeparate),
            webcamHeight = sp.getInt("webcamH", d.webcamHeight),
            webcamSource = enumOr(sp.getString("webcamSrc", null), d.webcamSource),
            showGrid = sp.getBoolean("grid", d.showGrid)
        )
    }

    fun saveSettings(s: CaptureSettings) {
        sp.edit()
            .putInt("resolution", s.resolution).putInt("fps", s.fps).putString("aspect", s.aspect.name)
            .putBoolean("stab", s.stabilization).putBoolean("mf", s.manualFocus).putFloat("focusDist", s.focusDistance)
            .putBoolean("me", s.manualExposure).putInt("iso", s.iso).putLong("expNs", s.exposureNs)
            .putInt("aeComp", s.aeCompensation).putInt("awb", s.awbMode).putString("flash", s.flash.name)
            .putBoolean("mirror", s.mirrorFront).putInt("mic", s.micDeviceId).putString("audio", s.audio.name)
            .putBoolean("separate", s.saveSeparate).putInt("webcamH", s.webcamHeight)
            .putString("webcamSrc", s.webcamSource.name).putBoolean("grid", s.showGrid)
            .apply()
    }

    fun loadPip(): PipLayout {
        val d = PipLayout()
        return PipLayout(
            cx = sp.getFloat("pipX", d.cx), cy = sp.getFloat("pipY", d.cy),
            widthFrac = sp.getFloat("pipW", d.widthFrac),
            shape = enumOr(sp.getString("pipShape", null), d.shape),
            visible = sp.getBoolean("pipVisible", d.visible),
            backIsMain = sp.getBoolean("pipBackMain", d.backIsMain)
        )
    }

    fun savePip(p: PipLayout) {
        sp.edit().putFloat("pipX", p.cx).putFloat("pipY", p.cy).putFloat("pipW", p.widthFrac)
            .putString("pipShape", p.shape.name).putBoolean("pipVisible", p.visible)
            .putBoolean("pipBackMain", p.backIsMain).apply()
    }

    var mode: CaptureMode
        get() = enumOr(sp.getString("mode", null), CaptureMode.VIDEO)
        set(v) = sp.edit().putString("mode", v.name).apply()

    var useFront: Boolean
        get() = sp.getBoolean("useFront", false)
        set(v) = sp.edit().putBoolean("useFront", v).apply()

    /** Par traseira/frontal que funcionou em simultâneo, ex.: "0,2". */
    var dualPair: String?
        get() = sp.getString("dualPair", null)
        set(v) = sp.edit().putString("dualPair", v).apply()

    var lastMediaUri: String?
        get() = sp.getString("lastUri", null)
        set(v) = sp.edit().putString("lastUri", v).apply()

    var lastMediaIsVideo: Boolean
        get() = sp.getBoolean("lastIsVideo", true)
        set(v) = sp.edit().putBoolean("lastIsVideo", v).apply()

    /** Token de acesso do modo webcam (exigido nas URLs). */
    val webcamToken: String
        get() = sp.getString("token", null) ?: (100000 + java.security.SecureRandom().nextInt(900000)).toString()
            .also { sp.edit().putString("token", it).apply() }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: def
}
