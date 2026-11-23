package com.capturepro.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.capturepro.app.engine.AudioCaptureManager
import com.capturepro.app.engine.AudioMixer
import com.capturepro.app.engine.MediaMuxerManager
import com.capturepro.app.engine.ScreenCaptureManager
import com.capturepro.app.engine.VideoEncoder
import com.capturepro.app.storage.StorageManager
import com.capturepro.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service hosting the active MediaProjection, MediaCodec VideoEncoder,
 * AudioCaptureManager, AudioMixer, and MediaMuxerManager pipeline.
 *
 * Enforces single active session, Android 14+ foregroundServiceType rules,
 * persistent notification controls, and optional movable floating overlay.
 */
class RecordingForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "capturepro_recording_channel"
        const val NOTIF_ID = 4101

        const val ACTION_START = "com.capturepro.app.ACTION_START"
        const val ACTION_PAUSE_RESUME = "com.capturepro.app.ACTION_PAUSE_RESUME"
        const val ACTION_STOP = "com.capturepro.app.ACTION_STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RES_HEIGHT = "res_height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_PORTRAIT = "is_portrait"
        const val EXTRA_INTERNAL_AUDIO = "internal_audio"
        const val EXTRA_MIC_AUDIO = "mic_audio"
        const val EXTRA_FLOATING = "floating_controls"

        val isRecordingActive = AtomicBoolean(false)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var storageManager: StorageManager
    private lateinit var audioCaptureManager: AudioCaptureManager

    private var muxerManager: MediaMuxerManager? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioMixer: AudioMixer? = null
    private var pendingOutputUri: android.net.Uri? = null
    private var isPaused = false

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        screenCaptureManager = ScreenCaptureManager(this)
        storageManager = StorageManager(this)
        audioCaptureManager = AudioCaptureManager()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE_RESUME -> handlePauseResume()
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (!isRecordingActive.compareAndSet(false, true)) {
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData == null) {
            isRecordingActive.set(false)
            stopSelf()
            return
        }

        val reqHeight = intent.getIntExtra(EXTRA_RES_HEIGHT, 1440)
        val reqFps = intent.getIntExtra(EXTRA_FPS, 60)
        val isPortrait = intent.getBooleanExtra(EXTRA_PORTRAIT, true)
        val useInternal = intent.getBooleanExtra(EXTRA_INTERNAL_AUDIO, true)
        val useMic = intent.getBooleanExtra(EXTRA_MIC_AUDIO, true)
        val showFloating = intent.getBooleanExtra(EXTRA_FLOATING, true)

        startForegroundTyped(buildNotification(paused = false), useMic)

        serviceScope.launch {
            try {
                val (uri, pfd) = storageManager.createPendingVideoFile(
                    "CapturePro_${System.currentTimeMillis()}.mp4"
                )
                pendingOutputUri = uri

                val expectAudio = useInternal || useMic
                val muxer = MediaMuxerManager(pfd.fileDescriptor, expectAudio)
                muxerManager = muxer

                val encoder = VideoEncoder(muxer)
                val profile = encoder.resolveSupportedProfile(reqHeight, reqFps, isPortrait)
                videoEncoder = encoder

                encoder.start(profile) { handleStop() }

                val densityDpi = resources.displayMetrics.densityDpi
                val projection = screenCaptureManager.startProjection(
                    resultCode = resultCode,
                    resultData = resultData,
                    width = profile.width,
                    height = profile.height,
                    densityDpi = densityDpi,
                    surface = encoder.inputSurface!!,
                    onSystemStopped = { handleStop() }
                )

                if (expectAudio) {
                    val mixer = AudioMixer(muxer)
                    audioMixer = mixer
                    mixer.start(useInternal, useMic) { }

                    audioCaptureManager.startCapture(
                        enableInternal = useInternal,
                        enableMic = useMic,
                        mediaProjection = projection,
                        onInternalPcm = { buf, len -> mixer.pushInternalPcm(buf, len) },
                        onMicPcm = { buf, len -> mixer.pushMicPcm(buf, len) },
                        onInternalRestrictedWarning = { /* Propagate to UI bridge if active */ }
                    )
                }

                if (showFloating && Settings.canDrawOverlays(this@RecordingForegroundService)) {
                    launch(Dispatchers.Main) { attachFloatingOverlay() }
                }
            } catch (_: Throwable) {
                handleStop()
            }
        }
    }

    private fun handlePauseResume() {
        if (!isRecordingActive.get()) return
        isPaused = !isPaused
        if (isPaused) {
            videoEncoder?.pause()
            audioCaptureManager.setPaused(true)
        } else {
            videoEncoder?.resume()
            audioCaptureManager.setPaused(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(paused = isPaused))
    }

    private fun handleStop() {
        if (!isRecordingActive.compareAndSet(true, false)) return

        detachFloatingOverlay()
        audioCaptureManager.stopCapture()
        audioMixer?.stop()
        videoEncoder?.stop()
        screenCaptureManager.stopProjection()
        muxerManager?.stopAndRelease()

        pendingOutputUri?.let { storageManager.finalizePendingVideo(it) }
        pendingOutputUri = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundTyped(notification: Notification, withMic: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (withMic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, notification, types)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(paused: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingForegroundService::class.java).setAction(ACTION_PAUSE_RESUME),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (paused) "CapturePro • Paused" else "CapturePro • Recording Screen")
            .setContentText("Tap Stop to save recording to MediaStore")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, if (paused) "Resume" else "Pause", pauseIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun attachFloatingOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE613161F.toInt())
            setPadding(24, 14, 24, 14)
        }

        val pauseBtn = TextView(this).apply {
            text = "Pause"
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(0, 0, 24, 0)
            setOnClickListener { handlePauseResume() }
        }
        val stopBtn = TextView(this).apply {
            text = "Stop"
            setTextColor(0xFFEF4444.toInt())
            setOnClickListener { handleStop() }
        }
        container.addView(pauseBtn)
        container.addView(stopBtn)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    wm.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        wm.addView(container, params)
        overlayView = container
    }

    private fun detachFloatingOverlay() {
        overlayView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Throwable) {
            }
        }
        overlayView = null
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CapturePro Recording",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        handleStop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
