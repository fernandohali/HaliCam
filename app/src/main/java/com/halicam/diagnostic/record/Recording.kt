package com.halicam.diagnostic.record

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.halicam.diagnostic.core.AudioProcessing
import com.halicam.diagnostic.core.AudioQuality
import com.halicam.diagnostic.gl.EncoderTarget
import com.halicam.diagnostic.gl.Renderer
import com.halicam.diagnostic.gl.SourceMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "HaliCam"

/** Arquivos na galeria: Movies/HaliCam e Pictures/HaliCam (MediaStore no Android 10+). */
object MediaOutput {
    class PendingFile(val uri: Uri?, val file: File?, val pfd: ParcelFileDescriptor)

    fun timestampName(prefix: String, ext: String): String =
        "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$ext"

    fun newVideo(context: Context, name: String): PendingFile {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HaliCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("MediaStore recusou o arquivo")
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Sem descritor")
            return PendingFile(uri, null, pfd)
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "HaliCam").apply { mkdirs() }
        val file = File(dir, name)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        return PendingFile(null, file, pfd)
    }

    /** Publica (ou apaga, se [ok] = false) e devolve a Uri final. */
    fun finish(context: Context, p: PendingFile, ok: Boolean): Uri? {
        try { p.pfd.close() } catch (_: Exception) {}
        if (p.uri != null) {
            return if (ok) {
                context.contentResolver.update(p.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                p.uri
            } else {
                context.contentResolver.delete(p.uri, null, null); null
            }
        }
        val f = p.file ?: return null
        if (!ok) { f.delete(); return null }
        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("video/mp4"), null)
        return Uri.fromFile(f)
    }

    fun saveJpeg(context: Context, bytes: ByteArray): Uri? {
        val name = timestampName("HaliCam", "jpg")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HaliCam")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val r = context.contentResolver
                val uri = r.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
                r.openOutputStream(uri)?.use { it.write(bytes) }
                r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                uri
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "HaliCam").apply { mkdirs() }
                val f = File(dir, name).apply { writeBytes(bytes) }
                MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
                Uri.fromFile(f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveJpeg: ${e.message}"); null
        }
    }
}

/** Bitrate de vídeo (bps) para [bitsPerPixel] bits por pixel por quadro, limitado ao que o encoder aceita. */
fun videoBitrate(w: Int, h: Int, fps: Int, bitsPerPixel: Double, mime: String): Int =
    (w.toLong() * h * fps * bitsPerPixel).toLong()
        .coerceIn(2_000_000L, minOf(100_000_000, EncoderSupport.maxBitrate(mime)).toLong()).toInt()

/** Uma saída de vídeo: composição principal ou original de uma câmera. */
data class VideoOutputSpec(val label: String, val source: SourceMode, val width: Int, val height: Int)

/**
 * Sessão de gravação: N encoders de vídeo (alimentados pelo renderizador) + 1 encoder de áudio
 * compartilhado por todos os arquivos. Suporta pausa/retomada sem cortes de timestamp.
 */
class RecordingSession(
    private val context: Context,
    private val renderer: Renderer,
    private val outputs: List<VideoOutputSpec>,
    private val rotation: Int,
    private val fps: Int,
    private val videoMime: String,
    private val bitsPerPixel: Double,
    private val audioQuality: AudioQuality?,
    private val audioProcessing: AudioProcessing,
    private val micDeviceId: Int
) {
    val clock = RecordingClock()
    private val files = mutableListOf<MediaOutput.PendingFile>()
    private val sinks = mutableListOf<MuxerSink>()
    private val encoders = mutableListOf<VideoEncoder>()
    private val targets = mutableListOf<EncoderTarget>()
    private var audio: AudioEncoder? = null
    var audioError: String? = null
        private set

    fun start() {
        val audioEnc = audioQuality?.let {
            try { AudioEncoder(context, it, audioProcessing, micDeviceId, clock) } catch (e: Exception) {
                audioError = "Áudio indisponível (${e.message}); gravando só vídeo."
                null
            }
        }
        audio = audioEnc
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            for (o in outputs) {
                val f = MediaOutput.newVideo(context, "HaliCam_${stamp}${o.label}.mp4")
                files += f
                // Se o encoder não aceita o quadro em pé (altura > limite), grava deitado e marca a rotação no MP4.
                val swap = !EncoderSupport.supports(o.width, o.height, fps, videoMime) &&
                    EncoderSupport.supports(o.height, o.width, fps, videoMime)
                val ew = if (swap) o.height else o.width
                val eh = if (swap) o.width else o.height
                val sink = MuxerSink(f.pfd.fileDescriptor, if (audioEnc != null) 2 else 1, if (swap) 270 else 0)
                sinks += sink
                val enc = VideoEncoder(ew, eh, fps, videoBitrate(ew, eh, fps, bitsPerPixel, videoMime), videoMime, sink)
                encoders += enc
                val rot = (rotation + if (swap) 90 else 0) % 360
                targets += EncoderTarget(enc.inputSurface, ew, eh, rot, o.source) { clock.pts() }
            }
            audioEnc?.attach(sinks.toList())
            clock.start()
            audioEnc?.start()
            targets.forEach { renderer.addEncoder(it) }
        } catch (e: Exception) {
            releaseAll(false)
            throw e
        }
    }

    /** Ajusta direção/zoom do microfone durante a gravação. Retorna false se o aparelho recusou. */
    fun setMicFocus(direction: Int, field: Float): Boolean = audio?.setMicFocus(direction, field) ?: true

    fun pause() = clock.pause()
    fun resume() = clock.resume()
    val paused: Boolean get() = clock.paused
    fun elapsedMs(): Long = clock.elapsedMs()

    /** Finaliza todos os arquivos (bloqueante, chamar fora da thread principal). Retorna a Uri principal. */
    fun stop(): Uri? = releaseAll(true)

    private fun releaseAll(publish: Boolean): Uri? {
        renderer.removeEncoders(targets.toList())
        try { audio?.stop() } catch (e: Exception) { Log.w(TAG, "audio stop: ${e.message}") }
        encoders.forEach { try { it.finish() } catch (e: Exception) { Log.w(TAG, "video finish: ${e.message}") } }
        var main: Uri? = null
        files.forEachIndexed { i, f ->
            val ok = sinks.getOrNull(i)?.stop() ?: false
            val uri = MediaOutput.finish(context, f, publish && ok)
            if (i == 0) main = uri
        }
        return main
    }
}
