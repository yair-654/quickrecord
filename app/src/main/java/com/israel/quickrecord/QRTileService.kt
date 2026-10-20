package com.israel.quickrecord

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * The Quick Settings tile.
 *
 * onClick() never calls unlockAndRun(): tapping on a locked phone must not
 * ask for credentials.
 *
 * Tile state refreshes instantly: the service broadcasts every state change
 * and this class listens while it is on screen. The service additionally
 * calls requestListeningState() so the tile wakes even when not listening.
 */
class QRTileService : TileService() {

    companion object {
        private const val VARIANT_PENDING_INTENT = 0
        private const val VARIANT_INTENT = 1

        /**
         * Which startActivityAndCollapse overload the tile uses.
         * Change this, rebuild, reinstall, and test the other one.
         */
        private const val START_VARIANT = VARIANT_PENDING_INTENT

        private const val REQ_CODE_ACTIVITY = 101
    }

    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(Constants.TAG, "Tile received state broadcast")
            refreshTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!receiverRegistered) {
            try {
                registerReceiver(
                    stateReceiver,
                    IntentFilter(Constants.BROADCAST_RECORDING_STATE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            } catch (e: Exception) {
                Log.w(Constants.TAG, "Tile receiver register failed: ${e.message}")
            }
        }
        refreshTile()
    }

    override fun onStopListening() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (e: Exception) {
                Log.w(Constants.TAG, "Tile receiver unregister failed: ${e.message}")
            }
            receiverRegistered = false
        }
        super.onStopListening()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        hapticTick()
        Log.i(Constants.TAG, "Tile clicked. isRecording=${Constants.isRecording}")

        if (Constants.isRecording) {
            requestStop()
        } else {
            requestStart()
        }
        refreshTile()
    }

    // ------------------------------------------------------------------
    // Haptics
    // ------------------------------------------------------------------

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
    // Start
    // ------------------------------------------------------------------

    private fun requestStart() {
        if (Constants.USE_ACTIVITY_PATH) {
            startViaActivity()
        } else {
            startViaServiceDirect()
        }
    }

    private fun startViaActivity() {
        val activityIntent = Intent(this, RecordActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

        if (START_VARIANT == VARIANT_PENDING_INTENT) {
            try {
                val pi = PendingIntent.getActivity(
                    this,
                    REQ_CODE_ACTIVITY,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
                Log.i(Constants.TAG, "startActivityAndCollapse(PendingIntent) issued")
            } catch (e: Exception) {
                recordFailure(
                    "PendingIntent variant failed: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        } else {
            collapseWithIntentOverload(activityIntent)
        }
    }

    /**
     * The deprecated Intent overload, invoked by reflection so the project
     * compiles even if API 37 removed the method from the public stubs.
     */
    private fun collapseWithIntentOverload(activityIntent: Intent) {
        try {
            val method = TileService::class.java.getMethod(
                "startActivityAndCollapse",
                Intent::class.java
            )
            method.invoke(this, activityIntent)
            Log.i(Constants.TAG, "startActivityAndCollapse(Intent) invoked by reflection")
        } catch (e: NoSuchMethodException) {
            recordFailure("Intent variant absent on this platform: ${e.message}")
            fallbackToPendingIntent(activityIntent)
        } catch (e: Exception) {
            recordFailure("Intent variant threw: ${e.javaClass.simpleName}: ${e.message}")
            fallbackToPendingIntent(activityIntent)
        }
    }

    private fun fallbackToPendingIntent(activityIntent: Intent) {
        try {
            val pi = PendingIntent.getActivity(
                this,
                REQ_CODE_ACTIVITY,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
            Log.i(Constants.TAG, "Fell back to PendingIntent variant")
        } catch (e: Exception) {
            recordFailure(
                "Fallback PendingIntent variant failed: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun startViaServiceDirect() {
        try {
            val serviceIntent = Intent(this, RecordService::class.java).apply {
                action = RecordService.ACTION_START
            }
            startForegroundService(serviceIntent)
            Log.i(Constants.TAG, "startForegroundService issued directly from tile")
        } catch (e: Exception) {
            recordFailure(
                "Direct service start failed: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    // ------------------------------------------------------------------
    // Stop
    // ------------------------------------------------------------------

    /**
     * startService, not startForegroundService: the service is already in the
     * foreground state, so startService is permitted, and it avoids imposing
     * the five-second startForeground deadline on a stop request.
     */
    private fun requestStop() {
        try {
            val serviceIntent = Intent(this, RecordService::class.java).apply {
                action = RecordService.ACTION_STOP
            }
            startService(serviceIntent)
            Log.i(Constants.TAG, "Stop intent sent to RecordService")
        } catch (e: Exception) {
            recordFailure("Stop request failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Tile appearance and diagnostics
    // ------------------------------------------------------------------

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.state = if (Constants.isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
        Log.i(Constants.TAG, "Tile refreshed to state=${tile.state}")
    }

    private fun recordFailure(message: String) {
        Log.e(Constants.TAG, "FAIL: $message")
        Constants.lastError = message
        Constants.lastErrorTime = SystemClock.elapsedRealtime()
    }
}
