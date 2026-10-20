package com.israel.quickrecord

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordService : Service() {

    companion object {
        const val ACTION_START = "com.israel.quickrecord.START"
        const val ACTION_STOP = "com.israel.quickrecord.STOP"
        private const val CHANNEL_ID = "quickrecord_recording"
        private const val NOTIF_ID = 1
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val VIDEO_FPS = 30
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var isRecording = false

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
                if (isRecording) {
                    Log.w(Constants.TAG, "Start requested but already recording; ignoring")
                    return START_NOT_STICKY
                }
                Log.i(Constants.TAG, "Start requested")
                startAsForeground()
                startRecordingInternal()
                return START_NOT_STICKY
            }
            else -> {
                Log.e(Constants.TAG, "Unknown or null action: ${intent?.action}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Foreground setup
    // ------------------------------------------------------------------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(Constants.TAG, "POST_NOTIFICATIONS not granted; notification may not show")
        }

        val stopIntent = Intent(this, RecordService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(getString(R.string.notif_recording_text))
            .setSmallIcon(R.drawable.ic_tile)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_action_stop), stopPi).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .build()

        // Both FGS types declared at once; Android 14+ requires the types to
        // match the manifest declaration of camera|microphone.
        startForeground(NOTIF_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    private fun startRecordingInternal() {
        // Permission gate. Without these nothing downstream can work.
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            fail("Camera or microphone permission missing. Open the app once and grant both.")
            return
        }

        handlerThread = HandlerThread("QuickRecordCamera").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = pickBackCamera(cm)
            if (cameraId == null) {
                fail("No back-facing camera found on this device.")
                return
            }
            Log.i(Constants.TAG, "Opening camera $cameraId")
            // This call throws if Android refuses camera use from the current
            // process state (the while-in-use restriction). We catch and report.
            cm.openCamera(cameraId, cameraStateCallback, handler)
        } catch (e: Exception) {
            fail("openCamera threw: ${e.javaClass.simpleName}: ${e.message}. " +
                 "This usually means the while-in-use restriction blocked camera " +
                 "access because no activity of ours was visible.")
        } catch (e: SecurityException) {
            fail("openCamera SecurityException: ${e.message}")
        }
    }

    private fun pickBackCamera(cm: CameraManager): String? {
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
            ) return id
        }
        return null
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.i(Constants.TAG, "Camera opened")
            cameraDevice = camera
            setUpRecorderAndSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.e(Constants.TAG, "Camera disconnected")
            fail("Camera disconnected during setup.")
            camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(Constants.TAG, "Camera error code $error")
            camera.close()
            fail("Camera onError code $error (1=ERROR_CAMERA_IN_USE, " +
                 "2=MAX_CAMERAS_IN_USE, 3=DISABLED, 4=DEVICE, " +
                 "5=INVALID_OPERATION, 6=callback error)")
        }
    }

    private fun setUpRecorderAndSession(camera: CameraDevice) {
        try {
            val resolver = applicationContext.contentResolver

            // Build the MediaStore target file first, so a failure here is
            // reported before we touch the camera further.
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "VID_${stamp}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (outputUri == null) {
                fail("MediaStore insert returned null; cannot create output file.")
                return
            }

            @Suppress("DEPRECATION")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(resolver.openFileDescriptor(outputUri!!, "rw")!!.fileDescriptor)
                setVideoEncodingBitRate(8_000_000)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                setVideoFrameRate(VIDEO_FPS)
                prepare()
            }

            val surface: Surface = mediaRecorder!!.surface
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        beginRecording(camera, session, surface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        fail("Capture session configuration failed. The requested " +
                             "${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FPS} combination may " +
                             "be unsupported on this camera.")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            fail("Recorder/session setup threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun beginRecording(
        camera: CameraDevice,
        session: CameraCaptureSession,
        surface: Surface
    ) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    intArrayOf(VIDEO_FPS, VIDEO_FPS))
            }
            session.setRepeatingRequest(request.build(), null, handler)

            @Suppress("DEPRECATION")
            mediaRecorder!!.start()

            isRecording = true
            Log.i(Constants.TAG, "Recording started to $outputUri")

            // Mark the MediaStore entry complete so galleries pick it up.
            // IS_PENDING is cleared after stop, not here; keep it pending so a
            // crashed recording leaves no corrupt playable-looking file.
        } catch (e: Exception) {
            fail("mediaRecorder.start threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    private fun stopEverything() {
        isRecording = false
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "session close: ${e.message}")
        }
        captureSession = null
        try {
            @Suppress("DEPRECATION")
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "recorder stop: ${e.message}")
        }
        try {
            @Suppress("DEPRECATION")
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "recorder release: ${e.message}")
        }
        mediaRecorder = null
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "camera close: ${e.message}")
        }
        cameraDevice = null

        // Mark the output file final in MediaStore.
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

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Records the failure for MainActivity display, logs it, and shuts down. */
    private fun fail(message: String) {
        Log.e(Constants.TAG, "FAIL: $message")
        Constants.lastError = message
        Constants.lastErrorTime = SystemClock.elapsedRealtime()
        stopEverything()
    }
}
