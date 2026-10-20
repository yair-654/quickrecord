package com.israel.quickrecord

/**
 * Global constants for QuickRecord.
 */
object Constants {

    /** Log tag for every log statement in the app. */
    const val TAG = "QuickRecord"

    /**
     * Architectural switch, spec point (e).
     *
     * true  = primary path: the tile launches RecordActivity via
     *         startActivityAndCollapse (PendingIntent variant, API 34+),
     *         which shows over the lock screen and starts the service.
     *
     * false = fallback path: the tile starts RecordService directly,
     *         no activity involved.
     *
     * Changing this requires rebuilding and reinstalling the APK.
     */
    const val USE_ACTIVITY_PATH = true

    /**
     * In-memory diagnostics: the last error message, if any, from the
     * camera/recording pipeline. Read and displayed by MainActivity.
     * Volatile so reads from the UI thread always see the latest write.
     */
    @Volatile
    var lastError: String? = null

    /** Timestamp (millis since epoch) of when lastError was set. */
    @Volatile
    var lastErrorTime: Long = 0L
}
