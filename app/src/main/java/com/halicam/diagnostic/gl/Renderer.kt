package com.halicam.diagnostic.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import com.halicam.diagnostic.camera.CameraCaps
import com.halicam.diagnostic.camera.SlotProvider
import com.halicam.diagnostic.core.PipLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** O que um alvo de saída desenha. */
enum class SourceMode { COMPOSITE, SLOT0, SLOT1 }

/** Estado da composição. Coordenadas da janela PiP no espaço de tela do quadro. */
data class Scene(
    val dual: Boolean = false,
    val pip: PipLayout = PipLayout(),
    val frameAspect: Float = 9f / 16f,   // largura/altura do quadro em espaço de tela
    val mirrorFront: Boolean = true,
    val showGrid: Boolean = false,
    val saturation: Float = 1f,          // perfil de cor (1 = original)
    val contrast: Float = 1f
) {
    /** Slot que ocupa o fundo: 0 = traseira, 1 = frontal (no modo dual). */
    val mainSlot: Int get() = if (dual && !pip.backIsMain) 1 else 0
}

/** Superfície de entrada de um encoder. [pts] devolve null enquanto a gravação está pausada. */
class EncoderTarget(
    val surface: Surface, val width: Int, val height: Int, val rotation: Int,
    val source: SourceMode, val pts: () -> Long?
) {
    internal var egl: EGLSurface? = null
    internal var lastPts = Long.MIN_VALUE
}

/** Recebe quadros RGBA (linhas de cima para baixo) para a webcam. Chamado na thread GL. */
interface FrameSink {
    fun wantsFrame(): Boolean
    fun onRgbaFrame(buffer: ByteBuffer, width: Int, height: Int)
}

class WebcamTarget(val sink: FrameSink, val width: Int, val height: Int, val rotation: Int, val fps: Int)

/**
 * Renderizador OpenGL ES 2.0 numa thread própria.
 * Recebe as duas câmeras como texturas externas (SurfaceTexture) e compõe o quadro final para:
 * tela (SurfaceView), encoders H.264 (superfície de entrada do MediaCodec) e webcam (leitura RGBA).
 * Toda a composição acontece na GPU; a CPU só toca pixels no caminho da webcam (JPEG).
 */
class Renderer : SlotProvider, SurfaceTexture.OnFrameAvailableListener {

    private class Slot(val tex: Int, val st: SurfaceTexture) {
        val matrix = FloatArray(16)
        var hasFrame = false
        val frames = AtomicLong()
        var bufW = 16
        var bufH = 9
        var rotated = true
        var front = false
        val contentAspect: Float get() = if (rotated) bufH.toFloat() / bufW else bufW.toFloat() / bufH
    }

    private val thread = HandlerThread("HaliCam-GL").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var pbuffer: EGLSurface
    private var program = 0
    private var aPos = 0
    private var uRect = 0; private var uRowX = 0; private var uRowY = 0; private var uUv = 0
    private var uTexMatrix = 0; private var uSizePx = 0; private var uRadius = 0; private var uMode = 0
    private var uSoft = 0; private var uAlpha = 0; private var uColor = 0; private var uSat = 0; private var uContrast = 0
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private val slots = arrayOfNulls<Slot>(2)
    @Volatile var scene = Scene()
    private var display: EGLSurface? = null
    private var displayW = 1
    private var displayH = 1
    private val encoders = mutableListOf<EncoderTarget>()

    private var webcam: WebcamTarget? = null
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private var readBuffer: ByteBuffer? = null
    private var lastWebcamNs = 0L

    /** FPS medido do quadro principal (exibido na interface). */
    @Volatile var measuredFps = 0f
        private set
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

    init {
        handler.post { initGl() }
    }

    private fun initGl() {
        egl = EglCore()
        pbuffer = egl.createPbuffer(1, 1)
        egl.makeCurrent(pbuffer)
        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uRect = GLES20.glGetUniformLocation(program, "uRect")
        uRowX = GLES20.glGetUniformLocation(program, "uRowX")
        uRowY = GLES20.glGetUniformLocation(program, "uRowY")
        uUv = GLES20.glGetUniformLocation(program, "uUv")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uSizePx = GLES20.glGetUniformLocation(program, "uSizePx")
        uRadius = GLES20.glGetUniformLocation(program, "uRadius")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        uSoft = GLES20.glGetUniformLocation(program, "uSoft")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uSat = GLES20.glGetUniformLocation(program, "uSat")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast")
    }

