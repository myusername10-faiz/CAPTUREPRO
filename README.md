# CapturePro — Native Android Project (Kotlin)

**Tagline:** Record. Capture. Create.  
**Architecture:** Native Kotlin recording engine (`MediaProjection`, `MediaCodec`, `AudioPlaybackCapture`, `AudioRecord`, `MediaMuxer`, `CameraX`, `MediaStore`, `Room`) paired with a scoped `WebViewAssetLoader` UI.

## 1. Building the Debug & Release APKs

> **Note:** This project was generated in an offline environment without the Android SDK/Gradle toolchain. Open this folder in **Android Studio (Ladybug or newer)** with **JDK 17** and **Android SDK API 35** installed.

```bash
# Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (configure signingConfigs in app/build.gradle.kts first)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Installing via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 2. Google Meet Audio Compatibility Analysis

Android's `AudioPlaybackCaptureConfiguration` (API 29+) enforces strict OS-level privacy and usage policies:
1. **Allowed Usages Only:** `AudioPlaybackCapture` only captures `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN` when the source app permits it (`android:allowAudioPlaybackCapture="true"` / `ALLOW_CAPTURE_BY_ALL`).
2. **Voice Communication Restriction:** Apps like **Google Meet** route call audio over `USAGE_VOICE_COMMUNICATION` (or set restrictive capture policies). Standard `AudioPlaybackCapture` **cannot** capture `USAGE_VOICE_COMMUNICATION` streams without root or system-privileged `CAPTURE_AUDIO_OUTPUT`.
3. **CapturePro's Compliant Behavior:**
   - `AudioCaptureManager` monitors incoming `AudioPlaybackCapture` PCM buffers and detects sustained silence when an app blocks playback capture.
   - CapturePro surfaces an honest warning and falls back to **Microphone (`AudioRecord`)** capture rather than pretending internal call audio was recorded.
   - `FacecamManager` (`CameraX`) also gracefully yields if Google Meet holds exclusive front-camera access.

## 3. Device Testing Checklist
- [ ] Verify `MediaProjection` fresh consent prompt on Android 14+ (Single App vs Entire Screen).
- [ ] Test 1440p QHD @ 60 FPS on flagship devices and verify automatic `1080p`/`720p` fallback on mid-range encoders.
- [ ] Verify Internal Audio + Mic mixing on games/media apps, and verify Mic fallback during a Google Meet call.
- [ ] Verify Pause/Resume A/V sync in the saved `Movies/CapturePro/*.mp4` output.
- [ ] Verify floating overlay permission (`SYSTEM_ALERT_WINDOW`) grant & denial fallback to notification controls.
