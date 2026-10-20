package com.israel.quickrecord

/**
 * Global constants and shared state for QuickRecord.
 */
object Constants {

    /** Log tag for every log statement in the app. */
    const val TAG = "QuickRecord"

    /**
     * Architectural switch, spec point (e).
     *
     * true  = primary path: the tile launches RecordActivity via
     *         startActivityAndCollapse, which shows over the lock screen
     *         and starts the service.
     *
     * false = fallback path: the tile starts RecordService directly,
     *         with no activity involved.
     *
     * Changing this requires rebuilding and reinstalling the APK.
     */
    const val USE_ACTIVITY_PATH = true

    /**
     * True while RecordService is actively recording. Written by the service,
     * read by the tile (same process) to decide start versus stop, and by
     * MainActivity for the status screen.
     */
    @Volatile
    var isRecording: Boolean = false

    /**
     * Last error message from the camera or recording pipeline, shown in
     * MainActivity. Volatile so the UI thread always sees the latest write.
     */
    @Volatile
    var lastError: String? = null

    /** Timestamp (millis since boot) of when lastError was set. */
    @Volatile
    var lastErrorTime: Long = 0L
}
