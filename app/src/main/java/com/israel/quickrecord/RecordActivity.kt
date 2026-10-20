package com.israel.quickrecord

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * The lock-screen bridge activity.
 *
 * The Quick Settings tile launches THIS, not the service, because Android 14+
 * while-in-use restrictions require our app to be visibly on screen when the
 * camera opens. This activity appears over the lock screen (like an alarm
 * clock), starts RecordService, stays barely long enough for the service to
 * begin the camera open, then removes itself.
 */
class RecordActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw over the keyguard WITHOUT unlocking.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        Log.i(Constants.TAG, "RecordActivity showing over lock screen")

        // Keep the screen lit during the brief camera-open window.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Kick off the foreground service.
        val startIntent = Intent(this, RecordService::class.java).apply {
            action = RecordService.ACTION_START
        }
        startForegroundService(startIntent)

        // Vanish almost immediately.
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(Constants.TAG, "RecordActivity finishing")
            finishAndRemoveTask()
        }, ACTIVITY_LINGER_MS)
    }

    companion object {
        /**
         * How long to stay visible while the service opens the camera.
         * Reduced from 600 to 150 ms in v1.1 to minimize the screen flash.
         * If the locked-camera test shows camera open failing with a
         * while-in-use error, raise this back toward 600 first.
         */
        private const val ACTIVITY_LINGER_MS = 150L
    }
}
