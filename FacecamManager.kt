package com.capturepro.app.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Manages the optional CameraX front-camera Facecam preview.
 * Gracefully reports unavailability when another app (e.g., Google Meet) holds
 * exclusive camera access.
 */
class FacecamManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null

    fun startFrontPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: (String) -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview
                )
            } catch (t: Throwable) {
                onError("Front camera unavailable (may be in use by another app): ${t.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopPreview() {
        cameraProvider?.unbindAll()
    }
}
