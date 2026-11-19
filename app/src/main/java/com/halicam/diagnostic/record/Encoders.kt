package com.halicam.diagnostic.record

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import android.view.Surface
import com.halicam.diagnostic.core.AudioProcessing
import com.halicam.diagnostic.core.AudioQuality
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.max

private const val TAG = "HaliCam"

/** Relógio da gravação com pausa: timestamps contínuos, sem o tempo pausado. */
class RecordingClock {
    private var startNs = 0L
    private var pausedTotal = 0L
    private var pauseStart = 0L
    @Volatile var paused = false
        private set

    @Synchronized fun start() { startNs = System.nanoTime(); pausedTotal = 0; paused = false }
    @Synchronized fun pause() { if (!paused) { paused = true; pauseStart = System.nanoTime() } }
    @Synchronized fun resume() { if (paused) { pausedTotal += System.nanoTime() - pauseStart; paused = false } }

    /** Tempo de mídia (ns) correspondente ao instante [at]; null se pausado. */
    @Synchronized fun pts(at: Long = System.nanoTime()): Long? =
        if (paused || startNs == 0L) null else max(0L, at - startNs - pausedTotal)

    @Synchronized fun elapsedMs(): Long {
        if (startNs == 0L) return 0
        val end = if (paused) pauseStart else System.nanoTime()
        return (end - startNs - pausedTotal) / 1_000_000
    }
}

/** MediaMuxer que só inicia quando todas as trilhas esperadas foram adicionadas. */
class MuxerSink(fd: FileDescriptor, private val expectedTracks: Int, orientationHint: Int = 0) {
    private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        if (orientationHint != 0) setOrientationHint(orientationHint)
    }
    private var added = 0
    private var started = false
    private var stopped = false
    private val pending = ArrayList<Pending>()
    var samplesWritten = 0
        private set

    @Synchronized fun addTrack(format: MediaFormat): Int {
        val t = muxer.addTrack(format)
        added++
        if (added == expectedTracks) {
            muxer.start()
            started = true
            for (p in pending) muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), p.info)
            samplesWritten += pending.size
            pending.clear()
        }
        return t
    }

    @Synchronized fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped) return
        if (!started) {
            if (pending.size < 500) {
                val copy = ByteArray(info.size)
                buffer.position(info.offset); buffer.get(copy)
                pending += Pending(track, copy, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
            }
            return
        }
        muxer.writeSampleData(track, buffer, info)
        samplesWritten++
    }

    /** Encerra o arquivo. Retorna true se ele é um MP4 válido. */
    @Synchronized fun stop(): Boolean {
        if (stopped) return false
        stopped = true
        var ok = false
        try {
            if (started) { muxer.stop(); ok = samplesWritten > 0 }
        } catch (e: Exception) { Log.w(TAG, "muxer.stop: ${e.message}") }
        try { muxer.release() } catch (_: Exception) {}
        return ok
    }
}

object EncoderSupport {
    private val capsCache = HashMap<String, MediaCodecInfo.CodecCapabilities?>()

    /** Capacidades do encoder preferido (hardware antes de software) para [mime]. */
    @Synchronized
    private fun caps(mime: String): MediaCodecInfo.CodecCapabilities? = capsCache.getOrPut(mime) {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .sortedBy { if (it.name.startsWith("OMX.google") || it.name.startsWith("c2.android")) 1 else 0 }
            .firstOrNull()?.getCapabilitiesForType(mime)
    }

    fun hasEncoder(mime: String): Boolean = caps(mime) != null

    fun supports(w: Int, h: Int, fps: Int, mime: String = MediaFormat.MIMETYPE_VIDEO_AVC): Boolean =
        caps(mime)?.videoCapabilities?.areSizeAndRateSupported(w, h, fps.toDouble()) ?: false

    /**
     * Aceita [w]x[h] diretamente ou deitado (gravado como [h]x[w] + rotação no MP4).
     * Encoders costumam limitar a altura (ex.: 4096x2304), o que barra 2160x3840 em pé.
     */
    fun supportsAnyOrientation(w: Int, h: Int, fps: Int, mime: String = MediaFormat.MIMETYPE_VIDEO_AVC): Boolean =
        supports(w, h, fps, mime) || supports(h, w, fps, mime)

    fun maxBitrate(mime: String): Int = caps(mime)?.videoCapabilities?.bitrateRange?.upper ?: 50_000_000

    fun supportsProfile(mime: String, profile: Int): Boolean =
        caps(mime)?.profileLevels?.any { it.profile == profile } ?: false
}

