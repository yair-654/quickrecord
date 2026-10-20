package com.israel.quickrecord

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.MediaStore
import android.service.quicksettings.TileService
import android.util.Log
import android.util.Range
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordService : Service() {

    private enum class Mode { FULL, VIDEO_ONLY, AUDIO_ONLY }

    companion object {
        const val ACTION_START = "com.israel.quickrecord.START"
        const val ACTION_STOP = "com.israel.quickrecord.STOP"
        const val EXTRA_IS_RECORDING = "isRecording"
        private const val CHANNEL_ID = "quickrecord_recording"
        private const val NOTIF_ID = 1
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val VIDEO_FPS = 30
        private const val MIC_POLL_INTERVAL_MS = 5000L
        private const val LOCATION_PREFS = "qr_locations"
    }

    private var mode: Mode? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var handlerThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    @Volatile private var switchingSegments = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var batteryReceiver: BroadcastReceiver? = null
    private var cameraAvailabilityCallback: CameraManager.AvailabilityCallback? = null

    private val micPollRunnable = object : Runnable {
        override fun run() {
            if (mode == Mode.VIDEO_ONLY &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(Constants.TAG, "Mic became available; switching to full mode")
                finishSegmentAndRestart(Mode.FULL)
                return
            }
            mainHandler.postDelayed(this, MIC_POLL_INTERVAL_MS)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(Constants.TAG, "Stop requested")
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (Constants.isRecording) {
                    Log.w(Constants.TAG, "Start requested but already recording; ignoring")
                    return START_NOT_STICKY
                }
                Log.i(Constants.TAG, "Start requested")
                registerBatteryReceiver()
                registerCameraAvailability()
                startMicPolling()
                startSegment()
                return START_NOT_STICKY
            }
            else -> {
                Log.e(Constants.TAG, "Unknown or null action: ${intent?.action}")
                stopEverything()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Segment start
    // ------------------------------------------------------------------

    private fun startSegment() {
        val camPerm = checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val micPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        if (!camPerm && !micPerm) {
            fail("Neither camera nor microphone permission is granted. " +
                "Open the app once and grant permissions.")
            return
        }

        val chosen = when {
            camPerm && micPerm -> Mode.FULL
            camPerm -> Mode.VIDEO_ONLY
            else -> Mode.AUDIO_ONLY
        }

        if (!startAsForeground(chosen)) return

        when (chosen) {
            Mode.AUDIO_ONLY -> setupAudioOnlyRecording()
            else -> {
                handlerThread = HandlerThread("QuickRecordCamera").also { it.start() }
                cameraHandler = Handler(handlerThread!!.looper)
                openCameraForVideo(chosen == Mode.FULL)
            }
        }
    }

    private fun openCameraForVideo(withAudio: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = pickBackCamera(cm)
            if (cameraId == null) {
                Log.w(Constants.TAG, "No back camera; falling back to audio only")
                if (withAudio) {
                    mode = Mode.AUDIO_ONLY
                    updateNotificationForMode(Mode.AUDIO_ONLY)
                    setupAudioOnlyRecording()
                } else {
                    fail("No back-facing camera found and no microphone permission.")
                }
                return
            }
            Log.i(Constants.TAG, "Opening camera $cameraId")
            cm.openCamera(cameraId, cameraStateCallback, cameraHandler)
        } catch (e: SecurityException) {
            Log.w(Constants.TAG, "openCamera SecurityException: ${e.message}")
            fallbackToAudioOnly("openCamera SecurityException: ${e.message}")
        } catch (e: Exception) {
            fail("openCamera threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.i(Constants.TAG, "Camera opened")
            cameraDevice = camera
            setupVideoRecording(camera, mode == Mode.FULL)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(Constants.TAG, "Camera disconnected")
            camera.close()
            if (mode == Mode.FULL || mode == Mode.VIDEO_ONLY) {
                fallbackToAudioOnly("Camera disconnected mid-session.")
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(Constants.TAG, "Camera error code $error")
            camera.close()
            val reason = "Camera onError code $error (1=IN_USE, 2=MAX_CAMERAS_IN_USE, " +
                "3=DISABLED, 4=DEVICE, 5=SERVICE)"
            if (error == 3 || error == 1 || error == 2) {
                fallbackToAudioOnly(reason)
            } else {
                fail(reason)
            }
        }
    }

    private fun fallbackToAudioOnly(reason: String) {
        teardownSegment(false)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail("$reason. No microphone permission either, cannot record.")
            return
        }
        mode = Mode.AUDIO_ONLY
        Log.w(Constants.TAG, "Falling back to AUDIO_ONLY: $reason")
        startAsForeground(Mode.AUDIO_ONLY)
        setupAudioOnlyRecording()
    }

    private fun fallbackToVideoOnly(reason: String) {
        teardownSegment(false)
        mode = Mode.VIDEO_ONLY
        Log.w(Constants.TAG, "Falling back to VIDEO_ONLY: $reason")
        startAsForeground(Mode.VIDEO_ONLY)
        val cam = cameraDevice
        if (cam != null) {
            setupVideoRecording(cam, false)
        } else {
            openCameraForVideo(false)
        }
    }

    // ------------------------------------------------------------------
    // Video recording setup
    // ------------------------------------------------------------------

    private fun setupVideoRecording(camera: CameraDevice, withAudio: Boolean) {
        val uriPfd = createOutputFile(video = true) ?: run {
            fail("MediaStore insert failed; cannot create video output file.")
            return
        }
        try {
            val recorder = MediaRecorder(this)
            if (withAudio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(uriPfd.second.fileDescriptor)
            if (withAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setAudioSamplingRate(44_100)
            }
            recorder.setVideoEncodingBitRate(8_000_000)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            recorder.setVideoFrameRate(VIDEO_FPS)
            recorder.prepare()

            mediaRecorder = recorder
            outputUri = uriPfd.first
            outputPfd = uriPfd.second

            val surface: Surface = recorder.surface
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        beginVideoRecording(camera, session, surface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (withAudio) {
                            Log.w(Constants.TAG, "Session config failed; trying video-only")
                            closeRecorder()
                            fallbackToVideoOnly("Capture session config failed with audio.")
                        } else {
                            fail("Capture session configuration failed. " +
                                "${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FPS} may be " +
                                "unsupported on this camera.")
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            if (withAudio) {
                Log.w(Constants.TAG, "Setup with audio threw: ${e.message}")
                closeRecorder()
                fallbackToVideoOnly("Recorder setup threw: ${e.javaClass.simpleName}: ${e.message}")
            } else {
                fail("Video recorder setup threw: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun beginVideoRecording(
        camera: CameraDevice,
        session: CameraCaptureSession,
        surface: Surface
    ) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(VIDEO_FPS, VIDEO_FPS)
                )
            }
            session.setRepeatingRequest(request.build(), null, cameraHandler)
            mediaRecorder!!.start()
            recordingStarted()
        } catch (e: Exception) {
            fail("mediaRecorder.start threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Audio-only recording setup
    // ------------------------------------------------------------------

    private fun setupAudioOnlyRecording() {
        val uriPfd = createOutputFile(video = false) ?: run {
            fail("MediaStore insert failed; cannot create audio output file.")
            return
        }
        try {
            val recorder = MediaRecorder(this)
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(uriPfd.second.fileDescriptor)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.prepare()
            mediaRecorder = recorder
            recorder.start()
            outputUri = uriPfd.first
            outputPfd = uriPfd.second
            recordingStarted()
        } catch (e: Exception) {
            fail("Audio recorder setup/start threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Output file and location
    // ------------------------------------------------------------------

    private fun createOutputFile(video: Boolean): Pair<Uri, ParcelFileDescriptor>? {
        return try {
            val resolver = applicationContext.contentResolver
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val folder = (Constants.customFolderOverride ?: Constants.DEFAULT_FOLDER)
                .trim().replace("/", "-")
                .ifEmpty { Constants.DEFAULT_FOLDER }
            val name = if (video) "VID_${stamp}.mp4" else "AUD_${stamp}.m4a"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(
                    MediaStore.Video.Media.MIME_TYPE,
                    if (video) "video/mp4" else "audio/mp4"
                )
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/" + folder
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: return null
            saveLocationSidecar(name)
            Pair(uri, pfd)
        } catch (e: Exception) {
            Log.e(Constants.TAG, "createOutputFile threw: ${e.message}")
            null
        }
    }

    private fun saveLocationSidecar(displayName: String) {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (provider in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )) {
                val loc = try {
                    if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
                } catch (se: SecurityException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (loc != null && (best == null || loc.time > best!!.time)) best = loc
            }
            if (best != null) {
                getSharedPreferences(LOCATION_PREFS, MODE_PRIVATE).edit()
                    .putString(displayName, "%.6f,%.6f".format(best.latitude, best.longitude))
                    .apply()
                Log.i(Constants.TAG, "Location stored: ${best.latitude},${best.longitude}")
            } else {
                Log.i(Constants.TAG, "No last known location; recording without geotag")
            }
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Location capture skipped: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Mid-session re-enable detection
    // ------------------------------------------------------------------

    private fun registerCameraAvailability() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    if (mode == Mode.AUDIO_ONLY && !switchingSegments &&
                        checkSelfPermission(android.Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.i(Constants.TAG, "Camera available again; switching to video")
                        finishSegmentAndRestart(Mode.FULL)
                    }
                }
            }
            cm.registerAvailabilityCallback(
                cameraAvailabilityCallback!!,
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.w(Constants.TAG, "registerAvailabilityCallback failed: ${e.message}")
        }
    }

    private fun startMicPolling() {
        mainHandler.removeCallbacks(micPollRunnable)
        mainHandler.postDelayed(micPollRunnable, MIC_POLL_INTERVAL_MS)
    }

    private fun finishSegmentAndRestart(newMode: Mode) {
        if (switchingSegments) return
        switchingSegments = true
        mainHandler.post {
            teardownSegment(true)
            mode = newMode
            switchingSegments = false
            updateNotificationForMode(newMode)
            when (newMode) {
                Mode.AUDIO_ONLY -> setupAudioOnlyRecording()
                else -> {
                    handlerThread = HandlerThread("QuickRecordCamera").also { it.start() }
                    cameraHandler = Handler(handlerThread!!.looper)
                    openCameraForVideo(newMode == Mode.FULL)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Battery guard
    // ------------------------------------------------------------------

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra("level", -1)
                val scale = intent.getIntExtra("scale", -1)
                if (level < 0 || scale <= 0) return
                val percent = level * 100 / scale
                if (percent <= Constants.BATTERY_THRESHOLD_PERCENT && Constants.isRecording) {
                    Log.w(Constants.TAG, "Battery at $percent%; stopping cleanly")
                    Constants.lastError = getString(
                        R.string.notif_battery_stop,
                        Constants.BATTERY_THRESHOLD_PERCENT
                    )
                    Constants.lastErrorTime = SystemClock.elapsedRealtime()
                    stopEverything()
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    // ------------------------------------------------------------------
    // Foreground and notification
    // ------------------------------------------------------------------

    private fun startAsForeground(chosen: Mode): Boolean {
        return try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)

            val types = when (chosen) {
                Mode.FULL -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                Mode.VIDEO_ONLY -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                Mode.AUDIO_ONLY -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }

            mode = chosen
            startForeground(NOTIF_ID, buildNotification(chosen), types)
            Log.i(Constants.TAG, "Foreground started, mode=$chosen")
            true
        } catch (e: SecurityException) {
            fail("startForeground SecurityException: ${e.message}.")
            false
        } catch (e: Exception) {
            fail("startForeground threw: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun buildNotification(current: Mode): Notification {
        val stopIntent = Intent(this, RecordService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = when (current) {
            Mode.VIDEO_ONLY -> getString(R.string.notif_video_only)
            Mode.AUDIO_ONLY -> getString(R.string.notif_audio_only)
            Mode.FULL -> getString(R.string.notif_recording_text)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tile)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.notif_action_stop), stopPi
                ).build()
            )
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setUsesChronometer(true)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis())
            .build()
    }

    private fun updateNotificationForMode(current: Mode) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(current))
        } catch (e: Exception) {
            Log.w(Constants.TAG, "updateNotification: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // State broadcast and haptics
    // ------------------------------------------------------------------

    private fun recordingStarted() {
        Constants.isRecording = true
        broadcastState()
        hapticTick()
        Log.i(Constants.TAG, "Recording started (${mode?.name}) to $outputUri")
    }

    private fun broadcastState() {
        val intent = Intent(Constants.BROADCAST_RECORDING_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RECORDING, Constants.isRecording)
        }
        sendBroadcast(intent)
        wakeTile()
    }

    /**
     * Wakes the tile even when the Quick Settings panel is closed, so its
     * highlight reflects reality without the user reopening the panel.
     */
    private fun wakeTile() {
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, QRTileService::class.java)
            )
        } catch (e: Exception) {
            Log.w(Constants.TAG, "wakeTile failed: ${e.message}")
        }
    }

    private fun hapticTick() {
        try {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vib = vm.defaultVibrator
            if (vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Haptic failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    private fun pickBackCamera(cm: CameraManager): String? {
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return null
    }

    private fun closeRecorder() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "recorder release: ${e.message}")
        }
        mediaRecorder = null
    }

    private fun teardownSegment(keepAlive: Boolean) {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "recorder stop: ${e.message}")
        }
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "session close: ${e.message}")
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "camera close: ${e.message}")
        }
        cameraDevice = null
        closeRecorder()
        try {
            outputPfd?.close()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "pfd close: ${e.message}")
        }
        outputPfd = null

        outputUri?.let { uri ->
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
                Log.i(Constants.TAG, "Saved $uri")
            } catch (e: Exception) {
                Log.w(Constants.TAG, "finalize uri: ${e.message}")
            }
        }
        outputUri = null

        if (!keepAlive) {
            handlerThread?.quitSafely()
            handlerThread = null
            cameraHandler = null
        }
    }

    private fun stopEverything() {
        val wasRecording = Constants.isRecording
        Constants.isRecording = false
        broadcastState()
        if (wasRecording) hapticTick()

        mainHandler.removeCallbacks(micPollRunnable)

        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // ignore
            }
            batteryReceiver = null
        }

        cameraAvailabilityCallback?.let {
            try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.unregisterAvailabilityCallback(it)
            } catch (e: Exception) {
                // ignore
            }
            cameraAvailabilityCallback = null
        }

        teardownSegment(false)

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(Constants.TAG, "stopForeground: ${e.message}")
        }
        stopSelf()
    }

    private fun fail(message: String) {
        Log.e(Constants.TAG, "FAIL: $message")
        Constants.lastError = message
        Constants.lastErrorTime = SystemClock.elapsedRealtime()
        stopEverything()
    }
}
