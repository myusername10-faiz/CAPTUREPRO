package com.capturepro.app.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Thread-safe MP4 MediaMuxer coordinator.
 * Waits for all expected tracks (video + optional audio) to emit INFO_OUTPUT_FORMAT_CHANGED
 * before calling muxer.start(), and enforces monotonic presentation timestamps.
 */
class MediaMuxerManager(
    fd: FileDescriptor,
    private val expectAudio: Boolean,
    orientationDegrees: Int = 0
) {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        if (orientationDegrees in listOf(0, 90, 180, 270)) {
            setOrientationHint(orientationDegrees)
        }
    }

    private val lock = Object()
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var started = false
    private var stopped = false
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    fun addVideoTrack(format: MediaFormat) = synchronized(lock) {
        if (videoTrackIndex == -1 && !stopped) {
            videoTrackIndex = muxer.addTrack(format)
            tryStartLocked()
        }
    }

    fun addAudioTrack(format: MediaFormat) = synchronized(lock) {
        if (audioTrackIndex == -1 && !stopped) {
            audioTrackIndex = muxer.addTrack(format)
            tryStartLocked()
        }
    }

    private fun tryStartLocked() {
        val videoReady = videoTrackIndex >= 0
        val audioReady = !expectAudio || audioTrackIndex >= 0
        if (videoReady && audioReady && !started) {
            muxer.start()
            started = true
        }
    }

    fun isStarted(): Boolean = synchronized(lock) { started && !stopped }

    fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (!started || stopped || videoTrackIndex < 0) return
        if (info.presentationTimeUs <= lastVideoPtsUs) {
            info.presentationTimeUs = lastVideoPtsUs + 1
        }
        lastVideoPtsUs = info.presentationTimeUs
        muxer.writeSampleData(videoTrackIndex, buffer, info)
    }

    fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (!started || stopped || audioTrackIndex < 0) return
        if (info.presentationTimeUs <= lastAudioPtsUs) {
            info.presentationTimeUs = lastAudioPtsUs + 1
        }
        lastAudioPtsUs = info.presentationTimeUs
        muxerManagerWriteSample(audioTrackIndex, buffer, info)
    }

    private fun muxerManagerWriteSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        muxer.writeSampleData(track, buffer, info)
    }

    fun stopAndRelease() = synchronized(lock) {
        if (stopped) return
        stopped = true
        try {
            if (started) muxer.stop()
        } catch (_: Throwable) {
        }
        try {
            muxer.release()
        } catch (_: Throwable) {
        }
    }
}
