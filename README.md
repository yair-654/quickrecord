# QuickRecord

Instant video recording from a Quick Settings tile, straight from the lock screen. One tap to start, one tap to stop and save.

Built for GrapheneOS and modern Android. Pure Android framework APIs: no internet permission, no dependencies, no analytics, no accounts.

## Features (v1.1)

- **Instant start:** Quick Settings tile taps, no unlock prompt, works while locked.
- **Modern dark UI:** Clean, contemporary look with material-style cards.
- **Recording modes:** Full video+audio, video-only (mic unavailable), audio-only (camera unavailable). Falls back gracefully when permissions are system-wide toggled off.
- **Segmented recording:** When a disabled component becomes available mid-session, recording continues in a new segment seamlessly.
- **Battery guard:** Stops automatically at 5% to prevent corrupted files on power loss.
- **File browser:** See all recordings made by this app with full details (duration, size, resolution, bitrate, location sidecar data). Tap any file to view complete metadata.
- **Custom folder:** Choose where recordings save under Movies/. Default: Movies/QuickRecord.
- **Silent notifications:** Low-importance notification with Stop action, never loud.
- **Haptic feedback:** Click feedback on tile press (where vibrator available).
- **Location simple-tier:** Geodata captured at recording start and stored for each file.

## Requirements

- Android 14+ (API 34). Tested on Android 17 (API 37), GrapheneOS.
- One-time setup: open the app, grant camera, microphone, notification, location, and files permissions, then add the QuickRecord tile to your Quick Settings panel.
- Long-press the tile to open this app.

## Permissions

- CAMERA, RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS
- ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION (simple-tier geodata)
- READ_MEDIA_VIDEO, READ_EXTERNAL_STORAGE (file browser)
- VIBRATE (haptics, auto-granted, normal permission)
- **NO INTERNET permission** — the kernel refuses to create any socket for this app.

## Building

The repo has no Gradle wrapper. Commits to `main` trigger GitHub Actions, which builds a debug APK and publishes it as a workflow artifact:

Actions tab, open the latest run, download `QuickRecord-debug-apk`, unzip, install the APK on the device.

Tagging `v1.1` (or any `v*`) creates a GitHub Release with the APK attached automatically.

## Architecture notes

- `Constants.kt` holds `USE_ACTIVITY_PATH`: switch between the primary path (activity over lock screen) and the fallback path (tile starts service directly).
- `QRTileService.kt` holds `START_VARIANT`: PendingIntent vs deprecated Intent variant of `startActivityAndCollapse`.
- Failures are logged under tag `QuickRecord` and shown in MainActivity under "Last error".
- Location data is stored in a sidecar file keyed by recording filename.

## Limitations

- Audio-only files are inserted into the MediaStore Video collection (simplified path). Functional; can be improved in v1.2.
- Mid-session mic/camera toggle detection is polling-based for mic; instantaneous for camera via the availability callback.

---

🇮🇱 Proudly made in Israel 🇮🇱
