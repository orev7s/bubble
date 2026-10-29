package com.accio.whisperbubble

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Records 16 kHz mono PCM-16 into memory and wraps it in a WAV container.
 * Whisper expects exactly this format, so no resampling is needed.
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MAX_MS = 120_000L
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    @Volatile
    private var active = false

    private var startedAt = 0L

    val isRecording: Boolean get() = active

    fun start(): Boolean {
        if (active) return true

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 8192)
            )
        } catch (t: Throwable) {
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        synchronized(buffer) { buffer.reset() }
        startedAt = System.currentTimeMillis()

        try {
            rec.startRecording()
        } catch (t: Throwable) {
            rec.release()
            return false
        }

        record = rec
        active = true

        thread = Thread {
            val chunk = ByteArray(4096)
            while (active) {
                val n = try {
                    rec.read(chunk, 0, chunk.size)
                } catch (t: Throwable) {
                    -1
                }
                if (n > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                } else if (n < 0) {
                    break
                }
                if (System.currentTimeMillis() - startedAt > MAX_MS) break
            }
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
            record = null
        }.also { it.start() }

        return true
    }

    /** Stops recording and returns a complete WAV file, or null if nothing usable was captured. */
    fun stop(): ByteArray? {
        if (!active) return null
        active = false
        // Unblocks the pending read() immediately instead of waiting for the
        // next buffer to fill, which shaves a noticeable amount off the tail.
        runCatching { record?.stop() }
        thread?.let { runCatching { it.join(600) } }

        val pcm = synchronized(buffer) { buffer.toByteArray() }
        if (pcm.size < SAMPLE_RATE / 2) return null // < ~0.25s of audio
        return wrapWav(pcm)
    }

    fun cancel() {
        active = false
        thread?.let { runCatching { it.join(500) } }
        synchronized(buffer) { buffer.reset() }
    }

    private fun wrapWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        val dataLen = pcm.size
        val byteRate = SAMPLE_RATE * 2
        val totalLen = dataLen + 36

        out.write("RIFF".toByteArray())
        out.write(le32(totalLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1))              // PCM
        out.write(le16(1))              // mono
        out.write(le32(SAMPLE_RATE))
        out.write(le32(byteRate))
        out.write(le16(2))              // block align
        out.write(le16(16))             // bits per sample
        out.write("data".toByteArray())
        out.write(le32(dataLen))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte()
    )

    private fun le16(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte()
    )
}
