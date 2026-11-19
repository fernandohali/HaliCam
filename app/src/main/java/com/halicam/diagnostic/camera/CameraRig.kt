package com.halicam.diagnostic.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface

/** Fornecedor das texturas de câmera (implementado pelo renderizador OpenGL). */
interface SlotProvider {
    /** Descarta as texturas antigas e cria duas novas. Bloqueante; não chamar da thread GL. */
    fun newSlots(): Array<SurfaceTexture>
    fun setSlotInfo(slot: Int, bufferSize: Size, caps: CameraCaps)
    fun frameCount(slot: Int): Long
}

/**
 * Pedido de configuração.
 * - Simples: [singleId] no slot 0.
 * - Dual: tenta cada par (traseira, frontal) de [pairs] em cada nível de tamanho de [sizeLevels];
 *   traseira no slot 0 e frontal no slot 1. Abre as duas câmeras antes de configurar as sessões
 *   (mesmo método validado pelo diagnóstico).
 */
data class RigRequest(
    val dual: Boolean,
    val singleId: String = "",
    val pairs: List<Pair<String, String>> = emptyList(),
    val sizeLevels: List<Pair<Size, Size>>,
    val jpegSize: Size? = null,
    val params: (CameraCaps, Boolean) -> UnitParams
)

class CameraRig(
    private val manager: CameraManager,
    private val catalog: List<CameraCaps>,
    private val slots: SlotProvider,
    private val listener: Listener
) {
    interface Listener {
        fun onRigReady(units: List<CameraUnit>, dual: Boolean, pair: Pair<String, String>?)
        fun onRigFailed(message: String, dualUnavailable: Boolean)
        fun onRigLost(message: String)
        fun onRigProgress(message: String)
    }

    private val thread = HandlerThread("HaliCam-Camera").apply { start() }
    val handler = Handler(thread.looper)
    private val units = arrayOfNulls<CameraUnit>(2)
    private val surfaces = mutableListOf<Surface>()
    private var generation = 0
    private var running = false

    private fun caps(id: String) = catalog.first { it.id == id }

    fun start(request: RigRequest) {
        handler.post {
            stopInternal()
            val gen = ++generation
            if (request.dual) tryDual(gen, request, 0, 0, mutableListOf())
            else startSingle(gen, request)
        }
    }

    fun stop() { handler.post { generation++; stopInternal() } }

    fun release() {
        handler.post { generation++; stopInternal(); thread.quitSafely() }
    }

    /** Executa [block] na thread de câmera com as unidades atuais (slot 0, slot 1). */
    fun withUnits(block: (CameraUnit?, CameraUnit?) -> Unit) {
        handler.post { if (running) block(units[0], units[1]) }
    }

    private fun startSingle(gen: Int, req: RigRequest) {
        val c = caps(req.singleId)
        val size = req.sizeLevels.first().first
        val unit = CameraUnit(manager, c, handler, ::onUnitLost)
        units[0] = unit
        unit.open(onOpened = {
            if (gen != generation) return@open
            val st = slots.newSlots()
            st[0].setDefaultBufferSize(size.width, size.height)
            slots.setSlotInfo(0, size, c)
            val surface = Surface(st[0]).also(surfaces::add)
            unit.startSession(surface, req.jpegSize, req.params(c, true), onReady = {
                if (gen != generation) return@startSession
                running = true
                listener.onRigReady(listOf(unit), false, null)
            }, onFailed = { msg ->
                if (gen == generation) { stopInternal(); listener.onRigFailed("Não foi possível iniciar a câmera: $msg", false) }
            })
        }, onFailed = { msg ->
            if (gen == generation) { stopInternal(); listener.onRigFailed("Não foi possível abrir a câmera: $msg", false) }
        })
    }

    private fun tryDual(gen: Int, req: RigRequest, pairIndex: Int, level: Int, failures: MutableList<String>) {
        if (gen != generation) return
        if (pairIndex >= req.pairs.size) {
            if (level + 1 < req.sizeLevels.size) {
                tryDual(gen, req, 0, level + 1, failures)
            } else {
                listener.onRigFailed(
                    "Dual Capture indisponível: o hardware/firmware não manteve as duas câmeras ativas.\n" +
                        failures.distinct().joinToString("\n"), true)
            }
            return
        }
        val (backId, frontId) = req.pairs[pairIndex]
        val (backSize, frontSize) = req.sizeLevels[level]
        val back = CameraUnit(manager, caps(backId), handler, ::onUnitLost)
        val front = CameraUnit(manager, caps(frontId), handler, ::onUnitLost)
        units[0] = back
        units[1] = front
        listener.onRigProgress("Testando câmeras $backId + $frontId (${backSize.width}x${backSize.height} + ${frontSize.width}x${frontSize.height})…")

        var settled = false
        fun fail(reason: String) {
            if (settled || gen != generation) return
            settled = true
            Log.i(TAG, "Par $backId+$frontId falhou: $reason")
            failures += "• $backId+$frontId: $reason"
            stopInternal()
            handler.postDelayed({ tryDual(gen, req, pairIndex + 1, level, failures) }, 600L)
        }
        dualFail = ::fail

        back.open(onOpened = {
            if (gen != generation || settled) return@open
            front.open(onOpened = {
                if (gen != generation || settled) return@open
                handler.postDelayed({
                    if (gen != generation || settled) return@postDelayed
                    if (!back.isOpen) { fail("o sistema fechou a traseira ao abrir a frontal"); return@postDelayed }
                    configureDual(gen, req, back, front, backSize, frontSize, ::fail) {
                        settled = true
                        dualFail = null
                        running = true
                        listener.onRigReady(listOf(back, front), true, backId to frontId)
                    }
                }, 150L)
            }, onFailed = { fail(it) })
        }, onFailed = { fail(it) })
    }

    private var dualFail: ((String) -> Unit)? = null

    private fun configureDual(
        gen: Int, req: RigRequest, back: CameraUnit, front: CameraUnit,
        backSize: Size, frontSize: Size, fail: (String) -> Unit, onOk: () -> Unit
    ) {
        val st = slots.newSlots()
        st[0].setDefaultBufferSize(backSize.width, backSize.height)
        st[1].setDefaultBufferSize(frontSize.width, frontSize.height)
        slots.setSlotInfo(0, backSize, back.caps)
        slots.setSlotInfo(1, frontSize, front.caps)
        val s0 = Surface(st[0]).also(surfaces::add)
        val s1 = Surface(st[1]).also(surfaces::add)
        var ready = 0
        val onReady = {
            ready++
            if (ready == 2 && gen == generation) {
                // Confirma que as DUAS câmeras realmente entregam quadros (não apenas abriram).
                val f0 = slots.frameCount(0)
                val f1 = slots.frameCount(1)
                handler.postDelayed({
                    if (gen != generation) return@postDelayed
                    val d0 = slots.frameCount(0) - f0
                    val d1 = slots.frameCount(1) - f1
                    if (d0 >= 5 && d1 >= 5) onOk()
                    else fail("sem quadros simultâneos (traseira=$d0, frontal=$d1 em 2 s)")
                }, 2000L)
            }
        }
        back.startSession(s0, null, req.params(back.caps, true), onReady = { onReady() }, onFailed = fail)
        front.startSession(s1, null, req.params(front.caps, false), onReady = { onReady() }, onFailed = fail)
    }

    private fun onUnitLost(unit: CameraUnit, reason: String) {
        if (units.none { it === unit }) return
        val pending = dualFail
        if (pending != null) { pending(reason); return }
        if (!running) return
        generation++
        stopInternal()
        listener.onRigLost(reason)
    }

    private fun stopInternal() {
        running = false
        dualFail = null
        units.forEachIndexed { i, u -> u?.close(); units[i] = null }
        surfaces.forEach { try { it.release() } catch (_: Exception) {} }
        surfaces.clear()
    }

    companion object { private const val TAG = "HaliCam" }
}
