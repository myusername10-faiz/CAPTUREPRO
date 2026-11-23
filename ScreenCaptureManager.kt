package com.capturepro.app.engine

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * Manages the single-use MediaProjection token and VirtualDisplay lifecycle.
 *
 * Android 14+ (API 34+) enforces per-session consent: a MediaProjection token
 * can only be used for one createVirtualDisplay() call and must never be cached
 * across sessions.
 */
class ScreenCaptureManager(private val context: Context) {

    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null

    fun createConsentIntent(): Intent = projectionManager.createScreenCaptureIntent()

    fun startProjection(
        resultCode: Int,
        resultData: Intent,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        onSystemStopped: () -> Unit
    ): MediaProjection {
        stopProjection()

        val projection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("Failed to obtain MediaProjection from consent result.")

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                onSystemStopped()
            }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projectionCallback = callback

        virtualDisplay = projection.createVirtualDisplay(
            "CaptureProDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        mediaProjection = projection
        return projection
    }

    fun resizeVirtualDisplay(width: Int, height: Int, densityDpi: Int) {
        virtualDisplay?.resize(width, height, densityDpi)
    }

    fun getActiveProjection(): MediaProjection? = mediaProjection

    fun stopProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        } finally {
            virtualDisplay = null
        }

        try {
            projectionCallback?.let { cb -> mediaProjection?.unregisterCallback(cb) }
        } catch (_: Throwable) {
        } finally {
            projectionCallback = null
        }

        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        } finally {
            mediaProjection = null
        }
    }
}