/** Encoder de vídeo (H.264 ou H.265) com superfície de entrada (alimentada pelo renderizador OpenGL). */
class VideoEncoder(val width: Int, val height: Int, fps: Int, bitrate: Int, mime: String, private val sink: MuxerSink) {
    private val codec = MediaCodec.createEncoderByType(mime)
    val inputSurface: Surface
    private val drainThread: Thread
    @Volatile private var track = -1

    init {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        // Perfil preferido: H.264 High / HEVC Main; se o encoder recusar, usa o padrão dele.
        val profile = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh to MediaCodecInfo.CodecProfileLevel.AVCLevel51
            MediaFormat.MIMETYPE_VIDEO_HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain to MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
            else -> null
        }
        var configured = false
        if (profile != null && EncoderSupport.supportsProfile(mime, profile.first)) {
            try {
                val p = MediaFormat(format).apply {
                    setInteger(MediaFormat.KEY_PROFILE, profile.first)
                    setInteger(MediaFormat.KEY_LEVEL, profile.second)
                }
                codec.configure(p, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configured = true
            } catch (e: Exception) {
                Log.w(TAG, "Perfil ${profile.first} indisponível em $mime, usando o padrão: ${e.message}")
                codec.reset()
            }
        }
        if (!configured) codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        drainThread = Thread(::drain, "HaliCam-VideoEnc").apply { start() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { Log.w(TAG, "video drain: ${e.message}"); return }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = sink.addTrack(codec.outputFormat)
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        sink.write(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Chamar somente depois que o renderizador parou de desenhar nesta superfície. */
    fun finish() {
        try { codec.signalEndOfInputStream() } catch (_: Exception) {}
        drainThread.join(4000)
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        inputSurface.release()
    }
}

/**
 * Captura AudioRecord → AAC, distribuindo as amostras para um ou mais arquivos.
 *
 * - Leitura do microfone e saída do codec em threads separadas: escrita em disco (até 3 arquivos
 *   no Dual Capture) nunca atrasa a leitura, evitando estouro do buffer do AudioRecord (cortes/estalos).
 * - Nenhuma amostra PCM é descartada: espera buffer livre no codec e divide blocos grandes.
 * - Timestamps derivados da contagem de amostras (sem jitter), reancorados no relógio após pausa
 *   ou se houver deriva real (perda de dados no driver).
 */
class AudioEncoder(
    context: Context,
    private val quality: AudioQuality,
    processing: AudioProcessing,
    micDeviceId: Int,
    private val clock: RecordingClock
) {
    private var sinks: List<MuxerSink> = emptyList()
    private val record: AudioRecord
    private val codec: MediaCodec
    private val channels: Int
    private var tracks = IntArray(0)
    @Volatile private var running = true
    private val captureThread: Thread
    private val drainThread: Thread
    private val effects = mutableListOf<AudioEffect>()

    init {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val (rec, ch) = createRecord(quality, sourceFor(processing, am))
        record = rec
        channels = ch
        if (micDeviceId != 0) {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == micDeviceId }?.let { record.preferredDevice = it }
        }
        if (processing == AudioProcessing.VOICE) attachVoiceEffects(record.audioSessionId)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, quality.sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        captureThread = Thread(::captureLoop, "HaliCam-AudioIn").apply { priority = Thread.MAX_PRIORITY }
        drainThread = Thread(::drainLoop, "HaliCam-AudioOut")
    }

    private fun sourceFor(p: AudioProcessing, am: AudioManager): Int = when (p) {
        AudioProcessing.CAMERA -> MediaRecorder.AudioSource.CAMCORDER
        AudioProcessing.VOICE -> MediaRecorder.AudioSource.MIC
        AudioProcessing.RAW ->
            if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.VOICE_RECOGNITION   // o mais "cru" garantido pelo CDD
    }

    private fun attachVoiceEffects(session: Int) {
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = true; effects += it }
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.enabled = true; effects += it }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(q: AudioQuality, source: Int): Pair<AudioRecord, Int> {
        val attempts = if (q.stereo) listOf(2, 1) else listOf(1)
        for (src in listOf(source, MediaRecorder.AudioSource.MIC).distinct()) {
            for (ch in attempts) {
                val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val min = AudioRecord.getMinBufferSize(q.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                // ~0,5 s de folga: absorve picos de CPU/disco sem perder amostras
                val size = max(min * 4, q.sampleRate * ch * 2 / 2)
                val r = try { AudioRecord(src, q.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, size) } catch (_: Exception) { continue }
                if (r.state == AudioRecord.STATE_INITIALIZED) return r to ch
                r.release()
            }
        }
        throw IllegalStateException("AudioRecord indisponível")
    }

    /** Define os arquivos que recebem o áudio (antes de [start]). */
    fun attach(targets: List<MuxerSink>) {
        sinks = targets
        tracks = IntArray(targets.size) { -1 }
    }

    fun start() {
        record.startRecording()
        drainThread.start()
        captureThread.start()
    }

    private fun captureLoop() {
        val bytesPerFrame = 2 * channels
        val chunk = ByteArray(1024 * bytesPerFrame)   // 1024 quadros = 1 quadro AAC
        var anchorUs = -1L        // pts do início do trecho contínuo atual
        var framesSinceAnchor = 0L
        var lastPtsUs = -1L
        while (running) {
            val n = record.read(chunk, 0, chunk.size)
            if (n <= 0) { if (n < 0) Log.w(TAG, "AudioRecord.read: $n"); continue }
            val frames = n / bytesPerFrame
            val readEnd = System.nanoTime()
            val wallNs = clock.pts(readEnd - frames * 1_000_000_000L / quality.sampleRate)
            if (wallNs == null) { anchorUs = -1; continue }   // pausado: descarta e reancora na retomada
            val wallUs = wallNs / 1000
            var ptsUs = if (anchorUs < 0) -1L else anchorUs + framesSinceAnchor * 1_000_000L / quality.sampleRate
            // Reancora no início, após pausa ou se a contagem divergir do relógio (> 80 ms = perda no driver)
            if (ptsUs < 0 || kotlin.math.abs(ptsUs - wallUs) > 80_000) {
                anchorUs = max(wallUs, lastPtsUs + 1)
                framesSinceAnchor = 0
                ptsUs = anchorUs
            }
            framesSinceAnchor += frames
            if (!queuePcm(chunk, n, ptsUs, bytesPerFrame)) break
            lastPtsUs = ptsUs + frames * 1_000_000L / quality.sampleRate
        }
        // fim de stream
        while (true) {
            val idx = try { codec.dequeueInputBuffer(20_000) } catch (_: Exception) { return }
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, max(0, lastPtsUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
        }
    }

    /** Entrega todo o bloco ao codec (sem descartar), dividindo se o buffer de entrada for menor. */
    private fun queuePcm(data: ByteArray, size: Int, ptsUs: Long, bytesPerFrame: Int): Boolean {
        var off = 0
        while (off < size) {
            val idx = try { codec.dequeueInputBuffer(20_000) } catch (_: Exception) { return false }
            if (idx < 0) { if (!running && off == 0) return true; continue }
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            val len = minOf(size - off, buf.capacity() / bytesPerFrame * bytesPerFrame)
            buf.put(data, off, len)
            val pts = ptsUs + (off / bytesPerFrame).toLong() * 1_000_000L / quality.sampleRate
            codec.queueInputBuffer(idx, 0, len, pts, 0)
            off += len
        }
        return true
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { codec.dequeueOutputBuffer(info, 20_000) } catch (e: Exception) { Log.w(TAG, "audio drain: ${e.message}"); return }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    sinks.forEachIndexed { i, s -> tracks[i] = s.addTrack(codec.outputFormat) }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        sinks.forEachIndexed { i, s ->
                            if (tracks[i] >= 0) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                s.write(tracks[i], buf, info)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun stop() {
        running = false
        captureThread.join(3000)
        drainThread.join(3000)
        try { record.stop() } catch (_: Exception) {}
        effects.forEach { try { it.release() } catch (_: Exception) {} }
        record.release()
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }

    /**
     * Direção (MicrophoneDirection) e "zoom de áudio" (-1 = aberto … 1 = estreito) preferidos.
     * Android 10+; retorna false se o aparelho não aplicou.
     */
    fun setMicFocus(direction: Int, field: Float): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val d = record.setPreferredMicrophoneDirection(direction)
            val f = record.setPreferredMicrophoneFieldDimension(field.coerceIn(-1f, 1f))
            Log.i(TAG, "mic focus dir=$direction field=$field -> $d/$f")
            d && f
        } catch (e: Exception) { Log.w(TAG, "mic focus: ${e.message}"); false }
    }

    companion object {
        /** Entradas úteis para gravação (exclui telefonia, submix e loopback). */
        fun inputDevices(context: Context): List<AudioDeviceInfo> {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val useful = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL
            )
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in useful }
        }

        fun deviceLabel(d: AudioDeviceInfo): String {
            if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                val addr = if (Build.VERSION.SDK_INT >= 28) d.address else ""
                return when (addr.lowercase()) {
                    "bottom" -> "Inferior"
                    "back" -> "Traseiro"
                    "both" -> "2 mics (estéreo)"
                    "top" -> "Superior"
                    else -> if (addr.isNotBlank()) "Interno ($addr)" else "Interno"
                }
            }
            return when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone com fio"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Linha"
                else -> "Entrada ${d.type}"
            }
        }
    }
}
