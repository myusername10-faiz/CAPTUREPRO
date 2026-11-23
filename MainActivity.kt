package com.capturepro.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.capturepro.app.engine.AudioCaptureManager
import com.capturepro.app.engine.ScreenCaptureManager
import com.capturepro.app.service.RecordingForegroundService
import com.capturepro.app.storage.StorageManager

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var storageManager: StorageManager
    private lateinit var audioCaptureManager: AudioCaptureManager

    private var pendingHeight = 1440
    private var pendingFps = 60
    private var pendingPortrait = true
    private var pendingInternal = true
    private var pendingMic = true
    private var pendingFloating = true

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_START
                putExtra(RecordingForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecordingForegroundService.EXTRA_RESULT_DATA, result.data)
                putExtra(RecordingForegroundService.EXTRA_RES_HEIGHT, pendingHeight)
                putExtra(RecordingForegroundService.EXTRA_FPS, pendingFps)
                putExtra(RecordingForegroundService.EXTRA_PORTRAIT, pendingPortrait)
                putExtra(RecordingForegroundService.EXTRA_INTERNAL_AUDIO, pendingInternal)
                putExtra(RecordingForegroundService.EXTRA_MIC_AUDIO, pendingMic)
                putExtra(RecordingForegroundService.EXTRA_FLOATING, pendingFloating)
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        projectionLauncher.launch(screenCaptureManager.createConsentIntent())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        screenCaptureManager = ScreenCaptureManager(this)
        storageManager = StorageManager(this)
        audioCaptureManager = AudioCaptureManager()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.addJavascriptInterface(
            CaptureProBridge(this, storageManager, audioCaptureManager),
            "CaptureProNative"
        )

        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/capturepro/index.html")
    }

    fun requestMediaProjectionAndStart(
        height: Int,
        fps: Int,
        isPortrait: Boolean,
        internalAudio: Boolean,
        micAudio: Boolean,
        floatingControls: Boolean
    ) {
        pendingHeight = height
        pendingFps = fps
        pendingPortrait = isPortrait
        pendingInternal = internalAudio
        pendingMic = micAudio
        pendingFloating = floatingControls

        if (floatingControls && !Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(overlayIntent)
        }

        val needed = mutableListOf<String>()
        if ((micAudio || internalAudio) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            projectionLauncher.launch(screenCaptureManager.createConsentIntent())
        }
    }

    fun sendServiceAction(action: String) {
        val intent = Intent(this, RecordingForegroundService::class.java).setAction(action)
        startService(intent)
    }
}
