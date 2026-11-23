package com.capturepro.app.engine

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures PCM 16-bit audio from:
 * 1) Internal playback via AudioPlaybackCaptureConfiguration (Android 10+ / API 29+)
 * 2) Microphone via AudioRecord
 *
 * IMPORTANT ANDROID POLICY NOTE (Google Meet / Voice Calls):
 * Android's AudioPlaybackCapture only captures USAGE_MEDIA, USAGE_GAME, and USAGE_UNKNOWN
 * when the source app permits it (ALLOW_CAPTURE_BY_ALL). Voice-communication audio
 * (USAGE_VOICE_COMMUNICATION, e.g., Google Meet / VoIP calls) or apps setting
 * ALLOW_CAPTURE_BY_NONE cannot be captured via standard AudioPlaybackCapture.
 * CapturePro detects silent/restricted playback capture and falls back cleanly to
 * Microphone input without bypassing Android security boundaries.
 */
class AudioCaptureManager {

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 2
    }

    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    private var micThread: Thread? = null
    private var internalThread: Thread? = null

    fun isInternalCaptureSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @SuppressLint("MissingPermission")
    fun startCapture(
        enableInternal: Boolean,
        enableMic: Boolean,
        mediaProjection: MediaProjection?,
        onInternalPcm: (ShortArray, Int) -> Unit,
        onMicPcm: (ShortArray, Int) -> Unit,
        onInternalRestrictedWarning: (String) -> Unit
    ) {
        running.set(true)
        paused.set(false)

        val bufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        if (enableInternal && isInternalCaptureSupported() && mediaProjection != null) {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()

                val record = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()

                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording()
                    internalRecord = record
                    internalThread = Thread({
                        val buf = ShortArray(bufferBytes / 2)
                        var silentFrames = 0
                        var warned = false
                        while (running.get()) {
                            val read = record.read(buf, 0, buf.size)
                            if (read > 0 && !paused.get()) {
                                val peak = buf.take(read).maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                                if (peak == 0) silentFrames++ else silentFrames = 0
                                if (!warned && silentFrames > 150) {
                                    warned = true
                                    onInternalRestrictedWarning(
                                        "Internal audio is silent — the active app (e.g. Google Meet or DRM media) may restrict AudioPlaybackCapture."
                                    )
                                }
                                onInternalPcm(buf, read)
                            }
                        }
                    }, "CapturePro-InternalAudio").apply { start() }
                } else {
                    record.release()
                    onInternalRestrictedWarning("Internal AudioRecord failed to initialize on this device.")
                }
            } catch (t: Throwable) {
                onInternalRestrictedWarning("Internal audio unavailable: ${t.message}")
            }
        }

        if (enableMic) {
            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording()
                    micRecord = record
                    micThread = Thread({
                        val buf = ShortArray(bufferBytes / 2)
                        while (running.get()) {
                            val read = record.read(buf, 0, buf.size)
                            if (read > 0 && !paused.get()) {
                                onMicPcm(buf, read)
                            }
                        }
                    }, "CapturePro-MicAudio").apply { start() }
                } else {
                    record.release()
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun setPaused(isPaused: Boolean) {
        paused.set(isPaused)
    }

    fun stopCapture() {
        running.set(false)
        try {
            internalThread?.join(500)
            micThread?.join(500)
        } catch (_: Throwable) {
        }
        try {
            internalRecord?.stop()
            internalRecord?.release()
        } catch (_: Throwable) {
        } finally {
            internalRecord = null
        }
        try {
            micRecord?.stop()
            micRecord?.release()
        } catch (_: Throwable) {
        } finally {
            micRecord = null
        }
    }
}