    // ---------------------------------------------------------------- SlotProvider

    override fun newSlots(): Array<SurfaceTexture> {
        var result: Array<SurfaceTexture>? = null
        runBlocking {
            for (i in 0..1) {
                slots[i]?.let { old ->
                    old.st.setOnFrameAvailableListener(null)
                    old.st.release()
                    GLES20.glDeleteTextures(1, intArrayOf(old.tex), 0)
                }
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val st = SurfaceTexture(tex[0])
                st.setOnFrameAvailableListener(this, handler)
                slots[i] = Slot(tex[0], st)
            }
            result = arrayOf(slots[0]!!.st, slots[1]!!.st)
        }
        return result ?: throw IllegalStateException("Renderizador não respondeu")
    }

    override fun setSlotInfo(slot: Int, bufferSize: Size, caps: CameraCaps) {
        handler.post {
            slots[slot]?.apply {
                bufW = bufferSize.width
                bufH = bufferSize.height
                rotated = caps.sensorOrientation % 180 != 0
                front = caps.isFront
            }
        }
    }

    override fun frameCount(slot: Int): Long = slots[slot]?.frames?.get() ?: 0L

    // ---------------------------------------------------------------- Alvos

    fun setDisplay(surface: Surface, width: Int, height: Int) {
        runBlocking {
            display?.let { egl.makeCurrent(pbuffer); egl.releaseSurface(it) }
            display = try { egl.createWindowSurface(surface) } catch (e: Exception) { Log.e(TAG, "display: ${e.message}"); null }
            displayW = width
            displayH = height
        }
    }

    fun clearDisplay() {
        runBlocking {
            display?.let { egl.makeCurrent(pbuffer); egl.releaseSurface(it) }
            display = null
        }
    }

    fun addEncoder(target: EncoderTarget) {
        runBlocking {
            target.egl = egl.createWindowSurface(target.surface)
            encoders += target
        }
    }

    /** Remove os alvos de encoder; ao retornar, nenhum quadro novo será enviado a eles. */
    fun removeEncoders(targets: List<EncoderTarget>) {
        runBlocking {
            egl.makeCurrent(pbuffer)
            for (t in targets) {
                t.egl?.let { egl.releaseSurface(it) }
                t.egl = null
                encoders.remove(t)
            }
        }
    }

    fun setWebcam(target: WebcamTarget?) {
        handler.post { webcam = target }
    }

    // ---------------------------------------------------------------- Quadros

    override fun onFrameAvailable(st: SurfaceTexture) {
        val index = slots.indexOfFirst { it?.st === st }
        if (index < 0) return
        val slot = slots[index]!!
        try {
            if (display == null) egl.makeCurrent(pbuffer)
            st.updateTexImage()
            st.getTransformMatrix(slot.matrix)
            slot.hasFrame = true
            slot.frames.incrementAndGet()
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage: ${e.message}")
            return
        }
        val sc = scene
        // A câmera principal dita o ritmo: cada quadro dela gera um quadro de saída;
        // a outra câmera contribui com o quadro mais recente (latch).
        if (index == sc.mainSlot || (!sc.dual && index == 0)) render(sc)
    }

