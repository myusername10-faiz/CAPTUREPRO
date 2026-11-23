package com.capturepro.app.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated H.264/AVC VideoEncoder using MediaCodec Surface input.
 * Inspects actual device MediaCodec capabilities for 1440p QHD / 60 FPS and
 * falls back to the nearest supported profile when unavailable.
 */
class VideoEncoder(
    private val muxerManager: MediaMuxerManager
) {
    data class VideoProfile(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateBps: Int,
        val fallbackApplied: Boolean,
        val fallbackReason: String? = null
    )

    private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var pauseStartUs: Long = 0L
    private var totalPausedUs: Long = 0L
    private var drainThread: Thread? = null

    fun resolveSupportedProfile(
        requestedHeight: Int,
        requestedFps: Int,
        isPortrait: Boolean
    ): VideoProfile {
        val candidates = listOf(1440, 1080, 720, 480)
            .filter { it <= requestedHeight }
            .ifEmpty { listOf(720, 480) }

        val codecInfo = findAvcEncoderInfo()
        val caps = codecInfo?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities

        for (h in candidates) {
            val w = when (h) {
                1440 -> 2560
                1080 -> 1920
                720 -> 1280
                else -> 854
            }
            val targetW = if (isPortrait) h else w
            val targetH = if (isPortrait) w else h

            val fpsList = listOf(requestedFps, 30, 24).distinct().filter { it <= requestedFps }
            for (fps in fpsList) {
                if (caps == null || caps.areSizeAndRateSupported(targetW, targetH, fps.toDouble())) {
                    val bitrate = estimateBitrate(h, fps)
                    val changed = (h != requestedHeight || fps != requestedFps)
                    val reason = if (changed) {
                        "Device hardware encoder capped profile to ${h}p @ ${fps}fps"
                    } else null
                    return VideoProfile(
                        width = alignEven(targetW),
                        height = alignEven(targetH),
                        fps = fps,
                        bitrateBps = bitrate,
                        fallbackApplied = changed,
                        fallbackReason = reason
                    )
                }
            }
        }

        // Safe universal fallback
        val fallbackW = if (isPortrait) 720 else 1280
        val fallbackH = if (isPortrait) 1280 else 720
        return VideoProfile(
            width = fallbackW,
            height = fallbackH,
            fps = 30,
            bitrateBps = 6_000_000,
            fallbackApplied = true,
            fallbackReason = "Fallback to 720p @ 30fps due to encoder constraints"
        )
    }

    fun start(profile: VideoProfile, onError: (Throwable) -> Unit) {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            profile.width,
            profile.height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        codec = encoder

        totalPausedUs = 0L
        pauseStartUs = 0L
        paused.set(false)
        running.set(true)

        drainThread = Thread({
            drainEncoder(onError)
        }, "CapturePro-VideoDrain").apply { start() }
    }

    fun pause() {
        if (running.get() && paused.compareAndSet(false, true)) {
            pauseStartUs = System.nanoTime() / 1000L
        }
    }

    fun resume() {
        if (running.get() && paused.compareAndSet(true, false)) {
            val nowUs = System.nanoTime() / 1000L
            if (pauseStartUs > 0L) {
                totalPausedUs += (nowUs - pauseStartUs).coerceAtLeast(0L)
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            codec?.signalEndOfInputStream()
        } catch (_: Throwable) {
        }
        try {
            drainThread?.join(1500)
        } catch (_: Throwable) {
        }
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Throwable) {
        } finally {
            codec = null
            try {
                inputSurface?.release()
            } catch (_: Throwable) {
            }
            inputSurface = null
        }
    }

    private fun drainEncoder(onError: (Throwable) -> Unit) {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = codec ?: return

        try {
            while (running.get()) {
                val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerManager.addVideoTrack(encoder.outputFormat)
                    }
                    index >= 0 -> {
                        val encoded = encoder.getOutputBuffer(index)
                        if (encoded != null &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            bufferInfo.size > 0 &&
                            !paused.get() &&
                            muxerManager.isStarted()
                        ) {
                            bufferInfo.presentationTimeUs =
                                (bufferInfo.presentationTimeUs - totalPausedUs).coerceAtLeast(0L)
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxerManager.writeVideoSample(encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) onError(t)
        }
    }

    private fun findAvcEncoderInfo(): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
    }

    private fun estimateBitrate(height: Int, fps: Int): Int {
        val base = when (height) {
            1440 -> 18_000_000
            1080 -> 12_000_000
            720 -> 6_500_000
            else -> 3_000_000
        }
        return if (fps >= 60) (base * 1.35).toInt() else base
    }

    private fun alignEven(value: Int): Int = if (value % 2 == 0) value else value - 1
}
