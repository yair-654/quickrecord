package com.israel.quickrecord

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * The lock-screen bridge activity, spec point (b) and (c).
 *
 * The Quick Settings tile launches THIS, not the service, because Android 14+
 * while-in-use restrictions require our app to be visibly on screen when the
 * camera opens. This activity appears over the lock screen (like an alarm
 * clock), starts RecordService, keeps the screen lit for a short moment
 * while the service opens the camera, then removes itself.
 */
class RecordActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The two calls that let us draw over the keyguard WITHOUT unlocking.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        Log.i(Constants.TAG, "RecordActivity showing over lock screen")

        // Keep the screen awake long enough for camera open to be covered
        // by a visible activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Kick off the foreground service. The service opens the camera;
        // because this activity is on screen, while-in-use rules are
        // satisfied. If it fails, the reason lands in Constants.lastError.
        val startIntent = Intent(this, RecordService::class.java).apply {
            action = RecordService.ACTION_START
        }
        startForegroundService(startIntent)

        // Vanish shortly after. No UI, no buttons, nothing for the user to do.
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(Constants.TAG, "RecordActivity finishing")
            finishAndRemoveTask()
        }, ACTIVITY_LINGER_MS)
    }

    companion object {
        /** How long to stay visible while the service opens the camera. */
        private const val ACTIVITY_LINGER_MS = 600L
    }
}
