package com.capturepro.app.ui

import android.webkit.JavascriptInterface
import com.capturepro.app.engine.AudioCaptureManager
import com.capturepro.app.engine.MediaMuxerManager
import com.capturepro.app.engine.VideoEncoder
import com.capturepro.app.service.RecordingForegroundService
import com.capturepro.app.storage.StorageManager
import org.json.JSONObject

/**
 * Strictly scoped and validated JavaScript <-> Native Kotlin bridge.
 * Only exposes explicit CapturePro recording, capability, and storage methods.
 */
class CaptureProBridge(
    private val activity: MainActivity,
    private val storageManager: StorageManager,
    private val audioCaptureManager: AudioCaptureManager
) {
    @JavascriptInterface
    fun startRecording(
        resolutionHeight: Int,
        fps: Int,
        isPortrait: Boolean,
        internalAudio: Boolean,
        micAudio: Boolean,
        floatingControls: Boolean
    ) {
        val safeHeight = when (resolutionHeight) {
            480, 720, 1080, 1440 -> resolutionHeight
            else -> 1080
        }
        val safeFps = when (fps) {
            24, 30, 60 -> fps
            else -> 30
        }
        activity.runOnUiThread {
            activity.requestMediaProjectionAndStart(
                safeHeight, safeFps, isPortrait, internalAudio, micAudio, floatingControls
            )
        }
    }

    @JavascriptInterface
    fun pauseOrResumeRecording() {
        activity.runOnUiThread { activity.sendServiceAction(RecordingForegroundService.ACTION_PAUSE_RESUME) }
    }

    @JavascriptInterface
    fun stopRecording() {
        activity.runOnUiThread { activity.sendServiceAction(RecordingForegroundService.ACTION_STOP) }
    }

    @JavascriptInterface
    fun getDeviceCapabilities(): String {
        val stats = storageManager.getDeviceStorageStats()
        return JSONObject().apply {
            put("supportsInternalAudio", audioCaptureManager.isInternalCaptureSupported())
            put("totalStorageBytes", stats.totalBytes)
            put("availableStorageBytes", stats.availableBytes)
            put("isRecording", RecordingForegroundService.isRecordingActive.get())
        }.toString()
    }
}
