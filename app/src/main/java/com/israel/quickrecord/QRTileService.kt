package com.israel.quickrecord

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * The Quick Settings tile, spec points (a), (b) and (e).
 *
 * Critical: onClick() never calls unlockAndRun(). Tapping the tile on a
 * locked phone must not ask for credentials.
 */
class QRTileService : TileService() {

    companion object {
        private const val VARIANT_PENDING_INTENT = 0
        private const val VARIANT_INTENT = 1

        /**
         * Which startActivityAndCollapse overload the tile uses, spec point (f).
         * Change this, rebuild, reinstall, and test the other one.
         */
        private const val START_VARIANT = VARIANT_PENDING_INTENT

        private const val REQ_CODE_ACTIVITY = 101
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        Log.i(Constants.TAG, "Tile clicked. isRecording=${Constants.isRecording}")

        if (Constants.isRecording) {
            requestStop()
        } else {
            requestStart()
        }
        refreshTile()
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

    /** Primary path: launch RecordActivity so it can appear over the keyguard. */
    private fun startViaActivity() {
        val activityIntent = Intent(this, RecordActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
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
     * still compiles even if API 37 removed the method from the public stubs.
     * If it is absent at runtime, we fall back to the PendingIntent variant.
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

    /** Fallback path: no activity at all, start the foreground service directly. */
    private fun startViaServiceDirect() {
        try {
            val serviceIntent = Intent(this, RecordService::class.java).apply {
                action = RecordService.ACTION_START
            }
            startForegroundService(serviceIntent)
            Log.i(Constants.TAG, "startForegroundService issued directly from tile")
        } catch (e: Exception) {
            recordFailure(
                "Direct service start failed: ${e.javaClass.simpleName}: ${e.message}. " +
                    "A background-start restriction likely blocked the foreground service."
            )
        }
    }

    // ------------------------------------------------------------------
    // Stop
    // ------------------------------------------------------------------

    /**
     * Uses startService, not startForegroundService. The service is already
     * in the foreground state, so startService is permitted, and it avoids
     * the five-second startForeground deadline that startForegroundService
     * would impose on a stop request.
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
    }

    private fun recordFailure(message: String) {
        Log.e(Constants.TAG, "FAIL: $message")
        Constants.lastError = message
        Constants.lastErrorTime = SystemClock.elapsedRealtime()
    }
}
