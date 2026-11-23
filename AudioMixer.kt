package com.capturepro.app.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes PCM 16-bit audio (internal, mic, or mixed internal + mic) to AAC-LC
 * using MediaCodec and feeds encoded frames to MediaMuxerManager.
 */
class AudioMixer(
    private val muxerManager: MediaMuxerManager
) {
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var encodeThread: Thread? = null

    private val internalQueue = LinkedBlockingQueue<ShortArray>(64)
    private val micQueue = LinkedBlockingQueue<ShortArray>(64)
    private var totalSamplesWritten: Long = 0L

    fun start(useInternal: Boolean, useMic: Boolean, onError: (Throwable) -> Unit) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AudioCaptureManager.SAMPLE_RATE,
            AudioCaptureManager.CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder
        totalSamplesWritten = 0L
        internalQueue.clear()
        micQueue.clear()
        running.set(true)

        encodeThread = Thread({
            runLoop(useInternal, useMic, onError)
        }, "CapturePro-AudioMixer").apply { start() }
    }

    fun pushInternalPcm(samples: ShortArray, count: Int) {
        if (!running.get()) return
        internalQueue.offer(samples.copyOf(count))
    }

    fun pushMicPcm(samples: ShortArray, count: Int) {
        if (!running.get()) return
        micQueue.offer(samples.copyOf(count))
    }

    fun stop() {
        running.set(false)
        try {
            encodeThread?.join(1200)
        } catch (_: Throwable) {
        }
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Throwable) {
        } finally {
            codec = null
        }
    }

    private fun runLoop(useInternal: Boolean, useMic: Boolean, onError: (Throwable) -> Unit) {
        val encoder = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (running.get()) {
                val intChunk = if (useInternal) internalQueue.poll(10, TimeUnit.MILLISECONDS) else null
                val micChunk = if (useMic) micQueue.poll(if (intChunk == null) 10 else 0, TimeUnit.MILLISECONDS) else null

                val mixed = mixChunks(intChunk, micChunk)
                if (mixed != null) {
                    val inIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val byteCount = mixed.size * 2
                            val bytes = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
                            mixed.forEach { bytes.putShort(it) }
                            inBuf.put(bytes.array(), 0, byteCount.coerceAtMost(inBuf.capacity()))
                            val ptsUs = (totalSamplesWritten * 1_000_000L) /
                                (AudioCaptureManager.SAMPLE_RATE * AudioCaptureManager.CHANNEL_COUNT)
                            totalSamplesWritten += mixed.size
                            encoder.queueInputBuffer(inIdx, 0, byteCount.coerceAtMost(inBuf.capacity()), ptsUs, 0)
                        }
                    }
                }

                var outIdx = encoder.dequeueOutputBuffer(bufferInfo, 0)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxerManager.addAudioTrack(encoder.outputFormat)
                    } else if (outIdx >= 0) {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        if (outBuf != null &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            bufferInfo.size > 0 &&
                            muxerManager.isStarted()
                        ) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxerManager.writeAudioSample(outBuf, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (t: Throwable) {
            if (running.get()) onError(t)
        }
    }

    private fun mixChunks(a: ShortArray?, b: ShortArray?): ShortArray? {
        if (a == null && b == null) return null
        if (a != null && b == null) return a
        if (a == null && b != null) return b
        val len = maxOf(a!!.size, b!!.size)
        val out = ShortArray(len)
        for (i in 0 until len) {
            val s1 = if (i < a.size) a[i].toInt() else 0
            val s2 = if (i < b.size) b[i].toInt() else 0
            out[i] = (s1 + s2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}
