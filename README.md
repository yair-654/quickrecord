# QuickRecord

Instant video recording from a Quick Settings tile, straight from the lock screen. One tap to start, one tap to stop and save.

Built for GrapheneOS and modern Android. Pure Android framework APIs: no internet permission, no dependencies, no analytics, no accounts.

---

🇮🇱 Proudly made in Israel 🇮🇱

## Features

- **Instant start:** Quick Settings tile taps, no unlock prompt, works while locked.
- **Modern dark UI:** Clean, contemporary card-based design.
- **Recording modes:** Full video+audio, video-only (mic unavailable), or audio-only (camera unavailable). Falls back gracefully when permissions are toggled off system-wide, and switches modes mid-session when a disabled component comes back.
- **Battery guard:** Stops and saves cleanly at 5% to prevent corrupted files on power loss.
- **File browser:** Browse your recordings in-app with full details (duration, size, resolution, date, location). Tap a recording to open it in your player app.
- **Custom folder:** Choose where recordings save under Movies/. Default: Movies/QuickRecord.
- **Silent notification:** Low-importance notification with Stop action and duration counter, never loud.
- **Haptic feedback:** Confirmation buzz when recording starts and stops.
- **Location tagging:** Optional geodata captured at recording start; never blocks recording.
- **Long-press tile:** Opens the app.

## Requirements

- Android 14+ (API 34). Tested on Android 17 (API 37), GrapheneOS.
- One-time setup: open the app, grant permissions, then add the QuickRecord tile to your Quick Settings panel.

## Permissions

CAMERA, RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION, READ_MEDIA_VIDEO, READ_EXTERNAL_STORAGE (legacy), VIBRATE (auto-granted).

**No INTERNET permission.** The kernel refuses to open any network socket for this app, so exfiltration is impossible.

## Building

Commits to `main` trigger GitHub Actions, which builds the debug APK and publishes it as a workflow artifact: Actions tab, open the latest run, download the artifact, unzip, install.

Tagging any `v*` release creates a GitHub Release with the APK attached automatically.

## Architecture

- `Constants.kt` holds `USE_ACTIVITY_PATH`: switch between the lock-screen activity path and the direct-service fallback path.
- `QRTileService.kt` holds `START_VARIANT`: PendingIntent vs deprecated Intent variant of `startActivityAndCollapse`.
- All failures are logged under tag `QuickRecord` and shown in the app under "Last error".

Recordings: 1080p 30fps H.264/AAC (video), AAC audio-only fallback, saved as `VID_YYYYMMDD_HHMMSS.mp4`.