    private fun render(sc: Scene) {
        val now = System.nanoTime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsWindowFrames++
        if (now - fpsWindowStart >= 1_000_000_000L) {
            measuredFps = fpsWindowFrames * 1e9f / (now - fpsWindowStart)
            fpsWindowStart = now
            fpsWindowFrames = 0
        }

        display?.let { surface ->
            try {
                egl.makeCurrent(surface)
                draw(displayW, displayH, 0, false, SourceMode.COMPOSITE, sc, sc.showGrid)
                egl.swap(surface)
            } catch (e: Exception) { Log.w(TAG, "display draw: ${e.message}") }
        }

        for (t in encoders) {
            val surface = t.egl ?: continue
            val pts = t.pts() ?: continue
            if (pts <= t.lastPts) continue
            try {
                egl.makeCurrent(surface)
                draw(t.width, t.height, t.rotation, false, t.source, sc, false)
                egl.setPresentationTime(surface, pts)
                egl.swap(surface)
                t.lastPts = pts
            } catch (e: Exception) { Log.w(TAG, "encoder draw: ${e.message}") }
        }

        webcam?.let { w ->
            if (!w.sink.wantsFrame()) return@let
            if (now - lastWebcamNs < 1_000_000_000L / w.fps - 2_000_000L) return@let
            lastWebcamNs = now
            try {
                egl.makeCurrent(pbuffer)
                ensureFbo(w.width, w.height)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                draw(w.width, w.height, w.rotation, true, SourceMode.COMPOSITE, sc, false)
                val buf = readBuffer!!
                buf.clear()
                GLES20.glReadPixels(0, 0, w.width, w.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                buf.rewind()
                w.sink.onRgbaFrame(buf, w.width, w.height)
            } catch (e: Exception) { Log.w(TAG, "webcam draw: ${e.message}") }
        }
    }

    private fun ensureFbo(w: Int, h: Int) {
        if (fbo != 0 && fboW == w && fboH == h) return
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        fboTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fboW = w
        fboH = h
        readBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    }

    // ---------------------------------------------------------------- Composição

    /**
     * Desenha a cena num alvo [tw]x[th]. [rotation] gira o quadro (espaço de tela) no sentido horário
     * para que o arquivo final fique em pé quando o aparelho está deitado. [flipY] inverte para leitura RGBA.
     */
    private fun draw(tw: Int, th: Int, rotation: Int, flipY: Boolean, source: SourceMode, sc: Scene, grid: Boolean) {
        GLES20.glViewport(0, 0, tw, th)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uSat, sc.saturation)
        GLES20.glUniform1f(uContrast, sc.contrast)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Mistura só a cor; o alfa do destino fica em 1 (a tela não pode ficar translúcida).
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ZERO, GLES20.GL_ONE)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val sideways = rotation % 180 != 0
        val fw = (if (sideways) th else tw).toFloat()
        val fh = (if (sideways) tw else th).toFloat()
        val ys = if (flipY) -1f else 1f
        when (rotation) {
            90 -> { GLES20.glUniform3f(uRowX, 0f, -2f, 1f); GLES20.glUniform3f(uRowY, -2f * ys, 0f, 1f * ys) }
            180 -> { GLES20.glUniform3f(uRowX, -2f, 0f, 1f); GLES20.glUniform3f(uRowY, 0f, 2f * ys, -1f * ys) }
            270 -> { GLES20.glUniform3f(uRowX, 0f, 2f, -1f); GLES20.glUniform3f(uRowY, 2f * ys, 0f, -1f * ys) }
            else -> { GLES20.glUniform3f(uRowX, 2f, 0f, -1f); GLES20.glUniform3f(uRowY, 0f, -2f * ys, 1f * ys) }
        }

