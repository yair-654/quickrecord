package com.israel.quickrecord

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.Surface
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
    private var outputPfd: ParcelFileDescriptor? = null
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
                if (!startAsForeground()) {
                    return START_NOT_STICKY
                }
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

    /**
     * Returns true if the service reached foreground state, false if the
     * system refused. Refusal reasons are recorded in Constants.lastError.
     */
    private fun startAsForeground(): Boolean {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)

            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(Constants.TAG, "POST_NOTIFICATIONS not granted; notification may not show")
            }

            val stopIntent = Intent(this, RecordService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPi = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_recording_title))
                .setContentText(getString(R.string.notif_recording_text))
                .setSmallIcon(R.drawable.ic_tile)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        getString(R.string.notif_action_stop),
                        stopPi
                    ).build()
                )
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .build()

            // Android 14+ validates the FGS types against the manifest and
            // against the app's current while-in-use state. If this throws,
            // the message tells us exactly which rule rejected us.
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            Log.i(Constants.TAG, "Foreground started with camera|microphone types")
            return true

        } catch (e: SecurityException) {
            fail("startForeground SecurityException: ${e.javaClass.simpleName}: " +
                 "${e.message}. The system refused the camera/microphone foreground " +
                 "service type, usually because the app lacked while-in-use access.")
            return false
        } catch (e: Exception) {
            fail("startForeground threw: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    private fun startRecordingInternal() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
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
            cm.openCamera(cameraId, cameraStateCallback, handler)
        } catch (e: SecurityException) {
            fail("openCamera SecurityException: ${e.message}. The while-in-use " +
                 "restriction likely blocked camera access because no activity " +
                 "of ours was visible.")
        } catch (e: Exception) {
            fail("openCamera threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun pickBackCamera(cm: CameraManager): String? {
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
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
            camera.close()
            fail("Camera disconnected during setup.")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(Constants.TAG, "Camera error code $error")
            camera.close()
            fail("Camera onError code $error (1=ERROR_CAMERA_IN_USE, " +
                 "2=MAX_CAMERAS_IN_USE, 3=ERROR_CAMERA_DISABLED, " +
                 "4=ERROR_CAMERA_DEVICE, 5=ERROR_CAMERA_SERVICE)")
        }
    }

    private fun setUpRecorderAndSession(camera: CameraDevice) {
        try {
            val resolver = applicationContext.contentResolver

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "VID_${stamp}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                fail("MediaStore insert returned null; cannot create output file.")
                return
            }
            outputUri = uri

            val pfd = resolver.openFileDescriptor(uri, "rw")
            if (pfd == null) {
                fail("Could not open a writable descriptor for $uri")
                return
            }
            outputPfd = pfd

            val recorder = MediaRecorder(this)
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(pfd.fileDescriptor)
            recorder.setVideoEncodingBitRate(8_000_000)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            recorder.setVideoFrameRate(VIDEO_FPS)
            recorder.prepare()
            mediaRecorder = recorder

            val surface: Surface = recorder.surface
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
                             "${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FPS} combination " +
                             "may be unsupported on this camera.")
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
                set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(VIDEO_FPS, VIDEO_FPS)
                )
            }
            session.setRepeatingRequest(request.build(), null, handler)

            mediaRecorder!!.start()
            isRecording = true
            Log.i(Constants.TAG, "Recording started to $outputUri")
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
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "recorder stop: ${e.message}")
        }
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "recorder release: ${e.message}")
        }
        mediaRecorder = null

        try {
            outputPfd?.close()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "pfd close: ${e.message}")
        }
        outputPfd = null

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "camera close: ${e.message}")
        }
        cameraDevice = null

        // Flip the MediaStore entry to visible only after a clean stop, so an
        // interrupted recording never appears as a playable file.
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

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(Constants.TAG, "stopForeground: ${e.message}")
        }
        stopSelf()
    }

    /** Records the failure for MainActivity, logs it, and shuts down. */
    private fun fail(message: String) {
        Log.e(Constants.TAG, "FAIL: $message")
        Constants.lastError = message
        Constants.lastErrorTime = SystemClock.elapsedRealtime()
        stopEverything()
    }
}
