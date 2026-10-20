# QuickRecord

A minimal Android app that starts a video recording instantly from a Quick Settings tile, including while the phone is locked. No preview, no buttons, no unlock prompt.

Built for personal use on GrapheneOS. No internet permission, no ads, no analytics, no accounts, no external dependencies. Pure Android framework APIs.

## How it works

- Tap the tile: starts recording immediately (back camera, mic, 1080p 30fps H.264/AAC).
- Tap again: stops and saves to Movies as VID_YYYYMMDD_HHMMSS.mp4.
- While locked, the tile launches a transparent show-when-locked activity (the same mechanism alarm clocks use), which starts a foreground service that owns the camera. Recording survives screen-off and app switching.
- Persistent notification with a Stop action while recording.

## Requirements

- Android 14+ (API 34). Developed and tested on Android 17 (API 37), GrapheneOS.
- One-time setup: open the app, grant camera, microphone, and notification permissions, then add the QuickRecord tile to your Quick Settings panel.

## Permissions

CAMERA, RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS.

The app has no INTERNET permission, so the OS refuses to open any network socket for it.

## Building

The repo has no Gradle wrapper and no local build environment is needed. Commits to `main` trigger GitHub Actions, which builds a debug APK and publishes it as a workflow artifact:

Actions tab, open the latest run, download `QuickRecord-debug-apk`, unzip, install the APK on the device.

## Architecture notes

- `Constants.kt` holds `USE_ACTIVITY_PATH`: the switch between the primary path (activity over lock screen) and the fallback path (tile starts the service directly). Change requires rebuild.
- `QRTileService.kt` holds `START_VARIANT`: the switch between the PendingIntent and deprecated Intent variants of `startActivityAndCollapse`.
- Failures anywhere in the recording pipeline are logged under the tag `QuickRecord` and shown in MainActivity under "Last error".

## License

None. All rights reserved. Personal use.
