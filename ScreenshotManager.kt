package com.capturepro.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.capturepro.app.storage.StorageManager

/**
 * Captures a single-frame PNG screenshot using an active MediaProjection
 * VirtualDisplay + ImageReader, trims row-stride padding, and writes to MediaStore.
 */
class ScreenshotManager(private val context: Context) {

    private val storageManager = StorageManager(context)

    fun captureFromProjection(
        projection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int,
        onComplete: (Result<Uri>) -> Unit
    ) {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display = projection.createVirtualDisplay(
            "CaptureProScreenshot",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                fullBitmap.recycle()

                val uri = storageManager.saveScreenshotBitmap(
                    cropped,
                    "Screenshot_${System.currentTimeMillis()}.png"
                )
                cropped.recycle()
                onComplete(Result.success(uri))
            } catch (t: Throwable) {
                onComplete(Result.failure(t))
            } finally {
                image.close()
                display.release()
                ir.close()
            }
        }, Handler(Looper.getMainLooper()))
    }
}