        when (source) {
            SourceMode.SLOT0 -> drawSlot(0, 0f, 0f, 1f, 1f, fw, fh, 0f, sc)
            SourceMode.SLOT1 -> drawSlot(1, 0f, 0f, 1f, 1f, fw, fh, 0f, sc)
            SourceMode.COMPOSITE -> {
                val main = sc.mainSlot
                drawSlot(main, 0f, 0f, 1f, 1f, fw, fh, 0f, sc)
                if (grid) drawGrid(fw, fh)
                if (sc.dual && sc.pip.visible) {
                    val other = 1 - main
                    if (slots[other]?.hasFrame == true) {
                        val r = sc.pip.rect(fw / fh)
                        val wPx = r.width() * fw
                        val hPx = r.height() * fh
                        val radius = if (sc.pip.shape.circle) min(wPx, hPx) / 2f else min(wPx, hPx) * 0.14f
                        val soft = min(wPx, hPx) * 0.07f
                        // Sombra suave
                        GLES20.glUniform4f(uRect, r.left - soft / fw, r.top - soft / fh + soft * 0.35f / fh,
                            r.width() + 2 * soft / fw, r.height() + 2 * soft / fh)
                        GLES20.glUniform2f(uSizePx, wPx + 2 * soft, hPx + 2 * soft)
                        GLES20.glUniform1f(uRadius, radius)
                        GLES20.glUniform1f(uSoft, soft)
                        GLES20.glUniform1f(uMode, 1f)
                        GLES20.glUniform1f(uAlpha, 0.45f)
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                        // Imagem
                        drawSlot(other, r.left, r.top, r.width(), r.height(), fw, fh, radius, sc)
                    }
                }
            }
        }
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun drawSlot(index: Int, x: Float, y: Float, w: Float, h: Float, fw: Float, fh: Float, radius: Float, sc: Scene) {
        val s = slots[index] ?: return
        if (!s.hasFrame) return
        val dstAspect = (w * fw) / (h * fh)
        val ca = s.contentAspect
        var u0 = 0f; var u1 = 1f; var v0 = 0f; var v1 = 1f
        if (ca > dstAspect) {
            val k = dstAspect / ca; u0 = 0.5f - k / 2; u1 = 0.5f + k / 2
        } else {
            val k = ca / dstAspect; v0 = 0.5f - k / 2; v1 = 0.5f + k / 2
        }
        // O framework já entrega a frontal espelhada (como um espelho); desfaz se o usuário desligar.
        if (s.front && !sc.mirrorFront) { val t = u0; u0 = u1; u1 = t }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.tex)
        GLES20.glUniform4f(uRect, x, y, w, h)
        GLES20.glUniform4f(uUv, u0, v0, u1, v1)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, s.matrix, 0)
        GLES20.glUniform2f(uSizePx, w * fw, h * fh)
        GLES20.glUniform1f(uRadius, radius)
        GLES20.glUniform1f(uMode, 0f)
        GLES20.glUniform1f(uAlpha, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawGrid(fw: Float, fh: Float) {
        GLES20.glUniform1f(uMode, 2f)
        GLES20.glUniform4f(uColor, 1f, 1f, 1f, 0.28f)
        GLES20.glUniform1f(uRadius, 0f)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, identity, 0)
        val lw = 1.5f
        for (k in 1..2) {
            val x = k / 3f
            GLES20.glUniform4f(uRect, x - lw / 2 / fw, 0f, lw / fw, 1f)
            GLES20.glUniform2f(uSizePx, lw, fh)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glUniform4f(uRect, 0f, x - lw / 2 / fh, 1f, lw / fh)
            GLES20.glUniform2f(uSizePx, fw, lw)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    // ---------------------------------------------------------------- Infra

    private fun runBlocking(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) { block(); return }
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try { block() } catch (t: Throwable) { error = t } finally { latch.countDown() }
        }
        if (!latch.await(3, TimeUnit.SECONDS)) Log.w(TAG, "Renderizador demorou a responder")
        error?.let { throw it }
    }

    fun release() {
        handler.post {
            try {
                egl.makeCurrent(pbuffer)
                slots.forEach { it?.st?.release() }
                display?.let { egl.releaseSurface(it) }
                encoders.forEach { t -> t.egl?.let { egl.releaseSurface(it) } }
                egl.releaseSurface(pbuffer)
                egl.release()
            } catch (_: Exception) {}
            thread.quitSafely()
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val TAG = "HaliCam"

        private const val VERTEX = """
attribute vec2 aPos;
uniform vec4 uRect;
uniform vec3 uRowX;
uniform vec3 uRowY;
uniform vec4 uUv;
uniform mat4 uTexMatrix;
uniform vec2 uSizePx;
varying vec2 vTex;
varying vec2 vLocal;
void main() {
    vec2 f = uRect.xy + aPos * uRect.zw;
    vec3 fh = vec3(f, 1.0);
    gl_Position = vec4(dot(uRowX, fh), dot(uRowY, fh), 0.0, 1.0);
    vec2 uv = vec2(mix(uUv.x, uUv.z, aPos.x), mix(uUv.y, uUv.w, aPos.y));
    vTex = (uTexMatrix * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy;
    vLocal = (aPos - 0.5) * uSizePx;
}
"""

        private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES uTex;
uniform vec2 uSizePx;
uniform float uRadius;
uniform float uMode;
uniform float uSoft;
uniform float uAlpha;
uniform vec4 uColor;
uniform float uSat;
uniform float uContrast;
varying vec2 vTex;
varying vec2 vLocal;
float sdRoundRect(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
void main() {
    if (uMode > 1.5) {
        gl_FragColor = uColor;
    } else if (uMode > 0.5) {
        float d = sdRoundRect(vLocal, uSizePx * 0.5 - vec2(uSoft), uRadius);
        float a = uAlpha * (1.0 - smoothstep(-uSoft * 0.6, uSoft, d));
        gl_FragColor = vec4(0.0, 0.0, 0.0, a);
    } else {
        vec4 c = texture2D(uTex, vTex);
        float a = 1.0;
        if (uRadius > 0.0) {
            float d = sdRoundRect(vLocal, uSizePx * 0.5, uRadius);
            a = 1.0 - smoothstep(-1.0, 1.0, d);
        }
        vec3 rgb = (c.rgb - 0.5) * uContrast + 0.5;
        float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        rgb = clamp(mix(vec3(luma), rgb, uSat), 0.0, 1.0);
        gl_FragColor = vec4(rgb, a * uAlpha);
    }
}
"""
    }
}
