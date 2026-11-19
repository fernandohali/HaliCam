package com.halicam.diagnostic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Primeiro marco: verificar se frente e traseira conseguem gerar prévias ao mesmo tempo.
 *  Não contém captura de vídeo/áudio nem transmissão para o OBS nesta versão.
 */
class MainActivity : Activity() {
    private lateinit var manager: CameraManager
    private lateinit var engine: DualCameraProbe
    private lateinit var backView: TextureView
    private lateinit var frontView: TextureView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var testButton: Button

    private val backPreviewFrames = AtomicInteger(0)
    private val frontPreviewFrames = AtomicInteger(0)
    private val report = StringBuilder()
    private val backCameraIds = mutableListOf<String>()
    private val frontCameraIds = mutableListOf<String>()
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        engine = DualCameraProbe(manager, ::appendLog, ::onTestFinished)
        buildInterface()
        appendLog("HaliCam 0.1 — Galaxy S9+ / Android 10: diagnóstico de câmera")
        appendLog("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} | API ${Build.VERSION.SDK_INT}")
        appendLog("AVISO: duas câmeras traseiras não garantem frente+traseira simultâneas.")
        if (hasCameraPermission()) scanCameras()
        else appendLog("Permissão de câmera pendente. Toque em Inventariar.")
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "HaliCam • Diagnóstico Camera2"
            textSize = 19f
        })
        root.addView(TextView(this).apply {
            text = "Teste de câmera frontal + traseira (duas prévias, sem gravação)"
            textSize = 12f
        })
        root.addView(Button(this).apply {
            text = "1. Inventariar câmeras"
            setOnClickListener { ensurePermissionThen { scanCameras() } }
        })
        testButton = Button(this).apply {
            text = "2. Testar câmera dupla (todas as combinações)"
            setOnClickListener { ensurePermissionThen { startDualTest() } }
        }
        root.addView(testButton)
        root.addView(Button(this).apply {
            text = "3. Compartilhar relatório"
            setOnClickListener { shareReport() }
        })

        val previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(previewRow, LinearLayout.LayoutParams(-1, dp(215)))
        fun previewColumn(title: String): Pair<LinearLayout, TextureView> {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(this).apply { text = title; textSize = 13f })
            val texture = TextureView(this)
            column.addView(texture, LinearLayout.LayoutParams(-1, dp(185)))
            return column to texture
        }
        val (backCol, bv) = previewColumn("Traseira")
        val (frontCol, fv) = previewColumn("Frontal")
        backView = bv
        frontView = fv
        previewRow.addView(backCol, LinearLayout.LayoutParams(0, -1, 1f))
        previewRow.addView(frontCol, LinearLayout.LayoutParams(0, -1, 1f))
        backView.surfaceTextureListener = frameListener(backPreviewFrames)
        frontView.surfaceTextureListener = frameListener(frontPreviewFrames)

        logScroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun frameListener(counter: AtomicInteger) = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { counter.incrementAndGet() }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            engine.stop()
            return true
        }
    }

    private fun ensurePermissionThen(action: () -> Unit) {
        if (hasCameraPermission()) action()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanCameras()
            else appendLog("Permissão de câmera negada. Autorize nas configurações do aplicativo.")
        }
    }

    private fun scanCameras() {
        backCameraIds.clear()
        frontCameraIds.clear()
        try {
            appendLog("----- INVENTÁRIO -----")
            val concurrentFlag = packageManager.hasSystemFeature("android.hardware.camera.concurrent")
            appendLog("FEATURE_CAMERA_CONCURRENT: $concurrentFlag (indicador; não substitui teste)")
            if (Build.VERSION.SDK_INT >= 30) {
                appendLog("Pares declarados: ${manager.concurrentCameraIds}")
            } else {
                appendLog("Android ${Build.VERSION.SDK_INT}: getConcurrentCameraIds indisponível; teste prático necessário.")
            }
            for (id in manager.cameraIdList) {
                val info = manager.getCameraCharacteristics(id)
                val facing = when (info.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "TRASEIRA"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONTAL"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNA"
                    else -> "OUTRA"
                }
                if (facing == "TRASEIRA") backCameraIds += id
                if (facing == "FRONTAL") frontCameraIds += id
                val level = info.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val capabilities = info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString()
                val fps = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString()
                appendLog("ID=$id | $facing | level=$level | cap=$capabilities | FPS=$fps")
            }
            appendLog("Traseiras: ${backCameraIds.ifEmpty { listOf("nenhuma") }}; frontais: ${frontCameraIds.ifEmpty { listOf("nenhuma") }}")
        } catch (e: Exception) {
            appendLog("Falha no inventário: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startDualTest() {
        if (testing) return
        if (backCameraIds.isEmpty() || frontCameraIds.isEmpty()) scanCameras()
        val attempts = backCameraIds.flatMap { back ->
            frontCameraIds.flatMap { front ->
                listOf(true, false).map { backFirst -> DualCameraProbe.Attempt(back, front, backFirst) }
            }
        }
        if (attempts.isEmpty()) {
            appendLog("Não existem IDs frontal e traseiro acessíveis para este teste.")
            return
        }
        if (!backView.isAvailable || !frontView.isAvailable) {
            appendLog("Prévias ainda não estão disponíveis. Mantenha a tela aberta e tente novamente.")
            return
        }
        backPreviewFrames.set(0)
        frontPreviewFrames.set(0)
        testing = true
        testButton.isEnabled = false
        appendLog("----- TESTE DUPLO -----")
        appendLog("${attempts.size} combinações a testar; cada uma abre as duas câmeras antes de configurar as sessões.")
        engine.start(
            attempts = attempts,
            backTexture = backView.surfaceTexture!!,
            frontTexture = frontView.surfaceTexture!!,
            previewCounters = { backPreviewFrames.get() to frontPreviewFrames.get() }
        )
    }

    private fun onTestFinished(succeeded: Boolean, explanation: String) {
        runOnUiThread {
            appendLog("RESULTADO: ${if (succeeded) "PRÉVIAS DUPLAS ATIVAS" else "NÃO COMPROVADO"} — $explanation")
            appendLog("Obs.: teste de gravação com 2 encoders e áudio ainda é outra etapa.")
            testing = false
            testButton.isEnabled = true
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "[$timestamp] $message\n"
            report.append(line)
            logView.text = report.toString()
            Log.i("HaliCam", message)
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun shareReport() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Relatório HaliCam - Camera2")
            putExtra(Intent.EXTRA_TEXT, report.toString())
        }, "Enviar diagnóstico da câmera"))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStop() {
        super.onStop()
        engine.stop()
        testing = false
        testButton.isEnabled = true
    }

    override fun onDestroy() {
        engine.shutdown()
        super.onDestroy()
    }

    companion object { private const val CAMERA_PERMISSION_REQUEST = 1001 }
}
