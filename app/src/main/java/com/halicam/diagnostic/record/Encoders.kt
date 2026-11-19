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
import android.util.Log
import android.view.Surface
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
class MuxerSink(fd: FileDescriptor, private val expectedTracks: Int) {
    private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
    private val avcCaps: MediaCodecInfo.CodecCapabilities? by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .sortedBy { if (it.name.startsWith("OMX.google") || it.name.startsWith("c2.android")) 1 else 0 }
            .firstOrNull()?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    fun supports(w: Int, h: Int, fps: Int): Boolean =
        avcCaps?.videoCapabilities?.areSizeAndRateSupported(w, h, fps.toDouble()) ?: false

    fun supportsHighProfile(): Boolean =
        avcCaps?.profileLevels?.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh } ?: false
}

/** Encoder H.264 com superfície de entrada (alimentada pelo renderizador OpenGL). */
class VideoEncoder(val width: Int, val height: Int, fps: Int, bitrate: Int, private val sink: MuxerSink) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    private val drainThread: Thread
    @Volatile private var track = -1

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        var configured = false
        if (EncoderSupport.supportsHighProfile()) {
            try {
                val high = MediaFormat(format).apply {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                }
                codec.configure(high, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configured = true
            } catch (e: Exception) {
                Log.w(TAG, "H.264 High indisponível, usando perfil padrão: ${e.message}")
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

/** Captura AudioRecord → AAC, distribuindo as amostras para um ou mais arquivos. */
class AudioEncoder(
    context: Context,
    private val quality: AudioQuality,
    micDeviceId: Int,
    private val clock: RecordingClock
) {
    private var sinks: List<MuxerSink> = emptyList()
    private val record: AudioRecord
    private val codec: MediaCodec
    private val channels: Int
    private var tracks = IntArray(0)
    @Volatile private var running = true
    private val thread: Thread

    init {
        val (rec, ch) = createRecord(quality)
        record = rec
        channels = ch
        if (micDeviceId != 0) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == micDeviceId }?.let { record.preferredDevice = it }
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, quality.sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        thread = Thread(::loop, "HaliCam-Audio")
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(q: AudioQuality): Pair<AudioRecord, Int> {
        val attempts = if (q.stereo) listOf(2, 1) else listOf(1)
        for (ch in attempts) {
            val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val min = AudioRecord.getMinBufferSize(q.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, q.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, max(min * 4, 16384))
            if (r.state == AudioRecord.STATE_INITIALIZED) return r to ch
            r.release()
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
        thread.start()
    }

    private fun loop() {
        val bytesPerFrame = 2 * channels
        val chunk = ByteArray(2048 * channels)
        val info = MediaCodec.BufferInfo()
        var lastPtsUs = -1L
        while (running) {
            val n = record.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            val readEnd = System.nanoTime()
            val durationNs = n.toLong() / bytesPerFrame * 1_000_000_000L / quality.sampleRate
            val pts = clock.pts(readEnd - durationNs) ?: continue   // pausado: descarta
            var ptsUs = pts / 1000
            if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
            lastPtsUs = ptsUs
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx)!!
                inBuf.clear()
                val len = minOf(n, inBuf.capacity())
                inBuf.put(chunk, 0, len)
                codec.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
            }
            drain(info, false)
        }
        val inIdx = codec.dequeueInputBuffer(50_000)
        if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, max(0, lastPtsUs + 1), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(info, true)
    }

    private fun drain(info: MediaCodec.BufferInfo, untilEos: Boolean) {
        val deadline = System.currentTimeMillis() + 1500
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
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
                else -> if (!untilEos || System.currentTimeMillis() > deadline) return
            }
        }
    }

    fun stop() {
        running = false
        thread.join(3000)
        try { record.stop() } catch (_: Exception) {}
        record.release()
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }

    companion object {
        fun inputDevices(context: Context): List<AudioDeviceInfo> {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }

        fun deviceLabel(d: AudioDeviceInfo): String {
            val type = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone interno"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone com fio"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                AudioDeviceInfo.TYPE_TELEPHONY -> "Telefonia"
                else -> "Entrada ${d.type}"
            }
            val addr = if (android.os.Build.VERSION.SDK_INT >= 28 && d.address.isNotBlank()) " (${d.address})" else ""
            return "$type$addr"
        }
    }
}
