package com.hemanth.dictask.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.hemanth.dictask.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Records 16 kHz mono PCM and writes a WAV file.
 *
 * Uncompressed on purpose: AAC at low bitrate throws away the soft, breathy detail a whisper is made of.
 * Before saving, the volume is raised so quiet speech reaches Whisper at a normal level.
 */
class Recorder(private val ctx: Context) {
    private val rate = 16_000
    private var rec: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var peak = 0
    private val pcm = ByteArrayOutputStream()
    private var startedAt = 0L

    @SuppressLint("MissingPermission") // VoiceActivity checks RECORD_AUDIO before calling start()
    fun start() {
        stopQuietly()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("the microphone is busy in another app")
        }
        // The phone's own gain and noise reduction, where available, help most with soft speech.
        if (AutomaticGainControl.isAvailable()) agc = runCatching { AutomaticGainControl.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        if (NoiseSuppressor.isAvailable()) ns = runCatching { NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()

        synchronized(pcm) { pcm.reset() }
        peak = 0
        r.startRecording()
        rec = r
        running = true
        startedAt = System.currentTimeMillis()
        thread = Thread {
            val buf = ShortArray(rate / 10)
            val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                var p = 0
                bytes.clear()
                for (i in 0 until n) {
                    val s = buf[i]
                    val a = abs(s.toInt())
                    if (a > p) p = a
                    bytes.putShort(s)
                }
                if (p > peak) peak = p
                synchronized(pcm) { pcm.write(bytes.array(), 0, n * 2) }
            }
        }.apply { name = "dictask-mic"; start() }
    }

    /** Loudest sample (0..32767) since the last call. */
    fun level(): Int { val p = peak; peak = 0; return p }

    fun elapsedMs(): Long = if (rec == null) 0 else System.currentTimeMillis() - startedAt

    /** Stops and writes the WAV, or returns null if under half a second was captured. */
    fun stop(): File? {
        if (rec == null) return null
        release()
        val raw = synchronized(pcm) { pcm.toByteArray() }
        if (raw.size < rate) return null // < 0.5 s
        val samples = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        boost(samples)
        val f = File(ctx.cacheDir, "dictation-${System.currentTimeMillis()}.wav")
        writeWav(f, samples)
        // Test builds keep the last recording so it can be pulled off the phone and compared.
        if (BuildConfig.DEBUG) runCatching { f.copyTo(File(ctx.filesDir, "last-dictation.wav"), overwrite = true) }
        return f
    }

    fun stopQuietly() { if (rec != null) release() }

    private fun release() {
        running = false
        runCatching { thread?.join(400) }
        rec?.let { runCatching { it.stop() }; it.release() }
        agc?.release(); ns?.release()
        rec = null; agc = null; ns = null; thread = null
    }

    /** Raise the volume so the loudest speech sits near 70% of full scale (ignoring the odd click), max 24x. */
    private fun boost(s: ShortArray) {
        if (s.isEmpty()) return
        val hist = IntArray(32769)
        for (x in s) hist[abs(x.toInt())]++
        var count = 0
        var p999 = 32768
        val target = (s.size * 0.999).toInt()
        for (i in hist.indices) { count += hist[i]; if (count >= target) { p999 = i; break } }
        val gain = min(24f, 0.7f * 32767f / max(p999, 1))
        if (gain <= 1.2f) return
        for (i in s.indices) s[i] = (s[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
    }

    private fun writeWav(f: File, s: ShortArray) {
        val dataLen = s.size * 2
        val b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataLen); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen)
        for (x in s) b.putShort(x)
        FileOutputStream(f).use { it.write(b.array()) }
    }
}
